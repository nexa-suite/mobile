package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryOperationalException
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryOperationalExceptionsSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.isValidOpaqueIdentifier

enum class DriverDeliveryOperationalExceptionAction { Claim, Review, ResolveWarning, CloseWarning }



data class DriverDeliveryOperationalExceptionCommand(
    val deliveryId: String,
    val exceptionId: String,
    val action: DriverDeliveryOperationalExceptionAction,
    val expectedDeliveryVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String
) {
    init {
        require(isValidOpaqueIdentifier(deliveryId))
        require(isValidOpaqueIdentifier(exceptionId))
        require(expectedDeliveryVersion >= 0)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(
            action != DriverDeliveryOperationalExceptionAction.CloseWarning || frozenBody.isEmpty()
        )
    }

    override fun toString(): String =
        "DriverDeliveryOperationalExceptionCommand(action=$action, version=$expectedDeliveryVersion, key=REDACTED)"
}



data class DriverDeliveryOperationalExceptionMutation(
    val deliveryId: String,
    val deliveryVersion: Long,
    val exception: DriverDeliveryOperationalException,
    val replayed: Boolean
) {
    init {
        require(isValidOpaqueIdentifier(deliveryId))
        require(deliveryVersion >= 0)
    }
}



enum class DriverDeliveryOperationalExceptionIntentStatus { Pending, UnknownOutcome, StaleVersion }

/** Encrypted retry metadata; it grants neither process authority nor permission to resolve an exception. */


data class DriverDeliveryOperationalExceptionIntent(
    val scope: DriverAttemptScopeIdentity,
    val command: DriverDeliveryOperationalExceptionCommand,
    val initiatedByMembershipId: String,
    val initiatedAt: String,
    val status: DriverDeliveryOperationalExceptionIntentStatus
) {
    init {
        require(isValidOpaqueIdentifier(initiatedByMembershipId))
        require(initiatedByMembershipId == scope.membershipId)
        require(initiatedAt.isNotBlank())
    }

    override fun toString(): String =
        "DriverDeliveryOperationalExceptionIntent(action=${command.action}, status=$status, command=REDACTED)"
}



sealed interface DriverDeliveryOperationalExceptionMetadataRead {
    data class Available(val intent: DriverDeliveryOperationalExceptionIntent?) :
        DriverDeliveryOperationalExceptionMetadataRead

    data object Unavailable : DriverDeliveryOperationalExceptionMetadataRead
}



sealed interface DriverDeliveryOperationalExceptionMetadataWrite {
    data object Saved : DriverDeliveryOperationalExceptionMetadataWrite
    data object Conflict : DriverDeliveryOperationalExceptionMetadataWrite
    data object Stale : DriverDeliveryOperationalExceptionMetadataWrite
    data object Unavailable : DriverDeliveryOperationalExceptionMetadataWrite
}



sealed interface DriverDeliveryOperationalExceptionsLoadResult {
    data class Loaded(val snapshot: DriverDeliveryOperationalExceptionsSnapshot) :
        DriverDeliveryOperationalExceptionsLoadResult

    data object NotFound : DriverDeliveryOperationalExceptionsLoadResult
    data object NetworkUnavailable : DriverDeliveryOperationalExceptionsLoadResult
    data object ServiceUnavailable : DriverDeliveryOperationalExceptionsLoadResult
    data object PermissionDenied : DriverDeliveryOperationalExceptionsLoadResult
    data object ContextInvalidated : DriverDeliveryOperationalExceptionsLoadResult
    data object SessionInvalidated : DriverDeliveryOperationalExceptionsLoadResult
}



sealed interface DriverDeliveryOperationalExceptionMutationResult {
    data class Changed(val mutation: DriverDeliveryOperationalExceptionMutation) :
        DriverDeliveryOperationalExceptionMutationResult

    data class Rejected(val code: String?) : DriverDeliveryOperationalExceptionMutationResult
    data object NotFound : DriverDeliveryOperationalExceptionMutationResult
    data object StaleVersion : DriverDeliveryOperationalExceptionMutationResult
    data object UnknownOutcome : DriverDeliveryOperationalExceptionMutationResult
    data object NetworkUnavailable : DriverDeliveryOperationalExceptionMutationResult
    data object ServiceUnavailable : DriverDeliveryOperationalExceptionMutationResult
    data object PermissionDenied : DriverDeliveryOperationalExceptionMutationResult
    data object ContextInvalidated : DriverDeliveryOperationalExceptionMutationResult
    data object SessionInvalidated : DriverDeliveryOperationalExceptionMutationResult
}
