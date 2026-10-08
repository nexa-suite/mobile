package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

private val executionJson = Json { ignoreUnknownKeys = true }
private val executionUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class ExecutionTemperatureLineTransport(
    val fulfillmentLineId: String,
    val skuId: String,
    val unit: String,
    val remainingQuantity: BigDecimal,
    val coldChainRequired: Boolean,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?
)
data class ExecutionTemperatureHoldTransport(
    val id: String,
    val readingId: String,
    val exceptionId: String,
    val fulfillmentLineId: String,
    val skuId: String,
    val affectedQuantity: BigDecimal,
    val quantityUnit: String,
    val status: String,
    val reportedByMembershipId: String,
    val reportedAt: Instant,
    val disposition: String?,
    val authorizedByMembershipId: String?,
    val disposedAt: Instant?,
    val reason: String?
)
data class ExecutionTemperatureSnapshotTransport(
    val deliveryId: String,
    val deliveryVersion: Long,
    val deliveryStatus: String,
    val attemptId: String?,
    val originWarehouseId: String?,
    val lines: List<ExecutionTemperatureLineTransport>,
    val holds: List<ExecutionTemperatureHoldTransport>
)
data class ExecutionTemperatureReadingTransport(
    val id: String,
    val deliveryId: String,
    val attemptId: String?,
    val fulfillmentLineId: String,
    val skuId: String,
    val affectedQuantity: BigDecimal,
    val quantityUnit: String,
    val valueCelsius: BigDecimal,
    val temperatureUnit: String,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?,
    val status: String,
    val actorMembershipId: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val evidenceObjectId: String?,
    val sourceIncidentId: String?,
    val hold: ExecutionTemperatureHoldTransport?,
    val deliveryVersion: Long,
    val replayed: Boolean
)
data class ExecutionTemperatureDispositionTransport(
    val hold: ExecutionTemperatureHoldTransport,
    val deliveryVersion: Long,
    val replayed: Boolean
)
data class ExecutionTemperatureReadingTransportCommand(
    val fulfillmentLineId: String,
    val skuId: String,
    val affectedQuantity: BigDecimal,
    val valueCelsius: BigDecimal,
    val occurredAt: Instant,
    val sourceIncidentId: String?,
    val evidenceObjectId: String?
) {
    fun isValid(body: String): Boolean {
        if (!executionUuid.matches(fulfillmentLineId) || !executionUuid.matches(skuId) ||
            affectedQuantity.signum() <= 0 || valueCelsius.abs() >= BigDecimal("1000") ||
            ((sourceIncidentId == null) != (evidenceObjectId == null)) ||
            (sourceIncidentId != null && !executionUuid.matches(sourceIncidentId)) ||
            (evidenceObjectId != null && !executionUuid.matches(evidenceObjectId))
        ) {
            return false
        }
        val source = sourceIncidentId?.let { ",\"sourceIncidentId\":\"$it\"" }.orEmpty()
        val evidence = evidenceObjectId?.let { ",\"evidenceObjectId\":\"$it\"" }.orEmpty()
        return body == "{\"fulfillmentLineId\":\"$fulfillmentLineId\",\"skuId\":\"$skuId\"," +
            "\"affectedQuantity\":" + affectedQuantity.stripTrailingZeros().toPlainString() +
            ",\"value\":" + valueCelsius.stripTrailingZeros().toPlainString() +
            ",\"unit\":\"CELSIUS\",\"occurredAt\":\"$occurredAt\"$source$evidence}"
    }
}

