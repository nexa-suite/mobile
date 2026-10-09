package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

import java.math.BigDecimal

data class TransferWarehouseChoice(
    val id: String,
    val code: String,
    val name: String,
    val status: String
) {
    val isSelectable: Boolean get() = id.isNotBlank() && status.equals("ACTIVE", ignoreCase = true)
}

data class TransferZoneChoice(
    val id: String,
    val warehouseId: String,
    val code: String,
    val name: String,
    val status: String
) {
    val isSelectable: Boolean get() = id.isNotBlank() && status.equals("ACTIVE", ignoreCase = true)
}

/** Source options are current server lot projections from the active authority epoch. */

data class TransferSourceLotChoice(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val physicalRemaining: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
) {
    val isSelectable: Boolean
        get() = status !in setOf("EXPIRED", "DEPLETED") && physicalRemaining.signum() > 0

    override fun toString(): String =
        "TransferSourceLotChoice(status=$status, quantity=REDACTED, version=$version)"
}

/** Exact decimal lexeme is kept inside the frozen payload and never rounded. */
data class StockTransferRequest(
    val sourceLotId: String,
    val sourceWarehouseId: String,
    val sourceZoneId: String,
    val destinationWarehouseId: String,
    val destinationZoneId: String,
    val skuId: String?,
    val catalogItemId: String?,
    val quantityText: String,
    val unit: String,
    val reason: String
) {
    override fun toString(): String = "StockTransferRequest(quantity=REDACTED, reason=REDACTED)"
}

data class ConfirmedStockTransfer(
    val id: String,
    val status: String,
    val sourceLotId: String,
    val sourceWarehouseId: String,
    val sourceZoneId: String,
    val destinationWarehouseId: String,
    val destinationZoneId: String,
    val requestedQuantity: BigDecimal,
    val transferredQuantity: BigDecimal,
    val unit: String,
    val sourceVersionBefore: Long,
    val version: Long
)
