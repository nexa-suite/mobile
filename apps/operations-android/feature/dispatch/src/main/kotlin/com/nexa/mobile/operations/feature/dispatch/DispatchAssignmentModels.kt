package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import java.time.Instant

@Immutable
data class DispatchDriverCandidate(
    val membershipId: String,
    val email: String,
    val displayName: String
) {
    override fun toString(): String = "DispatchDriverCandidate(membershipId=REDACTED)"
}

@Immutable
data class PreparedFulfillmentDriverAssignment(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val responsibleMembershipId: String,
    val responsibleDisplayName: String,
    val assignedAt: Instant,
    val deliveryId: String?,
    val plannedDispatchAt: Instant? = null,
    val current: Boolean = true
) {
    override fun toString(): String = "PreparedFulfillmentDriverAssignment(id=REDACTED, " +
        "fulfillmentVersion=$fulfillmentVersion, deliveryLinked=${deliveryId != null})"
}

enum class DispatchAssignmentStatus {
    Initial,
    Loading,
    Saving,
    Current,
    NotReady,
    PermissionUnknown,
    PermissionDenied,
    Stale,
    Conflict,
    UnknownOutcome,
    NetworkUnavailable,
    ServiceUnavailable,
    ContextInvalidated,
    SessionInvalidated
}

@Immutable
data class DispatchAssignmentUiState(
    val authorityEpoch: Long = 0,
    val fulfillmentId: String? = null,
    val status: DispatchAssignmentStatus = DispatchAssignmentStatus.Initial,
    val readiness: DispatchReadiness? = null,
    val candidates: List<DispatchDriverCandidate> = emptyList(),
    val selectedMembershipId: String? = null,
    val assignmentPermission: Boolean = false,
    val assignment: PreparedFulfillmentDriverAssignment? = null,
    val pendingIntent: DispatchAssignmentIntent? = null
) {
    val canAssign: Boolean
        get() = status == DispatchAssignmentStatus.Current && readiness?.let {
            it.ready && it.fulfillmentStatus == "READY_FOR_DISPATCH" && assignment == null
        } == true && selectedMembershipId != null && assignmentPermission && pendingIntent == null

    val canReplay: Boolean
        get() = status == DispatchAssignmentStatus.UnknownOutcome && pendingIntent != null &&
            readiness != null && candidates.any {
                it.membershipId == pendingIntent.responsibleMembershipId
            } && assignmentPermission

    override fun toString(): String = "DispatchAssignmentUiState(status=$status, " +
        "fulfillmentId=REDACTED, candidates=${candidates.size}, assigned=${assignment != null}, " +
        "pending=${pendingIntent != null})"
}

data class DispatchAssignmentSnapshot(
    val readiness: DispatchReadiness,
    val candidates: List<DispatchDriverCandidate>,
    val assignment: PreparedFulfillmentDriverAssignment?
)

data class DispatchAssignmentScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispatchAssignmentScopeIdentity(REDACTED)"
}

enum class DispatchAssignmentIntentStatus { Pending, UnknownOutcome }

/** Frozen wire command and verified actor scope. Fields must not change during replay. */
data class DispatchAssignmentIntent(
    val scope: DispatchAssignmentScopeIdentity,
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val responsibleMembershipId: String,
    val idempotencyKey: String,
    val status: DispatchAssignmentIntentStatus = DispatchAssignmentIntentStatus.Pending
) {
    override fun toString(): String = "DispatchAssignmentIntent(fulfillmentId=REDACTED, " +
        "versions=$expectedFulfillmentVersion/$physicalAllocationVersion, status=$status)"
}

sealed interface DispatchAssignmentMetadataRead {
    data class Available(val intent: DispatchAssignmentIntent?) : DispatchAssignmentMetadataRead
    data object Unavailable : DispatchAssignmentMetadataRead
}

enum class DispatchAssignmentMetadataWrite { Saved, Conflict, Stale, Unavailable }

/** Stores immutable uncertain commands before dispatch; this port never sends requests. */
interface DispatchAssignmentMetadataStore {
    suspend fun loadIntent(
        scope: DispatchAssignmentScopeIdentity,
        fulfillmentId: String
    ): DispatchAssignmentMetadataRead

    suspend fun saveIntent(intent: DispatchAssignmentIntent): DispatchAssignmentMetadataWrite

    suspend fun clearIntent(
        scope: DispatchAssignmentScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchAssignmentMetadataWrite
}

sealed interface DispatchAssignmentGatewayResult {
    data class Snapshot(val value: DispatchAssignmentSnapshot) : DispatchAssignmentGatewayResult
    data class Assigned(val value: PreparedFulfillmentDriverAssignment) :
        DispatchAssignmentGatewayResult
    data object NotReady : DispatchAssignmentGatewayResult
    data object Stale : DispatchAssignmentGatewayResult
    data object Conflict : DispatchAssignmentGatewayResult
    data object UnknownOutcome : DispatchAssignmentGatewayResult
    data object NetworkUnavailable : DispatchAssignmentGatewayResult
    data object ServiceUnavailable : DispatchAssignmentGatewayResult
    data object PermissionDenied : DispatchAssignmentGatewayResult
    data object ContextInvalidated : DispatchAssignmentGatewayResult
    data object SessionInvalidated : DispatchAssignmentGatewayResult
}

/** Only accepts current server readiness, current eligible drivers and current assignment facts. */
interface DispatchAssignmentGateway {
    suspend fun load(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchAssignmentGatewayResult

    suspend fun assign(
        fulfillment: DispatchReadiness,
        responsibleMembershipId: String,
        context: DispatchAuthorityContext,
        idempotencyKey: String
    ): DispatchAssignmentGatewayResult

    /** Reposts only the exact command already staged for this verified actor scope. */
    suspend fun replay(
        intent: DispatchAssignmentIntent,
        context: DispatchAuthorityContext
    ): DispatchAssignmentGatewayResult
}
