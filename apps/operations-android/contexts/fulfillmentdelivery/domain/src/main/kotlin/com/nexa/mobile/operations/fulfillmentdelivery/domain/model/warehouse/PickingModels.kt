package com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.PickingAllocationLine
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.PickingAllocationProjection
import java.math.BigDecimal

data class FulfillmentPickingLine(
    val id: String,
    val skuId: String,
    val catalogItemId: String?,
    val allocatedQuantity: BigDecimal,
    val pickedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String
)

/** Server projection returned by GET /api/v1/fulfillments/{id}. */

data class FulfillmentPickingSnapshot(
    val id: String,
    val status: String,
    val version: Long,
    val lines: List<FulfillmentPickingLine>
)

/** Exact current BC-05 allocation projection paired with the BC-06 fulfillment. */

data class PickingOffer(
    val fulfillmentLine: FulfillmentPickingLine?,
    val allocationLine: PickingAllocationLine,
    val ambiguousFulfillmentMatch: Boolean
) {
    val isPickable: Boolean
        get() = !ambiguousFulfillmentMatch &&
            fulfillmentLine != null &&
            allocationLine.remainingQuantity.signum() > 0 &&
            fulfillmentLine.remainingQuantity.signum() > 0 &&
            allocationLine.unit.equals(fulfillmentLine.unit, ignoreCase = true)
}

data class PickingFulfillmentSnapshot(
    val fulfillment: FulfillmentPickingSnapshot,
    val allocation: PickingAllocationProjection
) {
    fun offers(): List<PickingOffer> = allocation.lines.map { allocated ->
        val matching = fulfillment.lines.filter { line ->
            line.skuId == allocated.skuId &&
                (line.catalogItemId == null || line.catalogItemId == allocated.catalogItemId) &&
                line.unit.equals(allocated.unit, ignoreCase = true)
        }
        PickingOffer(
            fulfillmentLine = matching.singleOrNull(),
            allocationLine = allocated,
            ambiguousFulfillmentMatch = matching.size != 1
        )
    }
}
