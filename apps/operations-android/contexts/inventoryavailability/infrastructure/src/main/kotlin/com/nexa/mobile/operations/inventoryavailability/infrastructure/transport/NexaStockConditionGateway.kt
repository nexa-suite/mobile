package com.nexa.mobile.operations.inventoryavailability.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private const val LOTS_PATH = "/api/v1/inventory/lots"
private const val WAREHOUSES_PATH = "/api/v1/warehouses"
private const val LOT_PAGE_SIZE = 100
private const val MAX_LOT_PAGES = 100
private val stockConditionJson = Json { ignoreUnknownKeys = true }
private val uuidPattern = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val catalogItemPattern = Regex("(?i)CAT-[A-Z0-9-]{1,63}")

/** Physical lot projection. `available` is physical remainder, never SKU sellable quantity. */
data class StockConditionLotProjection(
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
    val physicalRemaining: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String =
        "StockConditionLotProjection(id=REDACTED, quantities=REDACTED, status=$status)"
}

data class StockAvailabilityProjection(
    val catalogItemId: String,
    val status: String,
    val asOf: Instant,
    val physicalQuantity: BigDecimal?,
    val safetyStock: BigDecimal?,
    val sellableQuantity: BigDecimal?
)

sealed interface StockConditionNetworkOutcome {
    data class Lots(val items: List<StockConditionLotProjection>) : StockConditionNetworkOutcome
    data class Lot(val item: StockConditionLotProjection) : StockConditionNetworkOutcome
    data class Availability(val item: StockAvailabilityProjection?) :
        StockConditionNetworkOutcome
    data object NetworkUnavailable : StockConditionNetworkOutcome
    data object ServiceUnavailable : StockConditionNetworkOutcome
    data object PermissionDenied : StockConditionNetworkOutcome
    data object ContextInvalidated : StockConditionNetworkOutcome
    data object SessionInvalidated : StockConditionNetworkOutcome
}

/** Protected reads for Warehouse-grant-filtered lot list and detail routes. */
class NexaStockConditionGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun lots(): StockConditionNetworkOutcome {
        val collected = mutableListOf<StockConditionLotProjection>()
        val seenIds = mutableSetOf<String>()
        var expectedTotal: Long? = null

