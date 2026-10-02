package com.nexa.mobile.operations.core.network

import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TEMPERATURE_EVIDENCE_PATH = "/api/v1/temperature-evidence"
private val temperatureJson = Json { ignoreUnknownKeys = true }
private val temperatureUuidPattern =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

enum class TemperatureSubjectTypeWire { LOT, WAREHOUSE }
enum class TemperatureUnitWire { CELSIUS, FAHRENHEIT }

data class TemperatureEvidenceCommandWire(
    val subjectType: TemperatureSubjectTypeWire,
    val subjectId: String,
    val value: String,
    val unit: TemperatureUnitWire,
    val occurredAt: Instant
) {
    init {
        require(temperatureUuidPattern.matches(subjectId))
        require(BigDecimal(value).toPlainString() == value)
    }

    override fun toString(): String = "TemperatureEvidenceCommandWire(REDACTED)"
}

data class TemperatureEvidenceResponseWire(
    val id: String,
    val subjectType: TemperatureSubjectTypeWire,
    val subjectId: String,
    val lotId: String?,
    val warehouseId: String?,
    val value: BigDecimal,
    val unit: TemperatureUnitWire,
    val occurredAt: Instant,
    val actorMembershipId: String,
    val status: String,
    val source: String
) {
    override fun toString(): String =
        "TemperatureEvidenceResponseWire(id=REDACTED, status=$status, source=$source)"
}

sealed interface TemperatureEvidenceNetworkOutcome {
    data class Confirmed(val response: TemperatureEvidenceResponseWire) :
        TemperatureEvidenceNetworkOutcome

    data class Rejected(val code: String?) : TemperatureEvidenceNetworkOutcome
    data object UnknownOutcome : TemperatureEvidenceNetworkOutcome
    data object NetworkUnavailable : TemperatureEvidenceNetworkOutcome
    data object PermissionDenied : TemperatureEvidenceNetworkOutcome
    data object ContextInvalidated : TemperatureEvidenceNetworkOutcome
    data object SessionInvalidated : TemperatureEvidenceNetworkOutcome
    data object ServiceUnavailable : TemperatureEvidenceNetworkOutcome
}

/** Narrow protected command adapter; warehouse and lot lookups reuse existing read gateways. */
class NexaTemperatureEvidenceGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun record(
        command: TemperatureEvidenceCommandWire,
        idempotencyKey: String,
        expectedMembershipId: String
    ): TemperatureEvidenceNetworkOutcome {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            !temperatureUuidPattern.matches(expectedMembershipId)
        ) {
            return TemperatureEvidenceNetworkOutcome.ServiceUnavailable
        }
        val payload = buildJsonObject {
            put("subjectType", command.subjectType.name)
            put("subjectId", command.subjectId)
            put("value", JsonUnquotedLiteral(command.value))
            put("unit", command.unit.name)
            put("occurredAt", command.occurredAt.toString())
        }.toString()
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.POST,
                    TEMPERATURE_EVIDENCE_PATH,
                    payload,
                    idempotencyKey
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toTemperatureEvidenceOutcome()

            is ProtectedResult.Success -> {
                if (result.status != HTTP_CREATED) {
                    return TemperatureEvidenceNetworkOutcome.ServiceUnavailable
                }
                val response = result.body.decode<TemperatureEvidenceWire>()?.toProjection()
                    ?: return TemperatureEvidenceNetworkOutcome.ServiceUnavailable
                if (response.subjectType != command.subjectType ||
                    response.subjectId != command.subjectId ||
                    response.value.compareTo(BigDecimal(command.value)) != 0 ||
                    response.unit != command.unit ||
                    response.occurredAt != command.occurredAt ||
                    response.actorMembershipId != expectedMembershipId ||
                    response.source != "MANUAL" ||
                    (
                        response.subjectType == TemperatureSubjectTypeWire.LOT &&
                            response.lotId != response.subjectId
                        ) ||
                    (
                        response.subjectType == TemperatureSubjectTypeWire.WAREHOUSE &&
                            response.warehouseId != response.subjectId
                        )
                ) {
                    TemperatureEvidenceNetworkOutcome.ServiceUnavailable
                } else {
                    TemperatureEvidenceNetworkOutcome.Confirmed(response)
                }
            }
        }
    }

    private fun ClientFailure.toTemperatureEvidenceOutcome(): TemperatureEvidenceNetworkOutcome =
        when {
            kind == FailureKind.UnknownOutcome -> TemperatureEvidenceNetworkOutcome.UnknownOutcome

            kind == FailureKind.AuthenticationRequired ->
                TemperatureEvidenceNetworkOutcome.SessionInvalidated

            httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
                TemperatureEvidenceNetworkOutcome.ContextInvalidated

            kind == FailureKind.AuthorizationFailure ->
                TemperatureEvidenceNetworkOutcome.PermissionDenied

            httpStatus in 400..499 -> TemperatureEvidenceNetworkOutcome.Rejected(problemCode)

            kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
                TemperatureEvidenceNetworkOutcome.NetworkUnavailable

            else -> TemperatureEvidenceNetworkOutcome.UnknownOutcome
        }

    private fun TemperatureEvidenceWire.toProjection(): TemperatureEvidenceResponseWire? {
        val safeId = id.requiredText()?.takeIf(temperatureUuidPattern::matches) ?: return null
        val safeType = subjectType.requiredText()?.let {
            runCatching { TemperatureSubjectTypeWire.valueOf(it) }.getOrNull()
        } ?: return null
        val safeSubject = subjectId.requiredText()?.takeIf(temperatureUuidPattern::matches)
            ?: return null
        val safeMembership = actorMembershipId.requiredText()
            ?.takeIf(temperatureUuidPattern::matches) ?: return null
        val safeUnit = unit.requiredText()?.let {
            runCatching { TemperatureUnitWire.valueOf(it) }.getOrNull()
        } ?: return null
        val safeValue = value.decimalValue() ?: return null
        val safeOccurredAt = occurredAt.requiredText()?.parseInstant() ?: return null
        val safeStatus = status.requiredText() ?: return null
        val safeSource = source.requiredText() ?: return null
        if ((lotId != null && !temperatureUuidPattern.matches(lotId)) ||
            (warehouseId != null && !temperatureUuidPattern.matches(warehouseId))
        ) {
            return null
        }
        return TemperatureEvidenceResponseWire(
            id = safeId,
            subjectType = safeType,
            subjectId = safeSubject,
            lotId = lotId,
            warehouseId = warehouseId,
            value = safeValue,
            unit = safeUnit,
            occurredAt = safeOccurredAt,
            actorMembershipId = safeMembership,
            status = safeStatus,
            source = safeSource
        )
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

    private fun String.parseInstant(): Instant? = try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun String?.requiredText(): String? = this?.takeIf(String::isNotBlank)

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { temperatureJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
        const val HTTP_CREATED = 201
    }
}

@Serializable
private data class TemperatureEvidenceWire(
    val id: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val lotId: String? = null,
    val warehouseId: String? = null,
    val value: JsonElement? = null,
    val unit: String? = null,
    val occurredAt: String? = null,
    val actorMembershipId: String? = null,
    val status: String? = null,
    val source: String? = null
)
