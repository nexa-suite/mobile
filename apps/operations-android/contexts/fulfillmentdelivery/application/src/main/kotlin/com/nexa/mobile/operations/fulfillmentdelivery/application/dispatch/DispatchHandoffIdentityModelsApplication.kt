package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchHandoffIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.isValidOpaqueIdentifier

data class DispatchHandoffIdentityCommand(
    val deliveryId: String,
    val assignmentId: String,
    val idempotencyKey: String,
    val frozenBody: String
) {
    init {
        require(isValidOpaqueIdentifier(deliveryId))
        require(isValidOpaqueIdentifier(assignmentId))
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
    }

    override fun toString(): String =
        "DispatchHandoffIdentityCommand(delivery=REDACTED, assignment=REDACTED, key=REDACTED)"
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