        for (page in 0 until MAX_LOT_PAGES) {
            val path = "$LOTS_PATH?page=$page&size=$LOT_PAGE_SIZE&sort=expirationDate,asc"
            when (
                val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, path))
            ) {
                is ProtectedResult.Failure -> return result.error.toStockConditionOutcome()

                is ProtectedResult.Success -> {
                    val response = result.body.decode<StockConditionLotPageWire>()
                        ?: return StockConditionNetworkOutcome.ServiceUnavailable
                    val wires = response.items
                        ?: return StockConditionNetworkOutcome.ServiceUnavailable
                    val responsePage = response.page
                        ?: return StockConditionNetworkOutcome.ServiceUnavailable
                    val responseSize = response.size
                        ?: return StockConditionNetworkOutcome.ServiceUnavailable
                    val total = response.total
                        ?: return StockConditionNetworkOutcome.ServiceUnavailable
                    if (responsePage != page || responseSize !in 1..LOT_PAGE_SIZE || total < 0 ||
                        wires.size > responseSize ||
                        (expectedTotal != null && total != expectedTotal)
                    ) {
                        return StockConditionNetworkOutcome.ServiceUnavailable
                    }
                    expectedTotal = total
                    if (total > MAX_LOT_PAGES.toLong() * LOT_PAGE_SIZE ||
                        collected.size.toLong() + wires.size > total
                    ) {
                        return StockConditionNetworkOutcome.ServiceUnavailable
                    }
                    for (wire in wires) {
                        val item = wire.toProjection()
                            ?: return StockConditionNetworkOutcome.ServiceUnavailable
                        if (!seenIds.add(item.id.lowercase())) {
                            return StockConditionNetworkOutcome.ServiceUnavailable
                        }
                        collected += item
                    }
                    if (wires.isEmpty() && collected.size.toLong() < total) {
                        return StockConditionNetworkOutcome.ServiceUnavailable
                    }
                    if (collected.size.toLong() == total) {
                        return StockConditionNetworkOutcome.Lots(collected.toList())
                    }
                }
            }
        }
        return StockConditionNetworkOutcome.ServiceUnavailable
    }

    suspend fun lot(lotId: String): StockConditionNetworkOutcome {
        if (!lotId.isUuid()) return StockConditionNetworkOutcome.ServiceUnavailable
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, "$LOTS_PATH/$lotId")
            )
        ) {
            is ProtectedResult.Failure -> result.error.toStockConditionOutcome()

            is ProtectedResult.Success -> {
                val item = result.body.decode<StockConditionLotWire>()?.toProjection()
                    ?: return StockConditionNetworkOutcome.ServiceUnavailable
                if (!item.id.equals(lotId, ignoreCase = true)) {
                    StockConditionNetworkOutcome.ServiceUnavailable
                } else {
                    StockConditionNetworkOutcome.Lot(item)
                }
            }
        }
    }

    suspend fun availability(
        warehouseId: String,
        catalogItemId: String
    ): StockConditionNetworkOutcome {
        if (!warehouseId.isUuid() || !catalogItemPattern.matches(catalogItemId)) {
            return StockConditionNetworkOutcome.ServiceUnavailable
        }
        val encodedCatalogItemId =
            URLEncoder.encode(catalogItemId, StandardCharsets.UTF_8.name())
        val path = "$WAREHOUSES_PATH/$warehouseId/inventory-availability" +
            "?catalogItemId=$encodedCatalogItemId"
        return when (
            val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, path))
        ) {
            is ProtectedResult.Failure -> result.error.toStockConditionOutcome()

            is ProtectedResult.Success -> {
                val response = result.body.decode<List<StockConditionAvailabilityWire>>()
                    ?: return StockConditionNetworkOutcome.ServiceUnavailable
                if (response.size > 1) return StockConditionNetworkOutcome.ServiceUnavailable
                val item = response.singleOrNull()?.toProjection()
                    ?: if (response.isEmpty()) {
                        null
                    } else {
                        return StockConditionNetworkOutcome.ServiceUnavailable
                    }
                if (item != null && item.catalogItemId != catalogItemId) {
                    return StockConditionNetworkOutcome.ServiceUnavailable
                }
                StockConditionNetworkOutcome.Availability(item)
            }
        }
    }

    private fun StockConditionLotWire.toProjection(): StockConditionLotProjection? {
        val safeId = id.requiredText()?.takeIf { it.isUuid() } ?: return null
        val safeWarehouse = warehouseId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val safeZone = zoneId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val safeBatch = batchNumber.requiredText() ?: return null
        val safeUnit = unit.requiredText() ?: return null
        val safeStatus = status.requiredText() ?: return null
        val safeVersion = version?.takeIf { it >= 0 } ?: return null
        val safeReceivedAt = receivedAt.requiredText()?.parseInstant() ?: return null
        val expiry = expirationDate.requiredText()?.parseLocalDate() ?: return null
        val onHandValue = onHand.decimalValue() ?: return null
        val reservedValue = reserved.decimalValue() ?: return null
        val remainingValue = available.decimalValue() ?: return null
        if ((catalogItemId != null && !catalogItemPattern.matches(catalogItemId)) ||
            (skuId != null && !skuId.isUuid())
        ) {
            return null
        }
        return StockConditionLotProjection(
            id = safeId,
            warehouseId = safeWarehouse,
            zoneId = safeZone,
            catalogItemId = catalogItemId,
            skuId = skuId,
            batchNumber = safeBatch,
            expirationDate = expiry,
            receivedAt = safeReceivedAt,
            onHand = onHandValue,
            reserved = reservedValue,
            physicalRemaining = remainingValue,
            unit = safeUnit,
            status = safeStatus,
            version = safeVersion
        )
    }

    private fun StockConditionAvailabilityWire.toProjection(): StockAvailabilityProjection? {
        val safeCatalogItemId = catalogItemId.requiredText()
            ?.takeIf(catalogItemPattern::matches) ?: return null
        val safeStatus = status.requiredText() ?: return null
        val safeAsOf = asOf.requiredText()?.parseInstant() ?: return null
        return StockAvailabilityProjection(
            catalogItemId = safeCatalogItemId,
            status = safeStatus,
            asOf = safeAsOf,
            physicalQuantity = physicalQuantity.decimalValue(),
            safetyStock = safetyStock.decimalValue(),
            sellableQuantity = sellableQuantity.decimalValue()
        )
    }

    private fun ClientFailure.toStockConditionOutcome(): StockConditionNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            StockConditionNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            StockConditionNetworkOutcome.ContextInvalidated

        httpStatus == 404 -> StockConditionNetworkOutcome.PermissionDenied

        kind == FailureKind.AuthorizationFailure ->
            StockConditionNetworkOutcome.PermissionDenied

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            StockConditionNetworkOutcome.NetworkUnavailable

        else -> StockConditionNetworkOutcome.ServiceUnavailable
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

    private fun String.parseLocalDate(): LocalDate? = try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun String.parseInstant(): Instant? = try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun String.isUuid(): Boolean = uuidPattern.matches(this)

    private fun String?.requiredText(): String? = this?.takeIf(String::isNotBlank)

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { stockConditionJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}

@Serializable
private data class StockConditionLotPageWire(
    val items: List<StockConditionLotWire>? = null,
    val page: Int? = null,
    val size: Int? = null,
    val total: Long? = null
)

@Serializable
private data class StockConditionLotWire(
    val id: String? = null,
    val warehouseId: String? = null,
    val zoneId: String? = null,
    val catalogItemId: String? = null,
    val skuId: String? = null,
    val batchNumber: String? = null,
    val expirationDate: String? = null,
    val receivedAt: String? = null,
    val onHand: JsonElement? = null,
    val reserved: JsonElement? = null,
    val available: JsonElement? = null,
    val unit: String? = null,
    val status: String? = null,
    val version: Long? = null
)

@Serializable
private data class StockConditionAvailabilityWire(
    val catalogItemId: String? = null,
    val status: String? = null,
    val asOf: String? = null,
    val physicalQuantity: JsonElement? = null,
    val safetyStock: JsonElement? = null,
    val sellableQuantity: JsonElement? = null
)
