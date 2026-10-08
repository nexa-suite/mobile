package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDriverCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeIntent
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment
import java.time.Instant

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

private fun String.toInstantOrNull(): Instant? =
    takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
