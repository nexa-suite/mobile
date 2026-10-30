package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentIntent
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverLiveLocation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDriverCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment

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

enum class DispatchDriverLocationStatus {
    Idle,
    Loading,
    Current,
    NoRecentLocation,
    AssignmentChanged,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable
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
    val pendingIntent: DispatchAssignmentIntent? = null,
    val driverLocationStatus: DispatchDriverLocationStatus = DispatchDriverLocationStatus.Idle,
    val driverLocation: DriverLiveLocation? = null
) {
    val canTrackDriverLocation: Boolean
        get() {
            val activeAssignment = assignment?.takeIf { it.current } ?: return false
            val currentReadiness = readiness ?: return false
            val deliveryId = activeAssignment.deliveryId ?: return false
            return currentReadiness.deliveryId == deliveryId &&
                currentReadiness.deliveryStatus in ACTIVE_DRIVER_LOCATION_DELIVERY_STATUSES
        }

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

private val ACTIVE_DRIVER_LOCATION_DELIVERY_STATUSES = setOf("DISPATCHED", "IN_TRANSIT")
