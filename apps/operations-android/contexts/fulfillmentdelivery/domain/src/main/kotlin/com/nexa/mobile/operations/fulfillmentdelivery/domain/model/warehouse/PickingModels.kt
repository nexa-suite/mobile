package com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse

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
