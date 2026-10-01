package com.nexa.mobile.operations.feature.delivery

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val operationalExceptionUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

@Immutable
data class DriverDeliveryOperationalException(
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
) {
    init {
        require(operationalExceptionUuid.matches(id))
        require(sourceKind in setOf("DISPATCH_INCIDENT", "DRIVER_INCIDENT"))
        require(operationalExceptionUuid.matches(sourceIncidentId))
        require(affectedObjectType == "DELIVERY")
        require(operationalExceptionUuid.matches(affectedObjectId))
        require(type.isNotBlank() && severity.isNotBlank() && status.isNotBlank())
        require(status in setOf("OPEN", "CLAIMED", "UNDER_REVIEW", "RESOLVED", "CLOSED"))
        require(description.isNotBlank())
        require(operationalExceptionUuid.matches(reportedByMembershipId))
        require(occurredAt.isNotBlank())
        require(reportedAt.isNotBlank())
        require(responsibleMembershipId == null || operationalExceptionUuid.matches(responsibleMembershipId))
        require(claimedAt == null || claimedAt.isNotBlank())
        require(underReviewByMembershipId == null || operationalExceptionUuid.matches(underReviewByMembershipId))
        require(underReviewAt == null || underReviewAt.isNotBlank())
        require(evidenceObjectIds.distinct().size == evidenceObjectIds.size)
        require(evidenceObjectIds.all(operationalExceptionUuid::matches))
    }
}

@Immutable
data class DriverDeliveryOperationalExceptionsSnapshot(
    val deliveryId: String,
    val deliveryVersion: Long,
    val exceptions: List<DriverDeliveryOperationalException>
) {
    init {
        require(operationalExceptionUuid.matches(deliveryId))
        require(deliveryVersion >= 0)
        require(exceptions.map { it.id.lowercase() }.distinct().size == exceptions.size)
    }
}

enum class DriverDeliveryOperationalExceptionAction { Claim, Review, ResolveWarning, CloseWarning }

@Immutable
data class DriverDeliveryOperationalExceptionCommand(
    val deliveryId: String,
    val exceptionId: String,
    val action: DriverDeliveryOperationalExceptionAction,
    val expectedDeliveryVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String
) {
    init {
        require(operationalExceptionUuid.matches(deliveryId))
        require(operationalExceptionUuid.matches(exceptionId))
        require(expectedDeliveryVersion >= 0)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(
            when (action) {
                DriverDeliveryOperationalExceptionAction.Claim,
                DriverDeliveryOperationalExceptionAction.Review -> frozenBody == DRIVER_OPERATIONAL_EXCEPTION_EMPTY_BODY

                DriverDeliveryOperationalExceptionAction.ResolveWarning ->
                    driverDeliveryOperationalExceptionResolutionFromBody(frozenBody) != null

                DriverDeliveryOperationalExceptionAction.CloseWarning -> frozenBody.isEmpty()
            }
        )
    }

    override fun toString(): String =
        "DriverDeliveryOperationalExceptionCommand(action=$action, version=$expectedDeliveryVersion, key=REDACTED)"
}

const val DRIVER_OPERATIONAL_EXCEPTION_EMPTY_BODY = "{}"
const val DRIVER_WARNING_RESOLUTION_MAX_CHARS = 2000

fun driverDeliveryOperationalExceptionResolutionBody(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty() && normalized.length <= DRIVER_WARNING_RESOLUTION_MAX_CHARS)
    return JsonObject(mapOf("resolution" to JsonPrimitive(normalized))).toString()
}

fun driverDeliveryOperationalExceptionResolutionFromBody(body: String): String? {
    return try {
        val root = Json.parseToJsonElement(body).jsonObject
        val value = root["resolution"]?.jsonPrimitive
        if (root.keys != setOf("resolution") || value == null || !value.isString) {
            null
        } else {
            value.content.trim().takeIf { it.isNotEmpty() && it.length <= DRIVER_WARNING_RESOLUTION_MAX_CHARS }
        }
    } catch (_: Exception) {
        null
    }
}

const val DRIVER_OPERATIONAL_EXCEPTION_BODYLESS = ""

@Immutable
data class DriverDeliveryOperationalExceptionMutation(
    val deliveryId: String,
    val deliveryVersion: Long,
    val exception: DriverDeliveryOperationalException,
    val replayed: Boolean
) {
    init {
        require(operationalExceptionUuid.matches(deliveryId))
        require(deliveryVersion >= 0)
    }
}

