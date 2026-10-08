package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentCurrentDelivery
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentEvidenceProjection
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentType
import java.util.UUID

data class DriverIncidentSelectionContext(
    val authorityEpoch: Long,
    val scope: DriverAttemptScopeIdentity,
    val deliveryId: String,
    val attemptId: String,
    val deliveryVersion: Long,
    val confirmedTerminalOutcome: Boolean,
    val draftId: String
) {
    init {
        require(authorityEpoch > 0 && deliveryVersion >= 0)
        require(deliveryId.isNotBlank() && attemptId.isNotBlank() && draftId.isNotBlank())
    }

    override fun toString(): String =
        "DriverIncidentSelectionContext(epoch=$authorityEpoch, scope=REDACTED)"
}

/** Driver-selected source classification. Severity is always assigned by the server. */


enum class DriverIncidentEvidenceStage {
    Staged,
    UploadPending,
    UploadUnknownOutcome,
    AwaitingAvailability,
    AvailableForReview,
    AttachPending,
    AttachUnknownOutcome,
    Linked
}

/** Protected, single-item offline evidence draft. The bytes are encrypted by the app store. */


data class DriverIncidentEvidenceDraft(
    val fileToken: String,
    val originalFilename: String,
    val contentType: String,
    val byteSize: Long,
    val checksumSha256: String,
    val stage: DriverIncidentEvidenceStage = DriverIncidentEvidenceStage.Staged,
    val uploadIdempotencyKey: String? = null,
    val evidenceId: String? = null,
    val attachIdempotencyKey: String? = null,
    val attachExpectedVersion: Long? = null,
    val attachBody: String? = null
) {
    init {
        require(
            fileToken.isNotBlank() && originalFilename.isNotBlank() &&
                originalFilename.length <= 255
        )
        require(contentType in setOf("image/jpeg", "image/png", "image/webp"))
        require(byteSize in 1..(10L * 1024L * 1024L))
        require(checksumSha256.matches(Regex("[0-9a-f]{64}")))
        require(
            (stage == DriverIncidentEvidenceStage.Staged) || !uploadIdempotencyKey.isNullOrBlank()
        )
        require(
            (
                stage !in setOf(
                    DriverIncidentEvidenceStage.AwaitingAvailability,
                    DriverIncidentEvidenceStage.AvailableForReview,
                    DriverIncidentEvidenceStage.AttachPending,
                    DriverIncidentEvidenceStage.AttachUnknownOutcome,
                    DriverIncidentEvidenceStage.Linked
                )
                ) ||
                !evidenceId.isNullOrBlank()
        )
        require(
            (
                stage !in setOf(
                    DriverIncidentEvidenceStage.AttachPending,
                    DriverIncidentEvidenceStage.AttachUnknownOutcome,
                    DriverIncidentEvidenceStage.Linked
                )
                ) ||
                (
                    !attachIdempotencyKey.isNullOrBlank() && attachExpectedVersion != null &&
                        attachBody != null
                    )
        )
    }

    override fun toString(): String =
        "DriverIncidentEvidenceDraft(stage=$stage, bytes=$byteSize, payload=REDACTED)"
}



data class DriverIncidentCommand(
    val deliveryId: String,
    val attemptId: String,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val reason: String,
    val description: String,
    val place: String,
    val frozenBody: String,
    /** Null only for an exact pre-type command recovered from encrypted storage. */
    val type: DriverIncidentType? = null
) {
    init {
        require(deliveryId.isNotBlank() && attemptId.isNotBlank())
        require(expectedVersion >= 0)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(reason.isNotBlank() && reason.length <= 500)
        require(description.isNotBlank() && description.length <= 2000)
        require(place.isNotBlank() && place.length <= 500)
    }

    override fun toString(): String =
        "DriverIncidentCommand(version=$expectedVersion, key=REDACTED)"
}



enum class DriverIncidentRecordStatus {
    Draft,
    Pending,
    UnknownOutcome,
    RecordedWithEvidence
}

/** Encrypted local metadata only. It holds no credentials,
 permission snapshot, or server authority. */


