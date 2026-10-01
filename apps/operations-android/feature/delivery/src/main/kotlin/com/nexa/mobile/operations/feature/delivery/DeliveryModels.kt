package com.nexa.mobile.operations.feature.delivery

import androidx.compose.runtime.Immutable
import java.math.BigDecimal

/** Current verified identity and permission snapshot supplied by the app boundary. */
@Immutable
data class DriverDeliveryAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
        require(authorityEpoch > 0)
    }

    val canRead: Boolean
        get() = permissions.any { it in DRIVER_READ_PERMISSIONS }

    val canStart: Boolean
        get() = permissions.any { it in DRIVER_START_PERMISSIONS }

    val scopeIdentity: DriverAttemptScopeIdentity
        get() = DriverAttemptScopeIdentity(userId, tenantId, workspaceId, membershipId)

    override fun toString(): String =
        "DriverDeliveryAuthority(scope=REDACTED, permissions=${permissions.size}, epoch=$authorityEpoch)"

    private companion object {
        val DRIVER_READ_PERMISSIONS = setOf("dispatch.read", "logistics:read")
        val DRIVER_START_PERMISSIONS = setOf("dispatch.start_route", "logistics:write")
    }
}

/** Full identity partition for harmless frozen attempt metadata, without permission state. */
@Immutable
data class DriverAttemptScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
    }

    override fun toString(): String = "DriverAttemptScopeIdentity(REDACTED)"
}

@Immutable
data class DriverDeliveryAttempt(
    val id: String,
    val attemptNumber: Int,
    val status: String,
    val startedByMembershipId: String?,
    val startedAt: String?
) {
    override fun toString(): String = "DriverDeliveryAttempt(number=$attemptNumber, status=$status)"
}

/** Server projection; assignment eligibility and current attempt remain server-owned. */
@Immutable
data class DriverDeliverySnapshot(
    val id: String,
    val fulfillmentId: String?,
    val salesOrderId: String?,
    val status: String,
    val destination: String?,
    val scheduledAt: String?,
    val dispatchedAt: String?,
    val deliveredAt: String?,
    val updatedAt: String?,
    val version: Long,
    val activeAttempt: DriverDeliveryAttempt?,
    val outcomeLines: List<DriverDeliveryOutcomeLine> = emptyList()
) {
    override fun toString(): String =
        "DriverDeliverySnapshot(status=$status, version=$version, active=$activeAttempt)"
}

@Immutable
data class DriverDeliveryOutcomeLine(
    val fulfillmentLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val dispatchedQuantity: BigDecimal,
    val deliveredQuantity: BigDecimal,
    val rejectedQuantity: BigDecimal,
    val cancelledQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String
)

@Immutable
data class DriverRemainingQuantityLine(
    val fulfillmentLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val quantity: BigDecimal,
    val unit: String
)

enum class DriverOutcomeKind { DELIVERED, PARTIAL, FAILED, REFUSED, ABSENT }

@Immutable
data class DriverOutcomeLineDecision(
    val fulfillmentLineId: String,
    val skuId: String,
    val attemptedQuantity: BigDecimal,
    val deliveredQuantity: BigDecimal,
    val rejectedQuantity: BigDecimal,
    val cancelledQuantity: BigDecimal,
    val unit: String
)

@Immutable
data class DriverOutcomeCommand(
    val deliveryId: String,
    val attemptId: String,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val outcome: DriverOutcomeKind,
    val failureReason: String?,
    val notes: String?,
    val attemptedAt: String,
    val lines: List<DriverOutcomeLineDecision>,
    val frozenBody: String
) {
    override fun toString(): String =
        "DriverOutcomeCommand(outcome=$outcome, version=$expectedVersion, key=REDACTED)"
}

@Immutable
data class DriverOutcomeSummary(
    val attemptId: String,
    val outcome: String,
    val attemptedAt: String,
    val deliveryVersion: Long,
    val partial: Boolean,
    val remainingLines: List<DriverRemainingQuantityLine>
)

enum class DriverOutcomeIntentStatus { Pending, UnknownOutcome }

@Immutable
data class DriverOutcomeIntentMetadata(
    val scope: DriverAttemptScopeIdentity,
    val command: DriverOutcomeCommand,
    val status: DriverOutcomeIntentStatus
) {
    override fun toString(): String = "DriverOutcomeIntentMetadata(status=$status, command=REDACTED)"
}

sealed interface DriverOutcomeMetadataRead {
    data class Available(val intent: DriverOutcomeIntentMetadata?) : DriverOutcomeMetadataRead
    data object Unavailable : DriverOutcomeMetadataRead
}

