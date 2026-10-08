package com.nexa.mobile.operations.feature.dispatch.model

import java.math.BigDecimal
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

data class DispatchReadinessLine(
    val fulfillmentLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val allocatedQuantity: BigDecimal,
    val physicallyAllocatedQuantity: BigDecimal,
    val pickedQuantity: BigDecimal,
    val evidencedPickedQuantity: BigDecimal,
    val allocationComplete: Boolean,
    val pickingComplete: Boolean,
    val evidenceComplete: Boolean
)

data class DispatchReadiness(
    val subjectKind: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val fulfillmentStatus: String,
    val physicalAllocationId: String,
    val physicalAllocationStatus: String,
    val physicalAllocationVersion: Long,
    val deliveryId: String?,
    val deliveryStatus: String?,
    val deliveryVersion: Long?,
    val allocationComplete: Boolean,
    val pickingComplete: Boolean,
    val pickingEvidenceComplete: Boolean,
    val ready: Boolean,
    val reasons: List<String>,
    val lines: List<DispatchReadinessLine>,
    val asOf: Instant,
    val windowStart: Instant? = null,
    val windowEnd: Instant? = null,
    val windowSource: String? = null
) {
    override fun toString(): String = "DispatchReadiness(fulfillmentId=REDACTED, " +
        "status=$fulfillmentStatus, ready=$ready, reasons=${reasons.size}, lines=${lines.size})"
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
