package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

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



data class DispatchOutgoingGoodsSnapshot(
    val allocation: DispatchOutgoingGoodsAllocation,
    val currentCheck: DispatchOutgoingGoodsCheck?
)
