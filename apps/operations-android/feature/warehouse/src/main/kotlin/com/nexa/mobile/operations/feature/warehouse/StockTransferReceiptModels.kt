package com.nexa.mobile.operations.feature.warehouse

import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservation as TransferReceiptObservation
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptTransfer
import com.nexa.mobile.operations.feature.warehouse.model.TransferWarehouseChoice

enum class StockTransferReceiptCommandStatus {
    Editing,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Confirmed,
    Rejected,
    PreconditionFailed,
    Conflict,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class StockTransferReceiptNotice {
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    PreconditionFailed,
    Conflict,
    MetadataUnavailable,
    CurrentTransferUnavailable,
    ExpectedQuantityOnly,
    ObservedDifferenceRequiresResolution
}

enum class StockTransferReceiptObservationCommandStatus {
    Editing,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Recorded,
    Rejected,
    PreconditionFailed,
    Conflict,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class StockTransferReceiptObservationNotice {
    InvalidBatch,
    InvalidExpirationDate,
    InvalidQuantity,
    UnitMismatch,
    NoDifference,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    PreconditionFailed,
    Conflict,
    MetadataUnavailable,
    CurrentTransferUnavailable
}

data class StockTransferReceiptUiState(
    val authorityEpoch: Long = 0,
    val canLookUp: Boolean = false,
    val canReceive: Boolean = false,
    val warehouses: List<TransferWarehouseChoice> = emptyList(),
    val warehouseLookup: TransferLookupStatus = TransferLookupStatus.NotRequested,
    val selectedDestinationWarehouseId: String? = null,
    val transfers: List<StockTransferReceiptTransfer> = emptyList(),
    val transferLookup: TransferLookupStatus = TransferLookupStatus.NotRequested,
    val page: Int = 0,
    val total: Long = 0,
    val selectedTransferId: String? = null,
    val metadata: TransferMetadataStatus = TransferMetadataStatus.Loading,
    val observationMetadata: TransferMetadataStatus = TransferMetadataStatus.Loading,
    val command: StockTransferReceiptCommandStatus = StockTransferReceiptCommandStatus.Editing,
    val frozenIntent: StockTransferReceiptIntent? = null,
    val observationCommand: StockTransferReceiptObservationCommandStatus =
        StockTransferReceiptObservationCommandStatus.Editing,
    val frozenObservationIntent: StockTransferReceiptObservationIntent? = null,
    val confirmed: StockTransferReceiptTransfer? = null,
    val recordedObservation: TransferReceiptObservation? = null,
    val notice: StockTransferReceiptNotice? = null,
    val observationNotice: StockTransferReceiptObservationNotice? = null,
    val rejectionCode: String? = null,
    val intentCleanupPending: Boolean = false,
    val observationCleanupPending: Boolean = false
) {
    val selectedTransfer: StockTransferReceiptTransfer?
        get() = transfers.firstOrNull { it.id == selectedTransferId }

    val hasMoreTransfers: Boolean
        get() = transferLookup == TransferLookupStatus.Ready && transfers.size.toLong() < total

    val isFrozen: Boolean
        get() = command in setOf(
            StockTransferReceiptCommandStatus.PersistingIntent,
            StockTransferReceiptCommandStatus.Pending,
            StockTransferReceiptCommandStatus.UnknownOutcome
        ) || intentCleanupPending || observationCommand in setOf(
            StockTransferReceiptObservationCommandStatus.PersistingIntent,
            StockTransferReceiptObservationCommandStatus.Pending,
            StockTransferReceiptObservationCommandStatus.UnknownOutcome
        ) || observationCleanupPending

    override fun toString(): String =
        "StockTransferReceiptUiState(epoch=$authorityEpoch, command=$command, transfers=${transfers.size})"
}
