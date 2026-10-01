package com.nexa.mobile.operations.feature.delivery

import androidx.compose.runtime.Immutable

enum class DriverProofEvidenceKind { PHOTO, SIGNATURE }

/** Captures route identity at picker launch so late results cannot target another delivery. */
@Immutable
data class DriverProofSelectionContext(
    val authorityEpoch: Long,
    val scope: DriverAttemptScopeIdentity,
    val deliveryId: String,
    val attemptId: String,
    val proofId: String
)

@Immutable
data class DriverProofCreateCommand(
    val deliveryId: String,
    val attemptId: String,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String,
    val receiverName: String,
    val capturedAt: String
) {
    override fun toString(): String = "DriverProofCreateCommand(version=$expectedVersion, key=REDACTED)"
}

@Immutable
data class DriverProofUploadCommand(
    val deliveryId: String,
    val attemptId: String,
    val proofId: String,
    val evidenceKind: DriverProofEvidenceKind,
    val idempotencyKey: String,
    val candidate: DriverProofFileCandidate
) {
    override fun toString(): String = "DriverProofUploadCommand(kind=$evidenceKind, key=REDACTED)"
}

@Immutable
data class DriverProofAttachCommand(
    val deliveryId: String,
    val attemptId: String,
    val proofId: String,
    val evidenceObjectId: String,
    val evidenceKind: DriverProofEvidenceKind,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String
) {
    override fun toString(): String = "DriverProofAttachCommand(version=$expectedVersion, key=REDACTED)"
}

@Immutable
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

@Immutable
data class DriverProofEvidenceSummary(
    val evidenceId: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String,
    val contentType: String,
    val checksumSha256: String?,
    val byteSize: Long
)

enum class DriverProofIntentStage {
    CreatingProof,
    ProofCreated,
    EvidenceReadyForReview,
    UploadingEvidence,
    EvidenceAwaitingScan,
    AttachingEvidence,
    Captured
}

enum class DriverProofIntentStatus { Pending, UnknownOutcome }

/** Recoverable metadata only. It contains no credentials, authority snapshot, or server-truth cache. */
@Immutable
data class DriverProofIntentMetadata(
    val scope: DriverAttemptScopeIdentity,
    val deliveryId: String,
    val attemptId: String,
    val createExpectedVersion: Long,
    val createIdempotencyKey: String,
    val createBody: String,
    val receiverName: String,
    val capturedAt: String,
    val notes: String?,
    val stage: DriverProofIntentStage,
    val status: DriverProofIntentStatus,
    val proofId: String? = null,
    val proofVersion: Long? = null,
    val evidenceKind: DriverProofEvidenceKind? = null,
    val evidenceId: String? = null,
    val evidenceUploadKey: String? = null,
    val candidateFileToken: String? = null,
    val candidateFilename: String? = null,
    val candidateContentType: String? = null,
    val candidateByteSize: Long? = null,
    val candidateChecksumSha256: String? = null,
    val attachExpectedVersion: Long? = null,
    val attachKey: String? = null,
    val attachBody: String? = null
) {
    init {
        require(deliveryId.isNotBlank() && attemptId.isNotBlank())
        require(createExpectedVersion >= 0)
        require(createIdempotencyKey.isNotBlank() && createIdempotencyKey.length <= 160)
        require(createBody.isNotBlank())
        require(receiverName.isNotBlank() && capturedAt.isNotBlank())
        require((proofId == null) == (proofVersion == null))
        require((evidenceId == null) || evidenceId.isNotBlank())
    }

    override fun toString(): String = "DriverProofIntentMetadata(stage=$stage, status=$status, payload=REDACTED)"
}

sealed interface DriverProofMetadataRead {
    data class Available(val intent: DriverProofIntentMetadata?) : DriverProofMetadataRead
    data object Unavailable : DriverProofMetadataRead
}

sealed interface DriverProofMetadataWrite {
    data object Saved : DriverProofMetadataWrite
    data object Conflict : DriverProofMetadataWrite
    data object Stale : DriverProofMetadataWrite
    data object Unavailable : DriverProofMetadataWrite
}

interface DriverProofMetadataStore {
    suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverProofMetadataRead
    suspend fun saveIntent(intent: DriverProofIntentMetadata): DriverProofMetadataWrite
    suspend fun stageCandidate(
        intent: DriverProofIntentMetadata,
        candidate: DriverProofFileCandidate
    ): DriverProofMetadataWrite
    suspend fun loadCandidate(intent: DriverProofIntentMetadata): DriverProofFileCandidate?
    suspend fun clearCandidate(intent: DriverProofIntentMetadata): Boolean
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        createIdempotencyKey: String
    ): DriverProofMetadataWrite
}

sealed interface DriverProofCreateResult {
    data class Created(val summary: DriverProofSummary) : DriverProofCreateResult
    data class Rejected(val code: String?) : DriverProofCreateResult
    data object NotFound : DriverProofCreateResult
    data object StaleVersion : DriverProofCreateResult
    data object UnknownOutcome : DriverProofCreateResult
    data object ServiceUnavailable : DriverProofCreateResult
    data object PermissionDenied : DriverProofCreateResult
    data object ContextInvalidated : DriverProofCreateResult
    data object SessionInvalidated : DriverProofCreateResult
}

sealed interface DriverProofUploadResult {
    data class Uploaded(val summary: DriverProofEvidenceSummary) : DriverProofUploadResult
    data class Rejected(val code: String?) : DriverProofUploadResult
    data object NotFound : DriverProofUploadResult
    data object UnknownOutcome : DriverProofUploadResult
    data object ServiceUnavailable : DriverProofUploadResult
    data object PermissionDenied : DriverProofUploadResult
    data object ContextInvalidated : DriverProofUploadResult
    data object SessionInvalidated : DriverProofUploadResult
}

sealed interface DriverProofEvidenceStatusResult {
    data class Loaded(val summary: DriverProofEvidenceSummary) : DriverProofEvidenceStatusResult
    data object NotFound : DriverProofEvidenceStatusResult
    data object ServiceUnavailable : DriverProofEvidenceStatusResult
    data object PermissionDenied : DriverProofEvidenceStatusResult
    data object ContextInvalidated : DriverProofEvidenceStatusResult
    data object SessionInvalidated : DriverProofEvidenceStatusResult
}

sealed interface DriverProofAttachResult {
    data class Attached(val summary: DriverProofSummary) : DriverProofAttachResult
    data class Rejected(val code: String?) : DriverProofAttachResult
    data object NotFound : DriverProofAttachResult
    data object StaleVersion : DriverProofAttachResult
    data object UnknownOutcome : DriverProofAttachResult
    data object ServiceUnavailable : DriverProofAttachResult
    data object PermissionDenied : DriverProofAttachResult
    data object ContextInvalidated : DriverProofAttachResult
    data object SessionInvalidated : DriverProofAttachResult
}
