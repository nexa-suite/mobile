package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import java.time.Instant

@Immutable
data class DispatchPlanChangeUiState(
    val authorityEpoch: Long = 0,
    val fulfillmentId: String? = null,
    val status: DispatchPlanChangeStatus = DispatchPlanChangeStatus.Initial,
    val readiness: DispatchReadiness? = null,
    val candidates: List<DispatchDriverCandidate> = emptyList(),
    val assignment: PreparedFulfillmentDriverAssignment? = null,
    val history: List<PreparedFulfillmentDriverAssignment> = emptyList(),
    val selectedMembershipId: String? = null,
    val plannedDispatchAtText: String = "",
    val canReassign: Boolean = false,
    val canSchedule: Boolean = false,
    val pendingIntent: DispatchPlanChangeIntent? = null,
    val inputInvalid: Boolean = false
) {
    val canSave: Boolean
        get() {
            val current = assignment ?: return false
            val readiness = readiness ?: return false
            if (status != DispatchPlanChangeStatus.Current ||
                !current.current || !readiness.ready ||
                readiness.fulfillmentStatus != "READY_FOR_DISPATCH" || pendingIntent != null
            ) {
                return false
            }
            val membershipChanged =
                canReassign && selectedMembershipId != current.responsibleMembershipId
            val dispatchAt = plannedDispatchAtText.toInstantOrNull()
            val scheduleChanged = canSchedule && dispatchAt != null &&
                dispatchAt != current.plannedDispatchAt
            return !inputInvalid && (membershipChanged || scheduleChanged)
        }

    val canReplay: Boolean
        get() = status == DispatchPlanChangeStatus.UnknownOutcome && pendingIntent != null &&
            candidates.any { it.membershipId == pendingIntent.resultResponsibleMembershipId } &&
            canReplayPermissions(pendingIntent)

    override fun toString(): String = "DispatchPlanChangeUiState(status=$status, " +
        "fulfillmentId=REDACTED, history=${history.size}, pending=${pendingIntent != null})"

    private fun canReplayPermissions(intent: DispatchPlanChangeIntent): Boolean =
        (!intent.requestsDriverChange || canReassign) &&
            (!intent.requestsScheduleChange || canSchedule)
}

enum class DispatchPlanChangeStatus {
    Initial,
    Loading,
    Saving,
    Current,
    NotReady,
    PermissionDenied,
    Stale,
    Conflict,
    UnknownOutcome,
    NetworkUnavailable,
    ServiceUnavailable,
    ContextInvalidated,
    SessionInvalidated
}

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

interface DispatchPlanChangeMetadataStore {
    suspend fun loadIntent(
        scope: DispatchPlanChangeScopeIdentity,
        fulfillmentId: String
    ): DispatchPlanChangeMetadataRead

    suspend fun saveIntent(intent: DispatchPlanChangeIntent): DispatchPlanChangeMetadataWrite

    suspend fun clearIntent(
        scope: DispatchPlanChangeScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchPlanChangeMetadataWrite
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

interface DispatchPlanChangeGateway {
    suspend fun load(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchPlanChangeGatewayResult

    suspend fun change(
        readiness: DispatchReadiness,
        assignment: PreparedFulfillmentDriverAssignment,
        intent: DispatchPlanChangeIntent,
        context: DispatchAuthorityContext
    ): DispatchPlanChangeGatewayResult

    /** Sends only the immutable request staged before its original mutation. */
    suspend fun replay(
        intent: DispatchPlanChangeIntent,
        context: DispatchAuthorityContext
    ): DispatchPlanChangeGatewayResult
}

private fun String.toInstantOrNull(): Instant? =
    takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
