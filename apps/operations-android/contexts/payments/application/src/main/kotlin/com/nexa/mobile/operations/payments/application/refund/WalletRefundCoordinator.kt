package com.nexa.mobile.operations.payments.application.refund

import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidate
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException

/** Coordinates a durable, explicit retry without making the client an authority for refund state. */
class WalletRefundCoordinator(
    private val gateway: WalletRefundGateway,
    private val store: WalletRefundIntentStore
) {
    suspend fun loadIntents(authority: WalletRefundAuthority): WalletRefundIntentsRead =
        if (authority.canReconcilePayments()) {
            store.load(authority)
        } else {
            WalletRefundIntentsRead.Unavailable
        }

    suspend fun loadCandidates(
        authority: WalletRefundAuthority,
        page: Int,
        contextIsCurrent: () -> Boolean
    ): WalletRefundCandidatesRead {
        if (!authority.canReconcilePayments()) return WalletRefundCandidatesRead.Denied
        if (!contextIsCurrent()) return WalletRefundCandidatesRead.Unavailable
        val result = try {
            gateway.candidates(authority, page.coerceAtLeast(0), PAGE_SIZE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WalletRefundCandidatesRead.Unavailable
        }
        return if (contextIsCurrent()) result else WalletRefundCandidatesRead.Unavailable
    }

    suspend fun submitNew(
        authority: WalletRefundAuthority,
        candidate: WalletRefundCandidate,
        amountInput: String,
        reasonInput: String,
        contextIsCurrent: () -> Boolean
    ): WalletRefundMutationResult {
        if (!authority.canReconcilePayments()) return WalletRefundMutationResult.NotAuthorized
        if (!contextIsCurrent()) return WalletRefundMutationResult.ContextLost
        val amount = amountInput.trim().takeIf(AMOUNT_PATTERN::matches)?.toBigDecimalOrNull()
            ?: return WalletRefundMutationResult.InvalidAmount
        if (amount.signum() <= 0 || amount.scale() > MAX_SCALE) {
            return WalletRefundMutationResult.InvalidAmount
        }
        if (amount.compareTo(candidate.refundablePEN) > 0) {
            return WalletRefundMutationResult.AmountExceedsServerBalance
        }
        val reason = reasonInput.trim()
        if (reason.isEmpty()) return WalletRefundMutationResult.MissingReason
        if (reason.length > MAX_REASON_LENGTH) return WalletRefundMutationResult.ReasonTooLong
        val intent = gateway.freezeIntent(candidate.salesOrderId, amount.toPlainString(), reason)
        return when (store.stage(authority, intent)) {
            WalletRefundIntentStage.Unavailable -> WalletRefundMutationResult.MetadataUnavailable

            WalletRefundIntentStage.SameOrderPending -> {
                val existing = (store.load(authority) as? WalletRefundIntentsRead.Available)
                    ?.intents
                    ?.singleOrNull { it.salesOrderId == candidate.salesOrderId }
                    ?: return WalletRefundMutationResult.MetadataUnavailable
                WalletRefundMutationResult.PendingIntent(existing)
            }

            WalletRefundIntentStage.Stored -> {
                if (!contextIsCurrent()) return WalletRefundMutationResult.ContextLost
                execute(authority, intent, contextIsCurrent)
            }
        }
    }

    suspend fun retry(
        authority: WalletRefundAuthority,
        intent: WalletRefundIntent,
        contextIsCurrent: () -> Boolean
    ): WalletRefundMutationResult {
        if (!authority.canReconcilePayments()) return WalletRefundMutationResult.NotAuthorized
        if (!contextIsCurrent()) return WalletRefundMutationResult.ContextLost
        val stored = store.load(authority) as? WalletRefundIntentsRead.Available
            ?: return WalletRefundMutationResult.MetadataUnavailable
        if (stored.intents.none { it == intent }) {
            return WalletRefundMutationResult.IntentUnavailable
        }
        return execute(authority, intent, contextIsCurrent)
    }

    private suspend fun execute(
        authority: WalletRefundAuthority,
        intent: WalletRefundIntent,
        contextIsCurrent: () -> Boolean
    ): WalletRefundMutationResult {
        if (!contextIsCurrent()) return WalletRefundMutationResult.ContextLost
        val submission = try {
            gateway.submit(authority, intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WalletRefundSubmission.UnknownOutcome
        }
        if (submission == WalletRefundSubmission.UnknownOutcome) {
            return if (contextIsCurrent()) {
                WalletRefundMutationResult.UnknownOutcome(intent)
            } else {
                WalletRefundMutationResult.ContextLost
            }
        }
        val cleared = store.remove(authority, intent)
        if (!contextIsCurrent()) return WalletRefundMutationResult.ContextLost
        return when (submission) {
            is WalletRefundSubmission.Posted ->
                WalletRefundMutationResult.Posted(submission.receipt, cleared)

            WalletRefundSubmission.Denied -> WalletRefundMutationResult.Denied(cleared)

            WalletRefundSubmission.Conflict -> WalletRefundMutationResult.Conflict(cleared)

            WalletRefundSubmission.Rejected -> WalletRefundMutationResult.Rejected(cleared)

            WalletRefundSubmission.UnknownOutcome -> error("handled above")
        }
    }

    companion object {
        const val PAGE_SIZE = 25
        private const val MAX_SCALE = 4
        private const val MAX_REASON_LENGTH = 500
        private val AMOUNT_PATTERN = Regex("\\d+(?:\\.\\d{1,4})?")
    }
}
