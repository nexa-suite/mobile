package com.nexa.mobile.operations.core.network

import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val INVENTORY_TRANSFERS_PATH = "/api/v1/inventory/transfers"
private val stockTransferJson = Json { ignoreUnknownKeys = true }

/** Server facts for a requested movement. A REQUESTED transfer has not moved or received stock. */
data class StockTransferProjection(
    val id: String,
    val sourceWarehouseId: String,
    val sourceZoneId: String,
    val sourceLotId: String,
    val destinationWarehouseId: String,
    val destinationZoneId: String,
    val destinationLotId: String?,
    val skuId: String?,
    val catalogItemId: String?,
    val requestedQuantity: BigDecimal,
    val transferredQuantity: BigDecimal,
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
    override fun toString(): String =
        "StockTransferProjection(status=$status, version=$version, quantity=REDACTED)"
}

sealed interface StockTransferNetworkOutcome {
    data class Confirmed(val transfer: StockTransferProjection) : StockTransferNetworkOutcome
    data class Rejected(val code: String?) : StockTransferNetworkOutcome
    data object UnknownOutcome : StockTransferNetworkOutcome
    data object PreconditionFailed : StockTransferNetworkOutcome
    data object Conflict : StockTransferNetworkOutcome
    data object NetworkUnavailable : StockTransferNetworkOutcome
    data object ServiceUnavailable : StockTransferNetworkOutcome
    data object PermissionDenied : StockTransferNetworkOutcome
    data object ContextInvalidated : StockTransferNetworkOutcome
    data object SessionInvalidated : StockTransferNetworkOutcome
}

