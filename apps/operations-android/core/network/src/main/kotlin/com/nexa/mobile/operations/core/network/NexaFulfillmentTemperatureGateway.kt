package com.nexa.mobile.operations.core.network

import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val FULFILLMENT_TEMPERATURE_BASE = "/api/v1/fulfillments"
private val fulfillmentTemperatureJson = Json { ignoreUnknownKeys = true }
private val fulfillmentTemperatureUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class FulfillmentTemperatureEvidenceProjection(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lotId: String,
    val value: BigDecimal,
    val unit: String,
    val occurredAt: Instant,
    val actorMembershipId: String,
    val status: String,
    val fulfillmentVersion: Long?
)

data class FulfillmentTemperatureLotProjection(
    val skuId: String,
    val lotId: String?,
    val warehouseId: String?,
    val zoneId: String?,
    val skuColdChainRequired: Boolean,
    val requiredForFulfillment: Boolean,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?,
    val status: String,
    val latestEvidence: FulfillmentTemperatureEvidenceProjection?
)

data class FulfillmentTemperatureReadinessProjection(
    val fulfillmentId: String,
    val fulfillmentStatus: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val temperatureRequiredForFulfillment: Boolean,
    val asOf: Instant,
    val lots: List<FulfillmentTemperatureLotProjection>
)

sealed interface FulfillmentTemperatureNetworkOutcome {
    data class Current(val readiness: FulfillmentTemperatureReadinessProjection) :
        FulfillmentTemperatureNetworkOutcome
    data class Recorded(val evidence: FulfillmentTemperatureEvidenceProjection) :
        FulfillmentTemperatureNetworkOutcome
    data object OutsideRangeBackendContractGap : FulfillmentTemperatureNetworkOutcome
    data object UnknownOutcome : FulfillmentTemperatureNetworkOutcome
    data object NetworkUnavailable : FulfillmentTemperatureNetworkOutcome
    data object ServiceUnavailable : FulfillmentTemperatureNetworkOutcome
    data object PermissionDenied : FulfillmentTemperatureNetworkOutcome
    data object ContextInvalidated : FulfillmentTemperatureNetworkOutcome
    data object SessionInvalidated : FulfillmentTemperatureNetworkOutcome
    data object Stale : FulfillmentTemperatureNetworkOutcome
    data object Conflict : FulfillmentTemperatureNetworkOutcome
}