sealed interface ExecutionTemperatureNetworkOutcome {
    data class Loaded(val snapshot: ExecutionTemperatureSnapshotTransport) :
        ExecutionTemperatureNetworkOutcome
    data class ReadingRecorded(val reading: ExecutionTemperatureReadingTransport) :
        ExecutionTemperatureNetworkOutcome
    data class Disposed(val result: ExecutionTemperatureDispositionTransport) :
        ExecutionTemperatureNetworkOutcome
    data class Rejected(val code: String?) : ExecutionTemperatureNetworkOutcome
    data object NotFound : ExecutionTemperatureNetworkOutcome
    data object StaleVersion : ExecutionTemperatureNetworkOutcome
    data object Conflict : ExecutionTemperatureNetworkOutcome
    data object UnknownOutcome : ExecutionTemperatureNetworkOutcome
    data object NetworkUnavailable : ExecutionTemperatureNetworkOutcome
    data object ServiceUnavailable : ExecutionTemperatureNetworkOutcome
    data object PermissionDenied : ExecutionTemperatureNetworkOutcome
    data object ContextInvalidated : ExecutionTemperatureNetworkOutcome
    data object SessionInvalidated : ExecutionTemperatureNetworkOutcome
}

/** Protected transport for execution readings and explicitly authorized HOLD dispositions. */
class NexaExecutionTemperatureGateway(private val calls: ProtectedCallExecutor) {
    suspend fun current(
        deliveryId: String,
        driverMode: Boolean
    ): ExecutionTemperatureNetworkOutcome {
        if (!executionUuid.matches(
                deliveryId
            )
        ) {
            return ExecutionTemperatureNetworkOutcome.ServiceUnavailable
        }
        val id = URLEncoder.encode(deliveryId, StandardCharsets.UTF_8.name())
        val path = if (driverMode) {
            "/api/v1/driver/deliveries/$id/execution-temperature-readings"
        } else {
            "/api/v1/deliveries/$id/execution-temperature-readings"
        }
        return when (val result = calls.execute(ProtectedRequest(ProtectedMethod.GET, path))) {
            is ProtectedResult.Failure -> result.error.toExecutionOutcome()

            is ProtectedResult.Success -> {
                if (result.status !=
                    200
                ) {
                    return ExecutionTemperatureNetworkOutcome.ServiceUnavailable
                }
                val snapshot =
                    result.body.obj()?.snapshot()
                        ?: return ExecutionTemperatureNetworkOutcome.ServiceUnavailable
                if (snapshot.deliveryId != deliveryId ||
                    result.etag.version() != snapshot.deliveryVersion
                ) {
                    ExecutionTemperatureNetworkOutcome.ServiceUnavailable
                } else {
                    ExecutionTemperatureNetworkOutcome.Loaded(snapshot)
                }
            }
        }
    }

