package com.nexa.mobile.operations.core.network

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val FULFILLMENTS_PATH = "/api/v1/fulfillments"
private const val FULFILLMENT_ALLOCATION_SUFFIX = "/physical-allocation"
private const val PICKING_STARTS_SUFFIX = "/picking-starts"
private const val PICKING_CONFIRMATIONS_SUFFIX = "/picking-confirmations"
private val pickingJson = Json { ignoreUnknownKeys = true }
private val uuidPattern = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class PickingFulfillmentLineProjection(
    val id: String,
    val skuId: String,
    val catalogItemId: String?,
    val allocatedQuantity: BigDecimal,
    val pickedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String
)

data class PickingFulfillmentProjection(
    val id: String,
    val status: String,
    val version: Long,
    val lines: List<PickingFulfillmentLineProjection>
)

data class PickingAllocationLineProjection(
    val physicalAllocationLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val warehouseId: String,
    val zoneId: String?,
    val lotId: String,
    val quantity: BigDecimal,
    val releasedQuantity: BigDecimal,
    val consumedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String,
    val expirationDate: LocalDate?
)

data class PickingAllocationProjection(
    val allocationId: String,
    val status: String,
    val version: Long,
    val asOf: Instant,
    val lines: List<PickingAllocationLineProjection>
) {
    override fun toString(): String =
        "PickingAllocationProjection(status=$status, version=$version, lines=${lines.size})"
}

data class PickingWorkListItemProjection(
    val fulfillmentId: String,
    val salesOrderId: String,
    val status: String,
    val version: Long,
    val physicalAllocationId: String,
    val allocationVersion: Long,
    val lineCount: Int
)

data class PickingWorkListProjection(
    val items: List<PickingWorkListItemProjection>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val asOf: Instant
)

data class PickingConfirmationRequest(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val allocationVersion: Long,
    val fulfillmentLineId: String,
    val skuId: String,
    val physicalAllocationLineId: String,
    val lotId: String,
    val warehouseId: String,
    val quantity: BigDecimal,
    val unit: String
) {
    init {
        require(uuidPattern.matches(fulfillmentId))
        require(expectedFulfillmentVersion >= 0 && allocationVersion >= 0)
        require(uuidPattern.matches(fulfillmentLineId) && uuidPattern.matches(skuId))
        require(uuidPattern.matches(physicalAllocationLineId))
        require(uuidPattern.matches(lotId) && uuidPattern.matches(warehouseId))
        require(quantity.signum() > 0 && unit.isNotBlank())
    }

    override fun toString(): String =
        "PickingConfirmationRequest(fulfillment=REDACTED, versions=$expectedFulfillmentVersion/$allocationVersion, quantity=$quantity, unit=$unit)"
}

sealed interface PickingNetworkOutcome {
    data class Fulfillment(val value: PickingFulfillmentProjection) : PickingNetworkOutcome
    data class Allocation(val value: PickingAllocationProjection) : PickingNetworkOutcome
    data class Updated(val value: PickingFulfillmentProjection) : PickingNetworkOutcome
    data class Rejected(val code: String?) : PickingNetworkOutcome
    data object NotFound : PickingNetworkOutcome
    data object StaleVersion : PickingNetworkOutcome
    data object UnknownOutcome : PickingNetworkOutcome
    data object NetworkUnavailable : PickingNetworkOutcome
    data object ServiceUnavailable : PickingNetworkOutcome
    data object PermissionDenied : PickingNetworkOutcome
    data object ContextInvalidated : PickingNetworkOutcome
    data object SessionInvalidated : PickingNetworkOutcome
}

sealed interface PickingWorkListNetworkOutcome {
    data class Loaded(val value: PickingWorkListProjection) : PickingWorkListNetworkOutcome
    data object NetworkUnavailable : PickingWorkListNetworkOutcome
    data object ServiceUnavailable : PickingWorkListNetworkOutcome
    data object PermissionDenied : PickingWorkListNetworkOutcome
    data object ContextInvalidated : PickingWorkListNetworkOutcome
    data object SessionInvalidated : PickingWorkListNetworkOutcome
}

