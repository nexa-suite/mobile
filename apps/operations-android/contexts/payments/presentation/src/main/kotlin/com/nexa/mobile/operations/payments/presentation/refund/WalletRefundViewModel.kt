package com.nexa.mobile.operations.payments.presentation.refund

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.payments.application.refund.WalletRefundAuthority
import com.nexa.mobile.operations.payments.application.refund.WalletRefundCandidatesRead
import com.nexa.mobile.operations.payments.application.refund.WalletRefundCoordinator
import com.nexa.mobile.operations.payments.application.refund.WalletRefundGateway
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntent
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntentStore
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntentsRead
import com.nexa.mobile.operations.payments.application.refund.WalletRefundMutationResult
import com.nexa.mobile.operations.payments.application.refund.canReconcilePayments
import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidate
import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidatePage
import com.nexa.mobile.operations.payments.domain.model.WalletRefundPosted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WalletRefundNotice {
    None,
    CandidatesUnavailable,
    AccessDenied,
    MetadataUnavailable,
    MissingReason,
    InvalidAmount,
    AmountExceedsServerBalance,
    ReasonTooLong,
    PendingIntent,
    UnknownOutcome,
    Posted,
    PostedIntentRetained,
    Rejected,
    Conflict,
    IntentUnavailable
}

data class WalletRefundUiState(
    val candidates: List<WalletRefundCandidate> = emptyList(),
    val pendingIntents: List<WalletRefundIntent> = emptyList(),
    val page: Int = 0,
    val total: Long = 0,
    val selectedSalesOrderId: String? = null,
    val amountInput: String = "",
    val reasonInput: String = "",
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val storageAvailable: Boolean = false,
    val lastPosted: WalletRefundPosted? = null,
    val notice: WalletRefundNotice = WalletRefundNotice.None
)

