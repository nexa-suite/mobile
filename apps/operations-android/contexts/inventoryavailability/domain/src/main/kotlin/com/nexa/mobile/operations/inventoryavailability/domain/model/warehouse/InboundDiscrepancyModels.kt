package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

import java.time.Instant

enum class InboundDiscrepancyKind(val apiReason: String) {
    Damage("DAMAGE"),
    Leakage("LEAKAGE"),
    WrongProduct("WRONG_PRODUCT"),
    QuantityDifference("QUANTITY_DIFFERENCE"),
    Other("OTHER")
}

/** Local observation form and frozen command metadata. Server facts remain separate. */

data class InboundDiscrepancyCase(
    val id: String,
    val warehouseId: String,
    val expectedSkuId: String?,
    val observedSkuId: String,
    val expectedBatchReference: String?,
    val observedBatchReference: String?,
    val expectedQuantity: String,
    val observedQuantity: String,
    val unit: String,
    val reason: String,
    val observationNotes: String?,
    val status: String,
    val evidenceObjectId: String?,
    val version: Long,
    val recordedByMembershipId: String,
    val recordedAt: Instant,
    val submittedByMembershipId: String?,
    val submittedAt: Instant?
)

data class InboundDiscrepancyEvidence(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String,
    val declaredContentType: String,
    val checksumSha256: String?,
    val byteSize: Long
)