/** Protected transport for current fulfillment/allocation projections and exact picking commands. */
class NexaPickingGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun workList(page: Int = 0, size: Int = 25): PickingWorkListNetworkOutcome {
        if (page < 0 || size !in 1..100) return PickingWorkListNetworkOutcome.ServiceUnavailable
        val result = protectedCalls.execute(
            ProtectedRequest(
                ProtectedMethod.GET,
                "$FULFILLMENTS_PATH?page=$page&size=$size"
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toWorkListOutcome()

            is ProtectedResult.Success -> {
                val projection = result.body.toWorkListProjection(page, size)
                    ?: return PickingWorkListNetworkOutcome.ServiceUnavailable
                PickingWorkListNetworkOutcome.Loaded(projection)
            }
        }
    }

    suspend fun fulfillment(fulfillmentId: String): PickingNetworkOutcome {
        if (!uuidPattern.matches(fulfillmentId)) return PickingNetworkOutcome.ServiceUnavailable
        val result = protectedCalls.execute(
            ProtectedRequest(ProtectedMethod.GET, "$FULFILLMENTS_PATH/$fulfillmentId")
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toReadOutcome()

            is ProtectedResult.Success -> {
                val projection = result.body.toFulfillmentProjection()
                    ?: return PickingNetworkOutcome.ServiceUnavailable
                if (projection.id != fulfillmentId ||
                    result.etag.toVersion() != projection.version
                ) {
                    PickingNetworkOutcome.ServiceUnavailable
                } else {
                    PickingNetworkOutcome.Fulfillment(projection)
                }
            }
        }
    }

    suspend fun allocation(fulfillmentId: String): PickingNetworkOutcome {
        if (!uuidPattern.matches(fulfillmentId)) return PickingNetworkOutcome.ServiceUnavailable
        val result = protectedCalls.execute(
            ProtectedRequest(
                ProtectedMethod.GET,
                "$FULFILLMENTS_PATH/$fulfillmentId$FULFILLMENT_ALLOCATION_SUFFIX"
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toReadOutcome()

            is ProtectedResult.Success -> {
                val projection = result.body.toAllocationProjection()
                    ?: return PickingNetworkOutcome.ServiceUnavailable
                if (result.etag.toVersion() != projection.version) {
                    PickingNetworkOutcome.ServiceUnavailable
                } else {
                    PickingNetworkOutcome.Allocation(projection)
                }
            }
        }
    }

    suspend fun startPicking(
        fulfillmentId: String,
        expectedFulfillmentVersion: Long,
        idempotencyKey: String
    ): PickingNetworkOutcome {
        if (!uuidPattern.matches(fulfillmentId) || expectedFulfillmentVersion < 0 ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160
        ) {
            return PickingNetworkOutcome.ServiceUnavailable
        }
        return mutate(
            fulfillmentId,
            expectedFulfillmentVersion,
            idempotencyKey,
            PICKING_STARTS_SUFFIX,
            payload = null
        )
    }

    suspend fun confirmPicking(
        request: PickingConfirmationRequest,
        idempotencyKey: String
    ): PickingNetworkOutcome {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 160) {
            return PickingNetworkOutcome.ServiceUnavailable
        }
        val payload = request.toJson().toString()
        return mutate(
            request.fulfillmentId,
            request.expectedFulfillmentVersion,
            idempotencyKey,
            PICKING_CONFIRMATIONS_SUFFIX,
            payload
        )
    }

    private suspend fun mutate(
        fulfillmentId: String,
        expectedVersion: Long,
        idempotencyKey: String,
        suffix: String,
        payload: String?
    ): PickingNetworkOutcome {
        val result = protectedCalls.execute(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = "$FULFILLMENTS_PATH/$fulfillmentId$suffix",
                payload = payload,
                idempotencyKey = idempotencyKey,
                ifMatch = "\"$expectedVersion\""
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toMutationOutcome()

            is ProtectedResult.Success -> {
                val updated = result.body.toFulfillmentProjection()
                    ?: return PickingNetworkOutcome.UnknownOutcome
                val responseVersion = result.etag.toVersion()
                if (updated.id != fulfillmentId || responseVersion != updated.version ||
                    updated.version <= expectedVersion
                ) {
                    PickingNetworkOutcome.UnknownOutcome
                } else {
                    PickingNetworkOutcome.Updated(updated)
                }
            }
        }
    }

