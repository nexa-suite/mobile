package com.nexa.mobile.operations.feature.warehouse.model

import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyEvidenceCandidate as DiscrepancyEvidenceCandidate
import java.io.File
import java.time.Instant

/** A verified identity boundary. It carries no permission or server authority. */
data class InboundDiscrepancyAuthority(
    val scope: InboundDiscrepancyScope,
    val authorityEpoch: Long
) {
    init {
        require(authorityEpoch > 0)
    }
    override fun toString(): String =
        "InboundDiscrepancyAuthority(scope=REDACTED, epoch=$authorityEpoch)"
}

data class InboundDiscrepancyScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
    }
    override fun toString(): String = "InboundDiscrepancyScope(REDACTED)"
}

/** Receiving entry context only prefills observed facts; it never supplies expected values. */
data class InboundDiscrepancyStartContext(
    val warehouseId: String,
    val observedSkuId: String? = null,
    val observedSkuLabel: String? = null,
    val observedBatchReference: String? = null,
    val observedQuantityText: String? = null,
    val unit: String? = null
)

enum class InboundDiscrepancyKind(val apiReason: String) {
    Damage("DAMAGE"),
    Leakage("LEAKAGE"),
    WrongProduct("WRONG_PRODUCT"),
    QuantityDifference("QUANTITY_DIFFERENCE"),
    Other("OTHER")
}

/** Local observation form and frozen command metadata. Server facts remain separate. */
data class InboundDiscrepancyDraft(
    val id: String,
    val warehouseId: String,
    val expectedSkuId: String,
    val observedSkuId: String,
    val observedSkuLabel: String? = null,
    val expectedBatchReference: String,
    val observedBatchReference: String,
    val expectedQuantityText: String,
    val observedQuantityText: String,
    val unit: String,
    val kind: InboundDiscrepancyKind,
    val reasonDetails: String,
    val observationNotes: String,
    val capturedAtDeviceMillis: Long,
    val createIdempotencyKey: String? = null,
    val createBody: String? = null,
    val caseId: String? = null,
    val caseVersion: Long? = null,
    val caseStatus: String? = null,
    val evidenceUploadKey: String? = null,
    val evidenceId: String? = null,
    val evidenceStatus: String? = null,
    val submitIdempotencyKey: String? = null,
    val submitBody: String? = null,
    val pendingAction: InboundDiscrepancyPendingAction? = null
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_REFERENCE_LENGTH)
        require(warehouseId.length <= MAX_REFERENCE_LENGTH)
        require(
            expectedSkuId.length <= MAX_REFERENCE_LENGTH &&
                observedSkuId.length <= MAX_REFERENCE_LENGTH
        )
        require(observedSkuLabel == null || observedSkuLabel.length <= MAX_NOTE_LENGTH)
        require(
            expectedBatchReference.length <= MAX_REFERENCE_LENGTH &&
                observedBatchReference.length <= MAX_REFERENCE_LENGTH
        )
        require(
            expectedQuantityText.length <= MAX_QUANTITY_LENGTH &&
                observedQuantityText.length <= MAX_QUANTITY_LENGTH
        )
        require(
            unit.length <= 32 && reasonDetails.length <= MAX_NOTE_LENGTH &&
                observationNotes.length <= MAX_NOTE_LENGTH
        )
        require(capturedAtDeviceMillis > 0)
        require(createIdempotencyKey == null || createIdempotencyKey.length <= MAX_REFERENCE_LENGTH)
        require(createBody == null || createBody.length <= MAX_COMMAND_LENGTH)
        require(caseId == null || caseId.length <= MAX_REFERENCE_LENGTH)
        require(caseVersion == null || caseVersion >= 0)
        require(caseStatus == null || caseStatus.length <= 32)
        require(evidenceUploadKey == null || evidenceUploadKey.length <= MAX_REFERENCE_LENGTH)
        require(evidenceId == null || evidenceId.length <= MAX_REFERENCE_LENGTH)
        require(evidenceStatus == null || evidenceStatus.length <= 32)
        require(submitIdempotencyKey == null || submitIdempotencyKey.length <= MAX_REFERENCE_LENGTH)
        require(submitBody == null || submitBody.length <= MAX_COMMAND_LENGTH)
    }

    override fun toString(): String = "InboundDiscrepancyDraft(id=REDACTED, caseStatus=$caseStatus)"

    private companion object {
        const val MAX_REFERENCE_LENGTH = 240
        const val MAX_QUANTITY_LENGTH = 80
        const val MAX_NOTE_LENGTH = 2_000
        const val MAX_COMMAND_LENGTH = 8_000
    }
}

enum class InboundDiscrepancyPendingAction {
    CreateCase,
    UploadEvidence,
    SubmitForReview
}

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

