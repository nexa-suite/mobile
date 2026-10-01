package com.nexa.mobile.operations.core.network

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val DRIVER_OPERATIONAL_EXCEPTIONS_PATH = "/api/v1/driver/deliveries"
private val operationalExceptionJson = Json { ignoreUnknownKeys = true }
private val operationalExceptionUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class DriverOperationalExceptionTransport(
    val id: String,
    val sourceKind: String,
    val sourceIncidentId: String,
    val affectedObjectType: String,
    val affectedObjectId: String,
    val type: String,
    val severity: String,
    val status: String,
    val reason: String?,
    val description: String,
    val place: String?,
    val resolution: String?,
    val outcome: String?,
    val reportedByMembershipId: String,
    val occurredAt: String,
    val reportedAt: String,
    val responsibleMembershipId: String?,
    val claimedAt: String?,
    val underReviewByMembershipId: String?,
    val underReviewAt: String?,
    val evidenceObjectIds: List<String>
)

data class DriverOperationalExceptionsTransport(
    val deliveryId: String,
    val deliveryVersion: Long,
    val exceptions: List<DriverOperationalExceptionTransport>
)

data class DriverOperationalExceptionMutationTransport(
    val deliveryId: String,
    val deliveryVersion: Long,
    val exception: DriverOperationalExceptionTransport,
    val replayed: Boolean
)

sealed interface DriverOperationalExceptionsNetworkOutcome {
    data class Loaded(val value: DriverOperationalExceptionsTransport) : DriverOperationalExceptionsNetworkOutcome
    data class Changed(val value: DriverOperationalExceptionMutationTransport) : DriverOperationalExceptionsNetworkOutcome
    data class Rejected(val code: String?) : DriverOperationalExceptionsNetworkOutcome
    data object NotFound : DriverOperationalExceptionsNetworkOutcome
    data object StaleVersion : DriverOperationalExceptionsNetworkOutcome
    data object UnknownOutcome : DriverOperationalExceptionsNetworkOutcome
    data object NetworkUnavailable : DriverOperationalExceptionsNetworkOutcome
    data object ServiceUnavailable : DriverOperationalExceptionsNetworkOutcome
    data object PermissionDenied : DriverOperationalExceptionsNetworkOutcome
    data object ContextInvalidated : DriverOperationalExceptionsNetworkOutcome
    data object SessionInvalidated : DriverOperationalExceptionsNetworkOutcome
}

