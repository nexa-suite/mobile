package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val DRIVER_WORKDAY_PATH = "/api/v1/driver/workdays"
private val workdayJson = Json { ignoreUnknownKeys = true }

data class DriverWorkdayProjection(
    val id: String,
    val version: Long,
    val status: String,
    val startedAt: String,
    val endedAt: String?,
    val locationAvailable: Boolean
)

data class DriverWorkdayLocationProjection(
    val sampleId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: String,
    val expiresAt: String
)

data class DriverWorkdayLocationCommand(
    val sampleId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: String
)

sealed interface DriverWorkdayNetworkResult {
    data class Current(val workday: DriverWorkdayProjection?) : DriverWorkdayNetworkResult
    data class LocationAccepted(val location: DriverWorkdayLocationProjection) :
        DriverWorkdayNetworkResult
    data object Accepted : DriverWorkdayNetworkResult
    data class Rejected(val code: String?) : DriverWorkdayNetworkResult
    data object NotFound : DriverWorkdayNetworkResult
    data object StaleVersion : DriverWorkdayNetworkResult
    data object UnknownOutcome : DriverWorkdayNetworkResult
    data object Unavailable : DriverWorkdayNetworkResult
    data object PermissionDenied : DriverWorkdayNetworkResult
}

/** Current workday and ephemeral location stream; location samples are never staged locally. */
class NexaDriverWorkdayGateway(private val calls: ProtectedCallExecutor) {
    suspend fun current(): DriverWorkdayNetworkResult = when (
        val result = calls.execute(
            ProtectedRequest(ProtectedMethod.GET, "$DRIVER_WORKDAY_PATH/current")
        )
    ) {
        is ProtectedResult.Failure -> result.error.toWorkdayResult()

        is ProtectedResult.Success -> {
            if (result.status == 204 && result.body.isNullOrBlank()) {
                DriverWorkdayNetworkResult.Current(null)
            } else {
                val workday = result.body?.toWorkdayProjection()
                    ?: return DriverWorkdayNetworkResult.Unavailable
                if (result.status != 200 || result.etag != "\"${workday.version}\"") {
                    DriverWorkdayNetworkResult.Unavailable
                } else {
                    DriverWorkdayNetworkResult.Current(workday)
                }
            }
        }
    }

    suspend fun start(idempotencyKey: String): DriverWorkdayNetworkResult {
        if (!validKey(idempotencyKey)) return DriverWorkdayNetworkResult.Unavailable
        return mutate(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = DRIVER_WORKDAY_PATH,
                payload = JsonObject(mapOf("locationAvailable" to JsonPrimitive(true))).toString(),
                idempotencyKey = idempotencyKey
            )
        )
    }

    suspend fun end(
        workdayId: String,
        version: Long,
        idempotencyKey: String
    ): DriverWorkdayNetworkResult {
        if (!validId(workdayId) || version < 0 || !validKey(idempotencyKey)) {
            return DriverWorkdayNetworkResult.Unavailable
        }
        return mutate(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = "$DRIVER_WORKDAY_PATH/$workdayId/ends",
                idempotencyKey = idempotencyKey,
                ifMatch = version.etag()
            )
        )
    }

    suspend fun setLocationAvailability(
        workdayId: String,
        version: Long,
        locationAvailable: Boolean,
        idempotencyKey: String
    ): DriverWorkdayNetworkResult {
        if (!validId(workdayId) || version < 0 || !validKey(idempotencyKey)) {
            return DriverWorkdayNetworkResult.Unavailable
        }
        return mutate(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = "$DRIVER_WORKDAY_PATH/$workdayId/location-availability",
                payload = JsonObject(
                    mapOf("locationAvailable" to JsonPrimitive(locationAvailable))
                ).toString(),
                idempotencyKey = idempotencyKey,
                ifMatch = version.etag()
            )
        )
    }

    suspend fun reportLocation(
        workdayId: String,
        location: DriverWorkdayLocationCommand
    ): DriverWorkdayNetworkResult {
        if (!validId(workdayId) || !validId(location.sampleId) ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0 ||
            !location.accuracyMeters.isFinite() || location.accuracyMeters !in 0.0..100_000.0 ||
            !validInstant(location.capturedAt)
        ) {
            return DriverWorkdayNetworkResult.Unavailable
        }
        val payload = JsonObject(
            mapOf(
                "sampleId" to JsonPrimitive(location.sampleId),
                "latitude" to JsonPrimitive(location.latitude),
                "longitude" to JsonPrimitive(location.longitude),
                "accuracyMeters" to JsonPrimitive(location.accuracyMeters),
                "capturedAt" to JsonPrimitive(location.capturedAt)
            )
        ).toString()
        return when (
            val result = calls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$DRIVER_WORKDAY_PATH/$workdayId/locations",
                    payload = payload
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toWorkdayResult()

            is ProtectedResult.Success -> {
                val accepted = result.body?.toLocationProjection()
                    ?: return DriverWorkdayNetworkResult.UnknownOutcome
                if (result.status !in setOf(200, 201) || accepted.sampleId != location.sampleId ||
                    accepted.latitude != location.latitude ||
                    accepted.longitude != location.longitude ||
                    accepted.accuracyMeters != location.accuracyMeters ||
                    accepted.capturedAt != location.capturedAt
                ) {
                    DriverWorkdayNetworkResult.UnknownOutcome
                } else {
                    DriverWorkdayNetworkResult.LocationAccepted(accepted)
                }
            }
        }
    }

    private suspend fun mutate(request: ProtectedRequest): DriverWorkdayNetworkResult =
        when (val result = calls.execute(request)) {
            is ProtectedResult.Failure -> result.error.toWorkdayResult()

            is ProtectedResult.Success -> if (result.status in setOf(200, 201, 204)) {
                DriverWorkdayNetworkResult.Accepted
            } else {
                DriverWorkdayNetworkResult.UnknownOutcome
            }
        }
}

