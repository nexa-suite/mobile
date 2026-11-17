package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import java.time.Instant

data class DispatchAuthorityIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>
) {
    override fun toString(): String = "DispatchAuthorityIdentity(REDACTED)"
}

/** Full current scope captured from the verified session for each protected read. */

data class DispatchAuthorityContext(
    val authorityEpoch: Long,
    val identity: DispatchAuthorityIdentity?
) {
    override fun toString(): String = "DispatchAuthorityContext(authorityEpoch=$authorityEpoch, " +
        "identity=${identity != null})"
}

sealed interface DispatchReadinessGatewayResult {
    data class ListResult(val items: List<DispatchReadiness>, val asOf: Instant) :
        DispatchReadinessGatewayResult

    data class Detail(val item: DispatchReadiness) : DispatchReadinessGatewayResult
    data object NetworkUnavailable : DispatchReadinessGatewayResult
    data object ServiceUnavailable : DispatchReadinessGatewayResult
    data object PermissionDenied : DispatchReadinessGatewayResult
    data object ContextInvalidated : DispatchReadinessGatewayResult
    data object SessionInvalidated : DispatchReadinessGatewayResult
}

/** Current server-owned Delivery and optional proof-of-delivery facts for a coordinator. */
data class DispatchDeliveryPodSnapshot(
    val deliveryId: String,
    val fulfillmentId: String,
    val deliveryStatus: String,
    val deliveryVersion: Long,
    val deliveryEtag: String,
    val pod: DispatchPodEvidence?
) {
    override fun toString(): String =
        "DispatchDeliveryPodSnapshot(deliveryId=REDACTED, status=$deliveryStatus, " +
            "version=$deliveryVersion, hasPod=${pod != null})"
}

data class DispatchPodEvidence(
    val id: String,
    val deliveryId: String,
    val status: String,
    val receiverName: String?,
    val capturedAt: Instant?,
    val sealedAt: Instant?,
    val deliveryVersion: Long,
    val deliveryEtag: String
) {
    override fun toString(): String =
        "DispatchPodEvidence(id=REDACTED, deliveryId=REDACTED, status=$status, " +
            "version=$deliveryVersion)"
}

/** Exact retry identity and current Delivery version for the explicit sealing command. */
data class SealDispatchPodCommand(
    val deliveryId: String,
    val deliveryEtag: String,
    val idempotencyKey: String
) {
    override fun toString(): String =
        "SealDispatchPodCommand(deliveryId=REDACTED, deliveryEtag=REDACTED, " +
            "idempotencyKey=REDACTED)"
}

data class DispatchTransitStartScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispatchTransitStartScopeIdentity(REDACTED)"
}

enum class DispatchTransitStartIntentStatus { Pending, UnknownOutcome }

/** Frozen, non-authoritative intent for the server-owned Delivery transition. */
data class DispatchTransitStartCommand(
    val deliveryId: String,
    val fulfillmentId: String,
    val deliveryEtag: String,
    val idempotencyKey: String
) {
    override fun toString(): String = "DispatchTransitStartCommand(REDACTED)"

    val expectedVersion: Long?
        get() = deliveryEtag.trim().removeSurrounding("\"").toLongOrNull()
            ?.takeIf { it >= 0 }

    fun isValid(): Boolean {
        val version = expectedVersion ?: return false
        return UUID_PATTERN.matches(deliveryId) &&
            UUID_PATTERN.matches(fulfillmentId) && version < Long.MAX_VALUE &&
            idempotencyKey.isNotBlank() && idempotencyKey.length <= 160
    }

    private companion object {
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

data class DispatchTransitStartIntent(
    val scope: DispatchTransitStartScopeIdentity,
    val command: DispatchTransitStartCommand,
    val status: DispatchTransitStartIntentStatus = DispatchTransitStartIntentStatus.Pending
) {
    override fun toString(): String = "DispatchTransitStartIntent(REDACTED, status=$status)"
}

sealed interface DispatchTransitStartMetadataRead {
    data class Available(val intent: DispatchTransitStartIntent?) : DispatchTransitStartMetadataRead
    data object Unavailable : DispatchTransitStartMetadataRead
}

enum class DispatchTransitStartMetadataWrite { Saved, Conflict, Stale, Unavailable }

/** Encrypted exact retry identity, scoped to the verified user and access context. */
interface DispatchTransitStartMetadataStore {
    suspend fun loadIntent(
        scope: DispatchTransitStartScopeIdentity,
        deliveryId: String
    ): DispatchTransitStartMetadataRead

    suspend fun saveIntent(intent: DispatchTransitStartIntent): DispatchTransitStartMetadataWrite

    suspend fun clearIntent(
        scope: DispatchTransitStartScopeIdentity,
        deliveryId: String,
        idempotencyKey: String
    ): DispatchTransitStartMetadataWrite
}

data class DispatchTransitStartConfirmation(
    val deliveryId: String,
    val fulfillmentId: String,
    val deliveryStatus: String,
    val deliveryVersion: Long,
    val deliveryEtag: String
) {
    override fun toString(): String = "DispatchTransitStartConfirmation(REDACTED, " +
        "status=$deliveryStatus, version=$deliveryVersion)"
}

sealed interface DispatchTransitStartGatewayResult {
    data class Started(val value: DispatchTransitStartConfirmation) :
        DispatchTransitStartGatewayResult
    data object UnknownOutcome : DispatchTransitStartGatewayResult
    data object NetworkUnavailable : DispatchTransitStartGatewayResult
    data object ServiceUnavailable : DispatchTransitStartGatewayResult
    data object PermissionDenied : DispatchTransitStartGatewayResult
    data object Stale : DispatchTransitStartGatewayResult
    data object Conflict : DispatchTransitStartGatewayResult
    data object ContextInvalidated : DispatchTransitStartGatewayResult
    data object SessionInvalidated : DispatchTransitStartGatewayResult
}

sealed interface DispatchDeliveryPodGatewayResult {
    data class Snapshot(val value: DispatchDeliveryPodSnapshot) : DispatchDeliveryPodGatewayResult
    data class Sealed(val value: DispatchPodEvidence) : DispatchDeliveryPodGatewayResult
    data object Stale : DispatchDeliveryPodGatewayResult
    data object Conflict : DispatchDeliveryPodGatewayResult
    data object UnknownOutcome : DispatchDeliveryPodGatewayResult
    data object NetworkUnavailable : DispatchDeliveryPodGatewayResult
    data object ServiceUnavailable : DispatchDeliveryPodGatewayResult
    data object PermissionDenied : DispatchDeliveryPodGatewayResult
    data object ContextInvalidated : DispatchDeliveryPodGatewayResult
    data object SessionInvalidated : DispatchDeliveryPodGatewayResult
}
