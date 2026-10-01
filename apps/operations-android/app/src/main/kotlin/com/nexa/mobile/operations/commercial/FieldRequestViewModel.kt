package com.nexa.mobile.operations.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local intention and a frozen write intent are separate from current server authority. */
class FieldRequestViewModel(private val gateway: FieldRequestGateway, private val store: FieldRequestStore) : ViewModel() {
    private val mutableState = MutableStateFlow(FieldRequestState())
    val state = mutableState.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L
    private val mutex = Mutex()
    fun deactivate() { generation++; authority = null; mutableState.value = FieldRequestState() }
    fun activate(value: CommercialAuthority, requestedCustomerId: String? = null) {
        deactivate(); authority = value.copy(permissions = value.permissions.toSet())
        val epoch = generation
        viewModelScope.launch { mutex.withLock {
            val loaded = store.load(value)
            if (!current(value, epoch)) return@withLock
            mutableState.value = when (loaded) {
                FieldRequestRead.Unavailable -> FieldRequestState(status = FieldRequestStatus.MetadataUnavailable)
                is FieldRequestRead.Available -> {
                    val prior = loaded.record
                    val record = if (requestedCustomerId != null && prior.intent == null && prior.draft.lines.isEmpty()) {
                        val selected = prior.copy(draft = prior.draft.copy(customerId = requestedCustomerId, customerVersion = null))
                        if (!store.save(value, selected)) {
                            mutableState.value = FieldRequestState(status = FieldRequestStatus.MetadataUnavailable)
                            return@withLock
                        }
                        selected
                    } else prior
                    if (!current(value, epoch)) return@withLock
                    FieldRequestState(record, when (record.intent?.outcome) {
                        "Confirmed" -> FieldRequestStatus.Confirmed
                        "Conflict" -> FieldRequestStatus.Conflict
                        null -> FieldRequestStatus.Draft
                        else -> FieldRequestStatus.UnknownOutcome
                    })
                }
            }
        } }
    }
    fun productChanged(value: String) { if (editable()) mutableState.value = state.value.copy(productId = value.trim()) }
    fun quantityChanged(value: String) { if (editable()) mutableState.value = state.value.copy(quantity = value.take(32)) }
    fun customerChanged(value: String) = edit { it.copy(customerId = value.trim(), customerVersion = null, lines = emptyList()) }
    fun deliveryDateChanged(value: String) = edit { it.copy(deliveryDate = value.take(10)) }
    fun deliveryProfileChanged(value: String) = edit { it.copy(deliveryProfile = value.take(2000)) }
    fun paymentChanged(value: String) = edit { it.copy(paymentOption = value) }
    fun commentChanged(value: String) = edit { it.copy(comment = value.take(2000)) }
    fun removeLine(id: String) = edit { it.copy(lines = it.lines.filterNot { line -> line.catalogItemId == id }) }
    fun lineQuantityChanged(id: String, value: String) = edit { draft ->
        draft.copy(lines = draft.lines.map { if (it.catalogItemId == id) it.copy(quantity = value.take(32)) else it })
    }
    private fun editable(): Boolean = authority != null && state.value.record.intent == null &&
        state.value.status !in setOf(FieldRequestStatus.Loading, FieldRequestStatus.MetadataUnavailable,
            FieldRequestStatus.Reviewing, FieldRequestStatus.Pending)
    private fun edit(change: (FieldRequestDraft) -> FieldRequestDraft) {
        if (!editable()) return
        val captured = authority ?: return
        val epoch = generation
        val record = state.value.record.copy(draft = change(state.value.record.draft))
        mutableState.value = state.value.copy(record = record, status = FieldRequestStatus.Draft)
        viewModelScope.launch { mutex.withLock {
            if (!current(captured, epoch) || state.value.record.intent != null) return@withLock
            // Persist the latest draft, rather than an older keystroke captured by this job.
            if (!store.save(captured, state.value.record) && current(captured, epoch))
                mutableState.value = state.value.copy(status = FieldRequestStatus.MetadataUnavailable)
        } }
    }
    fun addProduct() {
        if (!editable()) return
        val captured = authority ?: return
        val input = state.value
        val epoch = generation
        mutableState.value = input.copy(status = FieldRequestStatus.Reviewing)
        viewModelScope.launch { mutex.withLock {
            val line = safely { gateway.quote(captured, input.record.draft.customerId, input.productId, input.quantity) }
            if (!current(captured, epoch)) return@withLock
            if (line == null) { mutableState.value = input.copy(status = FieldRequestStatus.Unavailable); return@withLock }
            val draft = input.record.draft.copy(lines = input.record.draft.lines.filterNot { it.catalogItemId == line.catalogItemId } + line)
            val record = FieldRequestRecord(draft)
            val saved = store.save(captured, record)
            if (!current(captured, epoch)) return@withLock
            mutableState.value = input.copy(record = record, status = if (saved)
                FieldRequestStatus.Draft else FieldRequestStatus.MetadataUnavailable)
        } }
    }
    fun review() {
        if (!editable() || !state.value.record.draft.valid()) return
        val captured = authority ?: return
        val input = state.value
        val epoch = generation
        mutableState.value = input.copy(status = FieldRequestStatus.Reviewing)
        viewModelScope.launch { mutex.withLock {
            val result = safely { gateway.review(captured, input.record.draft) } ?: FieldRequestReview.Unavailable
            if (!current(captured, epoch)) return@withLock
            when (result) {
                is FieldRequestReview.Current -> {
                    val changed = input.record.draft.customerVersion?.let { it != result.draft.customerVersion } == true ||
                        input.record.draft.lines.zip(result.draft.lines).any { (before, now) ->
                            before.price != now.price || before.currency != now.currency || before.unit != now.unit || before.name != now.name }
                    val record = FieldRequestRecord(result.draft)
                    val saved = store.save(captured, record)
                    if (!current(captured, epoch)) return@withLock
                    mutableState.value = input.copy(record = record, status = if (!saved)
                        FieldRequestStatus.MetadataUnavailable else if (changed) FieldRequestStatus.Changed else FieldRequestStatus.Reviewed)
                }
                FieldRequestReview.PermissionDenied -> mutableState.value = input.copy(status = FieldRequestStatus.PermissionDenied)
                FieldRequestReview.Unavailable -> mutableState.value = input.copy(status = FieldRequestStatus.Unavailable)
            }
        } }
    }
    fun acceptChangedInformation() {
        if (state.value.status == FieldRequestStatus.Changed && editable())
            mutableState.value = state.value.copy(status = FieldRequestStatus.Reviewed)
    }
    fun submit() {
        if (state.value.status != FieldRequestStatus.Reviewed || !state.value.record.draft.valid()) return
        val captured = authority ?: return
        val input = state.value
        val intent = FieldRequestIntent(UUID.randomUUID().toString(), gateway.freeze(input.record.draft))
        execute(captured, input.record.copy(intent = intent), generation)
    }
    fun retryUnknownOutcome() {
        val captured = authority ?: return
        if (state.value.status != FieldRequestStatus.UnknownOutcome) return
        val intent = state.value.record.intent ?: return
        execute(captured, state.value.record.copy(intent = intent.copy(outcome = "Pending")), generation)
    }
    fun startNewDecision() {
        if (state.value.status !in setOf(FieldRequestStatus.Confirmed, FieldRequestStatus.Conflict)) return
        val captured = authority ?: return
        val epoch = generation
        viewModelScope.launch { mutex.withLock {
            val record = if (state.value.status == FieldRequestStatus.Confirmed) FieldRequestRecord()
                else state.value.record.copy(intent = null)
            if (store.save(captured, record) && current(captured, epoch))
                mutableState.value = FieldRequestState(record, FieldRequestStatus.Draft)
        } }
    }
    private fun execute(captured: CommercialAuthority, record: FieldRequestRecord, epoch: Long) {
        mutableState.value = state.value.copy(record = record, status = FieldRequestStatus.Pending)
        viewModelScope.launch { mutex.withLock {
            if (!current(captured, epoch)) return@withLock
            if (!store.save(captured, record)) {
                mutableState.value = state.value.copy(status = FieldRequestStatus.MetadataUnavailable); return@withLock
            }
            if (!current(captured, epoch)) return@withLock
            val result = safely { gateway.submit(captured, record.intent!!) } ?: FieldRequestSubmission.UnknownOutcome
            val outcome = when (result) {
                is FieldRequestSubmission.Confirmed -> "Confirmed"
                FieldRequestSubmission.Conflict, FieldRequestSubmission.Rejected -> "Conflict"
                else -> "UnknownOutcome"
            }
            val updated = record.copy(intent = record.intent!!.copy(outcome = outcome,
                receiptId = (result as? FieldRequestSubmission.Confirmed)?.id))
            val saved = store.save(captured, updated)
            if (!current(captured, epoch)) return@withLock
            mutableState.value = state.value.copy(record = updated, status = if (!saved) FieldRequestStatus.MetadataUnavailable
                else when (outcome) { "Confirmed" -> FieldRequestStatus.Confirmed; "Conflict" -> FieldRequestStatus.Conflict
                    else -> FieldRequestStatus.UnknownOutcome })
        } }
    }
    private fun current(captured: CommercialAuthority, epoch: Long) = authority == captured && generation == epoch
    private suspend fun <T> safely(action: suspend () -> T): T? = try { action() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
}