    suspend fun record(
        deliveryId: String,
        version: Long,
        key: String,
        body: String,
        command: ExecutionTemperatureReadingTransportCommand
    ): ExecutionTemperatureNetworkOutcome {
        if (!executionUuid.matches(
                deliveryId
            ) || version < 0 || key.isBlank() || key.length > 160 ||
            !command.isValid(body)
        ) {
            return ExecutionTemperatureNetworkOutcome.ServiceUnavailable
        }
        val id = URLEncoder.encode(deliveryId, StandardCharsets.UTF_8.name())
        return when (
            val result = calls.execute(
                ProtectedRequest(
                    ProtectedMethod.POST,
                    "/api/v1/driver/deliveries/$id/execution-temperature-readings",
                    payload = body,
                    idempotencyKey = key,
                    ifMatch = "\"$version\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toExecutionOutcome()

            is ProtectedResult.Success -> {
                if (result.status != 200 &&
                    result.status != 201
                ) {
                    return ExecutionTemperatureNetworkOutcome.UnknownOutcome
                }
                val reading =
                    result.body.obj()?.reading()
                        ?: return ExecutionTemperatureNetworkOutcome.UnknownOutcome
                val sourceCommand = command.sourceIncidentId != null
                if (reading.deliveryId != deliveryId ||
                    reading.fulfillmentLineId != command.fulfillmentLineId ||
                    reading.skuId != command.skuId ||
                    reading.affectedQuantity.compareTo(command.affectedQuantity) != 0 ||
                    reading.valueCelsius.compareTo(command.valueCelsius) != 0 ||
                    reading.temperatureUnit != "CELSIUS" ||
                    reading.occurredAt != command.occurredAt ||
                    reading.sourceIncidentId != command.sourceIncidentId ||
                    reading.evidenceObjectId != command.evidenceObjectId ||
                    reading.deliveryVersion <= version ||
                    reading.replayed != (result.status == 200) ||
                    result.etag.version() != reading.deliveryVersion ||
                    (
                        sourceCommand &&
                            (reading.status != "OUT_OF_RANGE" || reading.hold?.status != "HELD")
                        ) ||
                    (!sourceCommand && (reading.status != "WITHIN_RANGE" || reading.hold != null))
                ) {
                    ExecutionTemperatureNetworkOutcome.UnknownOutcome
                } else {
                    ExecutionTemperatureNetworkOutcome.ReadingRecorded(reading)
                }
            }
        }
    }

    suspend fun dispose(
        deliveryId: String,
        holdId: String,
        version: Long,
        key: String,
        body: String,
        disposition: String
    ): ExecutionTemperatureNetworkOutcome {
        if (!executionUuid.matches(deliveryId) || !executionUuid.matches(holdId) || version < 0 ||
            key.isBlank() || key.length > 160 || !body.validDisposition(disposition)
        ) {
            return ExecutionTemperatureNetworkOutcome.ServiceUnavailable
        }
        val d = URLEncoder.encode(deliveryId, StandardCharsets.UTF_8.name())
        val h = URLEncoder.encode(holdId, StandardCharsets.UTF_8.name())
        return when (
            val result = calls.execute(
                ProtectedRequest(
                    ProtectedMethod.POST,
                    "/api/v1/deliveries/$d/execution-holds/$h/dispositions",
                    payload = body,
                    idempotencyKey = key,
                    ifMatch = "\"$version\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toExecutionOutcome()

            is ProtectedResult.Success -> {
                if (result.status != 200 &&
                    result.status != 201
                ) {
                    return ExecutionTemperatureNetworkOutcome.UnknownOutcome
                }
                val root =
                    result.body.obj() ?: return ExecutionTemperatureNetworkOutcome.UnknownOutcome
                val hold =
                    root.objectValue("hold")?.hold()
                        ?: return ExecutionTemperatureNetworkOutcome.UnknownOutcome
                val newVersion =
                    root.long("deliveryVersion")
                        ?: return ExecutionTemperatureNetworkOutcome.UnknownOutcome
                val replayed =
                    root.bool("replayed")
                        ?: return ExecutionTemperatureNetworkOutcome.UnknownOutcome
                val expectedStatus = when (disposition) {
                    "RELEASE" -> "RELEASED"
                    "CONTINUE_HOLD" -> "HELD"
                    "REJECT" -> "REJECTED"
                    "WASTE" -> "WASTED"
                    else -> ""
                }
                if (hold.id != holdId || hold.status != expectedStatus || newVersion <= version ||
                    replayed != (result.status == 200) || result.etag.version() != newVersion
                ) {
                    ExecutionTemperatureNetworkOutcome.UnknownOutcome
                } else {
                    ExecutionTemperatureNetworkOutcome.Disposed(
                        ExecutionTemperatureDispositionTransport(hold, newVersion, replayed)
                    )
                }
            }
        }
    }

    private fun ClientFailure.toExecutionOutcome(): ExecutionTemperatureNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            ExecutionTemperatureNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == "ACCESS_CONTEXT_INVALID" ->
            ExecutionTemperatureNetworkOutcome.ContextInvalidated

        httpStatus == 403 || kind == FailureKind.AuthorizationFailure ->
            ExecutionTemperatureNetworkOutcome.PermissionDenied

        httpStatus == 404 || kind == FailureKind.ResourceUnavailable ->
            ExecutionTemperatureNetworkOutcome.NotFound

        httpStatus == 412 || httpStatus == 428 || kind == FailureKind.StaleState ||
            kind == FailureKind.PreconditionRequired ->
            ExecutionTemperatureNetworkOutcome.StaleVersion

        problemCode == "IDEMPOTENCY_PAYLOAD_CONFLICT" -> ExecutionTemperatureNetworkOutcome.Conflict

        httpStatus == 409 || kind == FailureKind.BusinessConflict ->
            ExecutionTemperatureNetworkOutcome.Rejected(
                problemCode
            )

        kind == FailureKind.UnknownOutcome -> ExecutionTemperatureNetworkOutcome.UnknownOutcome

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            ExecutionTemperatureNetworkOutcome.NetworkUnavailable

        else -> ExecutionTemperatureNetworkOutcome.ServiceUnavailable
    }

    private fun String?.obj(): JsonObject? = try {
        this?.let(executionJson::parseToJsonElement)?.jsonObject
    } catch (_: Exception) {
        null
    }
    private fun JsonObject.snapshot(): ExecutionTemperatureSnapshotTransport? = try {
        val id = uuid("deliveryId") ?: return null
        val version = long("deliveryVersion")?.takeIf { it >= 0 } ?: return null
        val status = text("deliveryStatus")?.takeIf(String::isNotBlank) ?: return null
        if (!validUuid("attemptId") || !validUuid("originWarehouseId")) return null
        val lines =
            this["lines"]?.jsonArray?.map { (it as? JsonObject)?.line() ?: return null }
                ?: return null
        val holds =
            this["holds"]?.jsonArray?.map { (it as? JsonObject)?.hold() ?: return null }
                ?: return null
        ExecutionTemperatureSnapshotTransport(
            id,
            version,
            status,
            optionalUuid("attemptId"),
            optionalUuid("originWarehouseId"),
            lines,
            holds
        )
    } catch (_: Exception) {
        null
    }
    private fun JsonObject.line(): ExecutionTemperatureLineTransport? = try {
        if (!validDecimal("minimumCelsius") || !validDecimal("maximumCelsius")) return null
        val min = optionalDecimal("minimumCelsius")
        val max = optionalDecimal("maximumCelsius")
        if (min != null && max != null && min > max) return null
        ExecutionTemperatureLineTransport(
            uuid("fulfillmentLineId") ?: return null,
            uuid("skuId") ?: return null,
            text("unit")?.takeIf(String::isNotBlank) ?: return null,
            decimal("remainingQuantity")?.takeIf { it.signum() >= 0 } ?: return null,
            bool("coldChainRequired") ?: return null,
            min,
            max
        )
    } catch (_: Exception) {
        null
    }
    private fun JsonObject.hold(): ExecutionTemperatureHoldTransport? = try {
        if (!validUuid("authorizedByMembershipId") || !validInstant("disposedAt") ||
            !validText("disposition") || !validText("reason")
        ) {
            return null
        }
        val disposition = text("disposition")
        if (disposition != null &&
            disposition !in setOf("RELEASE", "CONTINUE_HOLD", "REJECT", "WASTE")
        ) {
            return null
        }
        ExecutionTemperatureHoldTransport(
            uuid("id") ?: return null,
            uuid("readingId") ?: return null,
            uuid("exceptionId") ?: return null, uuid("fulfillmentLineId") ?: return null,
            uuid("skuId") ?: return null,
            decimal("affectedQuantity")?.takeIf { it.signum() > 0 } ?: return null,
            text("quantityUnit")?.takeIf(String::isNotBlank) ?: return null,
            text("status")?.takeIf(String::isNotBlank) ?: return null,
            uuid("reportedByMembershipId") ?: return null,
            instant(
                "reportedAt"
            ) ?: return null,
            disposition, optionalUuid("authorizedByMembershipId"),
            optionalInstant("disposedAt"), text("reason")
        )
    } catch (_: Exception) {
        null
    }
    private fun JsonObject.reading(): ExecutionTemperatureReadingTransport? = try {
        if (!validUuid("attemptId") || !validUuid("evidenceObjectId") ||
            !validUuid("sourceIncidentId") ||
            !validDecimal("minimumCelsius") || !validDecimal("maximumCelsius")
        ) {
            return null
        }
        val hold = when (val value = this["hold"]) {
            null, JsonNull -> null
            is JsonObject -> value.hold() ?: return null
            else -> return null
        }
        ExecutionTemperatureReadingTransport(
            uuid("id") ?: return null,
            uuid("deliveryId") ?: return null,
            optionalUuid("attemptId"), uuid("fulfillmentLineId") ?: return null,
            uuid("skuId") ?: return null,
            decimal("affectedQuantity")?.takeIf { it.signum() > 0 } ?: return null,
            text("quantityUnit")?.takeIf(String::isNotBlank) ?: return null,
            decimal("valueCelsius") ?: return null,
            text("temperatureUnit")?.takeIf(String::isNotBlank) ?: return null,
            optionalDecimal("minimumCelsius"), optionalDecimal("maximumCelsius"),
            text("status")?.takeIf(String::isNotBlank) ?: return null,
            uuid("actorMembershipId") ?: return null,
            instant("occurredAt") ?: return null, instant("recordedAt") ?: return null,
            optionalUuid("evidenceObjectId"), optionalUuid("sourceIncidentId"), hold,
            long("deliveryVersion")?.takeIf { it >= 0 } ?: return null,
            bool("replayed") ?: return null
        )
    } catch (_: Exception) {
        null
    }
    private fun JsonObject.uuid(k: String): String? = text(k)?.takeIf(executionUuid::matches)
    private fun JsonObject.optionalUuid(k: String): String? = when (this[k]) {
        null, JsonNull -> null
        else -> uuid(k)
    }
    private fun JsonObject.validUuid(k: String) =
        this[k] == null || this[k] == JsonNull || uuid(k) != null
    private fun JsonObject.text(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    private fun JsonObject.validText(k: String) =
        this[k] == null || this[k] == JsonNull || text(k) != null
    private fun JsonObject.long(k: String) =
        (this[k] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
    private fun JsonObject.decimal(k: String): BigDecimal? = (this[k] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.contentOrNull
        ?.takeIf {
            it.toBigDecimalOrNull() != null
        }?.let { runCatching { BigDecimal(it) }.getOrNull() }
    private fun JsonObject.optionalDecimal(k: String) = when (this[k]) {
        null, JsonNull -> null
        else -> decimal(k)
    }
    private fun JsonObject.validDecimal(k: String) =
        this[k] == null || this[k] == JsonNull || decimal(k) != null
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.instant(k: String): Instant? =
        text(k)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    private fun JsonObject.optionalInstant(k: String) = when (this[k]) {
        null, JsonNull -> null
        else -> instant(k)
    }
    private fun JsonObject.validInstant(k: String) =
        this[k] == null || this[k] == JsonNull || instant(k) != null
    private fun JsonObject.objectValue(k: String) = this[k] as? JsonObject
    private fun String?.version(): Long? {
        if (this == null || !matches(Regex("\"(?:0|[1-9][0-9]*)\""))) return null
        return substring(1, length - 1).toLongOrNull()
    }
    private fun String.validDisposition(expected: String): Boolean = try {
        val root = executionJson.parseToJsonElement(this).jsonObject
        expected in setOf("RELEASE", "CONTINUE_HOLD", "REJECT", "WASTE") &&
            root.keys == setOf("disposition", "reason") &&
            (root["disposition"] as? JsonPrimitive)?.content == expected &&
            !(root["reason"] as? JsonPrimitive)?.content.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}
