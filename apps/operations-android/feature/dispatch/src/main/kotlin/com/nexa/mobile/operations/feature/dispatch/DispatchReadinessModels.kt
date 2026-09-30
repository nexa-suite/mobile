package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.Instant

@Immutable
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
@Immutable
data class DispatchAuthorityContext(
    val authorityEpoch: Long,
    val identity: DispatchAuthorityIdentity?
) {
    override fun toString(): String = "DispatchAuthorityContext(authorityEpoch=$authorityEpoch, " +
        "identity=${identity != null})"
}

@Immutable
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

@Immutable
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
    val asOf: Instant
) {
    override fun toString(): String = "DispatchReadiness(fulfillmentId=REDACTED, " +
        "status=$fulfillmentStatus, ready=$ready, reasons=${reasons.size}, lines=${lines.size})"
}

enum class DispatchReadinessStatus {
    Initial,
    Loading,
    Current,
    Empty,
    PermissionUnknown,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable,
    ContextInvalidated,
    SessionInvalidated
}

enum class DispatchReadinessDetailStatus {
    NotRequested,
    Loading,
    Current,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

@Immutable
data class DispatchReadinessUiState(
    val authorityEpoch: Long = 0,
    val status: DispatchReadinessStatus = DispatchReadinessStatus.Initial,
    val items: List<DispatchReadiness> = emptyList(),
    val asOf: Instant? = null,
    val observedAt: Instant? = null,
    val selectedFulfillmentId: String? = null,
    val detail: DispatchReadiness? = null,
    val detailStatus: DispatchReadinessDetailStatus = DispatchReadinessDetailStatus.NotRequested
) {
    override fun toString(): String = "DispatchReadinessUiState(status=$status, " +
        "items=${items.size}, detailStatus=$detailStatus, authorityEpoch=$authorityEpoch)"
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

/** Read-only port. Implementations bind every request to verified current identity and lease. */
interface DispatchReadinessGateway {
    suspend fun list(context: DispatchAuthorityContext): DispatchReadinessGatewayResult

    suspend fun detail(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchReadinessGatewayResult
}
