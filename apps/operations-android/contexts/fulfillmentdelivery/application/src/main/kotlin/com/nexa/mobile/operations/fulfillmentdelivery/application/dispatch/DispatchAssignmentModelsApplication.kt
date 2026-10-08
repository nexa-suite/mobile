package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchAssignmentSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment

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
