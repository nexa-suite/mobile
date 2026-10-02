package com.nexa.mobile.operations.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FieldVisitIntent(
    val key: String,
    val customerId: String,
    val version: Long,
    val body: String,
    val outcome: String = "Pending",
    val receiptId: String? = null
) {
    override fun toString(): String = "FieldVisitIntent(REDACTED)"
}
data class FieldVisitRecord(
    val customerId: String = "",
    val purpose: String = "",
    val followUp: String = "",
    val intent: FieldVisitIntent? = null
) {
    override fun toString(): String = "FieldVisitRecord(REDACTED)"
}
data class FieldVisitState(
    val record: FieldVisitRecord = FieldVisitRecord(),
    val customer: CustomerRelationship? = null,
    val receivedAt: Instant? = null,
    val status: String = "Loading"
)
interface FieldVisitStore {
    suspend fun load(authority: CommercialAuthority): FieldVisitRecord?
    suspend fun save(authority: CommercialAuthority, record: FieldVisitRecord): Boolean
}
sealed interface FieldVisitResult {
    data class Recorded(val id: String) : FieldVisitResult
    data object Conflict : FieldVisitResult
    data object Denied : FieldVisitResult
    data object UnknownOutcome : FieldVisitResult
}
interface FieldVisitGateway {
    suspend fun customer(authority: CommercialAuthority, id: String): CustomerRelationship?
    fun body(purpose: String, followUp: String, occurredAt: Instant): String
    suspend fun record(authority: CommercialAuthority, intent: FieldVisitIntent): FieldVisitResult
}
class FieldVisitViewModel(
    private val gateway: FieldVisitGateway,
    private val store: FieldVisitStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(FieldVisitState())
    val state = mutableState.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L
    private val mutex = Mutex()
    fun deactivate() {
        generation++
        authority = null
        mutableState.value = FieldVisitState()
    }
    fun activate(value: CommercialAuthority) {
        deactivate()
        authority = value
        val epoch = generation
        viewModelScope.launch {
            mutex.withLock {
                val record = store.load(value)
                if (!current(value, epoch)) return@withLock
                mutableState.value = if (record == null) {
                    FieldVisitState(status = "MetadataUnavailable")
                } else {
                    FieldVisitState(
                        record,
                        status = when (record.intent?.outcome) {
                            null -> "Draft"
                            "Recorded" -> "Recorded"
                            "Conflict" -> "Conflict"
                            else -> "UnknownOutcome"
                        }
                    )
                }
            }
        }
    }
    private fun editable() = authority != null && state.value.record.intent == null &&
        state.value.status !in setOf("Loading", "MetadataUnavailable", "Pending")
    fun customerChanged(value: String) = edit { it.copy(customerId = value.trim()) }
    fun purposeChanged(value: String) = edit { it.copy(purpose = value.take(500)) }
    fun followUpChanged(value: String) = edit { it.copy(followUp = value.take(2000)) }
    private fun edit(change: (FieldVisitRecord) -> FieldVisitRecord) {
        if (!editable()) return
        val a = authority ?: return
        val epoch = generation
        mutableState.value = FieldVisitState(change(state.value.record), status = "Draft")
        viewModelScope.launch {
            mutex.withLock {
                if (!current(a, epoch) || state.value.record.intent != null) return@withLock
                if (!store.save(a, state.value.record) &&
                    current(a, epoch)
                ) {
                    mutableState.value =
                        state.value.copy(status = "MetadataUnavailable")
                }
            }
        }
    }
    fun reviewCustomer() {
        if (!editable()) return
        val a = authority ?: return
        val epoch = generation
        val record = state.value.record
        mutableState.value = FieldVisitState(record, status = "Pending")
        viewModelScope.launch {
            mutex.withLock {
                val customer = safe { gateway.customer(a, record.customerId) }
                if (!current(a, epoch)) return@withLock
                mutableState.value =
                    FieldVisitState(
                        record,
                        customer,
                        Instant.now(),
                        if (customer?.active ==
                            true
                        ) {
                            "Reviewed"
                        } else {
                            "Unavailable"
                        }
                    )
            }
        }
    }
    fun recordFollowUp() {
        val a = authority ?: return
        val input = state.value
        val customer = input.customer ?: return
        if (input.status != "Reviewed" || input.record.intent != null || !customer.active ||
            input.record.purpose.isBlank() || input.record.followUp.isBlank() ||
            ("client.manage" !in a.permissions && "sales:write" !in a.permissions)
        ) {
            return
        }
        val intent = FieldVisitIntent(
            UUID.randomUUID().toString(),
            customer.id,
            customer.version,
            gateway.body(input.record.purpose, input.record.followUp, Instant.now())
        )
        execute(a, input.record.copy(intent = intent), generation)
    }
    fun retryUnknownOutcome() {
        val a = authority ?: return
        val input = state.value
        if (input.status != "UnknownOutcome") return
        val intent = input.record.intent ?: return
        execute(a, input.record.copy(intent = intent.copy(outcome = "Pending")), generation)
    }
    fun newDecision() {
        val a = authority ?: return
        val epoch = generation
        if (state.value.status !in setOf("Recorded", "Conflict")) return
        viewModelScope.launch {
            mutex.withLock {
                val record = if (state.value.status ==
                    "Recorded"
                ) {
                    FieldVisitRecord()
                } else {
                    state.value.record.copy(intent = null)
                }
                if (store.save(a, record) &&
                    current(a, epoch)
                ) {
                    mutableState.value = FieldVisitState(record, status = "Draft")
                }
            }
        }
    }
    private fun execute(a: CommercialAuthority, record: FieldVisitRecord, epoch: Long) {
        mutableState.value = state.value.copy(record = record, status = "Pending")
        viewModelScope.launch {
            mutex.withLock {
                if (!current(a, epoch)) return@withLock
                if (!store.save(a, record)) {
                    mutableState.value =
                        state.value.copy(status = "MetadataUnavailable")
                    return@withLock
                }
                if (!current(a, epoch)) return@withLock
                val result =
                    safe { gateway.record(a, record.intent!!) } ?: FieldVisitResult.UnknownOutcome
                val outcome = when (result) {
                    is FieldVisitResult.Recorded -> "Recorded"
                    FieldVisitResult.Conflict -> "Conflict"
                    else -> "UnknownOutcome"
                }
                val updated = record.copy(
                    intent = record.intent!!.copy(
                        outcome = outcome,
                        receiptId = (result as? FieldVisitResult.Recorded)?.id
                    )
                )
                val saved = store.save(a, updated)
                if (!current(a, epoch)) return@withLock
                mutableState.value =
                    state.value.copy(
                        record = updated,
                        status = if (saved) outcome else "MetadataUnavailable"
                    )
            }
        }
    }
    private fun current(a: CommercialAuthority, epoch: Long) = authority == a && generation == epoch
    private suspend fun <T> safe(action: suspend () -> T): T? = try {
        action()
    } catch (
        cancelled: CancellationException
    ) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
