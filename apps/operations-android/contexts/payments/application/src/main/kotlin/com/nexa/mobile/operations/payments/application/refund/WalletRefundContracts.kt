package com.nexa.mobile.operations.payments.application.refund

import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidatePage
import com.nexa.mobile.operations.payments.domain.model.WalletRefundPosted
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity

data class WalletRefundAuthority(
    val identity: VerifiedOperationsIdentity,
    val authorityEpoch: Long
) {
    init {
        require(authorityEpoch > 0)
    }

    override fun toString(): String = "WalletRefundAuthority(REDACTED)"
}

/** Membership role and permission hints gate presentation; the API remains authoritative. */
fun WalletRefundAuthority.canReconcilePayments(): Boolean =
    identity.roles.any { it in WALLET_REFUND_ROLES } &&
        WALLET_REFUND_PERMISSION in identity.permissions

internal val WALLET_REFUND_ROLES = setOf("BUSINESS_OPERATIONS_MANAGER", "COMPANY_OWNER")
internal const val WALLET_REFUND_PERMISSION = "payment.reconcile"

data class WalletRefundIntent(
    val salesOrderId: String,
    val amountPEN: String,
    val reason: String,
    val idempotencyKey: String,
    val payloadHash: String,
    val exactPayload: String
) {
    override fun toString(): String =
        "WalletRefundIntent(order=REDACTED, amount=REDACTED, reason=REDACTED, key=REDACTED)"
}

sealed interface WalletRefundIntentsRead {
    data class Available(val intents: List<WalletRefundIntent>) : WalletRefundIntentsRead
    data object Unavailable : WalletRefundIntentsRead
}

sealed interface WalletRefundIntentStage {
    data object Stored : WalletRefundIntentStage
    data object SameOrderPending : WalletRefundIntentStage
    data object Unavailable : WalletRefundIntentStage
}

interface WalletRefundIntentStore {
    suspend fun load(authority: WalletRefundAuthority): WalletRefundIntentsRead
    suspend fun stage(
        authority: WalletRefundAuthority,
        intent: WalletRefundIntent
    ): WalletRefundIntentStage

    suspend fun remove(authority: WalletRefundAuthority, intent: WalletRefundIntent): Boolean
}

sealed interface WalletRefundCandidatesRead {
    data class Available(val page: WalletRefundCandidatePage) : WalletRefundCandidatesRead
    data object Unavailable : WalletRefundCandidatesRead
    data object Denied : WalletRefundCandidatesRead
    data object ProtocolFailure : WalletRefundCandidatesRead
}

sealed interface WalletRefundSubmission {
    data class Posted(val receipt: WalletRefundPosted) : WalletRefundSubmission
    data object Denied : WalletRefundSubmission
    data object Conflict : WalletRefundSubmission
    data object Rejected : WalletRefundSubmission
    data object UnknownOutcome : WalletRefundSubmission
}

interface WalletRefundGateway {
    fun freezeIntent(salesOrderId: String, amountPEN: String, reason: String): WalletRefundIntent

    suspend fun candidates(
        authority: WalletRefundAuthority,
        page: Int,
        size: Int
    ): WalletRefundCandidatesRead

    suspend fun submit(
        authority: WalletRefundAuthority,
        intent: WalletRefundIntent
    ): WalletRefundSubmission
}

sealed interface WalletRefundMutationResult {
    data class Posted(val receipt: WalletRefundPosted, val intentCleared: Boolean) :
        WalletRefundMutationResult

    data class Denied(val intentCleared: Boolean) : WalletRefundMutationResult
    data class Conflict(val intentCleared: Boolean) : WalletRefundMutationResult
    data class Rejected(val intentCleared: Boolean) : WalletRefundMutationResult
    data class UnknownOutcome(val intent: WalletRefundIntent) : WalletRefundMutationResult
    data class PendingIntent(val intent: WalletRefundIntent) : WalletRefundMutationResult
    data object MetadataUnavailable : WalletRefundMutationResult
    data object ContextLost : WalletRefundMutationResult
    data object NotAuthorized : WalletRefundMutationResult
    data object InvalidAmount : WalletRefundMutationResult
    data object AmountExceedsServerBalance : WalletRefundMutationResult
    data object MissingReason : WalletRefundMutationResult
    data object ReasonTooLong : WalletRefundMutationResult
    data object IntentUnavailable : WalletRefundMutationResult
}