enum class DriverDeliveryOperationalExceptionIntentStatus { Pending, UnknownOutcome, StaleVersion }

/** Encrypted retry metadata; it grants neither process authority nor permission to resolve an exception. */
@Immutable
data class DriverDeliveryOperationalExceptionIntent(
    val scope: DriverAttemptScopeIdentity,
    val command: DriverDeliveryOperationalExceptionCommand,
    val initiatedByMembershipId: String,
    val initiatedAt: String,
    val status: DriverDeliveryOperationalExceptionIntentStatus
) {
    init {
        require(operationalExceptionUuid.matches(initiatedByMembershipId))
        require(initiatedByMembershipId == scope.membershipId)
        require(initiatedAt.isNotBlank())
    }

    override fun toString(): String =
        "DriverDeliveryOperationalExceptionIntent(action=${command.action}, status=$status, command=REDACTED)"
}

sealed interface DriverDeliveryOperationalExceptionMetadataRead {
    data class Available(val intent: DriverDeliveryOperationalExceptionIntent?) :
        DriverDeliveryOperationalExceptionMetadataRead

    data object Unavailable : DriverDeliveryOperationalExceptionMetadataRead
}

sealed interface DriverDeliveryOperationalExceptionMetadataWrite {
    data object Saved : DriverDeliveryOperationalExceptionMetadataWrite
    data object Conflict : DriverDeliveryOperationalExceptionMetadataWrite
    data object Stale : DriverDeliveryOperationalExceptionMetadataWrite
    data object Unavailable : DriverDeliveryOperationalExceptionMetadataWrite
}

interface DriverDeliveryOperationalExceptionMetadataStore {
    suspend fun loadIntent(
        scope: DriverAttemptScopeIdentity
    ): DriverDeliveryOperationalExceptionMetadataRead

    suspend fun saveIntent(
        intent: DriverDeliveryOperationalExceptionIntent
    ): DriverDeliveryOperationalExceptionMetadataWrite

    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverDeliveryOperationalExceptionMetadataWrite
}

sealed interface DriverDeliveryOperationalExceptionsLoadResult {
    data class Loaded(val snapshot: DriverDeliveryOperationalExceptionsSnapshot) :
        DriverDeliveryOperationalExceptionsLoadResult

    data object NotFound : DriverDeliveryOperationalExceptionsLoadResult
    data object NetworkUnavailable : DriverDeliveryOperationalExceptionsLoadResult
    data object ServiceUnavailable : DriverDeliveryOperationalExceptionsLoadResult
    data object PermissionDenied : DriverDeliveryOperationalExceptionsLoadResult
    data object ContextInvalidated : DriverDeliveryOperationalExceptionsLoadResult
    data object SessionInvalidated : DriverDeliveryOperationalExceptionsLoadResult
}

sealed interface DriverDeliveryOperationalExceptionMutationResult {
    data class Changed(val mutation: DriverDeliveryOperationalExceptionMutation) :
        DriverDeliveryOperationalExceptionMutationResult

    data class Rejected(val code: String?) : DriverDeliveryOperationalExceptionMutationResult
    data object NotFound : DriverDeliveryOperationalExceptionMutationResult
    data object StaleVersion : DriverDeliveryOperationalExceptionMutationResult
    data object UnknownOutcome : DriverDeliveryOperationalExceptionMutationResult
    data object NetworkUnavailable : DriverDeliveryOperationalExceptionMutationResult
    data object ServiceUnavailable : DriverDeliveryOperationalExceptionMutationResult
    data object PermissionDenied : DriverDeliveryOperationalExceptionMutationResult
    data object ContextInvalidated : DriverDeliveryOperationalExceptionMutationResult
    data object SessionInvalidated : DriverDeliveryOperationalExceptionMutationResult
}

interface DriverDeliveryOperationalExceptionsGateway {
    suspend fun currentExceptions(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryOperationalExceptionsLoadResult

    suspend fun mutate(
        command: DriverDeliveryOperationalExceptionCommand,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryOperationalExceptionMutationResult
}

fun driverDeliveryOperationalExceptionEmptyBody(): String = DRIVER_OPERATIONAL_EXCEPTION_EMPTY_BODY
