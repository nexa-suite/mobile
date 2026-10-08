package com.nexa.mobile.operations.inventoryavailability.application.model.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ConfirmedStockTransfer
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TransferSourceLotChoice
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TransferWarehouseChoice
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TransferZoneChoice

data class StockTransferAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
        require(authorityEpoch > 0)
    }

    val scope: StockTransferScope
        get() = StockTransferScope(userId, tenantId, workspaceId, membershipId)

    val canLookUp: Boolean
        get() = permissions.any { it in LOOKUP_PERMISSIONS }

    val canCreate: Boolean
        get() = "warehouse:write" in permissions

    override fun toString(): String =
        "StockTransferAuthority(scope=REDACTED, permissions=${permissions.size}, epoch=$authorityEpoch)"

    private companion object {
        val LOOKUP_PERMISSIONS = setOf("warehouse:read", "warehouse.read", "inventory.read")
    }
}

data class StockTransferScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
    }

    override fun toString(): String = "StockTransferScope(REDACTED)"
}

sealed interface TransferLookupResult {
    data class Lots(val items: List<TransferSourceLotChoice>) : TransferLookupResult
    data class Warehouses(val items: List<TransferWarehouseChoice>) : TransferLookupResult
    data class Zones(val items: List<TransferZoneChoice>) : TransferLookupResult
    data object NetworkUnavailable : TransferLookupResult
    data object ServiceUnavailable : TransferLookupResult
    data object PermissionDenied : TransferLookupResult
    data object ContextInvalidated : TransferLookupResult
    data object SessionInvalidated : TransferLookupResult
}

sealed interface TransferSubmitResult {
    data class Confirmed(val transfer: ConfirmedStockTransfer) : TransferSubmitResult
    data class Rejected(val code: String?) : TransferSubmitResult
    data object UnknownOutcome : TransferSubmitResult
    data object PreconditionFailed : TransferSubmitResult
    data object Conflict : TransferSubmitResult
    data object NetworkUnavailable : TransferSubmitResult
    data object ServiceUnavailable : TransferSubmitResult
    data object PermissionDenied : TransferSubmitResult
    data object ContextInvalidated : TransferSubmitResult
    data object SessionInvalidated : TransferSubmitResult
}

enum class TransferIntentStatus { Pending, UnknownOutcome }

data class StockTransferIntent(
    val scope: StockTransferScope,
    val idempotencyKey: String,
    val frozenPayload: String,
    val expectedSourceVersion: Long,
    val status: TransferIntentStatus
) {
    init {
        require(idempotencyKey.isNotBlank() && frozenPayload.isNotBlank())
        require(expectedSourceVersion >= 0)
    }

    override fun toString(): String = "StockTransferIntent(status=$status, key=REDACTED)"
}

sealed interface TransferMetadataRead<out T> {
    data class Available<T>(val value: T?) : TransferMetadataRead<T>
    data object Unavailable : TransferMetadataRead<Nothing>
}

enum class TransferMetadataWrite { Saved, Unavailable }
