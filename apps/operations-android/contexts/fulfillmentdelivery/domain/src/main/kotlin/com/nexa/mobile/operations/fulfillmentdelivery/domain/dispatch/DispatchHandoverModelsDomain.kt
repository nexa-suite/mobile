package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

import java.time.Instant

data class DispatchHandoverReceipt(
    val fulfillmentId: String,
    val fulfillmentStatus: String,
    val fulfillmentVersion: Long,
    val deliveryId: String,
    val deliveryStatus: String,
    val deliveryVersion: Long,
    val recordedAt: Instant,
    val evidence: DispatchHandoverEvidence? = null
) {
    override fun toString(): String = "DispatchHandoverReceipt(REDACTED, status=$fulfillmentStatus)"
}

data class DispatchHandoverEvidence(
    val evidenceId: String,
    val fulfillmentVersion: Long,
    val deliveryId: String,
    val warehouseActorMembershipId: String?,
    val dispatchActorMembershipId: String?,
    val driverAssignmentId: String,
    val driverMembershipId: String,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val outgoingGoodsCheckId: String,
    val occurredAt: Instant,
    val current: Boolean
) {
    override fun toString(): String = "DispatchHandoverEvidence(REDACTED, current=$current)"
}

data class DispatchHandoverSnapshot(
    val readiness: DispatchReadiness,
    val outgoingCheck: DispatchHandoverOutgoingCheckSummary?,
    val driverAssignment: PreparedFulfillmentDriverAssignment?
) {
    val canConfirm: Boolean
        get() = readiness.ready && readiness.fulfillmentStatus == READY_FOR_DISPATCH &&
            readiness.physicalAllocationStatus == ALLOCATED &&
            driverAssignment?.let {
                it.fulfillmentId == readiness.fulfillmentId &&
                    it.fulfillmentVersion == readiness.fulfillmentVersion &&
                    it.physicalAllocationId == readiness.physicalAllocationId &&
                    it.physicalAllocationVersion == readiness.physicalAllocationVersion &&
                    it.deliveryId == null
            } == true &&
            outgoingCheck?.let {
                it.fulfillmentId == readiness.fulfillmentId &&
                    it.fulfillmentVersion == readiness.fulfillmentVersion &&
                    it.physicalAllocationId == readiness.physicalAllocationId &&
                    it.physicalAllocationVersion == readiness.physicalAllocationVersion &&
                    it.current && it.matches && !it.openDiscrepancy
            } == true

    override fun toString(): String = "DispatchHandoverSnapshot(ready=$canConfirm)"

    private companion object {
        const val READY_FOR_DISPATCH = "READY_FOR_DISPATCH"
        const val ALLOCATED = "ALLOCATED"
    }
}

/** Minimal server facts available to dispatch without warehouse-only check details. */
data class DispatchHandoverOutgoingCheckSummary(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val matches: Boolean,
    val current: Boolean,
    val openDiscrepancy: Boolean
)
