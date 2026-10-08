package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionAcknowledgementSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionsSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.isValidOpaqueIdentifier

data class DriverDeliveryInstructionAcknowledgementCommand(
    val deliveryId: String,
    val instructionSetVersion: Long,
    val instructionVersions: Map<String, Long>,
    val idempotencyKey: String,
    val frozenBody: String
) {
    val instructionIds: List<String> get() = instructionVersions.keys.sorted()

    init {
        require(isValidOpaqueIdentifier(deliveryId))
        require(instructionSetVersion >= 0)
        require(instructionVersions.isNotEmpty())
        require(
            instructionVersions.all { (id, version) ->
                isValidOpaqueIdentifier(id) &&
                    version >= 0
            }
        )
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
    }

    override fun toString(): String =
        "DriverDeliveryInstructionAcknowledgementCommand(setVersion=$instructionSetVersion, ids=${instructionVersions.size}, key=REDACTED)"
}

enum class DriverDeliveryInstructionIntentStatus { Pending, UnknownOutcome, StaleVersion }

/** Encrypted local retry metadata; never an instruction, permission, or assignment authority. */

data class DriverDeliveryInstructionIntentMetadata(
    val scope: DriverAttemptScopeIdentity,
    val command: DriverDeliveryInstructionAcknowledgementCommand,
    val initiatedByMembershipId: String,
    val initiatedAt: String,
    val status: DriverDeliveryInstructionIntentStatus
) {
    init {
        require(isValidOpaqueIdentifier(initiatedByMembershipId))
        require(initiatedAt.isNotBlank())
        require(initiatedByMembershipId == scope.membershipId)
    }

    override fun toString(): String =
        "DriverDeliveryInstructionIntentMetadata(status=$status, command=REDACTED)"
}

sealed interface DriverDeliveryInstructionMetadataRead {
    data class Available(val intent: DriverDeliveryInstructionIntentMetadata?) :
        DriverDeliveryInstructionMetadataRead

    data object Unavailable : DriverDeliveryInstructionMetadataRead
}

sealed interface DriverDeliveryInstructionMetadataWrite {
    data object Saved : DriverDeliveryInstructionMetadataWrite
    data object Conflict : DriverDeliveryInstructionMetadataWrite
    data object Stale : DriverDeliveryInstructionMetadataWrite
    data object Unavailable : DriverDeliveryInstructionMetadataWrite
}

sealed interface DriverDeliveryInstructionsLoadResult {
    data class Loaded(val snapshot: DriverDeliveryInstructionsSnapshot) :
        DriverDeliveryInstructionsLoadResult
    data object NotFound : DriverDeliveryInstructionsLoadResult
    data object NetworkUnavailable : DriverDeliveryInstructionsLoadResult
    data object ServiceUnavailable : DriverDeliveryInstructionsLoadResult
    data object PermissionDenied : DriverDeliveryInstructionsLoadResult
    data object ContextInvalidated : DriverDeliveryInstructionsLoadResult
    data object SessionInvalidated : DriverDeliveryInstructionsLoadResult
}

sealed interface DriverDeliveryInstructionAcknowledgementResult {
    data class Acknowledged(val summary: DriverDeliveryInstructionAcknowledgementSummary) :
        DriverDeliveryInstructionAcknowledgementResult

    data class Rejected(val code: String?) : DriverDeliveryInstructionAcknowledgementResult
    data object NotFound : DriverDeliveryInstructionAcknowledgementResult
    data object StaleVersion : DriverDeliveryInstructionAcknowledgementResult
    data object UnknownOutcome : DriverDeliveryInstructionAcknowledgementResult
    data object NetworkUnavailable : DriverDeliveryInstructionAcknowledgementResult
    data object ServiceUnavailable : DriverDeliveryInstructionAcknowledgementResult
    data object PermissionDenied : DriverDeliveryInstructionAcknowledgementResult
    data object ContextInvalidated : DriverDeliveryInstructionAcknowledgementResult
    data object SessionInvalidated : DriverDeliveryInstructionAcknowledgementResult
}
