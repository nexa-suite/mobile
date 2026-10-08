package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

import java.math.BigDecimal
import java.time.Instant

data class DispatchReadinessLine(
    val fulfillmentLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val allocatedQuantity: BigDecimal,
    val physicallyAllocatedQuantity: BigDecimal,
    val pickedQuantity: BigDecimal,
    val evidencedPickedQuantity: BigDecimal,
    val allocationComplete: Boolean,
    val pickingComplete: Boolean,
    val evidenceComplete: Boolean
)

data class DispatchReadiness(
    val subjectKind: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val fulfillmentStatus: String,
    val physicalAllocationId: String,
    val physicalAllocationStatus: String,
    val physicalAllocationVersion: Long,
    val deliveryId: String?,
    val deliveryStatus: String?,
    val deliveryVersion: Long?,
    val allocationComplete: Boolean,
    val pickingComplete: Boolean,
    val pickingEvidenceComplete: Boolean,
    val ready: Boolean,
    val reasons: List<String>,
    val lines: List<DispatchReadinessLine>,
    val asOf: Instant,
    val windowStart: Instant? = null,
    val windowEnd: Instant? = null,
    val windowSource: String? = null
) {
    override fun toString(): String = "DispatchReadiness(fulfillmentId=REDACTED, " +
        "status=$fulfillmentStatus, ready=$ready, reasons=${reasons.size}, lines=${lines.size})"
}
