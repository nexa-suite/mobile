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
