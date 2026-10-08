package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchHandoverReceipt
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchHandoverSnapshot

data class DispatchHandoverCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val driverAssignmentId: String,
    val driverAssignmentVersion: Long,
    val outgoingGoodsCheckId: String,
    val idempotencyKey: String,
    val exactRequestBody: String
) {
    override fun toString(): String =
        "DispatchHandoverCommand(REDACTED, version=$expectedFulfillmentVersion)"

    fun isValid(): Boolean = listOf(
        fulfillmentId,
        physicalAllocationId,
        driverAssignmentId,
        outgoingGoodsCheckId
    ).all(UUID_PATTERN::matches) && expectedFulfillmentVersion >= 0 &&
        physicalAllocationVersion >= 0 &&
        driverAssignmentVersion >= 0 &&
        idempotencyKey.isNotBlank() && idempotencyKey.length <= 160

    private companion object {
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}



data class DispatchHandoverIntent(
    val scope: DispatchOutgoingGoodsScopeIdentity,
    val command: DispatchHandoverCommand,
    val status: DispatchOutgoingGoodsIntentStatus = DispatchOutgoingGoodsIntentStatus.Pending
) {
    override fun toString(): String = "DispatchHandoverIntent(REDACTED, status=$status)"
}



sealed interface DispatchHandoverMetadataRead {
    data class Available(val intent: DispatchHandoverIntent?) : DispatchHandoverMetadataRead
    data object Unavailable : DispatchHandoverMetadataRead
}



enum class DispatchHandoverMetadataWrite {
    Saved,
    Conflict,
    Stale,
    Unavailable
}



sealed interface DispatchHandoverGatewayResult {
    data class Snapshot(val value: DispatchHandoverSnapshot) : DispatchHandoverGatewayResult
    data class AlreadyCompleted(val receipt: DispatchHandoverReceipt) :
        DispatchHandoverGatewayResult
    data class Dispatched(val receipt: DispatchHandoverReceipt) : DispatchHandoverGatewayResult
    data object UnknownOutcome : DispatchHandoverGatewayResult
    data object NetworkUnavailable : DispatchHandoverGatewayResult
    data object ServiceUnavailable : DispatchHandoverGatewayResult
    data object PermissionDenied : DispatchHandoverGatewayResult
    data object Stale : DispatchHandoverGatewayResult
    data object Conflict : DispatchHandoverGatewayResult
    data object ContextInvalidated : DispatchHandoverGatewayResult
    data object SessionInvalidated : DispatchHandoverGatewayResult
}
