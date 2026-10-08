package com.nexa.mobile.operations.feature.warehouse.model

import java.math.BigDecimal

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

/** Exact decimal lexeme is kept inside the frozen JSON payload and never rounded. */
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
    fun canonicalPayload(): String {
        val quantity = quantityText.trim()
        require(quantity.matches(DECIMAL_LEXEME))
        val decimal = BigDecimal(quantity)
        require(decimal.signum() > 0)
        require(
            sourceLotId.isNotBlank() && sourceWarehouseId.isNotBlank() && sourceZoneId.isNotBlank()
        )
        require(destinationWarehouseId.isNotBlank() && destinationZoneId.isNotBlank())
        require(!skuId.isNullOrBlank() || !catalogItemId.isNullOrBlank())
        require(
            unit.isNotBlank() && reason.isNotBlank() && reason == reason.trim() &&
                reason.length <= 2_000
        )
        return buildString {
            append("{\"sourceLotId\":").append(sourceLotId.jsonString())
            append(",\"sourceWarehouseId\":").append(sourceWarehouseId.jsonString())
            append(",\"sourceZoneId\":").append(sourceZoneId.jsonString())
            append(",\"destinationWarehouseId\":").append(destinationWarehouseId.jsonString())
            append(",\"destinationZoneId\":").append(destinationZoneId.jsonString())
            skuId?.let { append(",\"skuId\":").append(it.jsonString()) }
            catalogItemId?.let { append(",\"catalogItemId\":").append(it.jsonString()) }
            append(",\"quantity\":").append(quantity)
            append(",\"unit\":").append(unit.jsonString())
            append(",\"reason\":").append(reason.jsonString())
            append('}')
        }
    }

    override fun toString(): String = "StockTransferRequest(quantity=REDACTED, reason=REDACTED)"

    private fun String.jsonString(): String = buildString {
        append('"')
        for (character in this@jsonString) {
            when (character) {
                '"' -> append("\\\"")

                '\\' -> append("\\\\")

                '\b' -> append("\\b")

                '\u000C' -> append("\\f")

                '\n' -> append("\\n")

                '\r' -> append("\\r")

                '\t' -> append("\\t")

                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private companion object {
        val DECIMAL_LEXEME = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    }
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
