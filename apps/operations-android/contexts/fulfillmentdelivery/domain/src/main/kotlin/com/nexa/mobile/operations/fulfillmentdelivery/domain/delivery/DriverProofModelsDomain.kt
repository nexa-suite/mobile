package com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery

enum class DriverProofEvidenceKind { PHOTO, SIGNATURE }

/** Captures route identity at picker launch so late results cannot target another delivery. */

data class DriverProofSummary(
    val proofId: String,
    val deliveryId: String,
    val attemptId: String,
    val actorMembershipId: String,
    val status: String,
    val receiverName: String,
    val capturedAt: String,
    val photoEvidenceObjectId: String?,
    val signatureEvidenceObjectId: String?,
    val deliveryVersion: Long
)

data class DriverProofEvidenceSummary(
    val evidenceId: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String,
    val contentType: String,
    val checksumSha256: String?,
    val byteSize: Long
)
