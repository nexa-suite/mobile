package com.nexa.mobile.operations.core.network

import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val SUBSTITUTION_REQUESTS_PATH = "/api/v1/inventory/physical-allocation-substitution-requests"
private val lotSubstitutionJson = Json { ignoreUnknownKeys = true }
private val lotSubstitutionUuid = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class LotSubstitutionCommandNetwork(
    val idempotencyKey: String,
    val allocationVersion: Long,
    val fulfillmentId: String,
    val allocationId: String,
    val allocationLineId: String,
    val expectedLotId: String,
    val alternativeLotId: String,
    val quantityText: String,
    val unit: String,
    val reason: String,
    val frozenBody: String
)

data class LotSubstitutionRequestNetworkProjection(
    val id: String,
    val expectedLotId: String,
    val alternativeLotId: String,
    val quantity: BigDecimal,
    val reason: String,
    val status: String,
    val currentAllocationVersion: Long
) {
    override fun toString(): String =
        "LotSubstitutionRequestNetworkProjection(status=$status, version=$currentAllocationVersion, quantity=REDACTED)"
}

sealed interface LotSubstitutionNetworkOutcome {
    data class Requested(val request: LotSubstitutionRequestNetworkProjection) : LotSubstitutionNetworkOutcome
    data class Rejected(val code: String?) : LotSubstitutionNetworkOutcome
    data class Stale(val currentAllocationVersion: Long? = null) : LotSubstitutionNetworkOutcome
    data object Conflict : LotSubstitutionNetworkOutcome
    data object UnknownOutcome : LotSubstitutionNetworkOutcome
    data object NetworkUnavailable : LotSubstitutionNetworkOutcome
    data object ServiceUnavailable : LotSubstitutionNetworkOutcome
    data object PermissionDenied : LotSubstitutionNetworkOutcome
    data object ContextInvalidated : LotSubstitutionNetworkOutcome
    data object SessionInvalidated : LotSubstitutionNetworkOutcome
}

