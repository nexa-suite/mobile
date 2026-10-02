package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val businessExceptionUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

@Immutable
data class BusinessOperationalException(
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
) {
    init {
        require(businessExceptionUuid.matches(id) && businessExceptionUuid.matches(deliveryId))
        require(deliveryVersion >= 0 && businessExceptionUuid.matches(sourceIncidentId))
        require(businessExceptionUuid.matches(affectedObjectId) && affectedObjectType.isNotBlank())
        require(type.isNotBlank() && severity in setOf("WARNING", "BLOCKING", "CRITICAL"))
        require(status in BUSINESS_OPERATIONAL_EXCEPTION_STATUSES && description.isNotBlank())
        require(businessExceptionUuid.matches(reportedByMembershipId))
        require(occurredAt.isNotBlank() && reportedAt.isNotBlank())
        require(
            responsibleMembershipId == null ||
                businessExceptionUuid.matches(responsibleMembershipId)
        )
        require(
            underReviewByMembershipId == null ||
                businessExceptionUuid.matches(underReviewByMembershipId)
        )
        require(
            coordinationOwnerMembershipId == null ||
                businessExceptionUuid.matches(coordinationOwnerMembershipId)
        )
        require(evidenceObjectIds.distinct().size == evidenceObjectIds.size)
        require(evidenceObjectIds.all(businessExceptionUuid::matches))
    }
}

@Immutable
data class BusinessOperationalExceptionsSnapshot(
    val asOf: String,
    val exceptions: List<BusinessOperationalException>
) {
    init {
        require(asOf.isNotBlank())
        require(exceptions.map { it.id.lowercase() }.distinct().size == exceptions.size)
    }
}

@Immutable
data class BusinessOperationalExceptionActor(
    val membershipId: String,
    val displayName: String,
    val coordinator: Boolean,
    val driverReporter: Boolean
) {
    init {
        require(businessExceptionUuid.matches(membershipId) && displayName.isNotBlank())
    }
}

data class BusinessOperationalExceptionScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(
            listOf(userId, tenantId, workspaceId, membershipId).all(businessExceptionUuid::matches)
        )
    }
    override fun toString(): String = "BusinessOperationalExceptionScopeIdentity(REDACTED)"
}

data class BusinessOperationalExceptionAuthority(
    val authorityEpoch: Long,
    val scope: BusinessOperationalExceptionScopeIdentity?,
    val permissions: Set<String>
) {
    val canRead: Boolean get() = authorityEpoch > 0 && scope != null &&
        READ_PERMISSION in permissions
    val canCoordinate: Boolean get() = canRead && COORDINATE_PERMISSION in permissions

    override fun toString(): String =
        "BusinessOperationalExceptionAuthority(epoch=$authorityEpoch, permissions=${permissions.size})"

    companion object {
        const val READ_PERMISSION = "delivery.exception.read"
        const val COORDINATE_PERMISSION = "delivery.exception.coordinate"
    }
}

enum class BusinessOperationalExceptionAction { CLAIM, REASSIGN, FOLLOW_UP, RESOLVE, CLOSE }
enum class BusinessOperationalExceptionIntentStatus { Pending, UnknownOutcome }

/** Exact frozen request. The encrypted record supports recovery; it never supplies authority. */
data class BusinessOperationalExceptionCommand(
    val scope: BusinessOperationalExceptionScopeIdentity,
    val action: BusinessOperationalExceptionAction,
    val exceptionId: String,
    val expectedDeliveryVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String,
    val status: BusinessOperationalExceptionIntentStatus
) {
    init {
        require(businessExceptionUuid.matches(exceptionId))
        require(expectedDeliveryVersion >= 0)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(frozenBody.length <= 8_000)
        require(
            runCatching {
                Json.parseToJsonElement(frozenBody) is JsonObject
            }.getOrDefault(false)
        )
    }

    override fun toString(): String =
        "BusinessOperationalExceptionCommand(action=$action, version=$expectedDeliveryVersion, key=REDACTED, body=REDACTED)"
}