data class InboundDiscrepancySelectionContext(
    val authorityEpoch: Long,
    val scope: InboundDiscrepancyScope,
    val warehouseId: String,
    val caseId: String
) {
    init {
        require(authorityEpoch > 0 && warehouseId.isNotBlank() && caseId.isNotBlank())
    }
    override fun toString(): String =
        "InboundDiscrepancySelectionContext(scope=REDACTED, epoch=$authorityEpoch)"
}

data class InboundDiscrepancyArtifactIdentity(
    val scope: InboundDiscrepancyScope,
    val warehouseId: String,
    val caseId: String
) {
    init {
        require(warehouseId.isNotBlank() && caseId.isNotBlank())
    }
    override fun toString(): String = "InboundDiscrepancyArtifactIdentity(scope=REDACTED)"
}

data class InboundDiscrepancyEvidenceCandidate(
    val file: File,
    val originalFilename: String,
    val declaredContentType: String,
    val byteSize: Long,
    val checksumSha256: String
) {
    init {
        require(file.isFile && file.length() == byteSize)
        require(originalFilename.isNotBlank() && originalFilename.length <= 255)
        require(declaredContentType in ALLOWED_CONTENT_TYPES)
        require(byteSize in 1..MAX_BYTES)
        require(checksumSha256.matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString(): String =
        "DiscrepancyEvidenceCandidate(contentType=$declaredContentType, bytes=$byteSize, checksum=REDACTED)"

    private companion object {
        const val MAX_BYTES = 10L * 1024L * 1024L
        val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

data class InboundDiscrepancyEvidenceArtifact(
    val filename: String,
    val contentType: String,
    val byteSize: Long,
    val checksumSha256: String
) {
    init {
        require(filename.isNotBlank() && filename.length <= 255)
        require(contentType in setOf("image/jpeg", "image/png", "image/webp"))
        require(byteSize in 1..10L * 1024L * 1024L)
        require(checksumSha256.matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString(): String =
        "InboundDiscrepancyEvidenceArtifact(contentType=$contentType, bytes=$byteSize)"
}

sealed interface InboundDiscrepancyArtifactRead {
    data class Available(val artifact: InboundDiscrepancyEvidenceArtifact?) :
        InboundDiscrepancyArtifactRead
    data object Unavailable : InboundDiscrepancyArtifactRead
}

enum class InboundDiscrepancyArtifactWrite {
    Saved,
    Conflict,
    Unavailable
}

data class InboundDiscrepancyCreateCommand(val idempotencyKey: String, val frozenBody: String)

data class InboundDiscrepancyUploadCommand(
    val caseId: String,
    val idempotencyKey: String,
    val candidate: DiscrepancyEvidenceCandidate
)

data class InboundDiscrepancySubmitCommand(
    val caseId: String,
    val evidenceId: String,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String
)

sealed interface InboundDiscrepancyMutationResult {
    data class CaseConfirmed(val value: InboundDiscrepancyCase) : InboundDiscrepancyMutationResult
    data class EvidenceUploaded(val value: InboundDiscrepancyEvidence) :
        InboundDiscrepancyMutationResult
    data class Rejected(val code: String?) : InboundDiscrepancyMutationResult
    data object PreconditionFailed : InboundDiscrepancyMutationResult
    data object Conflict : InboundDiscrepancyMutationResult
    data object UnknownOutcome : InboundDiscrepancyMutationResult
    data object NetworkUnavailable : InboundDiscrepancyMutationResult
    data object ServiceUnavailable : InboundDiscrepancyMutationResult
    data object PermissionDenied : InboundDiscrepancyMutationResult
    data object ContextInvalidated : InboundDiscrepancyMutationResult
    data object SessionInvalidated : InboundDiscrepancyMutationResult
}

sealed interface InboundDiscrepancyEvidenceStatusResult {
    data class Loaded(val value: InboundDiscrepancyEvidence) :
        InboundDiscrepancyEvidenceStatusResult
    data class Rejected(val code: String?) : InboundDiscrepancyEvidenceStatusResult
    data object ServiceUnavailable : InboundDiscrepancyEvidenceStatusResult
    data object PermissionDenied : InboundDiscrepancyEvidenceStatusResult
    data object ContextInvalidated : InboundDiscrepancyEvidenceStatusResult
    data object SessionInvalidated : InboundDiscrepancyEvidenceStatusResult
}

/** Feature port; app boundary rechecks current session,
 permission and context before every call. */

sealed interface InboundDiscrepancyDraftRead {
    data class Available(val draft: InboundDiscrepancyDraft?) : InboundDiscrepancyDraftRead
    data object Unavailable : InboundDiscrepancyDraftRead
}

enum class InboundDiscrepancyDraftWrite {
    Saved,
    Discarded,
    Unavailable,
    Conflict
}
