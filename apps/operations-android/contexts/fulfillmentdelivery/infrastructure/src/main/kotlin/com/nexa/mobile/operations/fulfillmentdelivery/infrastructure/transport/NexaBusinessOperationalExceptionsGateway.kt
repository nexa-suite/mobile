package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.BusinessOperationalExceptionsNetworkResult as ExceptionsNetworkResult
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val BUSINESS_OPERATIONAL_EXCEPTIONS_PATH = "/api/v1/operational-exceptions"
private val businessExceptionJson = Json { ignoreUnknownKeys = true }
private val businessExceptionUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class BusinessOperationalExceptionTransport(
    val id: String,
    val deliveryId: String,
    val deliveryVersion: Long,
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
    val coordinationOwnerMembershipId: String?,
    val coordinationClaimedAt: String?,
    val evidenceObjectIds: List<String>
)

data class BusinessOperationalExceptionsTransport(
    val asOf: String,
    val exceptions: List<BusinessOperationalExceptionTransport>
)
data class BusinessOperationalExceptionAssigneeTransport(
    val membershipId: String,
    val displayName: String,
    val coordinator: Boolean,
    val driverReporter: Boolean
)
data class BusinessOperationalExceptionMutationTransport(
    val deliveryId: String,
    val deliveryVersion: Long,
    val exception: BusinessOperationalExceptionTransport,
    val replayed: Boolean
)

enum class BusinessOperationalExceptionActionTransport {
    CLAIM,
    REASSIGN,
    FOLLOW_UP,
    RESOLVE,
    CLOSE
}

sealed interface BusinessOperationalExceptionsNetworkResult {
    data class Current(val value: BusinessOperationalExceptionsTransport) :
        ExceptionsNetworkResult
    data class Assignees(val values: List<BusinessOperationalExceptionAssigneeTransport>) :
        ExceptionsNetworkResult
    data class Changed(val value: BusinessOperationalExceptionMutationTransport) :
        ExceptionsNetworkResult
    data class Failed(val code: String?, val unknownOutcome: Boolean = false) :
        ExceptionsNetworkResult
}

