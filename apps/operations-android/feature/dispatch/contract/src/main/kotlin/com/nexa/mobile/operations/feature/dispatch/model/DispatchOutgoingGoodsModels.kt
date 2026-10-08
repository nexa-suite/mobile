package com.nexa.mobile.operations.feature.dispatch.model

import java.math.BigDecimal
import java.time.Instant

data class DispatchOutgoingGoodsLine(
    val physicalAllocationLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val expectedLotId: String?,
    val allocatedQuantity: BigDecimal,
    val releasedQuantity: BigDecimal,
    val consumedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String,
    val observedLotId: String = "",
    val observedQuantity: String = ""
) {
    override fun toString(): String =
        "DispatchOutgoingGoodsLine(REDACTED, quantity=$remainingQuantity)"
}

data class DispatchOutgoingGoodsAllocation(
    val id: String,
    val status: String,
    val version: Long,
    val asOf: Instant,
    val lines: List<DispatchOutgoingGoodsLine>
)

data class DispatchOutgoingGoodsCheckLine(
    val physicalAllocationLineId: String,
    val expectedLotId: String?,
    val observedLotId: String?,
    val expectedQuantity: BigDecimal,
    val observedQuantity: BigDecimal,
    val unit: String,
    val matches: Boolean
)

data class DispatchOutgoingGoodsDiscrepancy(
    val id: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val checkedByMembershipId: String,
    val checkedAt: Instant,
    val lines: List<DispatchOutgoingGoodsCheckLine>
)

data class DispatchOutgoingGoodsCheck(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val matches: Boolean,
    val current: Boolean,
    val openDiscrepancy: Boolean,
    val checkedAt: Instant,
    val lines: List<DispatchOutgoingGoodsCheckLine>,
    val replayed: Boolean,
    val discrepancy: DispatchOutgoingGoodsDiscrepancy? = null
) {
    override fun toString(): String =
        "DispatchOutgoingGoodsCheck(REDACTED, matches=$matches, current=$current)"
}

data class DispatchOutgoingGoodsResolution(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val discrepancyCheckId: String,
    val matchingCheckId: String,
    val actorMembershipId: String,
    val reason: String,
    val resolvedAt: Instant,
    val current: Boolean,
    val replayed: Boolean
)

enum class DispatchOutgoingGoodsCommandType { RecordCheck, ResolveDiscrepancy }

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

data class DispatchOutgoingGoodsSnapshot(
    val allocation: DispatchOutgoingGoodsAllocation,
    val currentCheck: DispatchOutgoingGoodsCheck?
)

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
