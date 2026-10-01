package com.nexa.mobile.operations.feature.delivery

import androidx.compose.runtime.Immutable

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
    val activeAttempt: DriverDeliveryAttempt?
) {
    override fun toString(): String =
        "DriverDeliverySnapshot(status=$status, version=$version, active=$activeAttempt)"
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
}
