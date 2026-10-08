package com.nexa.mobile.operations.feature.dispatch.model

val DISPATCH_HANDOFF_UUID =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class DispatchHandoffIdentityCommand(
    val deliveryId: String,
    val assignmentId: String,
    val idempotencyKey: String,
    val frozenBody: String
) {
    init {
        require(DISPATCH_HANDOFF_UUID.matches(deliveryId))
        require(DISPATCH_HANDOFF_UUID.matches(assignmentId))
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(frozenBody == dispatchHandoffIssueBody(assignmentId))
    }

    override fun toString(): String =
        "DispatchHandoffIdentityCommand(delivery=REDACTED, assignment=REDACTED, key=REDACTED)"
}

fun dispatchHandoffIssueBody(assignmentId: String): String =

    """{"purpose":"DISPATCH_HANDOFF","assignmentId":"$assignmentId"}"""
data class DispatchHandoffIdentity(
    val handoffId: String,
    val deliveryId: String,
    val assignmentId: String,
    val deliveryVersion: Long,
    val expiresAt: String,
    val status: String
) {
    override fun toString(): String =
        "DispatchHandoffIdentity(delivery=REDACTED, assignment=REDACTED, status=$status)"
}

sealed interface DispatchHandoffIssueResult {
    data class Issued(val identity: DispatchHandoffIdentity, val token: String) :
        DispatchHandoffIssueResult {
        override fun toString(): String = "DispatchHandoffIssueResult.Issued(token=REDACTED)"
    }

    data class AcceptedWithoutToken(val identity: DispatchHandoffIdentity) :
        DispatchHandoffIssueResult
    data class Rejected(val code: String?) : DispatchHandoffIssueResult
    data object NotFound : DispatchHandoffIssueResult
    data object UnknownOutcome : DispatchHandoffIssueResult
    data object Unavailable : DispatchHandoffIssueResult
    data object PermissionDenied : DispatchHandoffIssueResult
    data object ContextInvalidated : DispatchHandoffIssueResult
    data object SessionInvalidated : DispatchHandoffIssueResult
}

sealed interface DispatchHandoffValidationResult {
    data class Validated(val identity: DispatchHandoffIdentity) : DispatchHandoffValidationResult
    data class Rejected(val code: String?) : DispatchHandoffValidationResult
    data object NotFound : DispatchHandoffValidationResult
    data object Unavailable : DispatchHandoffValidationResult
    data object PermissionDenied : DispatchHandoffValidationResult
    data object ContextInvalidated : DispatchHandoffValidationResult
    data object SessionInvalidated : DispatchHandoffValidationResult
}

sealed interface DispatchHandoffMetadataRead {
    data class Available(val command: DispatchHandoffIdentityCommand?) : DispatchHandoffMetadataRead
    data object Unavailable : DispatchHandoffMetadataRead
}

enum class DispatchHandoffMetadataWrite { Saved, Conflict, Stale, Unavailable }