/** Current readiness and manual Celsius evidence under BC-06 authority. */
class NexaFulfillmentTemperatureGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun current(fulfillmentId: String): FulfillmentTemperatureNetworkOutcome {
        if (!fulfillmentId.isUuid()) return FulfillmentTemperatureNetworkOutcome.ServiceUnavailable
        val pathId = URLEncoder.encode(fulfillmentId, StandardCharsets.UTF_8.name())
        return when (val result = protectedCalls.execute(
            ProtectedRequest(
                ProtectedMethod.GET,
                "$FULFILLMENT_TEMPERATURE_BASE/$pathId/temperature-evidence/current"
            )
        )) {
            is ProtectedResult.Failure -> result.error.toTemperatureOutcome()
            is ProtectedResult.Success -> {
                if (result.status != 200) return FulfillmentTemperatureNetworkOutcome.ServiceUnavailable
                val readiness = result.body.decodeObject()?.toReadiness()
                    ?: return FulfillmentTemperatureNetworkOutcome.ServiceUnavailable
                if (!readiness.fulfillmentId.equals(fulfillmentId, ignoreCase = true) ||
                    readiness.temperatureRequiredForFulfillment ||
                    result.etag.toVersion() != readiness.fulfillmentVersion
                ) {
                    FulfillmentTemperatureNetworkOutcome.ServiceUnavailable
                } else {
                    FulfillmentTemperatureNetworkOutcome.Current(readiness)
                }
            }
        }
    }

    suspend fun record(
        fulfillmentId: String,
        expectedFulfillmentVersion: Long,
        lotId: String,
        valueCelsius: BigDecimal,
        occurredAt: Instant,
        idempotencyKey: String,
        exactRequestBody: String
    ): FulfillmentTemperatureNetworkOutcome {
        if (!fulfillmentId.isUuid() || !lotId.isUuid() || expectedFulfillmentVersion < 0 ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160
        ) return FulfillmentTemperatureNetworkOutcome.ServiceUnavailable
        val encodedId = URLEncoder.encode(fulfillmentId, StandardCharsets.UTF_8.name())
        val expectedBody = "{\"lotId\":\"$lotId\",\"value\":" +
            "${valueCelsius.stripTrailingZeros().toPlainString()},\"unit\":\"CELSIUS\",\"occurredAt\":\"$occurredAt\"}"
        if (exactRequestBody != expectedBody) return FulfillmentTemperatureNetworkOutcome.ServiceUnavailable
        return when (val result = protectedCalls.execute(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = "$FULFILLMENT_TEMPERATURE_BASE/$encodedId/temperature-evidence",
                payload = exactRequestBody,
                idempotencyKey = idempotencyKey,
                ifMatch = "\"$expectedFulfillmentVersion\""
            )
        )) {
            is ProtectedResult.Failure -> result.error.toTemperatureOutcome()
            is ProtectedResult.Success -> {
                if (result.status != 200 && result.status != 201) {
                    return FulfillmentTemperatureNetworkOutcome.ServiceUnavailable
                }
                val evidence = result.body.decodeObject()?.toEvidence()
                    ?: return FulfillmentTemperatureNetworkOutcome.UnknownOutcome
                if (evidence.subjectType != "FULFILLMENT" ||
                    !evidence.subjectId.equals(fulfillmentId, ignoreCase = true) ||
                    !evidence.lotId.equals(lotId, ignoreCase = true) ||
                    evidence.unit != "CELSIUS" || evidence.value.compareTo(valueCelsius) != 0 ||
                    evidence.occurredAt != occurredAt ||
                    evidence.fulfillmentVersion != expectedFulfillmentVersion ||
                    result.etag.toVersion() != expectedFulfillmentVersion
                ) {
                    FulfillmentTemperatureNetworkOutcome.UnknownOutcome
                } else {
                    FulfillmentTemperatureNetworkOutcome.Recorded(evidence)
                }
            }
        }
    }

    private fun ClientFailure.toTemperatureOutcome(): FulfillmentTemperatureNetworkOutcome = when {
        problemCode == OUT_OF_RANGE_GAP -> FulfillmentTemperatureNetworkOutcome.OutsideRangeBackendContractGap
        kind == FailureKind.AuthenticationRequired -> FulfillmentTemperatureNetworkOutcome.SessionInvalidated
        httpStatus == 403 && problemCode == "ACCESS_CONTEXT_INVALID" ->
            FulfillmentTemperatureNetworkOutcome.ContextInvalidated
        httpStatus == 403 || httpStatus == 404 || kind == FailureKind.AuthorizationFailure ||
            kind == FailureKind.ResourceUnavailable -> FulfillmentTemperatureNetworkOutcome.PermissionDenied
        httpStatus == 412 || httpStatus == 428 || kind == FailureKind.StaleState ||
            kind == FailureKind.PreconditionRequired -> FulfillmentTemperatureNetworkOutcome.Stale
        httpStatus == 409 || kind == FailureKind.BusinessConflict -> FulfillmentTemperatureNetworkOutcome.Conflict
        kind == FailureKind.UnknownOutcome -> FulfillmentTemperatureNetworkOutcome.UnknownOutcome
        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            FulfillmentTemperatureNetworkOutcome.NetworkUnavailable
        else -> FulfillmentTemperatureNetworkOutcome.ServiceUnavailable
    }

    private fun JsonObject.toReadiness(): FulfillmentTemperatureReadinessProjection? {
        val id = uuid("fulfillmentId") ?: return null
        val status = text("fulfillmentStatus")?.takeIf(String::isNotBlank) ?: return null
        val version = number("fulfillmentVersion")?.takeIf { it >= 0 } ?: return null
        val allocation = uuid("physicalAllocationId") ?: return null
        val allocationVersion = number("physicalAllocationVersion")?.takeIf { it >= 0 } ?: return null
        val required = bool("temperatureRequiredForFulfillment") ?: return null
        val asOf = instant("asOf") ?: return null
        val lots = (this["lots"] as? JsonArray)?.map { element ->
            (element as? JsonObject)?.toLot() ?: return null
        } ?: return null
        return FulfillmentTemperatureReadinessProjection(
            id,
            status,
            version,
            allocation,
            allocationVersion,
            required,
            asOf,
            lots
        )
    }

    private fun JsonObject.toLot(): FulfillmentTemperatureLotProjection? {
        val skuId = uuid("skuId") ?: return null
        val lotId = optionalUuid("lotId") ?: return null
        val warehouseId = optionalUuid("warehouseId") ?: return null
        val zoneId = optionalUuid("zoneId") ?: return null
        val coldChainRequired = bool("skuColdChainRequired") ?: return null
        val requiredForFulfillment = bool("requiredForFulfillment") ?: return null
        if (requiredForFulfillment) return null
        if (!validOptionalUuid("lotId") || !validOptionalUuid("warehouseId") ||
            !validOptionalUuid("zoneId") || !validOptionalDecimal("minimumCelsius") ||
            !validOptionalDecimal("maximumCelsius")
        ) return null
        val minimum = optionalDecimal("minimumCelsius") ?: return null
        val maximum = optionalDecimal("maximumCelsius") ?: return null
        val status = text("status")?.takeIf(String::isNotBlank) ?: return null
        val evidenceValue = this["latestEvidence"]
        val evidence = when (evidenceValue) {
            null, JsonNull -> null
            is JsonObject -> evidenceValue.toEvidence() ?: return null
            else -> return null
        }
        return FulfillmentTemperatureLotProjection(
            skuId,
            lotId,
            warehouseId,
            zoneId,
            coldChainRequired,
            requiredForFulfillment,
            minimum,
            maximum,
            status,
            evidence
        )
    }

    private fun JsonObject.toEvidence(): FulfillmentTemperatureEvidenceProjection? {
        val id = uuid("id") ?: return null
        val subjectType = text("subjectType")?.takeIf(String::isNotBlank) ?: return null
        val subjectId = uuid("subjectId") ?: return null
        val lotId = uuid("lotId") ?: return null
        val value = decimal("value") ?: return null
        val unit = text("unit")?.takeIf(String::isNotBlank) ?: return null
        val occurredAt = instant("occurredAt") ?: return null
        val actor = uuid("actorMembershipId") ?: return null
        val status = text("status")?.takeIf(String::isNotBlank) ?: return null
        val version = optionalNumber("fulfillmentVersion")
        return FulfillmentTemperatureEvidenceProjection(
            id,
            subjectType,
            subjectId,
            lotId,
            value,
            unit,
            occurredAt,
            actor,
            status,
            version
        )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.uuid(key: String): String? = text(key)?.takeIf { it.isUuid() }

    private fun JsonObject.optionalUuid(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isUuid() }

    private fun JsonObject.validOptionalUuid(key: String): Boolean =
        this[key] == null || this[key] == JsonNull || optionalUuid(key) != null

    private fun JsonObject.number(key: String): Long? = text(key)?.toLongOrNull()

    private fun JsonObject.optionalNumber(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.decimal(key: String): BigDecimal? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()

    private fun JsonObject.optionalDecimal(key: String): BigDecimal? = when (val value = this[key]) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
    }

    private fun JsonObject.validOptionalDecimal(key: String): Boolean =
        this[key] == null || this[key] == JsonNull || optionalDecimal(key) != null

    private fun JsonObject.bool(key: String): Boolean? = text(key)?.toBooleanStrictOrNull()

    private fun JsonObject.instant(key: String): Instant? = text(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun String?.toVersion(): Long? = this?.removeSurrounding("\"")?.toLongOrNull()?.takeIf { it >= 0 }

    private fun String?.decodeObject(): JsonObject? = try {
        this?.let { fulfillmentTemperatureJson.parseToJsonElement(it) as? JsonObject }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun String.isUuid(): Boolean = fulfillmentTemperatureUuid.matches(this)

    private companion object {
        const val OUT_OF_RANGE_GAP = "TEMPERATURE_OUT_OF_RANGE_BACKEND_CONTRACT_GAP"
    }
}