    private fun PickingConfirmationRequest.toJson(): JsonObject = buildJsonObject {
        put("allocationVersion", allocationVersion)
        put(
            "lines",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("fulfillmentLineId", fulfillmentLineId)
                        put("skuId", skuId)
                        put("quantity", JsonPrimitive(quantity))
                        put("unit", unit)
                        put("physicalAllocationLineId", physicalAllocationLineId)
                        put("lotId", lotId)
                        put("warehouseId", warehouseId)
                    }
                )
            )
        )
    }

    private fun String?.toFulfillmentProjection(): PickingFulfillmentProjection? {
        return try {
            val root = this?.let(pickingJson::parseToJsonElement)?.jsonObject ?: return null
            val id = root.string("id") ?: return null
            val status = root.string("status") ?: return null
            val version = root.long("version")?.takeIf { it >= 0 } ?: return null
            val lines = root["lines"]?.jsonArray?.map { it.toFulfillmentLine() ?: return null }
                ?: return null
            if (lines.map { it.id }.distinct().size != lines.size) return null
            PickingFulfillmentProjection(id, status, version, lines)
        } catch (_: Exception) {
            null
        }
    }

    private fun String?.toWorkListProjection(
        requestedPage: Int,
        requestedSize: Int
    ): PickingWorkListProjection? {
        return try {
            val root = this?.let(pickingJson::parseToJsonElement)?.jsonObject ?: return null
            val page = root.long("page")?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                ?: return null
            val size = root.long("size")?.takeIf { it in 1..100 }?.toInt() ?: return null
            val totalItems = root.long("totalItems")?.takeIf { it >= 0 } ?: return null
            val asOf = root.string("asOf")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return null
            val items = root["items"]?.jsonArray?.map { it.toWorkListItem() ?: return null }
                ?: return null
            if (page != requestedPage || size != requestedSize || items.size > size ||
                totalItems < items.size ||
                items.map { it.fulfillmentId }.distinct().size != items.size
            ) {
                return null
            }
            PickingWorkListProjection(items, page, size, totalItems, asOf)
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement.toWorkListItem(): PickingWorkListItemProjection? {
        return try {
            val item = jsonObject
            val fulfillmentId = item.string("fulfillmentId")?.takeIf(uuidPattern::matches)
                ?: return null
            val salesOrderId = item.string("salesOrderId")?.takeIf(uuidPattern::matches)
                ?: return null
            val status = item.string("status")?.takeIf { it in setOf("ALLOCATED", "PICKING") }
                ?: return null
            val version = item.long("version")?.takeIf { it >= 0 } ?: return null
            val allocationId = item.string("physicalAllocationId")?.takeIf(uuidPattern::matches)
                ?: return null
            val allocationVersion = item.long("allocationVersion")?.takeIf { it >= 0 }
                ?: return null
            val lineCount = item.long("lineCount")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                ?.toInt() ?: return null
            PickingWorkListItemProjection(
                fulfillmentId,
                salesOrderId,
                status,
                version,
                allocationId,
                allocationVersion,
                lineCount
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement.toFulfillmentLine(): PickingFulfillmentLineProjection? {
        return try {
            val line = jsonObject
            val id = line.string("id") ?: return null
            val skuId = line.string("skuId")?.takeIf(uuidPattern::matches) ?: return null
            val allocated =
                line.decimal("allocatedQuantity")?.takeIf { it.signum() >= 0 } ?: return null
            val picked = line.decimal("pickedQuantity")?.takeIf { it.signum() >= 0 } ?: return null
            val remaining =
                line.decimal("remainingQuantity")?.takeIf { it.signum() >= 0 } ?: return null
            val unit = line.string("unit")?.takeIf(String::isNotBlank) ?: return null
            val catalogItemId = line.optionalString("catalogItemId")
            PickingFulfillmentLineProjection(
                id,
                skuId,
                catalogItemId,
                allocated,
                picked,
                remaining,
                unit
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun String?.toAllocationProjection(): PickingAllocationProjection? {
        return try {
            val root = this?.let(pickingJson::parseToJsonElement)?.jsonObject ?: return null
            val id = root.string("allocationId")?.takeIf(uuidPattern::matches) ?: return null
            val status = root.string("status") ?: return null
            val version = root.long("version")?.takeIf { it >= 0 } ?: return null
            val asOfText = root.string("asOf") ?: return null
            val asOf = runCatching { Instant.parse(asOfText) }.getOrNull() ?: return null
            val lines = root["lines"]?.jsonArray?.map { it.toAllocationLine() ?: return null }
                ?: return null
            if (lines.map { it.physicalAllocationLineId }.distinct().size != lines.size) return null
            PickingAllocationProjection(id, status, version, asOf, lines)
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement.toAllocationLine(): PickingAllocationLineProjection? {
        return try {
            val line = jsonObject
            val id =
                line.string("physicalAllocationLineId")?.takeIf(uuidPattern::matches) ?: return null
            val skuId = line.string("skuId")?.takeIf(uuidPattern::matches) ?: return null
            val catalogItemId =
                line.string("catalogItemId")?.takeIf(String::isNotBlank) ?: return null
            val warehouseId =
                line.string("warehouseId")?.takeIf(uuidPattern::matches) ?: return null
            val zoneId = line.optionalString("zoneId")?.takeIf(uuidPattern::matches)
                ?: if (line["zoneId"] == null || line["zoneId"] == JsonNull) null else return null
            val lotId = line.string("lotId")?.takeIf(uuidPattern::matches) ?: return null
            val quantity = line.decimal("quantity")?.takeIf { it.signum() >= 0 } ?: return null
            val released =
                line.decimal("releasedQuantity")?.takeIf { it.signum() >= 0 } ?: return null
            val consumed =
                line.decimal("consumedQuantity")?.takeIf { it.signum() >= 0 } ?: return null
            val remaining =
                line.decimal("remainingQuantity")?.takeIf { it.signum() >= 0 } ?: return null
            if (quantity.subtract(released).subtract(consumed).compareTo(remaining) !=
                0
            ) {
                return null
            }
            val unit = line.string("unit")?.takeIf(String::isNotBlank) ?: return null
            val expirationDate = line.optionalString("expirationDate")?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    return null
                }
            }
            PickingAllocationLineProjection(
                id,
                skuId,
                catalogItemId,
                warehouseId,
                zoneId,
                lotId,
                quantity,
                released,
                consumed,
                remaining,
                unit,
                expirationDate
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.takeIf(String::isNotBlank)

    private fun JsonObject.optionalString(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
        else -> null
    }

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun JsonObject.decimal(key: String): BigDecimal? = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)
        ?.contentOrNull
        ?.let { runCatching { BigDecimal(it) }.getOrNull() }

    private fun String?.toVersion(): Long? {
        val value = this?.trim()?.removePrefix("W/")?.trim()?.removeSurrounding("\"") ?: return null
        return value.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun ClientFailure.toReadOutcome(): PickingNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> PickingNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            PickingNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> PickingNetworkOutcome.PermissionDenied

        kind == FailureKind.ResourceUnavailable -> PickingNetworkOutcome.NotFound

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            PickingNetworkOutcome.NetworkUnavailable

        else -> PickingNetworkOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toWorkListOutcome(): PickingWorkListNetworkOutcome =
        when (toReadOutcome()) {
            PickingNetworkOutcome.NetworkUnavailable ->
                PickingWorkListNetworkOutcome.NetworkUnavailable

            PickingNetworkOutcome.PermissionDenied -> PickingWorkListNetworkOutcome.PermissionDenied

            PickingNetworkOutcome.ContextInvalidated ->
                PickingWorkListNetworkOutcome.ContextInvalidated

            PickingNetworkOutcome.SessionInvalidated ->
                PickingWorkListNetworkOutcome.SessionInvalidated

            else -> PickingWorkListNetworkOutcome.ServiceUnavailable
        }

    private fun ClientFailure.toMutationOutcome(): PickingNetworkOutcome = when {
        kind == FailureKind.UnknownOutcome || kind == FailureKind.NetworkUnavailable ||
            kind == FailureKind.Timeout || kind == FailureKind.RetryableServerFailure ->
            PickingNetworkOutcome.UnknownOutcome

        kind == FailureKind.AuthenticationRequired -> PickingNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            PickingNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> PickingNetworkOutcome.PermissionDenied

        kind == FailureKind.StaleState -> PickingNetworkOutcome.StaleVersion

        kind == FailureKind.ResourceUnavailable -> PickingNetworkOutcome.NotFound

        kind == FailureKind.ValidationFailure || kind == FailureKind.BusinessConflict ||
            kind == FailureKind.PreconditionRequired -> PickingNetworkOutcome.Rejected(problemCode)

        else -> PickingNetworkOutcome.ServiceUnavailable
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}
