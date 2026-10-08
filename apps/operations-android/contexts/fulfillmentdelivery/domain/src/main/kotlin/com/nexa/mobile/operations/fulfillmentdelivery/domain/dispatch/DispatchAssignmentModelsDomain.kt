package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

import java.time.Instant

data class DispatchDriverCandidate(
    val membershipId: String,
    val email: String,
    val displayName: String
) {
    override fun toString(): String = "DispatchDriverCandidate(membershipId=REDACTED)"
}



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



data class DispatchAssignmentSnapshot(
    val readiness: DispatchReadiness,
    val candidates: List<DispatchDriverCandidate>,
    val assignment: PreparedFulfillmentDriverAssignment?
)
