package com.nexa.mobile.operations.feature.dispatch.model

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

data class DispatchHandoverCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val driverAssignmentId: String,
    val driverAssignmentVersion: Long,
    val outgoingGoodsCheckId: String,
    val idempotencyKey: String,
    val exactRequestBody: String
) {
    override fun toString(): String =
        "DispatchHandoverCommand(REDACTED, version=$expectedFulfillmentVersion)"

    fun toRequestBody(): String = buildString {
        append("{\"physicalAllocationId\":\"").append(physicalAllocationId)
            .append("\",\"physicalAllocationVersion\":").append(physicalAllocationVersion)
            .append(",\"driverAssignmentId\":\"").append(driverAssignmentId)
            .append("\",\"driverAssignmentVersion\":").append(driverAssignmentVersion)
            .append(",\"outgoingGoodsCheckId\":\"").append(outgoingGoodsCheckId).append("\"}")
    }

    fun isValid(): Boolean = listOf(
        fulfillmentId,
        physicalAllocationId,
        driverAssignmentId,
        outgoingGoodsCheckId
    ).all(UUID_PATTERN::matches) && expectedFulfillmentVersion >= 0 &&
        physicalAllocationVersion >= 0 &&
        driverAssignmentVersion >= 0 &&
        idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 &&
        exactRequestBody == toRequestBody()

    private companion object {
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

data class DispatchHandoverIntent(
    val scope: DispatchOutgoingGoodsScopeIdentity,
    val command: DispatchHandoverCommand,
    val status: DispatchOutgoingGoodsIntentStatus = DispatchOutgoingGoodsIntentStatus.Pending
) {
    override fun toString(): String = "DispatchHandoverIntent(REDACTED, status=$status)"
}

sealed interface DispatchHandoverMetadataRead {
    data class Available(val intent: DispatchHandoverIntent?) : DispatchHandoverMetadataRead
    data object Unavailable : DispatchHandoverMetadataRead
}

enum class DispatchHandoverMetadataWrite {
    Saved,
    Conflict,
    Stale,
    Unavailable
}

sealed interface DispatchHandoverGatewayResult {
    data class Snapshot(val value: DispatchHandoverSnapshot) : DispatchHandoverGatewayResult
    data class AlreadyCompleted(val receipt: DispatchHandoverReceipt) :
        DispatchHandoverGatewayResult
    data class Dispatched(val receipt: DispatchHandoverReceipt) : DispatchHandoverGatewayResult
    data object UnknownOutcome : DispatchHandoverGatewayResult
    data object NetworkUnavailable : DispatchHandoverGatewayResult
    data object ServiceUnavailable : DispatchHandoverGatewayResult
    data object PermissionDenied : DispatchHandoverGatewayResult
    data object Stale : DispatchHandoverGatewayResult
    data object Conflict : DispatchHandoverGatewayResult
    data object ContextInvalidated : DispatchHandoverGatewayResult
    data object SessionInvalidated : DispatchHandoverGatewayResult
}
