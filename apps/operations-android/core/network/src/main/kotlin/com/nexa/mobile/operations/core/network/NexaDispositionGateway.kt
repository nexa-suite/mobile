package com.nexa.mobile.operations.core.network

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private const val INVENTORY_LOTS_PATH = "/api/v1/inventory/lots"
private val dispositionJson = Json { ignoreUnknownKeys = true }
private val uuidPattern = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val catalogIdPattern = Regex("(?i)CAT-[A-Z0-9-]{1,63}")

/** Typed current facts returned by the lot read and disposition response. */
data class DispositionLotProjection(
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
    val available: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String = "DispositionLotProjection(status=$status, version=$version)"
}

sealed interface DispositionNetworkOutcome {
    data class Lot(val item: DispositionLotProjection) : DispositionNetworkOutcome
    data class Confirmed(val item: DispositionLotProjection) : DispositionNetworkOutcome
    data class Rejected(val code: String?) : DispositionNetworkOutcome
    data object UnknownOutcome : DispositionNetworkOutcome
    data object PreconditionFailed : DispositionNetworkOutcome
    data object Conflict : DispositionNetworkOutcome
    data object NetworkUnavailable : DispositionNetworkOutcome
    data object ServiceUnavailable : DispositionNetworkOutcome
    data object PermissionDenied : DispositionNetworkOutcome
    data object ContextInvalidated : DispositionNetworkOutcome
    data object SessionInvalidated : DispositionNetworkOutcome
}

/** Protected transport for lot facts and the existing typed disposition command. */
class NexaDispositionGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun lot(lotId: String): DispositionNetworkOutcome {
        if (!uuidPattern.matches(lotId)) return DispositionNetworkOutcome.ServiceUnavailable
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, "$INVENTORY_LOTS_PATH/$lotId")
            )
        ) {
            is ProtectedResult.Failure -> result.error.toDispositionOutcome(isMutation = false)

            is ProtectedResult.Success -> {
                val item = result.body.decode<DispositionLotWire>()?.toProjection()
                    ?: return DispositionNetworkOutcome.ServiceUnavailable
                if (!item.id.equals(lotId, ignoreCase = true)) {
                    DispositionNetworkOutcome.ServiceUnavailable
                } else {
                    DispositionNetworkOutcome.Lot(item)
                }
            }
        }
    }

    suspend fun dispose(
        lotId: String,
        disposition: String,
        reason: String,
        expectedVersion: Long,
        idempotencyKey: String
    ): DispositionNetworkOutcome {
        if (!uuidPattern.matches(lotId) || disposition !in DISPOSITIONS ||
            reason.isBlank() || reason != reason.trim() || reason.length > 2_000 ||
            expectedVersion < 0 || idempotencyKey.isBlank() || idempotencyKey.length > 160
        ) {
            return DispositionNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        val payload = dispositionJson.encodeToString(DispositionRequestWire(disposition, reason))
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$INVENTORY_LOTS_PATH/$lotId/dispositions",
                    payload = payload,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toDispositionOutcome(isMutation = true)

            is ProtectedResult.Success -> {
                val item = result.body.decode<DispositionLotWire>()?.toProjection()
                    ?: return DispositionNetworkOutcome.ServiceUnavailable
                if (!item.id.equals(lotId, ignoreCase = true)) {
                    DispositionNetworkOutcome.ServiceUnavailable
                } else {
                    DispositionNetworkOutcome.Confirmed(item)
                }
            }
        }
    }

    private fun DispositionLotWire.toProjection(): DispositionLotProjection? {
        val safeId = id.requiredText()?.takeIf(uuidPattern::matches) ?: return null
        val safeWarehouseId =
            warehouseId.requiredText()?.takeIf(uuidPattern::matches) ?: return null
        val safeZoneId = zoneId.requiredText()?.takeIf(uuidPattern::matches) ?: return null
        val safeBatch = batchNumber.requiredText() ?: return null
        val safeUnit = unit.requiredText() ?: return null
        val safeStatus = status.requiredText() ?: return null
        val safeVersion = version?.takeIf { it >= 0 } ?: return null
        val expiry = expirationDate.requiredText()?.toLocalDate() ?: return null
        val received = receivedAt.requiredText()?.toInstant() ?: return null
        val currentOnHand = onHand.decimal() ?: return null
        val currentReserved = reserved.decimal() ?: return null
        val currentAvailable = available.decimal() ?: return null
        if ((catalogItemId != null && !catalogIdPattern.matches(catalogItemId)) ||
            (skuId != null && !uuidPattern.matches(skuId))
        ) {
            return null
        }
        return DispositionLotProjection(
            safeId,
            safeWarehouseId,
            safeZoneId,
            catalogItemId,
            skuId,
            safeBatch,
            expiry,
            received,
            currentOnHand,
            currentReserved,
            currentAvailable,
            safeUnit,
            safeStatus,
            safeVersion
        )
    }

    private fun ClientFailure.toDispositionOutcome(isMutation: Boolean): DispositionNetworkOutcome =
        when {
            kind == FailureKind.AuthenticationRequired ->
                DispositionNetworkOutcome.SessionInvalidated

            httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
                DispositionNetworkOutcome.ContextInvalidated

            kind == FailureKind.AuthorizationFailure || httpStatus == 404 ->
                DispositionNetworkOutcome.PermissionDenied

            kind == FailureKind.StaleState || httpStatus == 412 ->
                DispositionNetworkOutcome.PreconditionFailed

            kind == FailureKind.BusinessConflict || httpStatus == 409 ->
                DispositionNetworkOutcome.Conflict

            isMutation && kind == FailureKind.UnknownOutcome ->
                DispositionNetworkOutcome.UnknownOutcome

            kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
                if (isMutation) {
                    DispositionNetworkOutcome.UnknownOutcome
                } else {
                    DispositionNetworkOutcome.NetworkUnavailable
                }

            kind == FailureKind.ValidationFailure -> DispositionNetworkOutcome.Rejected(problemCode)

            else -> DispositionNetworkOutcome.ServiceUnavailable
        }

    private fun JsonElement?.decimal(): BigDecimal? = try {
        when (this) {
            null, JsonNull -> null
            is JsonPrimitive -> BigDecimal(content)
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }

    private fun String.toLocalDate(): LocalDate? = try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun String.toInstant(): Instant? = try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun String?.requiredText(): String? = this?.takeIf(String::isNotBlank)

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { dispositionJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        val DISPOSITIONS = setOf("RELEASE", "HOLD", "WASTE", "RETURN_TO_SUPPLIER")
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}

@Serializable
private data class DispositionRequestWire(val disposition: String, val reason: String)

@Serializable
private data class DispositionLotWire(
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