/** Protected BOM exception transport. Authorization remains in the app gateway and API. */
class NexaBusinessOperationalExceptionsGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun current(): ExceptionsNetworkResult {
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, BUSINESS_OPERATIONAL_EXCEPTIONS_PATH)
            )
        ) {
            is ProtectedResult.Failure -> result.error.toReadResult()

            is ProtectedResult.Success -> {
                val root = result.body.toObject()
                    ?: return ExceptionsNetworkResult.Failed("INVALID_RESPONSE")
                val value = root.toExceptionSet()
                    ?: return ExceptionsNetworkResult.Failed("INVALID_RESPONSE")
                if (result.status !=
                    200
                ) {
                    ExceptionsNetworkResult.Failed("INVALID_RESPONSE")
                } else {
                    ExceptionsNetworkResult.Current(value)
                }
            }
        }
    }

    suspend fun assignees(exceptionId: String): ExceptionsNetworkResult {
        if (!businessExceptionUuid.matches(
                exceptionId
            )
        ) {
            return ExceptionsNetworkResult.Failed("INVALID_ID")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.GET,
                    "$BUSINESS_OPERATIONAL_EXCEPTIONS_PATH/$exceptionId/assignees"
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toReadResult()

            is ProtectedResult.Success -> {
                val rows =
                    result.body.toArray()?.map {
                        it.toAssignee()
                            ?: return ExceptionsNetworkResult.Failed(
                                "INVALID_RESPONSE"
                            )
                    }
                        ?: return ExceptionsNetworkResult.Failed(
                            "INVALID_RESPONSE"
                        )
                if (result.status == 200 &&
                    rows.map { it.membershipId.lowercase() }.distinct().size == rows.size
                ) {
                    ExceptionsNetworkResult.Assignees(rows)
                } else {
                    ExceptionsNetworkResult.Failed("INVALID_RESPONSE")
                }
            }
        }
    }

    suspend fun mutate(
        exceptionId: String,
        action: BusinessOperationalExceptionActionTransport,
        expectedDeliveryVersion: Long,
        idempotencyKey: String,
        frozenBody: String
    ): ExceptionsNetworkResult {
        if (!businessExceptionUuid.matches(exceptionId) || expectedDeliveryVersion < 0 ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            !frozenBody.isValidFor(action)
        ) {
            return ExceptionsNetworkResult.Failed("INVALID_COMMAND")
        }
        val actionPath = when (action) {
            BusinessOperationalExceptionActionTransport.CLAIM -> "claims"
            BusinessOperationalExceptionActionTransport.REASSIGN -> "assignments"
            BusinessOperationalExceptionActionTransport.FOLLOW_UP -> "follow-ups"
            BusinessOperationalExceptionActionTransport.RESOLVE -> "resolutions"
            BusinessOperationalExceptionActionTransport.CLOSE -> "closures"
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$BUSINESS_OPERATIONAL_EXCEPTIONS_PATH/$exceptionId/$actionPath",
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedDeliveryVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toMutationResult()

            is ProtectedResult.Success -> {
                if (result.status !in
                    setOf(200, 201)
                ) {
                    return ExceptionsNetworkResult.Failed(
                        "INVALID_RESPONSE",
                        true
                    )
                }
                val root =
                    result.body.toObject()
                        ?: return ExceptionsNetworkResult.Failed(
                            "UNKNOWN_OUTCOME",
                            true
                        )
                val value =
                    root.toMutation()
                        ?: return ExceptionsNetworkResult.Failed(
                            "UNKNOWN_OUTCOME",
                            true
                        )
                if (value.exception.id != exceptionId ||
                    value.deliveryId != value.exception.deliveryId ||
                    value.deliveryVersion != value.exception.deliveryVersion ||
                    result.etag.toStrongDeliveryVersion() != value.deliveryVersion ||
                    value.replayed != (result.status == 200)
                ) {
                    ExceptionsNetworkResult.Failed("UNKNOWN_OUTCOME", true)
                } else {
                    ExceptionsNetworkResult.Changed(value)
                }
            }
        }
    }

    private fun String?.toObject(): JsonObject? = try {
        this?.let(businessExceptionJson::parseToJsonElement)?.jsonObject
    } catch (_: Exception) {
        null
    }

    private fun String?.toArray(): JsonArray? = try {
        this?.let(businessExceptionJson::parseToJsonElement)?.jsonArray
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toExceptionSet(): BusinessOperationalExceptionsTransport? = try {
        val asOf = requiredText("asOf")?.takeIf { it.isIsoInstant() } ?: return null
        val rows = this["exceptions"]?.jsonArray ?: return null
        val exceptions = rows.map { it.jsonObject.toException() ?: return null }
        if (exceptions.map { it.id.lowercase() }.distinct().size != exceptions.size) return null
        BusinessOperationalExceptionsTransport(asOf, exceptions)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toMutation(): BusinessOperationalExceptionMutationTransport? = try {
        val deliveryId =
            requiredText("deliveryId")?.takeIf(businessExceptionUuid::matches) ?: return null
        val deliveryVersion = requiredLong("deliveryVersion")?.takeIf { it >= 0 } ?: return null
        val exception = this["exception"]?.jsonObject?.toException() ?: return null
        val replayed = requiredBoolean("replayed") ?: return null
        BusinessOperationalExceptionMutationTransport(
            deliveryId,
            deliveryVersion,
            exception,
            replayed
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toException(): BusinessOperationalExceptionTransport? = try {
        val id = requiredText("id")?.takeIf(businessExceptionUuid::matches) ?: return null
        val deliveryId =
            requiredText("deliveryId")?.takeIf(businessExceptionUuid::matches) ?: return null
        val deliveryVersion = requiredLong("deliveryVersion")?.takeIf { it >= 0 } ?: return null
        val sourceKind = requiredText("sourceKind") ?: return null
        val sourceIncidentId =
            requiredText("sourceIncidentId")?.takeIf(businessExceptionUuid::matches) ?: return null
        val affectedObjectType = requiredText("affectedObjectType") ?: return null
        val affectedObjectId =
            requiredText("affectedObjectId")?.takeIf(businessExceptionUuid::matches) ?: return null
        val type = requiredText("type") ?: return null
        val severity =
            requiredText("severity")?.takeIf { it in setOf("WARNING", "BLOCKING", "CRITICAL") }
                ?: return null
        val status =
            requiredText("status")?.takeIf {
                it in
                    setOf("OPEN", "CLAIMED", "UNDER_REVIEW", "RESOLVED", "CLOSED")
            }
                ?: return null
        val reason = requiredNullableText("reason")
        val description = requiredText("description") ?: return null
        val place = requiredNullableText("place")
        val resolution = requiredNullableText("resolution")
        val outcome = requiredNullableText("outcome")
        val reportedBy =
            requiredText("reportedByMembershipId")?.takeIf(businessExceptionUuid::matches)
                ?: return null
        val occurredAt = requiredText("occurredAt")?.takeIf { it.isIsoInstant() } ?: return null
        val reportedAt = requiredText("reportedAt")?.takeIf { it.isIsoInstant() } ?: return null
        val responsible = requiredNullableUuid("responsibleMembershipId")
        val claimedAt = requiredNullableInstant("claimedAt")
        val underReviewBy = requiredNullableUuid("underReviewByMembershipId")
        val underReviewAt = requiredNullableInstant("underReviewAt")
        val coordinationOwner = requiredNullableUuid("coordinationOwnerMembershipId")
        val coordinationClaimedAt = requiredNullableInstant("coordinationClaimedAt")
        val evidenceObjectIds = this["evidenceObjectIds"]?.jsonArray
            ?.map {
                it.jsonPrimitive.contentOrNull?.takeIf(businessExceptionUuid::matches)
                    ?: return null
            }
            ?: return null
        if (description.isBlank() ||
            evidenceObjectIds.distinct().size != evidenceObjectIds.size
        ) {
            return null
        }
        BusinessOperationalExceptionTransport(
            id, deliveryId, deliveryVersion, sourceKind, sourceIncidentId,
            affectedObjectType, affectedObjectId,
            type, severity, status, reason, description, place, resolution, outcome,
            reportedBy, occurredAt, reportedAt,
            responsible, claimedAt, underReviewBy, underReviewAt, coordinationOwner,
            coordinationClaimedAt, evidenceObjectIds
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonElement.toAssignee(): BusinessOperationalExceptionAssigneeTransport? = try {
        val root = jsonObject
        val membershipId =
            root.requiredText("membershipId")?.takeIf(businessExceptionUuid::matches)
                ?: return null
        val displayName = root.requiredText("displayName") ?: return null
        val coordinator = root.requiredBoolean("coordinator") ?: return null
        val driverReporter = root.requiredBoolean("driverReporter") ?: return null
        BusinessOperationalExceptionAssigneeTransport(
            membershipId,
            displayName,
            coordinator,
            driverReporter
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

    private fun JsonObject.requiredNullableText(key: String): String? =
        when (val value = this[key]) {
            null -> throw IllegalArgumentException("Missing $key")

            JsonNull -> null

            is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: throw IllegalArgumentException("Invalid $key")

            else -> throw IllegalArgumentException("Invalid $key")
        }

    private fun JsonObject.requiredNullableUuid(key: String): String? =
        when (val value = requiredNullableText(key)) {
            null -> null

            else -> value.takeIf(businessExceptionUuid::matches)
                ?: throw IllegalArgumentException("Invalid $key")
        }

    private fun JsonObject.requiredNullableInstant(key: String): String? =
        when (val value = requiredNullableText(key)) {
            null -> null

            else -> value.takeIf { it.isIsoInstant() }
                ?: throw IllegalArgumentException("Invalid $key")
        }

    private fun String.isIsoInstant(): Boolean = try {
        Instant.parse(this)
        true
    } catch (_: Exception) {
        false
    }

    private fun String?.toStrongDeliveryVersion(): Long? {
        val tag = this?.takeUnless { it.startsWith("W/", true) } ?: return null
        if (!tag.startsWith('"') || !tag.endsWith('"')) return null
        return tag.removeSurrounding("\"").toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun String.isValidFor(action: BusinessOperationalExceptionActionTransport): Boolean =
        try {
            val root = businessExceptionJson.parseToJsonElement(this).jsonObject
            when (action) {
                BusinessOperationalExceptionActionTransport.CLAIM,
                BusinessOperationalExceptionActionTransport.RESOLVE,
                BusinessOperationalExceptionActionTransport.CLOSE -> root.keys == setOf("reason") &&
                    root.requiredText("reason") != null

                BusinessOperationalExceptionActionTransport.REASSIGN ->
                    root.keys ==
                        setOf("responsibleMembershipId", "reason") &&
                        root.requiredText(
                            "responsibleMembershipId"
                        )?.let(businessExceptionUuid::matches) ==
                        true &&
                        root.requiredText("reason") != null

                BusinessOperationalExceptionActionTransport.FOLLOW_UP ->
                    root.keys ==
                        setOf("reason", "note") &&
                        root.requiredText("reason") != null && root.requiredText("note") != null
            }
        } catch (_: Exception) {
            false
        }

    private fun ClientFailure.toReadResult(): ExceptionsNetworkResult.Failed = when {
        kind == FailureKind.AuthenticationRequired ->
            ExceptionsNetworkResult.Failed(
                "SESSION_INVALID"
            )

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            ExceptionsNetworkResult.Failed(
                "ACCESS_CONTEXT_INVALID"
            )

        kind == FailureKind.AuthorizationFailure ->
            ExceptionsNetworkResult.Failed(
                "PERMISSION_DENIED"
            )

        kind == FailureKind.ResourceUnavailable ->
            ExceptionsNetworkResult.Failed(
                "NOT_FOUND"
            )

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            ExceptionsNetworkResult.Failed(
                "NETWORK_UNAVAILABLE"
            )

        else -> ExceptionsNetworkResult.Failed(
            problemCode ?: "SERVICE_UNAVAILABLE"
        )
    }

    private fun ClientFailure.toMutationResult(): ExceptionsNetworkResult.Failed = when {
        kind == FailureKind.AuthenticationRequired ->
            ExceptionsNetworkResult.Failed(
                "SESSION_INVALID"
            )

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            ExceptionsNetworkResult.Failed(
                "ACCESS_CONTEXT_INVALID"
            )

        kind == FailureKind.AuthorizationFailure ->
            ExceptionsNetworkResult.Failed(
                "PERMISSION_DENIED"
            )

        kind == FailureKind.StaleState -> ExceptionsNetworkResult.Failed(
            "STALE_VERSION"
        )

        kind == FailureKind.ResourceUnavailable ->
            ExceptionsNetworkResult.Failed(
                "NOT_FOUND"
            )

        kind == FailureKind.ValidationFailure || kind == FailureKind.BusinessConflict ||
            kind == FailureKind.PreconditionRequired ->
            ExceptionsNetworkResult.Failed(problemCode ?: "REJECTED")

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ||
            kind == FailureKind.UnknownOutcome ||
            kind == FailureKind.RetryableServerFailure ->
            ExceptionsNetworkResult.Failed(
                "UNKNOWN_OUTCOME",
                true
            )

        else -> ExceptionsNetworkResult.Failed(
            problemCode ?: "SERVICE_UNAVAILABLE",
            true
        )
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}
