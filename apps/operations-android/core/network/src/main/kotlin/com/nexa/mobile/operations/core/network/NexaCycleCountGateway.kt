package com.nexa.mobile.operations.core.network

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private const val LOTS_PATH = "/api/v1/inventory/lots"
private const val CYCLE_COUNTS_PATH = "/api/v1/inventory/cycle-counts"
private val cycleCountJson = Json { ignoreUnknownKeys = true }
private val cycleCountUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class CycleCountLotProjection(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val batchNumber: String,
    val expirationDate: LocalDate,
    val onHand: BigDecimal,
    val reserved: BigDecimal,
    val available: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
)

data class CycleCountCountCommand(
    val idempotencyKey: String,
    val lotId: String,
    val warehouseId: String,
    val zoneId: String,
    val lotVersion: Long,
    val expectedQuantityText: String,
    val observedQuantityText: String,
    val unit: String,
    val membershipId: String,
    val frozenBody: String
)

data class CycleCountCorrectionCommand(
    val idempotencyKey: String,
    val countId: String,
    val lotId: String,
    val warehouseId: String,
    val zoneId: String,
    val expectedLotVersion: Long,
    val expectedQuantityText: String,
    val observedQuantityText: String,
    val unit: String,
    val membershipId: String
)

data class CycleCountRecordProjection(
    val id: String,
    val lotId: String,
    val warehouseId: String,
    val zoneId: String,
    val lotVersion: Long,
    val expectedQuantity: BigDecimal,
    val observedQuantity: BigDecimal,
    val unit: String,
    val status: String,
    val actorMembershipId: String,
    val recordedAt: Instant
)

data class CycleCountCorrectionProjection(
    val id: String,
    val countId: String,
    val lotId: String,
    val warehouseId: String,
    val zoneId: String,
    val lotVersionBefore: Long,
    val lotVersionAfter: Long,
    val quantityBefore: BigDecimal,
    val quantityAfter: BigDecimal,
    val quantityDelta: BigDecimal,
    val unit: String,
    val actorMembershipId: String,
    val recordedAt: Instant
)

sealed interface CycleCountNetworkOutcome {
    data class Lots(val items: List<CycleCountLotProjection>, val page: Int, val total: Long) :
        CycleCountNetworkOutcome
    data class Recorded(val count: CycleCountRecordProjection) : CycleCountNetworkOutcome
    data class Applied(val correction: CycleCountCorrectionProjection) : CycleCountNetworkOutcome
    data class Rejected(val code: String?) : CycleCountNetworkOutcome
    data object UnknownOutcome : CycleCountNetworkOutcome
    data object PreconditionFailed : CycleCountNetworkOutcome
    data object Conflict : CycleCountNetworkOutcome
    data object NetworkUnavailable : CycleCountNetworkOutcome
    data object ServiceUnavailable : CycleCountNetworkOutcome
    data object PermissionDenied : CycleCountNetworkOutcome
    data object ContextInvalidated : CycleCountNetworkOutcome
    data object SessionInvalidated : CycleCountNetworkOutcome
}