class WalletRefundViewModel(gateway: WalletRefundGateway, store: WalletRefundIntentStore) :
    ViewModel() {
    private val coordinator = WalletRefundCoordinator(gateway, store)
    private val mutableState = MutableStateFlow(WalletRefundUiState())
    val state = mutableState.asStateFlow()
    private var authority: WalletRefundAuthority? = null
    private var generation = 0L
    private val operationMutex = Mutex()

    fun activate(value: WalletRefundAuthority) {
        deactivate()
        if (!value.canReconcilePayments()) {
            mutableState.value = WalletRefundUiState(notice = WalletRefundNotice.AccessDenied)
            return
        }
        val frozen = value.copy(
            identity = value.identity.copy(
                permissions = value.identity.permissions.toSet(),
                roles = value.identity.roles.toSet()
            )
        )
        authority = frozen
        mutableState.value = WalletRefundUiState(isLoading = true)
        val requestGeneration = generation
        viewModelScope.launch {
            operationMutex.withLock {
                if (!current(frozen, requestGeneration)) return@withLock
                val pending = coordinator.loadIntents(frozen)
                if (!current(frozen, requestGeneration)) return@withLock
                when (pending) {
                    WalletRefundIntentsRead.Unavailable -> {
                        mutableState.value = WalletRefundUiState(
                            isLoading = false,
                            notice = WalletRefundNotice.MetadataUnavailable
                        )
                        return@withLock
                    }

                    is WalletRefundIntentsRead.Available -> {
                        mutableState.value = mutableState.value.copy(
                            pendingIntents = pending.intents,
                            storageAvailable = true
                        )
                    }
                }
                loadPage(frozen, requestGeneration, PAGE_START)
            }
        }
    }

    fun deactivate() {
        generation++
        authority = null
        mutableState.value = WalletRefundUiState()
    }

    fun selectOrder(orderId: String) {
        if (mutableState.value.isSubmitting) return
        val candidate = mutableState.value.candidates.singleOrNull { it.salesOrderId == orderId }
            ?: return
        mutableState.value = mutableState.value.copy(
            selectedSalesOrderId = candidate.salesOrderId,
            amountInput = "",
            reasonInput = "",
            notice = WalletRefundNotice.None
        )
    }

    fun amountChanged(value: String) {
        if (!mutableState.value.isSubmitting) {
            mutableState.value = mutableState.value.copy(amountInput = value)
        }
    }

    fun reasonChanged(value: String) {
        if (!mutableState.value.isSubmitting) {
            mutableState.value = mutableState.value.copy(reasonInput = value)
        }
    }

    fun useServerRefundableAmount() {
        if (mutableState.value.isSubmitting) return
        val candidate = selectedCandidate() ?: return
        mutableState.value = mutableState.value.copy(
            amountInput = candidate.refundablePEN.toPlainString(),
            notice = WalletRefundNotice.None
        )
    }

    fun refresh() {
        val currentAuthority = authority ?: return
        if (!mutableState.value.storageAvailable) return
        val requestGeneration = generation
        viewModelScope.launch {
            operationMutex.withLock {
                if (current(currentAuthority, requestGeneration)) {
                    reload(currentAuthority, requestGeneration, mutableState.value.page)
                }
            }
        }
    }

    fun previousPage() = changePage(mutableState.value.page - 1)

    fun nextPage() = changePage(mutableState.value.page + 1)

    fun submit() {
        val currentAuthority = authority ?: return
        if (!mutableState.value.storageAvailable) return
        val candidate = selectedCandidate() ?: return
        val amount = mutableState.value.amountInput
        val reason = mutableState.value.reasonInput
        val requestGeneration = generation
        viewModelScope.launch {
            operationMutex.withLock {
                if (!current(currentAuthority, requestGeneration)) return@withLock
                mutableState.value = mutableState.value.copy(
                    isSubmitting = true,
                    notice = WalletRefundNotice.None
                )
                val result = coordinator.submitNew(
                    currentAuthority,
                    candidate,
                    amount,
                    reason
                ) { current(currentAuthority, requestGeneration) }
                if (!current(currentAuthority, requestGeneration)) return@withLock
                handleMutationResult(result, currentAuthority, requestGeneration)
            }
        }
    }

    fun retry(intent: WalletRefundIntent) {
        val currentAuthority = authority ?: return
        if (!mutableState.value.storageAvailable || mutableState.value.isSubmitting) return
        val requestGeneration = generation
        viewModelScope.launch {
            operationMutex.withLock {
                if (!current(currentAuthority, requestGeneration)) return@withLock
                mutableState.value = mutableState.value.copy(
                    isSubmitting = true,
                    notice = WalletRefundNotice.None
                )
                val result = coordinator.retry(
                    currentAuthority,
                    intent
                ) { current(currentAuthority, requestGeneration) }
                if (!current(currentAuthority, requestGeneration)) return@withLock
                handleMutationResult(result, currentAuthority, requestGeneration)
            }
        }
    }

    private fun changePage(page: Int) {
        val currentAuthority = authority ?: return
        if (page < 0 || mutableState.value.isLoading || mutableState.value.isSubmitting) return
        val requestGeneration = generation
        viewModelScope.launch {
            operationMutex.withLock {
                if (current(currentAuthority, requestGeneration)) {
                    loadPage(currentAuthority, requestGeneration, page)
                }
            }
        }
    }

    private suspend fun reload(
        currentAuthority: WalletRefundAuthority,
        requestGeneration: Long,
        page: Int
    ) {
        mutableState.value = mutableState.value.copy(isLoading = true)
        val pending = coordinator.loadIntents(currentAuthority)
        if (!current(currentAuthority, requestGeneration)) return
        if (pending is WalletRefundIntentsRead.Unavailable) {
            mutableState.value = mutableState.value.copy(
                isLoading = false,
                notice = WalletRefundNotice.MetadataUnavailable
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            pendingIntents = (pending as WalletRefundIntentsRead.Available).intents,
            storageAvailable = true
        )
        loadPage(currentAuthority, requestGeneration, page)
    }

    private suspend fun loadPage(
        currentAuthority: WalletRefundAuthority,
        requestGeneration: Long,
        page: Int
    ) {
        mutableState.value = mutableState.value.copy(isLoading = true)
        val result = coordinator.loadCandidates(currentAuthority, page) {
            current(currentAuthority, requestGeneration)
        }
        if (!current(currentAuthority, requestGeneration)) return
        mutableState.value = when (result) {
            is WalletRefundCandidatesRead.Available -> mutableState.value.copy(
                candidates = result.page.items,
                page = result.page.page,
                total = result.page.total,
                selectedSalesOrderId = null,
                amountInput = "",
                reasonInput = "",
                isLoading = false,
                notice = if (mutableState.value.storageAvailable) {
                    WalletRefundNotice.None
                } else {
                    WalletRefundNotice.MetadataUnavailable
                }
            )

            WalletRefundCandidatesRead.Denied -> mutableState.value.copy(
                isLoading = false,
                notice = WalletRefundNotice.AccessDenied
            )

            WalletRefundCandidatesRead.Unavailable,
            WalletRefundCandidatesRead.ProtocolFailure -> mutableState.value.copy(
                isLoading = false,
                notice = WalletRefundNotice.CandidatesUnavailable
            )
        }
    }

    private suspend fun handleMutationResult(
        result: WalletRefundMutationResult,
        currentAuthority: WalletRefundAuthority,
        requestGeneration: Long
    ) {
        val stateBefore = mutableState.value
        val notice = when (result) {
            is WalletRefundMutationResult.Posted -> if (result.intentCleared) {
                WalletRefundNotice.Posted
            } else {
                WalletRefundNotice.PostedIntentRetained
            }

            is WalletRefundMutationResult.Denied -> WalletRefundNotice.AccessDenied

            is WalletRefundMutationResult.Conflict -> WalletRefundNotice.Conflict

            is WalletRefundMutationResult.Rejected -> WalletRefundNotice.Rejected

            is WalletRefundMutationResult.UnknownOutcome -> WalletRefundNotice.UnknownOutcome

            is WalletRefundMutationResult.PendingIntent -> WalletRefundNotice.PendingIntent

            WalletRefundMutationResult.MetadataUnavailable -> WalletRefundNotice.MetadataUnavailable

            WalletRefundMutationResult.ContextLost -> return

            WalletRefundMutationResult.NotAuthorized -> WalletRefundNotice.AccessDenied

            WalletRefundMutationResult.InvalidAmount -> WalletRefundNotice.InvalidAmount

            WalletRefundMutationResult.AmountExceedsServerBalance ->
                WalletRefundNotice.AmountExceedsServerBalance

            WalletRefundMutationResult.MissingReason -> WalletRefundNotice.MissingReason

            WalletRefundMutationResult.ReasonTooLong -> WalletRefundNotice.ReasonTooLong

            WalletRefundMutationResult.IntentUnavailable -> WalletRefundNotice.IntentUnavailable
        }
        val receipt = (result as? WalletRefundMutationResult.Posted)?.receipt
        val terminalAndCleared = when (result) {
            is WalletRefundMutationResult.Posted -> result.intentCleared
            is WalletRefundMutationResult.Denied -> result.intentCleared
            is WalletRefundMutationResult.Conflict -> result.intentCleared
            is WalletRefundMutationResult.Rejected -> result.intentCleared
            else -> false
        }
        mutableState.value = stateBefore.copy(
            isSubmitting = false,
            lastPosted = receipt ?: stateBefore.lastPosted,
            selectedSalesOrderId = if (terminalAndCleared) {
                null
            } else {
                stateBefore.selectedSalesOrderId
            },
            amountInput = if (terminalAndCleared) "" else stateBefore.amountInput,
            reasonInput = if (terminalAndCleared) "" else stateBefore.reasonInput,
            notice = notice
        )
        if (!current(currentAuthority, requestGeneration)) return
        reload(currentAuthority, requestGeneration, mutableState.value.page)
        if (!current(currentAuthority, requestGeneration)) return
        mutableState.value = mutableState.value.copy(
            isSubmitting = false,
            lastPosted = receipt ?: mutableState.value.lastPosted,
            notice = notice
        )
    }

    private fun selectedCandidate(): WalletRefundCandidate? =
        mutableState.value.selectedSalesOrderId?.let { id ->
            mutableState.value.candidates.singleOrNull { it.salesOrderId == id }
        }

    private fun current(value: WalletRefundAuthority, requestGeneration: Long) =
        authority == value && generation == requestGeneration

    private companion object {
        const val PAGE_START = 0
    }
}
