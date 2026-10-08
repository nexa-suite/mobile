package com.nexa.mobile.operations.feature.dispatch.model

import java.time.Instant

data class DispatchPlanChangeSnapshot(
    val readiness: DispatchReadiness,
    val candidates: List<DispatchDriverCandidate>,
    val assignment: PreparedFulfillmentDriverAssignment?,
    val history: List<PreparedFulfillmentDriverAssignment>
)

data class DispatchPlanChangeScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispatchPlanChangeScopeIdentity(REDACTED)"
}

enum class DispatchPlanChangeIntentStatus {
    Pending,
    UnknownOutcome
}

/** Exact request body and authority snapshots frozen before the first POST. */
data class DispatchPlanChangeIntent(
    val scope: DispatchPlanChangeScopeIdentity,
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val expectedAssignmentId: String,
    val expectedAssignmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val requestedMembershipId: String?,
    val requestedDispatchAt: Instant?,
    val resultResponsibleMembershipId: String,
    val resultPlannedDispatchAt: Instant?,
    val requestBody: String,
    val idempotencyKey: String,
    val status: DispatchPlanChangeIntentStatus = DispatchPlanChangeIntentStatus.Pending
) {
    val requestsDriverChange: Boolean get() = requestedMembershipId != null
    val requestsScheduleChange: Boolean get() = requestedDispatchAt != null

    override fun toString(): String = "DispatchPlanChangeIntent(fulfillmentId=REDACTED, " +
        "versions=$expectedFulfillmentVersion/$expectedAssignmentVersion, status=$status)"
}

sealed interface DispatchPlanChangeMetadataRead {
    data class Available(val intent: DispatchPlanChangeIntent?) : DispatchPlanChangeMetadataRead
    data object Unavailable : DispatchPlanChangeMetadataRead
}

enum class DispatchPlanChangeMetadataWrite {
    Saved,
    Conflict,
    Stale,
    Unavailable
}

sealed interface DispatchPlanChangeGatewayResult {
    data class Snapshot(val value: DispatchPlanChangeSnapshot) : DispatchPlanChangeGatewayResult
    data class Changed(val value: PreparedFulfillmentDriverAssignment) :
        DispatchPlanChangeGatewayResult
    data object NotReady : DispatchPlanChangeGatewayResult
    data object Stale : DispatchPlanChangeGatewayResult
    data object Conflict : DispatchPlanChangeGatewayResult
    data object UnknownOutcome : DispatchPlanChangeGatewayResult
    data object NetworkUnavailable : DispatchPlanChangeGatewayResult
    data object ServiceUnavailable : DispatchPlanChangeGatewayResult
    data object PermissionDenied : DispatchPlanChangeGatewayResult
    data object ContextInvalidated : DispatchPlanChangeGatewayResult
    data object SessionInvalidated : DispatchPlanChangeGatewayResult
}
