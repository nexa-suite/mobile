package com.nexa.mobile.operations.inventoryavailability.application.model.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferReceiptObservation
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferReceiptTransfer
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TransferWarehouseChoice

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
    data class Recorded(val observation: StockTransferReceiptObservation) :
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
