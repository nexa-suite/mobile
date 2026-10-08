package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class StockConditionLot(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val expirationDate: LocalDate,
    val receivedAt: Instant,
    val onHand: BigDecimal,
    val reserved: BigDecimal,
    /** Server's `available` lot projection is physical remainder, not sellable quantity. */
    val physicalRemaining: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String =
        "StockConditionLot(id=REDACTED, warehouseId=REDACTED, quantities=REDACTED)"
}

data class StockConditionAvailability(
    val catalogItemId: String,
    val status: String,
    val asOf: Instant,
    val physicalQuantity: BigDecimal?,
    val safetyStock: BigDecimal?,
    val sellableQuantity: BigDecimal?
)
