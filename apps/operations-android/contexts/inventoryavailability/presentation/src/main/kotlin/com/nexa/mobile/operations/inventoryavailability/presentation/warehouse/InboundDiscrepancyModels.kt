package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyEvidenceArtifact
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyPendingAction
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.InboundDiscrepancyKind

enum class InboundDiscrepancyMetadataStatus {
    Loading,
    Available,
    Unavailable
}

enum class InboundDiscrepancyFlowStatus {
    Editing,
    CreatingCase,
    UnknownOutcome,
    PendingEvidence,
    EvidenceSelected,
    UploadingEvidence,
    EvidenceAwaitingScan,
    EvidenceAvailable,
    SubmittingForReview,
    ReadyForReview,
    Stale,
    Rejected,
    Unauthorized,
    ServiceUnavailable
}

enum class InboundDiscrepancyValidationError {
    WarehouseRequired,
    WarehouseInvalid,
    ObservedSkuRequired,
    ObservedSkuInvalid,
    ExpectedSkuInvalid,
    BatchReferenceInvalid,
    ExpectedQuantityRequired,
    ObservedQuantityRequired,
    QuantityInvalid,
    QuantityDifferenceRequired,
    UnitRequired,
    ReasonRequired,
    EvidenceRequired
}

enum class InboundDiscrepancySaveNotice {
    SavedLocally,
    StoreUnavailable,
    ExistingDraftConflict,
    Discarded,
    CaseRecorded,
    EvidenceStaged,
    EvidenceUploaded,
    EvidenceAwaitingScan,
    ReviewRequested,
    UnknownOutcome,
    Stale,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    NetworkUnavailable,
    ServiceUnavailable,
    EvidenceRejected
}

@Immutable
data class InboundDiscrepancyUiState(
    val authorityEpoch: Long = 0,
    val active: Boolean = false,
    val metadata: InboundDiscrepancyMetadataStatus = InboundDiscrepancyMetadataStatus.Loading,
    val draftId: String = "",
    val warehouseId: String = "",
    val expectedSkuId: String = "",
    val observedSkuId: String = "",
    val observedSkuLabel: String? = null,
    val expectedBatchReference: String = "",
    val observedBatchReference: String = "",
    val expectedQuantityText: String = "",
    val observedQuantityText: String = "",
    val unit: String = "",
    val kind: InboundDiscrepancyKind? = null,
    val reasonDetails: String = "",
    val observationNotes: String = "",
    val capturedAtDeviceMillis: Long? = null,
    val flow: InboundDiscrepancyFlowStatus = InboundDiscrepancyFlowStatus.Editing,
    val caseId: String? = null,
    val caseVersion: Long? = null,
    val caseStatus: String? = null,
    val evidenceId: String? = null,
    val evidenceStatus: String? = null,
    val artifact: InboundDiscrepancyEvidenceArtifact? = null,
    val createIdempotencyKey: String? = null,
    val createBody: String? = null,
    val evidenceUploadKey: String? = null,
    val submitIdempotencyKey: String? = null,
    val submitBody: String? = null,
    val pendingAction: InboundDiscrepancyPendingAction? = null,
    val isSaving: Boolean = false,
    val hasSavedDraft: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val showDiscardConfirmation: Boolean = false,
    val validationError: InboundDiscrepancyValidationError? = null,
    val notice: InboundDiscrepancySaveNotice? = null,
    val rejectionCode: String? = null
) {
    val canSave: Boolean
        get() = active && metadata == InboundDiscrepancyMetadataStatus.Available && !isSaving &&
            pendingAction == null
    val canCreateCase: Boolean
        get() = canSave && caseId == null && flow !in setOf(
            InboundDiscrepancyFlowStatus.CreatingCase,
            InboundDiscrepancyFlowStatus.UnknownOutcome
        )
    val canSelectEvidence: Boolean
        get() = active && caseStatus ==
            "PENDING_EVIDENCE" && evidenceId == null && artifact == null &&
            !isSaving && pendingAction == null
    val canUploadEvidence: Boolean
        get() = active && caseStatus ==
            "PENDING_EVIDENCE" && evidenceId == null && artifact != null &&
            !isSaving &&
            pendingAction == null &&
            evidenceStatus !in setOf("AVAILABLE", "REJECTED")
    val canRefreshEvidence: Boolean
        get() = active && caseId != null && evidenceId != null && !isSaving &&
            evidenceStatus !in setOf("AVAILABLE", "REJECTED")
    val canSubmitForReview: Boolean
        get() = active && caseId != null && caseStatus == "PENDING_EVIDENCE" &&
            evidenceId != null && evidenceStatus == "AVAILABLE" && !isSaving &&
            pendingAction == null

    override fun toString(): String =
        "InboundDiscrepancyUiState(epoch=$authorityEpoch, active=$active, flow=$flow, case=REDACTED)"
}