/** Sends one frozen, scope-bound request body unchanged with its source-lot version and key. */
class NexaStockTransferGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun createTransfer(
        frozenPayload: String,
        expectedSourceVersion: Long,
        idempotencyKey: String
    ): StockTransferNetworkOutcome {
        val command = frozenPayload.parseTransferCommand()
            ?: return StockTransferNetworkOutcome.Rejected("INVALID_REQUEST")
        if (expectedSourceVersion < 0 || idempotencyKey.isBlank() || idempotencyKey.length > 160) {
            return StockTransferNetworkOutcome.Rejected("INVALID_REQUEST")
        }

        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = INVENTORY_TRANSFERS_PATH,
                    payload = frozenPayload,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedSourceVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toStockTransferOutcome()
            is ProtectedResult.Success -> {
                if (result.status != HTTP_CREATED) return StockTransferNetworkOutcome.UnknownOutcome
                val wire = result.body.decode<TransferResponseWire>()
                    ?: return StockTransferNetworkOutcome.UnknownOutcome
                val projection = wire.toProjection()
                    ?: return StockTransferNetworkOutcome.UnknownOutcome
                if (!projection.matches(command, expectedSourceVersion)) {
                    StockTransferNetworkOutcome.UnknownOutcome
                } else {
                    StockTransferNetworkOutcome.Confirmed(projection)
                }
            }
        }
    }

    private fun TransferResponseWire.toProjection(): StockTransferProjection? {
        val safeId = id?.takeIf { it.isUuid() } ?: return null
        val safeSourceWarehouse = sourceWarehouseId?.takeIf { it.isUuid() } ?: return null
        val safeSourceZone = sourceZoneId?.takeIf { it.isUuid() } ?: return null
        val safeSourceLot = sourceLotId?.takeIf { it.isUuid() } ?: return null
        val safeDestinationWarehouse = destinationWarehouseId?.takeIf { it.isUuid() } ?: return null
        val safeDestinationZone = destinationZoneId?.takeIf { it.isUuid() } ?: return null
        val safeDestinationLot = destinationLotId?.takeIf { it.isUuid() }
        val safeSku = skuId?.takeIf { it.isUuid() }
        val safeCatalog = catalogItemId?.takeIf(CATALOG_ITEM_ID::matches)
        val requested = requestedQuantity.decimalValue() ?: return null
        val transferred = transferredQuantity.decimalValue() ?: return null
        val safeMode = mode?.takeIf { it == "FULL" || it == "PARTIAL" } ?: return null
        val safeUnit = unit?.takeIf(String::isNotBlank) ?: return null
        val safeStatus = status?.takeIf(String::isNotBlank) ?: return null
        val safeReason = reason?.takeIf(String::isNotBlank) ?: return null
        val safeSourceVersion = sourceVersionBefore?.takeIf { it >= 0 } ?: return null
        val safeVersion = version?.takeIf { it >= 0 } ?: return null
        val safeReceivedAt = receivedAt?.takeIf(String::isNotBlank)
        if ((destinationLotId != null && safeDestinationLot == null) ||
            (skuId != null && safeSku == null) || (catalogItemId != null && safeCatalog == null)
        ) {
            return null
        }
        return StockTransferProjection(
            safeId,
            safeSourceWarehouse,
            safeSourceZone,
            safeSourceLot,
            safeDestinationWarehouse,
            safeDestinationZone,
            safeDestinationLot,
            safeSku,
            safeCatalog,
            requested,
            transferred,
            safeMode,
            safeUnit,
            safeStatus,
            safeReason,
            safeSourceVersion,
            sourceVersionAfter,
            destinationVersionAfter,
            safeVersion,
            dispatchedAt?.takeIf(String::isNotBlank),
            safeReceivedAt
        )
    }

    private fun StockTransferProjection.matches(
        command: TransferCommandWire,
        expectedSourceVersion: Long
    ): Boolean =
        sourceLotId == command.sourceLotId && sourceWarehouseId == command.sourceWarehouseId &&
            sourceZoneId == command.sourceZoneId &&
            destinationWarehouseId == command.destinationWarehouseId &&
            destinationZoneId == command.destinationZoneId && skuId == command.skuId &&
            catalogItemId == command.catalogItemId &&
            requestedQuantity.compareTo(command.quantity) == 0 &&
            transferredQuantity.compareTo(BigDecimal.ZERO) == 0 &&
            unit.equals(command.unit, ignoreCase = true) && reason == command.reason &&
            sourceVersionBefore == expectedSourceVersion && status == "REQUESTED" &&
            sourceVersionAfter == null && destinationVersionAfter == null &&
            destinationLotId == null && dispatchedAt == null && receivedAt == null

    private fun ClientFailure.toStockTransferOutcome(): StockTransferNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> StockTransferNetworkOutcome.SessionInvalidated
        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            StockTransferNetworkOutcome.ContextInvalidated
        kind == FailureKind.AuthorizationFailure || httpStatus == 404 ->
            StockTransferNetworkOutcome.PermissionDenied
        kind == FailureKind.StaleState || httpStatus == 412 ->
            StockTransferNetworkOutcome.PreconditionFailed
        kind == FailureKind.BusinessConflict || httpStatus == 409 ->
            StockTransferNetworkOutcome.Conflict
        kind == FailureKind.ValidationFailure -> StockTransferNetworkOutcome.Rejected(problemCode)
        kind == FailureKind.UnknownOutcome -> StockTransferNetworkOutcome.UnknownOutcome
        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            StockTransferNetworkOutcome.UnknownOutcome
        else -> StockTransferNetworkOutcome.ServiceUnavailable
    }

    private fun String.parseTransferCommand(): TransferCommandWire? = try {
        val objectValue = stockTransferJson.parseToJsonElement(this) as? JsonObject ?: return null
        val required = setOf(
            "sourceLotId", "sourceWarehouseId", "sourceZoneId", "destinationWarehouseId",
            "destinationZoneId", "quantity", "unit", "reason"
        )
        val allowed = required + setOf("skuId", "catalogItemId")
        if (!objectValue.keys.containsAll(required) || objectValue.keys.any { it !in allowed }) {
            return null
        }
        val quantity = (objectValue["quantity"] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.content
            ?.let(::BigDecimal)
            ?: return null
        val parsed = TransferCommandWire(
            sourceLotId = objectValue.requiredString("sourceLotId") ?: return null,
            sourceWarehouseId = objectValue.requiredString("sourceWarehouseId") ?: return null,
            sourceZoneId = objectValue.requiredString("sourceZoneId") ?: return null,
            destinationWarehouseId = objectValue.requiredString("destinationWarehouseId") ?: return null,
            destinationZoneId = objectValue.requiredString("destinationZoneId") ?: return null,
            skuId = objectValue.optionalString("skuId") ?: return null,
            catalogItemId = objectValue.optionalString("catalogItemId") ?: return null,
            quantity = quantity,
            unit = objectValue.requiredString("unit") ?: return null,
            reason = objectValue.requiredString("reason") ?: return null
        )
        parsed.takeIf { it.isValid() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun TransferCommandWire.isValid(): Boolean =
        sourceLotId.isUuid() && sourceWarehouseId.isUuid() && sourceZoneId.isUuid() &&
            destinationWarehouseId.isUuid() && destinationZoneId.isUuid() &&
            (skuId == null || skuId.isUuid()) &&
            (catalogItemId == null || CATALOG_ITEM_ID.matches(catalogItemId)) &&
            (skuId != null || catalogItemId != null) && quantity.signum() > 0 &&
            unit.isNotBlank() && reason.isNotBlank() && reason == reason.trim() && reason.length <= 2_000

    private fun String.isUuid(): Boolean = try {
        UUID.fromString(this).toString().equals(this, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun JsonElement?.decimalValue(): BigDecimal? = try {
        when (this) {
            null, JsonNull -> null
            is JsonPrimitive -> BigDecimal(content)
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }

    private fun JsonObject.requiredString(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank)

    private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else "\u0000"
        else -> "\u0000"
    }

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { stockTransferJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private data class TransferCommandWire(
        val sourceLotId: String,
        val sourceWarehouseId: String,
        val sourceZoneId: String,
        val destinationWarehouseId: String,
        val destinationZoneId: String,
        val skuId: String? = null,
        val catalogItemId: String? = null,
        val quantity: BigDecimal,
        val unit: String,
        val reason: String
    )

    @Serializable
    private data class TransferResponseWire(
        val id: String? = null,
        val sourceWarehouseId: String? = null,
        val sourceZoneId: String? = null,
        val sourceLotId: String? = null,
        val destinationWarehouseId: String? = null,
        val destinationZoneId: String? = null,
        val destinationLotId: String? = null,
        val skuId: String? = null,
        val catalogItemId: String? = null,
        val requestedQuantity: JsonElement? = null,
        val transferredQuantity: JsonElement? = null,
        val unit: String? = null,
        val status: String? = null,
        val reason: String? = null,
        val mode: String? = null,
        val sourceVersionBefore: Long? = null,
        val sourceVersionAfter: Long? = null,
        val destinationVersionAfter: Long? = null,
        val version: Long? = null,
        val dispatchedAt: String? = null,
        val receivedAt: String? = null
    )

    private companion object {
        val CATALOG_ITEM_ID = Regex("(?i)CAT-[A-Z0-9-]{1,63}")
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
        const val HTTP_CREATED = 201
    }
}