/** Sends the persisted request body exactly as frozen, guarded by allocation version and idempotency key. */
class NexaLotSubstitutionGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun request(command: LotSubstitutionCommandNetwork): LotSubstitutionNetworkOutcome {
        if (!command.isValid()) return LotSubstitutionNetworkOutcome.Rejected("INVALID_REQUEST")
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = SUBSTITUTION_REQUESTS_PATH,
                    payload = command.frozenBody,
                    idempotencyKey = command.idempotencyKey,
                    ifMatch = "\"${command.allocationVersion}\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toLotSubstitutionOutcome()
            is ProtectedResult.Success -> {
                if (result.status != HTTP_CREATED && result.status != HTTP_OK) {
                    return LotSubstitutionNetworkOutcome.UnknownOutcome
                }
                val projection = result.body?.decode<LotSubstitutionRequestWire>()?.toProjection()
                    ?: return LotSubstitutionNetworkOutcome.UnknownOutcome
                if (projection.matches(command)) LotSubstitutionNetworkOutcome.Requested(projection)
                else LotSubstitutionNetworkOutcome.UnknownOutcome
            }
        }
    }

    private fun LotSubstitutionCommandNetwork.isValid(): Boolean {
        val quantity = quantityText.toBigDecimalOrNull() ?: return false
        if (idempotencyKey.isBlank() || idempotencyKey.length > 160 || allocationVersion < 0 ||
            !lotSubstitutionUuid.matches(fulfillmentId) || !lotSubstitutionUuid.matches(allocationId) ||
            !lotSubstitutionUuid.matches(allocationLineId) || !lotSubstitutionUuid.matches(expectedLotId) ||
            !lotSubstitutionUuid.matches(alternativeLotId) || expectedLotId == alternativeLotId ||
            quantity.signum() <= 0 || unit.isBlank() || reason.isBlank() || reason != reason.trim() ||
            reason.length > 2_000 || reason.any(Char::isISOControl)
        ) return false
        return commandBodyMatches(frozenBody, this, quantity)
    }

    private fun commandBodyMatches(
        body: String,
        command: LotSubstitutionCommandNetwork,
        quantity: BigDecimal
    ): Boolean = try {
        val root = lotSubstitutionJson.parseToJsonElement(body).jsonObject
        root.keys == setOf(
            "fulfillmentId", "allocationId", "physicalAllocationLineId", "expectedLotId",
            "alternativeLotId", "quantity", "unit", "reason"
        ) && root["fulfillmentId"]?.jsonPrimitive?.content == command.fulfillmentId &&
            root["allocationId"]?.jsonPrimitive?.content == command.allocationId &&
            root["physicalAllocationLineId"]?.jsonPrimitive?.content == command.allocationLineId &&
            root["expectedLotId"]?.jsonPrimitive?.content == command.expectedLotId &&
            root["alternativeLotId"]?.jsonPrimitive?.content == command.alternativeLotId &&
            root["quantity"]?.decimalValue()?.compareTo(quantity) == 0 &&
            root["unit"]?.jsonPrimitive?.content == command.unit &&
            root["reason"]?.jsonPrimitive?.content == command.reason
    } catch (_: Exception) {
        false
    }

    private fun LotSubstitutionRequestWire.toProjection(): LotSubstitutionRequestNetworkProjection? {
        val safeId = id?.takeIf(lotSubstitutionUuid::matches) ?: return null
        val expected = expectedLotId?.takeIf(lotSubstitutionUuid::matches) ?: return null
        val alternative = alternativeLotId?.takeIf(lotSubstitutionUuid::matches) ?: return null
        val safeQuantity = quantity.decimalValue() ?: return null
        val safeReason = reason?.takeIf(String::isNotBlank) ?: return null
        val safeStatus = status?.takeIf(String::isNotBlank) ?: return null
        val version = currentAllocationVersion?.takeIf { it >= 0 } ?: return null
        if (expected == alternative || safeQuantity.signum() <= 0) return null
        return LotSubstitutionRequestNetworkProjection(
            safeId, expected, alternative, safeQuantity, safeReason, safeStatus, version
        )
    }

    private fun LotSubstitutionRequestNetworkProjection.matches(
        command: LotSubstitutionCommandNetwork
    ): Boolean = id.isNotBlank() && expectedLotId.equals(command.expectedLotId, ignoreCase = true) &&
        alternativeLotId.equals(command.alternativeLotId, ignoreCase = true) &&
        quantity.compareTo(command.quantityText.toBigDecimal()) == 0 && reason == command.reason &&
        status == "REQUESTED" && currentAllocationVersion == command.allocationVersion

    private fun ClientFailure.toLotSubstitutionOutcome(): LotSubstitutionNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> LotSubstitutionNetworkOutcome.SessionInvalidated
        httpStatus == 403 && problemCode == "ACCESS_CONTEXT_INVALID" -> LotSubstitutionNetworkOutcome.ContextInvalidated
        kind == FailureKind.AuthorizationFailure || httpStatus == 404 -> LotSubstitutionNetworkOutcome.PermissionDenied
        kind == FailureKind.StaleState || httpStatus == 412 -> LotSubstitutionNetworkOutcome.Stale()
        kind == FailureKind.BusinessConflict || httpStatus == 409 -> LotSubstitutionNetworkOutcome.Conflict
        kind == FailureKind.ValidationFailure -> LotSubstitutionNetworkOutcome.Rejected(problemCode)
        kind == FailureKind.UnknownOutcome || kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            LotSubstitutionNetworkOutcome.UnknownOutcome
        else -> LotSubstitutionNetworkOutcome.ServiceUnavailable
    }
}

@Serializable
private data class LotSubstitutionRequestWire(
    val id: String? = null,
    val expectedLotId: String? = null,
    val alternativeLotId: String? = null,
    val quantity: JsonElement? = null,
    val reason: String? = null,
    val status: String? = null,
    val currentAllocationVersion: Long? = null
)

private fun JsonElement?.decimalValue(): BigDecimal? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull?.toBigDecimalOrNull()
    else -> null
}

private inline fun <reified T> String.decode(): T? = try {
    lotSubstitutionJson.decodeFromString<T>(this)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private const val HTTP_OK = 200
private const val HTTP_CREATED = 201
