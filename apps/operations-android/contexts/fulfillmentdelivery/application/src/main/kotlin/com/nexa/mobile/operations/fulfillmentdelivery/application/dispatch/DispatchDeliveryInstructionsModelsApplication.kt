package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionIntentStatus as InstructionIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionKind
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionReceipt
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionsSnapshot

data class DispatchDeliveryInstructionScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispatchDeliveryInstructionScopeIdentity(REDACTED)"
}



enum class DispatchDeliveryInstructionIntentStatus {
    Pending,
    UnknownOutcome
}

/** Exact request, key and version frozen before first publication attempt. */


data class DispatchDeliveryInstructionIntent(
    val scope: DispatchDeliveryInstructionScopeIdentity,
    val deliveryId: String,
    val expectedDeliveryVersion: Long,
    val instructionId: String?,
    val kind: DispatchDeliveryInstructionKind,
    val content: String,
    val exactRequestBody: String,
    val idempotencyKey: String,
    val status: InstructionIntentStatus = InstructionIntentStatus.Pending
) {
    override fun toString(): String = "DispatchDeliveryInstructionIntent(deliveryId=REDACTED, " +
        "version=$expectedDeliveryVersion, status=$status)"
}



sealed interface DispatchDeliveryInstructionMetadataRead {
    data class Available(val intent: DispatchDeliveryInstructionIntent?) :
        DispatchDeliveryInstructionMetadataRead
    data object Unavailable : DispatchDeliveryInstructionMetadataRead
}



enum class DispatchDeliveryInstructionMetadataWrite {
    Saved,
    Conflict,
    Stale,
    Unavailable
}



sealed interface DispatchDeliveryInstructionsGatewayResult {
    data class Snapshot(val value: DispatchDeliveryInstructionsSnapshot) :
        DispatchDeliveryInstructionsGatewayResult
    data class Published(val value: DispatchDeliveryInstructionReceipt) :
        DispatchDeliveryInstructionsGatewayResult
    data class Rejected(val code: String?) : DispatchDeliveryInstructionsGatewayResult
    data object NotFound : DispatchDeliveryInstructionsGatewayResult
    data object StaleVersion : DispatchDeliveryInstructionsGatewayResult
    data object UnknownOutcome : DispatchDeliveryInstructionsGatewayResult
    data object NetworkUnavailable : DispatchDeliveryInstructionsGatewayResult
    data object ServiceUnavailable : DispatchDeliveryInstructionsGatewayResult
    data object PermissionDenied : DispatchDeliveryInstructionsGatewayResult
    data object ContextInvalidated : DispatchDeliveryInstructionsGatewayResult
    data object SessionInvalidated : DispatchDeliveryInstructionsGatewayResult
}
