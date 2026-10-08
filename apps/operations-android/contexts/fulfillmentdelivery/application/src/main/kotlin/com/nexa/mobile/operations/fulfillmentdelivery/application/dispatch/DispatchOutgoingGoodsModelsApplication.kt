package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCheck
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCommandType
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsResolution
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsSnapshot
import java.math.BigDecimal

data class DispatchOutgoingGoodsObservation(
    val physicalAllocationLineId: String,
    val observedLotId: String?,
    val observedQuantity: BigDecimal
)

data class DispatchOutgoingGoodsCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val observations: List<DispatchOutgoingGoodsObservation>,
    val idempotencyKey: String,
    val exactRequestBody: String,
    val type: DispatchOutgoingGoodsCommandType = DispatchOutgoingGoodsCommandType.RecordCheck,
    val discrepancyCheckId: String? = null,
    val matchingCheckId: String? = null,
    val reason: String? = null
) {
    override fun toString(): String = "DispatchOutgoingGoodsCommand(REDACTED, " +
        "versions=$expectedFulfillmentVersion/$physicalAllocationVersion)"
}

data class DispatchOutgoingGoodsScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispatchOutgoingGoodsScopeIdentity(REDACTED)"
}

enum class DispatchOutgoingGoodsIntentStatus { Pending, UnknownOutcome }

/** Frozen request intent is encrypted and scoped before a mutating request can leave the device. */

data class DispatchOutgoingGoodsIntent(
    val scope: DispatchOutgoingGoodsScopeIdentity,
    val command: DispatchOutgoingGoodsCommand,
    val status: DispatchOutgoingGoodsIntentStatus = DispatchOutgoingGoodsIntentStatus.Pending
) {
    override fun toString(): String = "DispatchOutgoingGoodsIntent(REDACTED, status=$status)"
}

sealed interface DispatchOutgoingGoodsMetadataRead {
    data class Available(val intent: DispatchOutgoingGoodsIntent?) :
        DispatchOutgoingGoodsMetadataRead
    data object Unavailable : DispatchOutgoingGoodsMetadataRead
}

enum class DispatchOutgoingGoodsMetadataWrite { Saved, Conflict, Stale, Unavailable }

sealed interface DispatchOutgoingGoodsGatewayResult {
    data class Snapshot(val value: DispatchOutgoingGoodsSnapshot) :
        DispatchOutgoingGoodsGatewayResult
    data class Recorded(val value: DispatchOutgoingGoodsCheck) : DispatchOutgoingGoodsGatewayResult
    data class Resolved(val value: DispatchOutgoingGoodsResolution) :
        DispatchOutgoingGoodsGatewayResult
    data object UnknownOutcome : DispatchOutgoingGoodsGatewayResult
    data object NetworkUnavailable : DispatchOutgoingGoodsGatewayResult
    data object ServiceUnavailable : DispatchOutgoingGoodsGatewayResult
    data object PermissionDenied : DispatchOutgoingGoodsGatewayResult
    data object Stale : DispatchOutgoingGoodsGatewayResult
    data object Conflict : DispatchOutgoingGoodsGatewayResult
    data object ContextInvalidated : DispatchOutgoingGoodsGatewayResult
    data object SessionInvalidated : DispatchOutgoingGoodsGatewayResult
}
