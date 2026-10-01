package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import java.math.BigDecimal

@Immutable
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

    override fun toString(): String = "StockTransferReceiptTransfer(status=$status, version=$version, quantity=REDACTED)"
}

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
    ExpectedQuantityOnly
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
    val command: StockTransferReceiptCommandStatus = StockTransferReceiptCommandStatus.Editing,
    val frozenIntent: StockTransferReceiptIntent? = null,
    val confirmed: StockTransferReceiptTransfer? = null,
    val notice: StockTransferReceiptNotice? = null,
    val rejectionCode: String? = null,
    val intentCleanupPending: Boolean = false
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
        ) || intentCleanupPending

    override fun toString(): String =
        "StockTransferReceiptUiState(epoch=$authorityEpoch, command=$command, transfers=${transfers.size})"
}

sealed interface StockTransferReceiptLookupResult {
    data class Warehouses(val items: List<TransferWarehouseChoice>) : StockTransferReceiptLookupResult
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

interface StockTransferReceiptGateway {
    suspend fun warehouses(authority: StockTransferAuthority): StockTransferReceiptLookupResult
    suspend fun transfers(
        destinationWarehouseId: String,
        page: Int,
        authority: StockTransferAuthority
    ): StockTransferReceiptLookupResult
    suspend fun transfer(transferId: String, authority: StockTransferAuthority): StockTransferReceiptLookupResult
    suspend fun receive(
        intent: StockTransferReceiptIntent,
        authority: StockTransferAuthority
    ): StockTransferReceiptResult
}

enum class StockTransferReceiptIntentStatus { Pending, UnknownOutcome }

@Immutable
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

enum class StockTransferReceiptMetadataWrite { Saved, Unavailable }

interface StockTransferReceiptMetadataStore {
    suspend fun loadIntent(scope: StockTransferScope): StockTransferReceiptMetadataRead
    suspend fun saveIntent(intent: StockTransferReceiptIntent): StockTransferReceiptMetadataWrite
    suspend fun markUnknownOutcome(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptMetadataWrite
    suspend fun clearIntent(scope: StockTransferScope, idempotencyKey: String): StockTransferReceiptMetadataWrite
}