private fun String.toWorkdayProjection(): DriverWorkdayProjection? {
    return try {
        val row = workdayJson.parseToJsonElement(this).jsonObject
        val id = row["id"]?.jsonPrimitive?.content ?: return null
        val version = row["version"]?.jsonPrimitive?.longOrNull ?: return null
        val status = row["status"]?.jsonPrimitive?.content ?: return null
        val startedAt = row["startedAt"]?.jsonPrimitive?.content ?: return null
        val endedAt = row["endedAt"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        val locationAvailable =
            row["locationAvailable"]?.jsonPrimitive?.booleanOrNull ?: return null
        if (!validId(id) || version < 0 ||
            status !in setOf("ACTIVE", "LOCATION_UNAVAILABLE", "CLOSED") ||
            !validInstant(startedAt) || (endedAt != null && !validInstant(endedAt)) ||
            (status == "CLOSED") != (endedAt != null) ||
            (status == "ACTIVE" && !locationAvailable) ||
            (status == "LOCATION_UNAVAILABLE" && locationAvailable)
        ) {
            return null
        }
        DriverWorkdayProjection(id, version, status, startedAt, endedAt, locationAvailable)
    } catch (_: Exception) {
        null
    }
}

private fun String.toLocationProjection(): DriverWorkdayLocationProjection? {
    return try {
        val row = workdayJson.parseToJsonElement(this).jsonObject
        val sampleId = row["sampleId"]?.jsonPrimitive?.content ?: return null
        val latitude = row["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val longitude = row["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val accuracy = row["accuracyMeters"]?.jsonPrimitive?.doubleOrNull ?: return null
        val capturedAt = row["capturedAt"]?.jsonPrimitive?.content ?: return null
        val expiresAt = row["expiresAt"]?.jsonPrimitive?.content ?: return null
        if (!validId(sampleId) || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            !accuracy.isFinite() || accuracy !in 0.0..100_000.0 ||
            !validInstant(capturedAt) || !validInstant(expiresAt) ||
            Duration.between(Instant.parse(capturedAt), Instant.parse(expiresAt)).let {
                it.isNegative || it.isZero || it > Duration.ofHours(24)
            }
        ) {
            return null
        }
        DriverWorkdayLocationProjection(
            sampleId,
            latitude,
            longitude,
            accuracy,
            capturedAt,
            expiresAt
        )
    } catch (_: Exception) {
        null
    }
}

private fun ClientFailure.toWorkdayResult(): DriverWorkdayNetworkResult = when {
    httpStatus == 404 -> DriverWorkdayNetworkResult.NotFound

    kind == FailureKind.StaleState || httpStatus == 412 -> DriverWorkdayNetworkResult.StaleVersion

    kind == FailureKind.AuthorizationFailure || kind == FailureKind.AuthenticationRequired ->
        DriverWorkdayNetworkResult.PermissionDenied

    kind == FailureKind.UnknownOutcome || kind == FailureKind.RetryableServerFailure ->
        DriverWorkdayNetworkResult.UnknownOutcome

    kind == FailureKind.BusinessConflict -> DriverWorkdayNetworkResult.Rejected(problemCode)

    else -> DriverWorkdayNetworkResult.Unavailable
}

private fun Long.etag(): String = "\"$this\""
private fun validKey(value: String): Boolean = value.isNotBlank() && value.length <= 160
private fun validId(value: String): Boolean = runCatching {
    UUID.fromString(value).toString() ==
        value.lowercase()
}.getOrDefault(false)
private fun validInstant(value: String): Boolean = runCatching { Instant.parse(value) }.isSuccess