sealed interface DriverOutcomeMetadataWrite {
    data object Saved : DriverOutcomeMetadataWrite
    data object Conflict : DriverOutcomeMetadataWrite
    data object Stale : DriverOutcomeMetadataWrite
    data object Unavailable : DriverOutcomeMetadataWrite
}

interface DriverOutcomeMetadataStore {
    suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverOutcomeMetadataRead
    suspend fun saveIntent(intent: DriverOutcomeIntentMetadata): DriverOutcomeMetadataWrite
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverOutcomeMetadataWrite
}

@Immutable
data class DriverAttemptStartCommand(
    val deliveryId: String,
    val expectedVersion: Long,
    val idempotencyKey: String
) {
    override fun toString(): String =
        "DriverAttemptStartCommand(version=$expectedVersion, key=REDACTED)"
}

sealed interface DriverDeliveryLoadResult {
    data class ListLoaded(val items: List<DriverDeliverySnapshot>) : DriverDeliveryLoadResult
    data class DetailLoaded(val item: DriverDeliverySnapshot) : DriverDeliveryLoadResult
    data object NotFound : DriverDeliveryLoadResult
    data object NetworkUnavailable : DriverDeliveryLoadResult
    data object ServiceUnavailable : DriverDeliveryLoadResult
    data object PermissionDenied : DriverDeliveryLoadResult
    data object ContextInvalidated : DriverDeliveryLoadResult
    data object SessionInvalidated : DriverDeliveryLoadResult
}

sealed interface DriverAttemptStartResult {
    data class Started(val delivery: DriverDeliverySnapshot, val attempt: DriverDeliveryAttempt) :
        DriverAttemptStartResult
    data class Rejected(val code: String?) : DriverAttemptStartResult
    data object StaleVersion : DriverAttemptStartResult
    data object NotFound : DriverAttemptStartResult
    data object UnknownOutcome : DriverAttemptStartResult
    data object NetworkUnavailable : DriverAttemptStartResult
    data object ServiceUnavailable : DriverAttemptStartResult
    data object PermissionDenied : DriverAttemptStartResult
    data object ContextInvalidated : DriverAttemptStartResult
    data object SessionInvalidated : DriverAttemptStartResult
}

enum class DriverAttemptMetadataStatus { Pending, UnknownOutcome }

/** Persisted command identity only; it never represents an active server attempt. */
@Immutable
data class DriverAttemptIntentMetadata(
    val scope: DriverAttemptScopeIdentity,
    val idempotencyKey: String,
    val deliveryId: String,
    val expectedVersion: Long,
    val status: DriverAttemptMetadataStatus
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(deliveryId.isNotBlank())
        require(expectedVersion >= 0)
    }

    override fun toString(): String = "DriverAttemptIntentMetadata(status=$status, key=REDACTED)"
}

sealed interface DriverAttemptMetadataRead {
    data class Available(val intent: DriverAttemptIntentMetadata?) : DriverAttemptMetadataRead
    data object Unavailable : DriverAttemptMetadataRead
}

sealed interface DriverAttemptMetadataWrite {
    data object Saved : DriverAttemptMetadataWrite
    data object Conflict : DriverAttemptMetadataWrite
    data object Stale : DriverAttemptMetadataWrite
    data object Unavailable : DriverAttemptMetadataWrite
}

interface DriverAttemptMetadataStore {
    suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverAttemptMetadataRead
    suspend fun saveIntent(intent: DriverAttemptIntentMetadata): DriverAttemptMetadataWrite
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverAttemptMetadataWrite
}

/** Feature boundary for server-authorized driver delivery reads and start command. */
interface DriverDeliveryGateway {
    suspend fun assignedDeliveries(authority: DriverDeliveryAuthority): DriverDeliveryLoadResult

    suspend fun delivery(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryLoadResult

    suspend fun startAttempt(
        command: DriverAttemptStartCommand,
        authority: DriverDeliveryAuthority
    ): DriverAttemptStartResult

    suspend fun recordOutcome(
        command: DriverOutcomeCommand,
        authority: DriverDeliveryAuthority
    ): DriverOutcomeResult
}

sealed interface DriverOutcomeResult {
    data class Recorded(val summary: DriverOutcomeSummary) : DriverOutcomeResult
    data class Rejected(val code: String?) : DriverOutcomeResult
    data object NotFound : DriverOutcomeResult
    data object StaleVersion : DriverOutcomeResult
    data object UnknownOutcome : DriverOutcomeResult
    data object NetworkUnavailable : DriverOutcomeResult
    data object ServiceUnavailable : DriverOutcomeResult
    data object PermissionDenied : DriverOutcomeResult
    data object ContextInvalidated : DriverOutcomeResult
    data object SessionInvalidated : DriverOutcomeResult
}
