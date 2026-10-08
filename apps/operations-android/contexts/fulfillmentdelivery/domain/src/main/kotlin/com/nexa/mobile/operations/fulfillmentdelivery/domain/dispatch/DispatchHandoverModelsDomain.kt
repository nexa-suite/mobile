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
    val warehouseActorMembershipId: String,
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
    val allocation: DispatchOutgoingGoodsAllocation,
    val outgoingCheck: DispatchOutgoingGoodsCheck?,
    val driverAssignment: PreparedFulfillmentDriverAssignment?
) {
    val canConfirm: Boolean
        get() = readiness.ready && readiness.fulfillmentStatus == READY_FOR_DISPATCH &&
            readiness.physicalAllocationStatus == ALLOCATED &&
            allocation.id == readiness.physicalAllocationId &&
            allocation.version == readiness.physicalAllocationVersion &&
            allocation.status == ALLOCATED &&
            driverAssignment?.let {
                it.fulfillmentId == readiness.fulfillmentId &&
                    it.fulfillmentVersion == readiness.fulfillmentVersion &&
                    it.physicalAllocationId == allocation.id &&
                    it.physicalAllocationVersion == allocation.version && it.deliveryId == null
            } == true &&
            outgoingCheck?.let {
                it.fulfillmentId == readiness.fulfillmentId &&
                    it.fulfillmentVersion == readiness.fulfillmentVersion &&
                    it.physicalAllocationId == allocation.id &&
                    it.physicalAllocationVersion == allocation.version &&
                    it.current && it.matches && !it.openDiscrepancy
            } == true

    override fun toString(): String = "DispatchHandoverSnapshot(ready=$canConfirm)"

    private companion object {
        const val READY_FOR_DISPATCH = "READY_FOR_DISPATCH"
        const val ALLOCATED = "ALLOCATED"
    }
}