data class BusinessOperationalExceptionIntent(val command: BusinessOperationalExceptionCommand) {
    override fun toString(): String = "BusinessOperationalExceptionIntent(command=REDACTED)"
}

sealed interface BusinessOperationalExceptionMetadataRead {
    data class Available(val intent: BusinessOperationalExceptionIntent?) :
        BusinessOperationalExceptionMetadataRead
    data object Unavailable : BusinessOperationalExceptionMetadataRead
}

enum class BusinessOperationalExceptionMetadataWrite { Saved, Conflict, Unavailable }

interface BusinessOperationalExceptionMetadataStore {
    suspend fun load(
        scope: BusinessOperationalExceptionScopeIdentity
    ): BusinessOperationalExceptionMetadataRead
    suspend fun save(
        intent: BusinessOperationalExceptionIntent
    ): BusinessOperationalExceptionMetadataWrite
    suspend fun clear(
        scope: BusinessOperationalExceptionScopeIdentity,
        idempotencyKey: String
    ): BusinessOperationalExceptionMetadataWrite
}

sealed interface BusinessOperationalExceptionsGatewayResult {
    data class Current(val snapshot: BusinessOperationalExceptionsSnapshot) :
        BusinessOperationalExceptionsGatewayResult
    data class Changed(
        val exception: BusinessOperationalException,
        val deliveryVersion: Long,
        val replayed: Boolean
    ) : BusinessOperationalExceptionsGatewayResult
    data class Failed(val code: String?, val unknownOutcome: Boolean = false) :
        BusinessOperationalExceptionsGatewayResult
}

sealed interface BusinessOperationalExceptionAssigneesResult {
    data class Loaded(val values: List<BusinessOperationalExceptionActor>) :
        BusinessOperationalExceptionAssigneesResult
    data class Failed(val code: String?) : BusinessOperationalExceptionAssigneesResult
}

interface BusinessOperationalExceptionsGateway {
    suspend fun current(
        authority: BusinessOperationalExceptionAuthority
    ): BusinessOperationalExceptionsGatewayResult
    suspend fun assignees(
        exceptionId: String,
        authority: BusinessOperationalExceptionAuthority
    ): BusinessOperationalExceptionAssigneesResult
    suspend fun mutate(
        command: BusinessOperationalExceptionCommand,
        authority: BusinessOperationalExceptionAuthority
    ): BusinessOperationalExceptionsGatewayResult
}

fun businessOperationalExceptionRequestBody(
    action: BusinessOperationalExceptionAction,
    responsibleMembershipId: String? = null,
    followUpNote: String? = null,
    reason: String? = null
): String {
    val values = when (action) {
        BusinessOperationalExceptionAction.CLAIM,
        BusinessOperationalExceptionAction.RESOLVE,
        BusinessOperationalExceptionAction.CLOSE -> {
            val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 }
                ?: throw IllegalArgumentException("Reason is required")
            mapOf("reason" to JsonPrimitive(normalizedReason))
        }

        BusinessOperationalExceptionAction.FOLLOW_UP -> {
            val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 }
                ?: throw IllegalArgumentException("Reason is required")
            val normalizedNote =
                followUpNote?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 }
                    ?: throw IllegalArgumentException("Follow-up note is required")
            mapOf(
                "reason" to JsonPrimitive(normalizedReason),
                "note" to JsonPrimitive(normalizedNote)
            )
        }

        BusinessOperationalExceptionAction.REASSIGN -> {
            require(
                responsibleMembershipId != null &&
                    businessExceptionUuid.matches(responsibleMembershipId)
            )
            val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 }
                ?: throw IllegalArgumentException("Reason is required")
            mapOf(
                "responsibleMembershipId" to JsonPrimitive(responsibleMembershipId),
                "reason" to JsonPrimitive(normalizedReason)
            )
        }
    }
    return JsonObject(values).toString()
}

internal val BUSINESS_OPERATIONAL_EXCEPTION_STATUSES =
    setOf("OPEN", "CLAIMED", "UNDER_REVIEW", "RESOLVED", "CLOSED")