data class DriverIncidentMetadata(
    val scope: DriverAttemptScopeIdentity,
    val deliveryId: String,
    val attemptId: String,
    val draftVersion: Long,
    val reason: String,
    val description: String,
    val place: String,
    val status: DriverIncidentRecordStatus,
    val command: DriverIncidentCommand? = null,
    val draftId: String = UUID.randomUUID().toString(),
    val incidentId: String? = null,
    val evidence: DriverIncidentEvidenceDraft? = null,
    val recordedAt: String? = null,
    val recordedByMembershipId: String? = null,
    val deliveryVersion: Long? = null,
    /** Null for legacy drafts/intents that predate explicit source classification. */
    val type: DriverIncidentType? = null,
    val severity: String? = null,
    val operationalExceptionId: String? = null
) {
    init {
        require(deliveryId.isNotBlank() && attemptId.isNotBlank())
        require(draftId.isNotBlank())
        require(draftVersion >= 0)
        require(
            (
                status in
                    setOf(
                        DriverIncidentRecordStatus.Draft,
                        DriverIncidentRecordStatus.RecordedWithEvidence
                    )
                ) ==
                (command == null)
        )
        require(
            (status == DriverIncidentRecordStatus.RecordedWithEvidence) ==
                !incidentId.isNullOrBlank()
        )
        require(
            command == null || (command.deliveryId == deliveryId && command.attemptId == attemptId)
        )
        require(
            command == null || (
                command.expectedVersion == draftVersion &&
                    command.reason == reason && command.description == description &&
                    command.place == place &&
                    command.type == type
                )
        )
        require(reason.length <= 500 && description.length <= 2000 && place.length <= 500)
        require(deliveryVersion == null || deliveryVersion >= 0)
        require(recordedByMembershipId == null || recordedByMembershipId.isNotBlank())
        require(severity == null || severity in setOf("WARNING", "BLOCKING", "CRITICAL"))
        require(
            operationalExceptionId == null ||
                UUID.fromString(operationalExceptionId).toString() == operationalExceptionId
        )
    }

    override fun toString(): String = "DriverIncidentMetadata(status=$status, payload=REDACTED)"
}



sealed interface DriverIncidentMetadataRead {
    data class Available(val metadata: DriverIncidentMetadata?) : DriverIncidentMetadataRead
    data object Unavailable : DriverIncidentMetadataRead
}



sealed interface DriverIncidentMetadataWrite {
    data object Saved : DriverIncidentMetadataWrite
    data object Conflict : DriverIncidentMetadataWrite
    data object Stale : DriverIncidentMetadataWrite
    data object Unavailable : DriverIncidentMetadataWrite
}



data class DriverIncidentEvidenceUploadCommand(
    val incidentId: String,
    val idempotencyKey: String,
    val candidate: DriverProofFileCandidate
) {
    override fun toString(): String =
        "DriverIncidentEvidenceUploadCommand(key=REDACTED, bytes=${candidate.byteSize})"
}



data class DriverIncidentEvidenceAttachCommand(
    val deliveryId: String,
    val attemptId: String,
    val incidentId: String,
    val evidenceId: String,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String
) {
    override fun toString(): String =
        "DriverIncidentEvidenceAttachCommand(version=$expectedVersion, key=REDACTED)"
}



sealed interface DriverIncidentEvidenceResult {
    data class Uploaded(val evidence: DriverIncidentEvidenceProjection) :
        DriverIncidentEvidenceResult
    data class Current(val evidence: DriverIncidentEvidenceProjection) :
        DriverIncidentEvidenceResult
    data class Rejected(val code: String?) : DriverIncidentEvidenceResult
    data object NotFound : DriverIncidentEvidenceResult
    data object UnknownOutcome : DriverIncidentEvidenceResult
    data object Unavailable : DriverIncidentEvidenceResult
    data object PermissionDenied : DriverIncidentEvidenceResult
    data object ContextInvalidated : DriverIncidentEvidenceResult
    data object SessionInvalidated : DriverIncidentEvidenceResult
}



sealed interface DriverIncidentCurrentDeliveryResult {
    data class Loaded(val delivery: DriverIncidentCurrentDelivery) :
        DriverIncidentCurrentDeliveryResult
    data object NotFound : DriverIncidentCurrentDeliveryResult
    data object Unavailable : DriverIncidentCurrentDeliveryResult
    data object PermissionDenied : DriverIncidentCurrentDeliveryResult
    data object ContextInvalidated : DriverIncidentCurrentDeliveryResult
    data object SessionInvalidated : DriverIncidentCurrentDeliveryResult
}



sealed interface DriverIncidentResult {
    data class Recorded(val summary: DriverIncidentSummary) : DriverIncidentResult
    data class Rejected(val code: String?) : DriverIncidentResult
    data object NotFound : DriverIncidentResult
    data object StaleVersion : DriverIncidentResult
    data object UnknownOutcome : DriverIncidentResult
    data object Unavailable : DriverIncidentResult
    data object PermissionDenied : DriverIncidentResult
    data object ContextInvalidated : DriverIncidentResult
    data object SessionInvalidated : DriverIncidentResult
}