/** Protected lot read and versioned count/correction commands; all quantities come from typed API projections. */
class NexaCycleCountGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun lots(page: Int = 0, size: Int = 100): CycleCountNetworkOutcome {
        if (page !in 0..MAX_PAGE ||
            size !in 1..MAX_PAGE_SIZE
        ) {
            return CycleCountNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.GET,
                    "$LOTS_PATH?page=$page&size=$size&sort=expirationDate,asc"
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toCycleCountOutcome(mutation = false)

            is ProtectedResult.Success -> {
                if (result.status != HTTP_OK) return CycleCountNetworkOutcome.ServiceUnavailable
                val wire =
                    result.body.decode<CycleCountLotsWire>()
                        ?: return CycleCountNetworkOutcome.ServiceUnavailable
                val items =
                    wire.items?.map {
                        it.toLotProjection()
                            ?: return CycleCountNetworkOutcome.ServiceUnavailable
                    }
                        ?: return CycleCountNetworkOutcome.ServiceUnavailable
                if (wire.page != page || wire.size != size || wire.total == null ||
                    wire.total < items.size ||
                    items.size > size
                ) {
                    CycleCountNetworkOutcome.ServiceUnavailable
                } else {
                    CycleCountNetworkOutcome.Lots(items, page, wire.total)
                }
            }
        }
    }

    suspend fun record(command: CycleCountCountCommand): CycleCountNetworkOutcome {
        val quantity = command.observedQuantityText.toBigDecimalOrNull()
            ?: return CycleCountNetworkOutcome.Rejected("INVALID_REQUEST")
        val expected = command.expectedQuantityText.toBigDecimalOrNull()
            ?: return CycleCountNetworkOutcome.Rejected("INVALID_REQUEST")
        if (!command.lotId.isUuid() || !command.warehouseId.isUuid() || !command.zoneId.isUuid() ||
            command.lotVersion < 0 || quantity.signum() < 0 || expected.signum() < 0 ||
            !fitsLotQuantity(quantity) || !command.idempotencyKey.isValidIdempotencyKey() ||
            !command.unit.isValidUnit() || !command.membershipId.isUuid()
        ) {
            return CycleCountNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        val expectedBody =
            "{\"observedQuantity\":${quantity.toPlainString()},\"unit\":\"${command.unit}\"}"
        if (command.frozenBody !=
            expectedBody
        ) {
            return CycleCountNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.POST,
                    "$LOTS_PATH/${command.lotId}/cycle-counts",
                    command.frozenBody,
                    command.idempotencyKey,
                    "\"${command.lotVersion}\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toCycleCountOutcome(mutation = true)

            is ProtectedResult.Success -> {
                if (result.status != HTTP_CREATED) return CycleCountNetworkOutcome.UnknownOutcome
                val count =
                    result.body.decode<CycleCountWire>()?.toRecord()
                        ?: return CycleCountNetworkOutcome.UnknownOutcome
                val expectedStatus = if (quantity.compareTo(expected) ==
                    0
                ) {
                    "RECORDED"
                } else {
                    "REQUESTED"
                }
                if (count.lotId == command.lotId && count.warehouseId == command.warehouseId &&
                    count.zoneId == command.zoneId && count.lotVersion == command.lotVersion &&
                    count.expectedQuantity.compareTo(expected) == 0 &&
                    count.observedQuantity.compareTo(quantity) == 0 &&
                    count.unit.equals(command.unit, ignoreCase = true) &&
                    count.status == expectedStatus &&
                    count.actorMembershipId == command.membershipId
                ) {
                    CycleCountNetworkOutcome.Recorded(count)
                } else {
                    CycleCountNetworkOutcome.UnknownOutcome
                }
            }
        }
    }

    suspend fun applyCorrection(command: CycleCountCorrectionCommand): CycleCountNetworkOutcome {
        if (!command.countId.isUuid() || !command.lotId.isUuid() || !command.warehouseId.isUuid() ||
            !command.zoneId.isUuid() || command.expectedLotVersion < 0 ||
            !command.idempotencyKey.isValidIdempotencyKey() || !command.membershipId.isUuid() ||
            !command.unit.isValidUnit()
        ) {
            return CycleCountNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        val expected = command.expectedQuantityText.toBigDecimalOrNull()
            ?: return CycleCountNetworkOutcome.Rejected("INVALID_REQUEST")
        val observed = command.observedQuantityText.toBigDecimalOrNull()
            ?: return CycleCountNetworkOutcome.Rejected("INVALID_REQUEST")
        if (expected.signum() < 0 || observed.signum() < 0 || expected.compareTo(observed) == 0) {
            return CycleCountNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.POST,
                    "$CYCLE_COUNTS_PATH/${command.countId}/corrections",
                    payload = null,
                    idempotencyKey = command.idempotencyKey,
                    ifMatch = "\"${command.expectedLotVersion}\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toCycleCountOutcome(mutation = true)

            is ProtectedResult.Success -> {
                if (result.status != HTTP_OK) return CycleCountNetworkOutcome.UnknownOutcome
                val correction = result.body.decode<CycleCountCorrectionWire>()?.toCorrection()
                    ?: return CycleCountNetworkOutcome.UnknownOutcome
                if (correction.countId == command.countId && correction.lotId == command.lotId &&
                    correction.warehouseId == command.warehouseId &&
                    correction.zoneId == command.zoneId &&
                    correction.lotVersionBefore == command.expectedLotVersion &&
                    correction.lotVersionAfter == command.expectedLotVersion + 1 &&
                    correction.quantityBefore.compareTo(expected) == 0 &&
                    correction.quantityAfter.compareTo(observed) == 0 &&
                    correction.quantityDelta.compareTo(observed.subtract(expected)) == 0 &&
                    correction.unit.equals(command.unit, ignoreCase = true) &&
                    correction.actorMembershipId == command.membershipId
                ) {
                    CycleCountNetworkOutcome.Applied(correction)
                } else {
                    CycleCountNetworkOutcome.UnknownOutcome
                }
            }
        }
    }

    private fun CycleCountLotWire.toLotProjection(): CycleCountLotProjection? {
        val safeId = id.requiredText()?.takeIf { it.isUuid() } ?: return null
        val warehouse = warehouseId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val zone = zoneId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val batch = batchNumber.requiredText() ?: return null
        val expiry = expirationDate.requiredText()?.toLocalDate() ?: return null
        val current = onHand.decimalValue() ?: return null
        val reservedValue = reserved.decimalValue() ?: return null
        val availableValue = available.decimalValue() ?: return null
        val safeUnit = unit.requiredText()?.takeIf { it.isValidUnit() } ?: return null
        val safeStatus = status.requiredText() ?: return null
        val safeVersion = version?.takeIf { it >= 0 } ?: return null
        if (current.signum() < 0 || reservedValue.signum() < 0 || availableValue.signum() < 0 ||
            (catalogItemId != null && !Regex("(?i)CAT-[A-Z0-9-]{1,63}").matches(catalogItemId)) ||
            (skuId != null && !skuId.isUuid())
        ) {
            return null
        }
        return CycleCountLotProjection(
            safeId, warehouse, zone, catalogItemId, batch, expiry,
            current, reservedValue, availableValue, safeUnit, safeStatus, safeVersion
        )
    }

    private fun CycleCountWire.toRecord(): CycleCountRecordProjection? {
        val safeId = id.requiredText()?.takeIf { it.isUuid() } ?: return null
        val lot = lotId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val warehouse = warehouseId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val zone = zoneId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val version = lotVersion?.takeIf { it >= 0 } ?: return null
        val expected = expectedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
        val observed = observedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
        val safeUnit = unit.requiredText()?.takeIf { it.isValidUnit() } ?: return null
        val safeStatus =
            status.requiredText()?.takeIf { it == "RECORDED" || it == "REQUESTED" } ?: return null
        val actor = actorMembershipId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val recorded = recordedAt.requiredText()?.takeIf { it.isInstant() } ?: return null
        if ((expected.compareTo(observed) == 0) != (safeStatus == "RECORDED")) return null
        return CycleCountRecordProjection(
            safeId, lot, warehouse, zone, version, expected, observed,
            safeUnit, safeStatus, actor, Instant.parse(recorded)
        )
    }

    private fun CycleCountCorrectionWire.toCorrection(): CycleCountCorrectionProjection? {
        val safeId = id.requiredText()?.takeIf { it.isUuid() } ?: return null
        val count = cycleCountId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val lot = lotId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val warehouse = warehouseId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val zone = zoneId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val beforeVersion = lotVersionBefore?.takeIf { it >= 0 } ?: return null
        val afterVersion = lotVersionAfter?.takeIf { it == beforeVersion + 1 } ?: return null
        val before = quantityBefore.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
        val after = quantityAfter.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
        val delta = quantityDelta.decimalValue() ?: return null
        val safeUnit = unit.requiredText()?.takeIf { it.isValidUnit() } ?: return null
        val actor = actorMembershipId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val recorded = recordedAt.requiredText()?.takeIf { it.isInstant() } ?: return null
        if (before.compareTo(after) == 0 ||
            delta.compareTo(after.subtract(before)) != 0
        ) {
            return null
        }
        return CycleCountCorrectionProjection(
            safeId, count, lot, warehouse, zone, beforeVersion, afterVersion,
            before, after, delta, safeUnit, actor, Instant.parse(recorded)
        )
    }

    private fun ClientFailure.toCycleCountOutcome(mutation: Boolean): CycleCountNetworkOutcome =
        when {
            kind == FailureKind.AuthenticationRequired ->
                CycleCountNetworkOutcome.SessionInvalidated

            httpStatus == 403 && problemCode == "ACCESS_CONTEXT_INVALID" ->
                CycleCountNetworkOutcome.ContextInvalidated

            kind == FailureKind.AuthorizationFailure || httpStatus == 404 ->
                CycleCountNetworkOutcome.PermissionDenied

            kind == FailureKind.StaleState || httpStatus == 412 ->
                CycleCountNetworkOutcome.PreconditionFailed

            kind == FailureKind.BusinessConflict || httpStatus == 409 ->
                CycleCountNetworkOutcome.Conflict

            kind == FailureKind.ValidationFailure -> CycleCountNetworkOutcome.Rejected(problemCode)

            kind == FailureKind.UnknownOutcome -> CycleCountNetworkOutcome.UnknownOutcome

            kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
                if (mutation) {
                    CycleCountNetworkOutcome.UnknownOutcome
                } else {
                    CycleCountNetworkOutcome.NetworkUnavailable
                }

            else -> CycleCountNetworkOutcome.ServiceUnavailable
        }

    private fun String.isUuid(): Boolean = try {
        UUID.fromString(this).toString().equals(this, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }
    private fun String.isValidUnit(): Boolean =
        isNotBlank() && length <= 32 && uppercase().matches(Regex("[A-Z0-9._/-]+"))
    private fun String.isValidIdempotencyKey(): Boolean = isNotBlank() && length <= 160
    private fun String.isInstant(): Boolean = try {
        Instant.parse(this)
        true
    } catch (
        _: RuntimeException
    ) {
        false
    }
    private fun String?.requiredText(): String? = this?.takeIf {
        it.isNotBlank() && it == it.trim()
    }
    private fun String.toLocalDate(): LocalDate? = try {
        LocalDate.parse(this).takeIf {
            it.toString() ==
                this
        }
    } catch (_: RuntimeException) {
        null
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
    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { cycleCountJson.decodeFromString<T>(it) }
    } catch (
        _: SerializationException
    ) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun fitsLotQuantity(value: BigDecimal): Boolean {
        val normalized = value.stripTrailingZeros()
        return maxOf(0, normalized.scale()) <= 4 &&
            maxOf(0, normalized.precision() - normalized.scale()) <= 15
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val MAX_PAGE = 10_000
        const val MAX_PAGE_SIZE = 100
    }
}

@Serializable private data class CycleCountLotsWire(
    val items: List<CycleCountLotWire>? = null,
    val page: Int? = null,
    val size: Int? = null,
    val total: Long? = null
)

@Serializable private data class CycleCountLotWire(
    val id: String? = null,
    val warehouseId: String? = null,
    val zoneId: String? = null,
    val catalogItemId: String? = null,
    val skuId: String? = null,
    val batchNumber: String? = null,
    val expirationDate: String? = null,
    val onHand: JsonElement? = null,
    val reserved: JsonElement? = null,
    val available: JsonElement? = null,
    val unit: String? = null,
    val status: String? = null,
    val version: Long? = null
)

@Serializable private data class CycleCountWire(
    val id: String? = null,
    val lotId: String? = null,
    val warehouseId: String? = null,
    val zoneId: String? = null,
    val lotVersion: Long? = null,
    val expectedQuantity: JsonElement? = null,
    val observedQuantity: JsonElement? = null,
    val unit: String? = null,
    val status: String? = null,
    val actorMembershipId: String? = null,
    val recordedAt: String? = null
)

@Serializable private data class CycleCountCorrectionWire(
    val id: String? = null,
    val cycleCountId: String? = null,
    val lotId: String? = null,
    val warehouseId: String? = null,
    val zoneId: String? = null,
    val lotVersionBefore: Long? = null,
    val lotVersionAfter: Long? = null,
    val quantityBefore: JsonElement? = null,
    val quantityAfter: JsonElement? = null,
    val quantityDelta: JsonElement? = null,
    val unit: String? = null,
    val actorMembershipId: String? = null,
    val recordedAt: String? = null
)