/** Protected transport for current Driver Delivery operational exception reads and response actions. */
class NexaDriverDeliveryOperationalExceptionsGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun currentExceptions(deliveryId: String): DriverOperationalExceptionsNetworkOutcome {
        if (!operationalExceptionUuid.matches(deliveryId)) {
            return DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.GET,
                    "$DRIVER_OPERATIONAL_EXCEPTIONS_PATH/$deliveryId/operational-exceptions"
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toOperationalExceptionReadOutcome()
            is ProtectedResult.Success -> {
                val root = result.body.toObject() ?: return DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable
                val value = root.toExceptions()
                    ?: return DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable
                if (value.deliveryId != deliveryId || result.etag.toStrongDeliveryVersion() != value.deliveryVersion) {
                    DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable
                } else {
                    DriverOperationalExceptionsNetworkOutcome.Loaded(value)
                }
            }
        }
    }

    suspend fun mutate(
        deliveryId: String,
        exceptionId: String,
        action: DriverOperationalExceptionActionTransport,
        expectedDeliveryVersion: Long,
        idempotencyKey: String,
        frozenBody: String
    ): DriverOperationalExceptionsNetworkOutcome {
        if (!operationalExceptionUuid.matches(deliveryId) || !operationalExceptionUuid.matches(exceptionId) ||
            expectedDeliveryVersion < 0 || idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            !frozenBody.isValidFor(action)
        ) return DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable

        val actionPath = when (action) {
            DriverOperationalExceptionActionTransport.Claim -> "claims"
            DriverOperationalExceptionActionTransport.Review -> "reviews"
            DriverOperationalExceptionActionTransport.ResolveWarning -> "resolutions"
            DriverOperationalExceptionActionTransport.CloseWarning -> "closures"
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$DRIVER_OPERATIONAL_EXCEPTIONS_PATH/$deliveryId/operational-exceptions/$exceptionId/$actionPath",
                    payload = frozenBody.takeUnless { action == DriverOperationalExceptionActionTransport.CloseWarning },
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedDeliveryVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toOperationalExceptionMutationOutcome()
            is ProtectedResult.Success -> {
                if (result.status !in setOf(200, 201)) {
                    return DriverOperationalExceptionsNetworkOutcome.UnknownOutcome
                }
                val root = result.body.toObject()
                    ?: return DriverOperationalExceptionsNetworkOutcome.UnknownOutcome
                val value = root.toMutation() ?: return DriverOperationalExceptionsNetworkOutcome.UnknownOutcome
                if (value.deliveryId != deliveryId || value.exception.id != exceptionId ||
                    result.etag.toStrongDeliveryVersion() != value.deliveryVersion ||
                    value.replayed != (result.status == 200)
                ) {
                    DriverOperationalExceptionsNetworkOutcome.UnknownOutcome
                } else {
                    DriverOperationalExceptionsNetworkOutcome.Changed(value)
                }
            }
        }
    }

    private fun String?.toObject(): JsonObject? = try {
        this?.let(operationalExceptionJson::parseToJsonElement)?.jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toExceptions(): DriverOperationalExceptionsTransport? = try {
        val deliveryId = requiredText("deliveryId")?.takeIf(operationalExceptionUuid::matches) ?: return null
        val deliveryVersion = requiredLong("deliveryVersion") ?: return null
        val rows = this["exceptions"]?.jsonArray ?: return null
        val exceptions = rows.map { it.jsonObject.toOperationalException() ?: return null }
        if (deliveryVersion < 0 || exceptions.map { it.id.lowercase() }.distinct().size != exceptions.size) return null
        DriverOperationalExceptionsTransport(deliveryId, deliveryVersion, exceptions)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toMutation(): DriverOperationalExceptionMutationTransport? = try {
        val deliveryId = requiredText("deliveryId")?.takeIf(operationalExceptionUuid::matches) ?: return null
        val deliveryVersion = requiredLong("deliveryVersion") ?: return null
        val exception = this["exception"]?.jsonObject?.toOperationalException() ?: return null
        val replayed = requiredBoolean("replayed") ?: return null
        if (deliveryVersion < 0) return null
        DriverOperationalExceptionMutationTransport(deliveryId, deliveryVersion, exception, replayed)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toOperationalException(): DriverOperationalExceptionTransport? = try {
        val id = requiredText("id")?.takeIf(operationalExceptionUuid::matches) ?: return null
        val sourceKind = requiredText("sourceKind")?.takeIf { it in setOf("DISPATCH_INCIDENT", "DRIVER_INCIDENT") }
            ?: return null
        val sourceIncidentId = requiredText("sourceIncidentId")
            ?.takeIf(operationalExceptionUuid::matches) ?: return null
        val affectedObjectType = requiredText("affectedObjectType") ?: return null
        val affectedObjectId = requiredText("affectedObjectId")?.takeIf(operationalExceptionUuid::matches) ?: return null
        val type = requiredText("type") ?: return null
        val severity = requiredText("severity") ?: return null
        val status = requiredText("status")
            ?.takeIf { it in setOf("OPEN", "CLAIMED", "UNDER_REVIEW", "RESOLVED", "CLOSED") } ?: return null
        val reason = requiredNullableText("reason")
        val description = requiredText("description") ?: return null
        val place = requiredNullableText("place")
        val resolution = requiredNullableText("resolution")
        val outcome = requiredNullableText("outcome")
        val reportedBy = requiredText("reportedByMembershipId")
            ?.takeIf(operationalExceptionUuid::matches) ?: return null
        val occurredAt = requiredText("occurredAt")?.takeIf { it.isIsoInstant() } ?: return null
        val reportedAt = requiredText("reportedAt")?.takeIf { it.isIsoInstant() } ?: return null
        val responsible = requiredNullableText("responsibleMembershipId")
        val claimedAt = requiredNullableInstant("claimedAt")
        val underReviewBy = requiredNullableText("underReviewByMembershipId")
        val underReviewAt = requiredNullableInstant("underReviewAt")
        val evidenceObjectIds = this["evidenceObjectIds"]?.jsonArray
            ?.map { it.jsonPrimitive.contentOrNull?.takeIf(operationalExceptionUuid::matches) ?: return null }
            ?: return null
        if (responsible != null && !operationalExceptionUuid.matches(responsible) ||
            underReviewBy != null && !operationalExceptionUuid.matches(underReviewBy) ||
            affectedObjectType != "DELIVERY" ||
            evidenceObjectIds.distinct().size != evidenceObjectIds.size
        ) return null
        DriverOperationalExceptionTransport(
            id, sourceKind, sourceIncidentId, affectedObjectType, affectedObjectId, type, severity, status,
            reason, description, place, resolution, outcome, reportedBy, occurredAt, reportedAt,
            responsible, claimedAt, underReviewBy, underReviewAt, evidenceObjectIds
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredText(key: String): String? = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.requiredLong(key: String): Long? = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun JsonObject.requiredBoolean(key: String): Boolean? = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

    private fun JsonObject.requiredNullableText(key: String): String? = when (val value = this[key]) {
        null -> throw IllegalArgumentException("Missing $key")
        JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Invalid $key")
        else -> throw IllegalArgumentException("Invalid $key")
    }

    private fun JsonObject.requiredNullableInstant(key: String): String? = when (val value = this[key]) {
        null -> throw IllegalArgumentException("Missing $key")
        JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.takeIf { it.isIsoInstant() } ?: throw IllegalArgumentException("Invalid $key")
        else -> throw IllegalArgumentException("Invalid $key")
    }

    private fun String.isIsoInstant(): Boolean = try {
        Instant.parse(this)
        true
    } catch (_: Exception) {
        false
    }

    private fun String?.toStrongDeliveryVersion(): Long? {
        val tag = this?.takeUnless { it.startsWith("W/", ignoreCase = true) } ?: return null
        if (!tag.startsWith('"') || !tag.endsWith('"')) return null
        return tag.removeSurrounding("\"").toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun String.isValidFor(action: DriverOperationalExceptionActionTransport): Boolean = when (action) {
        DriverOperationalExceptionActionTransport.Claim,
        DriverOperationalExceptionActionTransport.Review -> this == EMPTY_BODY

        DriverOperationalExceptionActionTransport.CloseWarning -> isEmpty()
        DriverOperationalExceptionActionTransport.ResolveWarning -> isResolutionBody()
    }

    private fun String.isResolutionBody(): Boolean {
        return try {
            val root = operationalExceptionJson.parseToJsonElement(this).jsonObject
            val resolution = root["resolution"]?.jsonPrimitive
                ?.takeIf(JsonPrimitive::isString)?.contentOrNull
            root.keys == setOf("resolution") && resolution != null && resolution.isNotBlank() &&
                resolution.trim().length <= WARNING_RESOLUTION_MAX_CHARS
        } catch (_: Exception) {
            false
        }
    }

    private fun ClientFailure.toOperationalExceptionReadOutcome(): DriverOperationalExceptionsNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> DriverOperationalExceptionsNetworkOutcome.SessionInvalidated
        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID -> DriverOperationalExceptionsNetworkOutcome.ContextInvalidated
        kind == FailureKind.AuthorizationFailure -> DriverOperationalExceptionsNetworkOutcome.PermissionDenied
        kind == FailureKind.ResourceUnavailable -> DriverOperationalExceptionsNetworkOutcome.NotFound
        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout -> DriverOperationalExceptionsNetworkOutcome.NetworkUnavailable
        else -> DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toOperationalExceptionMutationOutcome(): DriverOperationalExceptionsNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> DriverOperationalExceptionsNetworkOutcome.SessionInvalidated
        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID -> DriverOperationalExceptionsNetworkOutcome.ContextInvalidated
        kind == FailureKind.AuthorizationFailure -> DriverOperationalExceptionsNetworkOutcome.PermissionDenied
        kind == FailureKind.StaleState -> DriverOperationalExceptionsNetworkOutcome.StaleVersion
        kind == FailureKind.ResourceUnavailable -> DriverOperationalExceptionsNetworkOutcome.NotFound
        kind == FailureKind.ValidationFailure || kind == FailureKind.BusinessConflict ||
            kind == FailureKind.PreconditionRequired -> DriverOperationalExceptionsNetworkOutcome.Rejected(problemCode)
        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ||
            kind == FailureKind.UnknownOutcome || kind == FailureKind.RetryableServerFailure ->
            DriverOperationalExceptionsNetworkOutcome.UnknownOutcome
        else -> DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable
    }

    private companion object {
        const val EMPTY_BODY = "{}"
        const val WARNING_RESOLUTION_MAX_CHARS = 2000
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}

enum class DriverOperationalExceptionActionTransport { Claim, Review, ResolveWarning, CloseWarning }
