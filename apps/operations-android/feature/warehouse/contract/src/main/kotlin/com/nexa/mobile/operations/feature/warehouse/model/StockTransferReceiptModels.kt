package com.nexa.mobile.operations.feature.warehouse.model

import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservation as TransferReceiptObservation
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
        "TransferReceiptObservation(id=$observationId, hasDifference=$hasDifference, quantity=REDACTED)"
}

sealed interface StockTransferReceiptLookupResult {
    data class Warehouses(val items: List<TransferWarehouseChoice>) :
        StockTransferReceiptLookupResult
    data class TransferPage(
        val items: List<StockTransferReceiptTransfer>,
        val page: Int,
        val total: Long
    ) : StockTransferReceiptLookupResult
    data class Transfer(val item: StockTransferReceiptTransfer) : StockTransferReceiptLookupResult
    data object NetworkUnavailable : StockTransferReceiptLookupResult
    data object ServiceUnavailable : StockTransferReceiptLookupResult
    data object PermissionDenied : StockTransferReceiptLookupResult
    data object ContextInvalidated : StockTransferReceiptLookupResult
    data object SessionInvalidated : StockTransferReceiptLookupResult
}

sealed interface StockTransferReceiptResult {
    data class Confirmed(val transfer: StockTransferReceiptTransfer) : StockTransferReceiptResult
    data class Rejected(val code: String?) : StockTransferReceiptResult
    data object UnknownOutcome : StockTransferReceiptResult
    data object PreconditionFailed : StockTransferReceiptResult
    data object Conflict : StockTransferReceiptResult
    data object NetworkUnavailable : StockTransferReceiptResult
    data object ServiceUnavailable : StockTransferReceiptResult
    data object PermissionDenied : StockTransferReceiptResult
    data object ContextInvalidated : StockTransferReceiptResult
    data object SessionInvalidated : StockTransferReceiptResult
}

enum class StockTransferReceiptIntentStatus {
    Pending,
    UnknownOutcome
}

data class StockTransferReceiptIntent(
    val scope: StockTransferScope,
    val idempotencyKey: String,
    val transfer: StockTransferReceiptTransfer,
    val status: StockTransferReceiptIntentStatus
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(transfer.id.isNotBlank() && transfer.version >= 0)
    }

    override fun toString(): String = "StockTransferReceiptIntent(status=$status, key=REDACTED)"

    fun sameFrozenCommand(other: StockTransferReceiptIntent): Boolean =
        scope == other.scope && idempotencyKey == other.idempotencyKey && transfer == other.transfer
}

sealed interface StockTransferReceiptMetadataRead {
    data class Available(val value: StockTransferReceiptIntent?) : StockTransferReceiptMetadataRead
    data object Unavailable : StockTransferReceiptMetadataRead
}

enum class StockTransferReceiptMetadataWrite {
    Saved,
    Unavailable
}

enum class StockTransferReceiptObservationIntentStatus {
    Pending,
    UnknownOutcome
}

data class StockTransferReceiptObservationIntent(
    val scope: StockTransferScope,
    val idempotencyKey: String,
    val transfer: StockTransferReceiptTransfer,
    val observedBatchNumber: String,
    val observedExpirationDate: String?,
    val observedQuantityText: String,
    val observedUnit: String,
    val status: StockTransferReceiptObservationIntentStatus
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(transfer.id.isNotBlank() && transfer.version >= 0)
    }

    override fun toString(): String =
        "StockTransferReceiptObservationIntent(status=$status, key=REDACTED)"

    fun sameFrozenCommand(other: StockTransferReceiptObservationIntent): Boolean =
        scope == other.scope && idempotencyKey ==
            other.idempotencyKey && transfer == other.transfer &&
            observedBatchNumber == other.observedBatchNumber &&
            observedExpirationDate == other.observedExpirationDate &&
            observedQuantityText == other.observedQuantityText && observedUnit == other.observedUnit
}

sealed interface StockTransferReceiptObservationResult {
    data class Recorded(val observation: TransferReceiptObservation) :
        StockTransferReceiptObservationResult
    data class Rejected(val code: String?) : StockTransferReceiptObservationResult
    data object UnknownOutcome : StockTransferReceiptObservationResult
    data object PreconditionFailed : StockTransferReceiptObservationResult
    data object Conflict : StockTransferReceiptObservationResult
    data object NetworkUnavailable : StockTransferReceiptObservationResult
    data object ServiceUnavailable : StockTransferReceiptObservationResult
    data object PermissionDenied : StockTransferReceiptObservationResult
    data object ContextInvalidated : StockTransferReceiptObservationResult
    data object SessionInvalidated : StockTransferReceiptObservationResult
}

sealed interface StockTransferReceiptObservationMetadataRead {
    data class Available(val value: StockTransferReceiptObservationIntent?) :
        StockTransferReceiptObservationMetadataRead
    data object Unavailable : StockTransferReceiptObservationMetadataRead
}

enum class StockTransferReceiptObservationMetadataWrite {
    Saved,
    Unavailable
}
