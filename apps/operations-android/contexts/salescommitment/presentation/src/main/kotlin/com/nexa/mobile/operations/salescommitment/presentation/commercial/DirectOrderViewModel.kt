package com.nexa.mobile.operations.salescommitment.presentation.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderExecution
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderRead
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderReview
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderStore
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderSubmissionCoordinator
import com.nexa.mobile.operations.salescommitment.application.commercial.isValidForSubmission
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderDraft
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DirectOrderViewModel(
    private val gateway: DirectOrderGateway,
    private val store: DirectOrderStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(DirectOrderState())
    val state = mutableState.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L
    private val mutex = Mutex()
    private val submission = DirectOrderSubmissionCoordinator(gateway, store)
    fun deactivate() {
        generation++
        authority = null
        mutableState.value = DirectOrderState()
    }
    fun activate(
        value: CommercialAuthority,
        requestedCustomerId: String? = null,
        requestedProductId: String? = null
    ) {
        deactivate()
        authority = value.copy(permissions = value.permissions.toSet())
        val epoch = generation
        viewModelScope.launch {
            mutex.withLock {
                val loaded = store.load(value)
                if (!current(value, epoch)) return@withLock
                mutableState.value = when (loaded) {
                    DirectOrderRead.Unavailable -> DirectOrderState(
                        status = DirectOrderStatus.MetadataUnavailable
                    )

                    is DirectOrderRead.Available -> {
                        val prior = loaded.record
                        val record = if (requestedCustomerId != null && prior.intent == null &&
                            prior.draft.lines.isEmpty()
                        ) {
                            val selected = prior.copy(
                                draft = prior.draft.copy(
                                    customerId = requestedCustomerId,
                                    customerVersion = null
                                )
                            )
                            if (!store.save(value, selected)) {
                                mutableState.value =
                                    DirectOrderState(
                                        status = DirectOrderStatus.MetadataUnavailable
                                    )
                                return@withLock
                            }
                            selected
                        } else {
                            prior
                        }
                        if (!current(value, epoch)) return@withLock
                        DirectOrderState(
                            record,
                            record.statusFromStoredIntent(),
                            productId = if (record.intent == null &&
                                record.draft.customerId == requestedCustomerId
                            ) {
                                requestedProductId ?: ""
                            } else {
                                ""
                            }
                        )
                    }
                }
            }
        }
    }
    fun productChanged(value: String) {
        if (editable()) {
            mutableState.value =
                state.value.copy(productId = value.trim())
        }
    }
    fun quantityChanged(value: String) {
        if (editable()) {
            mutableState.value =
                state.value.copy(quantity = value.take(32))
        }
    }
    fun customerChanged(value: String) =
        edit { it.copy(customerId = value.trim(), customerVersion = null, lines = emptyList()) }
    fun deliveryDateChanged(value: String) = edit { it.copy(deliveryDate = value.take(10)) }
    fun deliveryProfileChanged(value: String) = edit { it.copy(deliveryProfile = value.take(2000)) }
    fun paymentChanged(value: String) = edit { it.copy(paymentOption = value) }
    fun commentChanged(value: String) = edit { it.copy(comment = value.take(2000)) }
    fun removeLine(id: String) = edit {
        it.copy(
            lines = it.lines.filterNot { line ->
                line.catalogItemId ==
                    id
            }
        )
    }
    fun lineQuantityChanged(id: String, value: String) = edit { draft ->
        draft.copy(
            lines = draft.lines.map {
                if (it.catalogItemId ==
                    id
                ) {
                    it.copy(quantity = value.take(32))
                } else {
                    it
                }
            }
        )
    }
    private fun editable(): Boolean = authority != null && state.value.record.intent == null &&
        state.value.status !in
        setOf(
            DirectOrderStatus.Loading,
            DirectOrderStatus.MetadataUnavailable,
            DirectOrderStatus.Reviewing,
            DirectOrderStatus.Pending
        )
    private fun edit(change: (DirectOrderDraft) -> DirectOrderDraft) {
        if (!editable()) return
        val captured = authority ?: return
        val epoch = generation
        val record = state.value.record.copy(draft = change(state.value.record.draft))
        mutableState.value = state.value.copy(record = record, status = DirectOrderStatus.Draft)
        viewModelScope.launch {
            mutex.withLock {
                if (!current(captured, epoch) || state.value.record.intent != null) return@withLock
                // Persist the latest draft, rather than an older keystroke captured by this job.
                if (!store.save(captured, state.value.record) && current(captured, epoch)) {
                    mutableState.value =
                        state.value.copy(status = DirectOrderStatus.MetadataUnavailable)
                }
            }
        }
    }
    fun addProduct() {
        if (!editable()) return
        val captured = authority ?: return
        val input = state.value
        val epoch = generation
        mutableState.value = input.copy(status = DirectOrderStatus.Reviewing)
        viewModelScope.launch {
            mutex.withLock {
                val line =
                    safely {
                        gateway.quote(
                            captured,
                            input.record.draft.customerId,
                            input.productId,
                            input.quantity
                        )
                    }
                if (!current(captured, epoch)) return@withLock
                if (line ==
                    null
                ) {
                    mutableState.value = input.copy(status = DirectOrderStatus.Unavailable)
                    return@withLock
                }
                val draft = input.record.draft.copy(
                    lines =
                        input.record.draft.lines.filterNot {
                            it.catalogItemId == line.catalogItemId
                        } + line
                )
                val record = DirectOrderRecord(draft)
                val saved = store.save(captured, record)
                if (!current(captured, epoch)) return@withLock
                mutableState.value = input.copy(
                    record = record,
                    status = if (saved) {
                        DirectOrderStatus.Draft
                    } else {
                        DirectOrderStatus.MetadataUnavailable
                    }
                )
            }
        }
    }
    fun review() {
        if (!editable() || !state.value.record.draft.isValidForSubmission()) return
        val captured = authority ?: return
        val input = state.value
        val epoch = generation
        mutableState.value = input.copy(status = DirectOrderStatus.Reviewing)
        viewModelScope.launch {
            mutex.withLock {
                val result =
                    safely { gateway.review(captured, input.record.draft) }
                        ?: DirectOrderReview.Unavailable
                if (!current(captured, epoch)) return@withLock
                when (result) {
                    is DirectOrderReview.Current -> {
                        val changed =
                            input.record.draft.customerVersion?.let {
                                it != result.draft.customerVersion
                            } ==
                                true ||
                                input.record.draft.lines.zip(
                                    result.draft.lines
                                ).any { (before, now) ->
                                    before.price != now.price || before.currency != now.currency ||
                                        before.unit != now.unit ||
                                        before.name != now.name
                                }
                        val record = DirectOrderRecord(result.draft)
                        val saved = store.save(captured, record)
                        if (!current(captured, epoch)) return@withLock
                        mutableState.value = input.copy(
                            record = record,
                            status = if (!saved) {
                                DirectOrderStatus.MetadataUnavailable
                            } else if (changed) {
                                DirectOrderStatus.Changed
                            } else {
                                DirectOrderStatus.Reviewed
                            }
                        )
                    }

                    DirectOrderReview.PermissionDenied ->
                        mutableState.value =
                            input.copy(status = DirectOrderStatus.PermissionDenied)

                    DirectOrderReview.Unavailable ->
                        mutableState.value =
                            input.copy(status = DirectOrderStatus.Unavailable)
                }
            }
        }
    }
    fun acceptChangedInformation() {
        if (state.value.status == DirectOrderStatus.Changed && editable()) {
            mutableState.value = state.value.copy(status = DirectOrderStatus.Reviewed)
        }
    }
    fun submit() {
        if (state.value.status != DirectOrderStatus.Reviewed ||
            !state.value.record.draft.isValidForSubmission()
        ) {
            return
        }
        val captured = authority ?: return
        val input = state.value
        val intent =
            DirectOrderIntent(UUID.randomUUID().toString(), gateway.freeze(input.record.draft))
        execute(captured, input.record.copy(intent = intent), generation)
    }
    fun retryUnknownOutcome() {
        val captured = authority ?: return
        if (state.value.status != DirectOrderStatus.UnknownOutcome) return
        val intent = state.value.record.intent ?: return
        if (intent.operation != DirectOrderIntent.DIRECT_ORDER_OPERATION) return
        execute(
            captured,
            state.value.record.copy(intent = intent.copy(outcome = "Pending")),
            generation
        )
    }
    fun startNewDecision() {
        if (!state.value.status.permitsNewDecision) return
        val captured = authority ?: return
        val epoch = generation
        viewModelScope.launch {
            mutex.withLock {
                val record = if (state.value.status ==
                    DirectOrderStatus.Confirmed ||
                    state.value.status == DirectOrderStatus.PrepaidPending
                ) {
                    DirectOrderRecord()
                } else {
                    state.value.record.copy(intent = null)
                }
                if (store.save(captured, record) && current(captured, epoch)) {
                    mutableState.value = DirectOrderState(record, DirectOrderStatus.Draft)
                }
            }
        }
    }
    private fun execute(captured: CommercialAuthority, record: DirectOrderRecord, epoch: Long) {
        mutableState.value = state.value.copy(record = record, status = DirectOrderStatus.Pending)
        viewModelScope.launch {
            mutex.withLock {
                val execution = submission.execute(captured, record) {
                    current(captured, epoch)
                }
                if (!current(captured, epoch)) return@withLock
                when (execution) {
                    DirectOrderExecution.ContextLost -> return@withLock

                    DirectOrderExecution.MetadataUnavailable ->
                        mutableState.value =
                            state.value.copy(status = DirectOrderStatus.MetadataUnavailable)

                    is DirectOrderExecution.Resolved -> mutableState.value = state.value.copy(
                        record = execution.record,
                        status = if (!execution.persisted) {
                            DirectOrderStatus.MetadataUnavailable
                        } else {
                            when (execution.record.intent?.outcome) {
                                "Confirmed" -> DirectOrderStatus.Confirmed
                                "PrepaidPending" -> DirectOrderStatus.PrepaidPending
                                "Conflict" -> DirectOrderStatus.Conflict
                                "PermissionDenied" -> DirectOrderStatus.PermissionDenied
                                "Rejected" -> DirectOrderStatus.Rejected
                                "Unavailable" -> DirectOrderStatus.Unavailable
                                "LegacyIntent" -> DirectOrderStatus.LegacyIntent
                                else -> DirectOrderStatus.UnknownOutcome
                            }
                        }
                    )
                }
            }
        }
    }
    private fun current(captured: CommercialAuthority, epoch: Long) =
        authority == captured && generation == epoch

    private fun DirectOrderRecord.statusFromStoredIntent(): DirectOrderStatus {
        val intent = intent ?: return DirectOrderStatus.Draft
        if (intent.operation != DirectOrderIntent.DIRECT_ORDER_OPERATION) {
            return DirectOrderStatus.LegacyIntent
        }
        return when (intent.outcome) {
            "Confirmed" -> DirectOrderStatus.Confirmed
            "PrepaidPending" -> DirectOrderStatus.PrepaidPending
            "Conflict" -> DirectOrderStatus.Conflict
            "PermissionDenied" -> DirectOrderStatus.PermissionDenied
            "Rejected" -> DirectOrderStatus.Rejected
            "Unavailable" -> DirectOrderStatus.Unavailable
            "LegacyIntent" -> DirectOrderStatus.LegacyIntent
            else -> DirectOrderStatus.UnknownOutcome
        }
    }
    private suspend fun <T> safely(action: suspend () -> T): T? = try {
        action()
    } catch (
        cancelled: CancellationException
    ) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
