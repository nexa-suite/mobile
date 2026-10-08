package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

import java.math.BigDecimal

data class StockTransferReceiptTransfer(
    val id: String,
    val sourceWarehouseId: String,
    val sourceZoneId: String,
    val sourceLotId: String,
    val destinationWarehouseId: String,
    val destinationZoneId: String,
    val destinationLotId: String?,
    val skuId: String?,
    val catalogItemId: String?,
    val batchNumber: String?,
    val expirationDate: String?,
    val requestedQuantityText: String,
    val transferredQuantityText: String,
    val mode: String,
    val unit: String,
    val status: String,
    val reason: String,
    val sourceVersionBefore: Long,
    val sourceVersionAfter: Long?,
    val destinationVersionAfter: Long?,
    val version: Long,
    val dispatchedAt: String?,
    val receivedAt: String?
) {
    val expectedQuantity: BigDecimal?
        get() = transferredQuantityText.toBigDecimalOrNull()

    val canReceiveExpectedQuantity: Boolean
        get() = status == "IN_TRANSIT" && expectedQuantity?.signum() == 1 && dispatchedAt != null

    override fun toString(): String =
        "StockTransferReceiptTransfer(status=$status, version=$version, quantity=REDACTED)"
}

/** A server-attributed record of arrived facts. It neither receives stock nor changes transfer status. */

data class StockTransferReceiptObservation(
    val observationId: String,
    val transferId: String,
    val transferVersion: Long,
    val observedBatchNumber: String,
    val observedExpirationDate: String?,
    val observedQuantityText: String,
    val observedUnit: String,
    val hasDifference: Boolean,
    val actorMembershipId: String,
    val recordedAt: String
) {
    override fun toString(): String =
        "StockTransferReceiptObservation(id=$observationId, hasDifference=$hasDifference, quantity=REDACTED)"
}
