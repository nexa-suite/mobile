package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentCurrentDeliveryResult as CurrentDeliveryResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceAttachCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceDraft
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceStage
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceUploadCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentRecordStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentSelectionContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofFileCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentCurrentDelivery
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentEvidenceProjection
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentType
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DriverIncidentUiStatus {
    Loading,
    EditingDraft,
    NeedsReview,
    ReadyForReview,
    SavingDraft,
    DraftSaved,
    CheckingCurrent,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    PersistenceUnavailable,
    Recorded,
    Stale,
    Rejected,
    NotFound,
    Unavailable,
    PermissionDenied
}

data class DriverDeliveryIncidentUiState(
    val authorityEpoch: Long = 0,
    val deliveryId: String? = null,
    val attemptId: String? = null,
    val delivery: DriverIncidentCurrentDelivery? = null,
    val draftId: String? = null,
    val status: DriverIncidentUiStatus = DriverIncidentUiStatus.Loading,
    /** Null for untyped legacy drafts/intents; a new report requires an explicit type. */
    val type: DriverIncidentType? = null,
    val reason: String = "",
    val description: String = "",
    val place: String = "",
    val draftSaved: Boolean = false,
    val savedDraftVersion: Long? = null,
    val reviewedVersion: Long? = null,
    val command: DriverIncidentCommand? = null,
    val summary: DriverIncidentSummary? = null,
    val evidence: DriverIncidentEvidenceDraft? = null,
    val evidenceReviewedVersion: Long? = null,
    val evidenceLifecycleStatus: String? = null,
    val evidenceError: String? = null,
    val evidenceBusy: Boolean = false,
    val canCaptureEvidence: Boolean = false,
    val rejectionCode: String? = null,
    val persistenceCleanupPending: Boolean = false
) {
    val validDraft: Boolean
        get() = type != null && reason.isNotBlank() && reason.length <= 500 &&
            description.isNotBlank() && description.length <= 2000 &&
            place.isNotBlank() && place.length <= 500

    val canSubmit: Boolean
        get() = status == DriverIncidentUiStatus.ReadyForReview && validDraft &&
            draftSaved && reviewedVersion != null && command == null

    val canSelectEvidence: Boolean
        get() = canCaptureEvidence && draftSaved && command == null &&
            status !in
            setOf(
                DriverIncidentUiStatus.CheckingCurrent,
                DriverIncidentUiStatus.SavingDraft,
                DriverIncidentUiStatus.PersistingIntent,
                DriverIncidentUiStatus.Pending,
                DriverIncidentUiStatus.UnknownOutcome,
                DriverIncidentUiStatus.PersistenceUnavailable,
                DriverIncidentUiStatus.Recorded
            ) && evidence == null

    val canUploadEvidence: Boolean
        get() = !evidenceBusy && status == DriverIncidentUiStatus.Recorded && summary != null &&
            evidence != null &&
            (
                evidence.stage == DriverIncidentEvidenceStage.Staged ||
                    evidence.stage == DriverIncidentEvidenceStage.UploadUnknownOutcome
                )

    val canCheckEvidence: Boolean
        get() = !evidenceBusy && status == DriverIncidentUiStatus.Recorded &&
            evidence?.stage == DriverIncidentEvidenceStage.AwaitingAvailability

    val canReviewEvidenceLink: Boolean
        get() = !evidenceBusy && status == DriverIncidentUiStatus.Recorded &&
            evidence?.stage in setOf(
                DriverIncidentEvidenceStage.AvailableForReview,
                DriverIncidentEvidenceStage.AttachUnknownOutcome
            )

    val canAttachEvidence: Boolean
        get() = !evidenceBusy && status == DriverIncidentUiStatus.Recorded &&
            evidence?.stage in setOf(
                DriverIncidentEvidenceStage.AvailableForReview,
                DriverIncidentEvidenceStage.AttachUnknownOutcome
            ) && evidenceReviewedVersion != null &&
            delivery?.version == evidenceReviewedVersion

    override fun toString(): String =
        "DriverDeliveryIncidentUiState(status=$status, delivery=${deliveryId != null}, payload=REDACTED)"
}

/** Safe local draft, fresh-context review, then one persisted append-only API command. */
class DriverDeliveryIncidentViewModel(
    private val gateway: DriverIncidentGateway,
    private val metadataStore: DriverIncidentMetadataStore?,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val requestBodyCodec: DeliveryRequestBodyCodec
) : ViewModel() {
    private val mutableState = MutableStateFlow(DriverDeliveryIncidentUiState())
    val state = mutableState.asStateFlow()

    private var authority: DriverDeliveryAuthority? = null
    private var generation = 0L
    private var targetVersion = 0L
    private var confirmedTerminalOutcome = false
    private var pendingCommand: DriverIncidentCommand? = null
    private var pendingPersisted = false

    fun activate(
        currentAuthority: DriverDeliveryAuthority,
        deliveryId: String,
        attemptId: String,
        deliveryVersion: Long,
        confirmedTerminalOutcome: Boolean = false
    ) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        targetVersion = deliveryVersion.coerceAtLeast(0)
        this.confirmedTerminalOutcome = confirmedTerminalOutcome
        pendingCommand = null
        pendingPersisted = false
        mutableState.value = DriverDeliveryIncidentUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            deliveryId = deliveryId,
            attemptId = attemptId,
            draftId = UUID.randomUUID().toString(),
            canCaptureEvidence = currentAuthority.canCaptureProof,
            status = DriverIncidentUiStatus.Loading
        )
        if (!currentAuthority.canRead || !currentAuthority.canStart ||
            deliveryId.isBlank() || attemptId.isBlank() || deliveryVersion < 0
        ) {
            mutableState.update { it.copy(status = DriverIncidentUiStatus.PermissionDenied) }
            return
        }
        viewModelScope.launch {
            var recovered: DriverIncidentMetadata? = null
            when (val stored = safeLoad(currentAuthority.scopeIdentity)) {
                is DriverIncidentMetadataRead.Available -> {
                    val candidate = stored.metadata
                    val candidateCommand = candidate?.command
                    if (candidate != null && candidate.scope == currentAuthority.scopeIdentity &&
                        candidate.deliveryId == deliveryId && candidate.attemptId == attemptId &&
                        (candidateCommand == null || requestBodyCodec.isValid(candidateCommand))
                    ) {
                        val recoveredCandidate = candidate.copy(
                            status = if (candidate.status == DriverIncidentRecordStatus.Pending) {
                                DriverIncidentRecordStatus.UnknownOutcome
                            } else {
                                candidate.status
                            }
                        )
                        recovered = recoveredCandidate
                        if (recoveredCandidate.status ==
                            DriverIncidentRecordStatus.UnknownOutcome &&
                            recoveredCandidate != candidate
                        ) {
                            if (safePersist(recoveredCandidate) !=
                                DriverIncidentMetadataWrite.Saved
                            ) {
                                mutableState.update {
                                    it.copy(status = DriverIncidentUiStatus.PersistenceUnavailable)
                                }
                                return@launch
                            }
                        }
                    }
                }

                DriverIncidentMetadataRead.Unavailable -> {
                    mutableState.update {
                        it.copy(status = DriverIncidentUiStatus.PersistenceUnavailable)
                    }
                    return@launch
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            recovered?.let { metadata ->
                if (metadata.status == DriverIncidentRecordStatus.RecordedWithEvidence &&
                    metadata.toSummaryOrNull() == null
                ) {
                    mutableState.update {
                        it.copy(status = DriverIncidentUiStatus.PersistenceUnavailable)
                    }
                    return@launch
                }
                targetVersion = metadata.draftVersion
                pendingCommand = metadata.command
                pendingPersisted = metadata.command != null
                mutableState.update {
                    it.copy(
                        reason = metadata.reason,
                        description = metadata.description,
                        place = metadata.place,
                        type = metadata.type,
                        draftId = metadata.draftId,
                        savedDraftVersion = metadata.draftVersion,
                        evidence = metadata.evidence,
                        summary = metadata.toSummaryOrNull(),
                        evidenceLifecycleStatus = metadata.evidence?.let {
                            it.stage.lifecycleLabel()
                        },
                        draftSaved = true,
                        command = metadata.command,
                        status = if (metadata.command == null) {
                            if (metadata.incidentId != null) {
                                DriverIncidentUiStatus.Recorded
                            } else {
                                DriverIncidentUiStatus.NeedsReview
                            }
                        } else {
                            DriverIncidentUiStatus.UnknownOutcome
                        }
                    )
                }
            }
            loadCurrent(requestGeneration, currentAuthority, deliveryId, review = false)
        }
    }

    fun invalidate() {
        generation++
        authority = null
        pendingCommand = null
        pendingPersisted = false
        confirmedTerminalOutcome = false
        mutableState.value = DriverDeliveryIncidentUiState()
    }

    fun editReason(value: String) = editDraft { copy(reason = value) }

    fun editType(value: DriverIncidentType) = editDraft { copy(type = value) }

    fun editDescription(value: String) = editDraft { copy(description = value) }

    fun editPlace(value: String) = editDraft { copy(place = value) }

    /** Explicitly saves only draft text and the selected delivery/attempt scope; it sends no request. */
    fun saveDraft() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val deliveryId = current.deliveryId ?: return
        val attemptId = current.attemptId ?: return
        if (pendingCommand != null || current.status in FROZEN_STATUSES ||
            current.status == DriverIncidentUiStatus.Recorded
        ) {
            return
        }
        if (!current.validDraft) {
            mutableState.update {
                it.copy(
                    status = DriverIncidentUiStatus.Rejected,
                    rejectionCode = "INCIDENT_FIELDS_REQUIRED"
                )
            }
            return
        }
        val version = current.delivery?.version ?: targetVersion
        val metadata = DriverIncidentMetadata(
            scope = currentAuthority.scopeIdentity,
            deliveryId = deliveryId,
            attemptId = attemptId,
            draftVersion = version,
            reason = current.reason.trim(),
            description = current.description.trim(),
            place = current.place.trim(),
            status = DriverIncidentRecordStatus.Draft,
            draftId = current.draftId ?: UUID.randomUUID().toString(),
            evidence = current.evidence,
            type = current.type
        )
        val requestGeneration = generation
        mutableState.update {
            it.copy(status = DriverIncidentUiStatus.SavingDraft, rejectionCode = null)
        }
        viewModelScope.launch {
            val result = safeSaveDraft(metadata)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (result) {
                DriverIncidentMetadataWrite.Saved -> {
                    targetVersion = version
                    mutableState.update {
                        it.copy(
                            reason = metadata.reason,
                            description = metadata.description,
                            place = metadata.place,
                            type = metadata.type,
                            draftId = metadata.draftId,
                            savedDraftVersion = metadata.draftVersion,
                            evidence = metadata.evidence,
                            draftSaved = true,
                            reviewedVersion = null,
                            status = if (it.delivery == null) {
                                DriverIncidentUiStatus.DraftSaved
                            } else {
                                DriverIncidentUiStatus.NeedsReview
                            }
                        )
                    }
                }

                DriverIncidentMetadataWrite.Conflict,
                DriverIncidentMetadataWrite.Stale -> mutableState.update {
                    it.copy(status = DriverIncidentUiStatus.PersistenceUnavailable)
                }

                DriverIncidentMetadataWrite.Unavailable -> mutableState.update {
                    it.copy(status = DriverIncidentUiStatus.PersistenceUnavailable)
                }
            }
        }
    }

    /** Refreshes assigned server facts for review; it never submits the draft. */
    fun reviewDraft() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val deliveryId = current.deliveryId ?: return
        if (!current.draftSaved || !current.validDraft || pendingCommand != null ||
            current.status in FROZEN_STATUSES
        ) {
            return
        }
        val requestGeneration = generation
        mutableState.update {
            it.copy(status = DriverIncidentUiStatus.CheckingCurrent, rejectionCode = null)
        }
        viewModelScope.launch {
            loadCurrent(requestGeneration, currentAuthority, deliveryId, review = true)
        }
    }

    /** Captures the saved draft's exact identity before MainActivity launches the native picker. */
    fun prepareEvidenceSelection(): DriverIncidentSelectionContext? {
        val currentAuthority = authority ?: return null
        val current = mutableState.value
        val deliveryId = current.deliveryId ?: return null
        val attemptId = current.attemptId ?: return null
        val draftId = current.draftId ?: return null
        if (!currentAuthority.canCaptureProof || !current.draftSaved || current.command != null ||
            current.status in FROZEN_STATUSES ||
            current.status == DriverIncidentUiStatus.Recorded ||
            current.evidence != null || current.savedDraftVersion == null
        ) {
            return null
        }
        val version = current.savedDraftVersion
        return DriverIncidentSelectionContext(
            currentAuthority.authorityEpoch,
            currentAuthority.scopeIdentity,
            deliveryId,
            attemptId,
            version,
            confirmedTerminalOutcome,
            draftId
        )
    }

    /** The activity calls this after encrypted local staging and a fresh manual route open. */
    fun evidenceStaged(context: DriverIncidentSelectionContext) {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (context.scope != currentAuthority.scopeIdentity ||
            context.deliveryId != current.deliveryId || context.attemptId != current.attemptId ||
            context.draftId != current.draftId || context.deliveryVersion != targetVersion ||
            context.confirmedTerminalOutcome != confirmedTerminalOutcome
        ) {
            return
        }
        val requestGeneration = generation
        viewModelScope.launch {
            val stored = safeLoad(currentAuthority.scopeIdentity)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val metadata = (stored as? DriverIncidentMetadataRead.Available)?.metadata
                ?.takeIf {
                    it.draftId == context.draftId && it.deliveryId == context.deliveryId &&
                        it.attemptId == context.attemptId
                }
                ?: return@launch
            mutableState.update {
                it.copy(
                    evidence = metadata.evidence,
                    draftSaved = true,
                    savedDraftVersion = metadata.draftVersion,
                    status = if (it.delivery?.version ==
                        metadata.draftVersion
                    ) {
                        DriverIncidentUiStatus.NeedsReview
                    } else {
                        DriverIncidentUiStatus.Stale
                    }
                )
            }
        }
    }

    /** Upload is always user initiated and reuses the durable key and encrypted bytes after uncertainty. */
    fun uploadEvidence() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val incidentId = current.summary?.incidentId ?: return
        val evidence = current.evidence ?: return
        if (!current.canUploadEvidence || !currentAuthority.canCaptureProof) return
        val requestGeneration = generation
        mutableState.update { it.copy(evidenceBusy = true, evidenceError = null) }
        viewModelScope.launch {
            var metadata = loadRecordedMetadata(currentAuthority, current) ?: run {
                evidenceFailure("INCIDENT_EVIDENCE_DRAFT_UNAVAILABLE")
                return@launch
            }
            val key =
                evidence.uploadIdempotencyKey
                    ?: keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
            if (key == null) {
                evidenceFailure("IDEMPOTENCY_KEY_INVALID")
                return@launch
            }
            val uploadIntent = evidence.copy(
                stage = DriverIncidentEvidenceStage.UploadPending,
                uploadIdempotencyKey = key
            )
            metadata = metadata.copy(evidence = uploadIntent)
            if (safeUpdateRecordedEvidence(metadata) != DriverIncidentMetadataWrite.Saved) {
                evidenceFailure("INCIDENT_EVIDENCE_INTENT_NOT_SAVED")
                return@launch
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            mutableState.update { it.copy(evidence = uploadIntent) }
            val candidate = safeLoadCandidate(metadata)
            if (candidate == null) {
                val reset = metadata.copy(
                    evidence = uploadIntent.copy(
                        stage = DriverIncidentEvidenceStage.Staged,
                        uploadIdempotencyKey = null
                    )
                )
                safeUpdateRecordedEvidence(reset)
                evidenceFailure("INCIDENT_EVIDENCE_FILE_UNAVAILABLE")
                return@launch
            }
            val result = try {
                gateway.uploadEvidence(
                    DriverIncidentEvidenceUploadCommand(incidentId, key, candidate),
                    currentAuthority
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverIncidentEvidenceResult.UnknownOutcome
            } finally {
                candidate.file.delete()
            }
            if (!isCurrent(requestGeneration, currentAuthority)) {
                safeUpdateRecordedEvidence(
                    metadata.copy(
                        evidence = uploadIntent.copy(
                            stage = DriverIncidentEvidenceStage.UploadUnknownOutcome
                        )
                    )
                )
                return@launch
            }
            when (result) {
                is DriverIncidentEvidenceResult.Uploaded -> {
                    val projection = result.evidence
                    if (!matchesEvidence(projection, incidentId, evidence)) {
                        persistUploadUnknown(
                            metadata,
                            uploadIntent,
                            "INCIDENT_EVIDENCE_RESPONSE_MISMATCH"
                        )
                    } else {
                        val awaiting = uploadIntent.copy(
                            stage = DriverIncidentEvidenceStage.AwaitingAvailability,
                            evidenceId = projection.evidenceId
                        )
                        val saved = safeUpdateRecordedEvidence(metadata.copy(evidence = awaiting))
                        if (saved == DriverIncidentMetadataWrite.Saved) {
                            mutableState.update {
                                it.copy(
                                    evidence = awaiting,
                                    evidenceLifecycleStatus = projection.lifecycleStatus,
                                    evidenceBusy = false,
                                    evidenceError = null,
                                    evidenceReviewedVersion = null
                                )
                            }
                        } else {
                            evidenceFailure("INCIDENT_EVIDENCE_STATE_NOT_SAVED")
                        }
                    }
                }

                is DriverIncidentEvidenceResult.Rejected ->
                    persistUploadUnknown(
                        metadata,
                        uploadIntent,
                        result.code ?: "EVIDENCE_UPLOAD_REJECTED"
                    )

                DriverIncidentEvidenceResult.NotFound -> persistUploadUnknown(
                    metadata,
                    uploadIntent,
                    "EVIDENCE_SUBJECT_NOT_FOUND"
                )

                DriverIncidentEvidenceResult.PermissionDenied -> persistUploadUnknown(
                    metadata,
                    uploadIntent,
                    "DOCUMENT_UPLOAD_REQUIRED"
                )

                DriverIncidentEvidenceResult.ContextInvalidated,
                DriverIncidentEvidenceResult.SessionInvalidated,
                DriverIncidentEvidenceResult.UnknownOutcome,
                DriverIncidentEvidenceResult.Unavailable -> persistUploadUnknown(
                    metadata,
                    uploadIntent,
                    "EVIDENCE_UPLOAD_UNKNOWN"
                )

                is DriverIncidentEvidenceResult.Current -> persistUploadUnknown(
                    metadata,
                    uploadIntent,
                    "EVIDENCE_UPLOAD_RESPONSE_INVALID"
                )
            }
        }
    }

    /** A fresh status read is required; only the exact DELIVERY_INCIDENT subject in
     AVAILABLE can advance. */
    fun checkEvidenceAvailability() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val incidentId = current.summary?.incidentId ?: return
        val evidence = current.evidence ?: return
        val evidenceId = evidence.evidenceId ?: return
        if (!current.canCheckEvidence) return
        val requestGeneration = generation
        mutableState.update { it.copy(evidenceBusy = true, evidenceError = null) }
        viewModelScope.launch {
            val result = try {
                gateway.evidenceStatus(evidenceId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverIncidentEvidenceResult.Unavailable
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (result) {
                is DriverIncidentEvidenceResult.Current -> {
                    val projection = result.evidence
                    if (!matchesEvidence(projection, incidentId, evidence)) {
                        evidenceFailure("INCIDENT_EVIDENCE_SUBJECT_MISMATCH")
                    } else if (projection.lifecycleStatus != "AVAILABLE") {
                        mutableState.update {
                            it.copy(
                                evidenceBusy = false,
                                evidenceLifecycleStatus = projection.lifecycleStatus,
                                evidenceError = "INCIDENT_EVIDENCE_NOT_AVAILABLE"
                            )
                        }
                    } else {
                        val available = evidence.copy(
                            stage = DriverIncidentEvidenceStage.AvailableForReview
                        )
                        updateEvidenceState(
                            currentAuthority,
                            requestGeneration,
                            current,
                            available
                        ) {
                            it.copy(
                                evidenceLifecycleStatus = projection.lifecycleStatus,
                                evidenceReviewedVersion = null,
                                evidenceError = null
                            )
                        }
                    }
                }

                is DriverIncidentEvidenceResult.Rejected -> evidenceFailure(
                    result.code ?: "EVIDENCE_STATUS_REJECTED"
                )

                DriverIncidentEvidenceResult.NotFound -> evidenceFailure("EVIDENCE_NOT_FOUND")

                DriverIncidentEvidenceResult.PermissionDenied -> evidenceFailure(
                    "DOCUMENT_READ_REQUIRED"
                )

                DriverIncidentEvidenceResult.ContextInvalidated,
                DriverIncidentEvidenceResult.SessionInvalidated -> evidenceFailure(
                    "AUTHORITY_CHANGED"
                )

                DriverIncidentEvidenceResult.UnknownOutcome,
                DriverIncidentEvidenceResult.Unavailable,
                is DriverIncidentEvidenceResult.Uploaded -> evidenceFailure(
                    "EVIDENCE_STATUS_UNAVAILABLE"
                )
            }
        }
    }

    /** Rechecks both the delivery attempt and AVAILABLE status before presenting an attach decision. */
    fun reviewEvidenceLink() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val deliveryId = current.deliveryId ?: return
        val attemptId = current.attemptId ?: return
        val incidentId = current.summary?.incidentId ?: return
        val evidence = current.evidence ?: return
        val evidenceId = evidence.evidenceId ?: return
        if (!current.canReviewEvidenceLink) return
        val requestGeneration = generation
        mutableState.update {
            it.copy(evidenceBusy = true, evidenceError = null, evidenceReviewedVersion = null)
        }
        viewModelScope.launch {
            val freshResult = safeCurrentDelivery(deliveryId, currentAuthority)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val fresh = (freshResult as? CurrentDeliveryResult.Loaded)?.delivery
            if (fresh == null || !matchesTarget(fresh, attemptId)) {
                evidenceFailure(
                    if (fresh ==
                        null
                    ) {
                        "DELIVERY_REFRESH_REQUIRED"
                    } else {
                        "DELIVERY_ATTEMPT_CHANGED"
                    }
                )
                mutableState.update {
                    it.copy(status = DriverIncidentUiStatus.Stale, delivery = fresh)
                }
                return@launch
            }
            val statusResult = try {
                gateway.evidenceStatus(evidenceId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverIncidentEvidenceResult.Unavailable
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val projection = (statusResult as? DriverIncidentEvidenceResult.Current)?.evidence
            if (projection == null || !matchesEvidence(projection, incidentId, evidence) ||
                projection.lifecycleStatus != "AVAILABLE"
            ) {
                evidenceFailure("EVIDENCE_REVIEW_REQUIRED")
                return@launch
            }
            if (evidence.stage == DriverIncidentEvidenceStage.AttachUnknownOutcome &&
                evidence.attachExpectedVersion != fresh.version
            ) {
                evidenceFailure("ATTACH_REVIEW_VERSION_CHANGED")
                mutableState.update {
                    it.copy(delivery = fresh, evidenceLifecycleStatus = projection.lifecycleStatus)
                }
                return@launch
            }
            val available = if (evidence.stage ==
                DriverIncidentEvidenceStage.AttachUnknownOutcome
            ) {
                evidence
            } else {
                evidence.copy(stage = DriverIncidentEvidenceStage.AvailableForReview)
            }
            updateEvidenceState(currentAuthority, requestGeneration, current, available) {
                it.copy(
                    delivery = fresh,
                    evidenceLifecycleStatus = projection.lifecycleStatus,
                    evidenceReviewedVersion = fresh.version,
                    evidenceError = null
                )
            }
        }
    }

    /** Links only after a fresh review; an uncertain replay uses its original key,
     version and body. */
    fun attachEvidence() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val summary = current.summary ?: return
        val evidence = current.evidence ?: return
        val evidenceId = evidence.evidenceId ?: return
        if (!current.canAttachEvidence || !currentAuthority.canStart) return
        val requestGeneration = generation
        mutableState.update { it.copy(evidenceBusy = true, evidenceError = null) }
        viewModelScope.launch {
            val deliveryId =
                current.deliveryId
                    ?: run {
                        evidenceFailure("INCIDENT_CONTEXT_MISSING")
                        return@launch
                    }
            val attemptId =
                current.attemptId
                    ?: run {
                        evidenceFailure("INCIDENT_CONTEXT_MISSING")
                        return@launch
                    }
            val freshResult = safeCurrentDelivery(deliveryId, currentAuthority)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val fresh = (freshResult as? CurrentDeliveryResult.Loaded)?.delivery
            if (fresh == null || !matchesTarget(fresh, attemptId)) {
                evidenceFailure("DELIVERY_ATTEMPT_CHANGED")
                mutableState.update {
                    it.copy(status = DriverIncidentUiStatus.Stale, delivery = fresh)
                }
                return@launch
            }
            val statusResult = try {
                gateway.evidenceStatus(evidenceId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverIncidentEvidenceResult.Unavailable
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val currentEvidence = (statusResult as? DriverIncidentEvidenceResult.Current)?.evidence
            if (currentEvidence == null ||
                !matchesEvidence(currentEvidence, summary.incidentId, evidence) ||
                currentEvidence.lifecycleStatus != "AVAILABLE"
            ) {
                evidenceFailure("EVIDENCE_REVIEW_REQUIRED")
                return@launch
            }
            val command = if (evidence.stage == DriverIncidentEvidenceStage.AttachUnknownOutcome) {
                val attachExpectedVersion = evidence.attachExpectedVersion
                if (attachExpectedVersion == null || fresh.version != attachExpectedVersion) {
                    evidenceFailure("ATTACH_REVIEW_VERSION_CHANGED")
                    return@launch
                }
                DriverIncidentEvidenceAttachCommand(
                    deliveryId,
                    attemptId,
                    summary.incidentId,
                    evidenceId,
                    attachExpectedVersion,
                    evidence.attachIdempotencyKey ?: return@launch,
                    evidence.attachBody ?: return@launch
                )
            } else {
                val version = current.evidenceReviewedVersion ?: return@launch
                if (fresh.version != version) {
                    mutableState.update {
                        it.copy(
                            delivery = fresh,
                            evidenceBusy = false,
                            evidenceReviewedVersion = null,
                            evidenceError = "ATTACH_REVIEW_VERSION_CHANGED"
                        )
                    }
                    return@launch
                }
                val key = keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
                if (key == null) {
                    evidenceFailure("IDEMPOTENCY_KEY_INVALID")
                    return@launch
                }
                val body = requestBodyCodec.driverIncidentEvidenceAttachBody(evidenceId)
                DriverIncidentEvidenceAttachCommand(
                    deliveryId,
                    attemptId,
                    summary.incidentId,
                    evidenceId,
                    fresh.version,
                    key,
                    body
                )
            }
            val attaching = evidence.copy(
                stage = DriverIncidentEvidenceStage.AttachPending,
                attachIdempotencyKey = command.idempotencyKey,
                attachExpectedVersion = command.expectedVersion,
                attachBody = command.frozenBody
            )
            val metadata = loadRecordedMetadata(currentAuthority, current) ?: run {
                evidenceFailure("INCIDENT_EVIDENCE_DRAFT_UNAVAILABLE")
                return@launch
            }
            val intentMetadata = metadata.copy(evidence = attaching)
            if (safeUpdateRecordedEvidence(intentMetadata) != DriverIncidentMetadataWrite.Saved) {
                evidenceFailure("INCIDENT_EVIDENCE_INTENT_NOT_SAVED")
                return@launch
            }
            mutableState.update { it.copy(delivery = fresh, evidence = attaching) }
            val result = try {
                gateway.attachEvidence(command, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverIncidentResult.UnknownOutcome
            }
            if (!isCurrent(requestGeneration, currentAuthority)) {
                safeUpdateRecordedEvidence(
                    intentMetadata.copy(
                        evidence = attaching.copy(
                            stage = DriverIncidentEvidenceStage.AttachUnknownOutcome
                        )
                    )
                )
                return@launch
            }
            when (result) {
                is DriverIncidentResult.Recorded -> {
                    if (result.summary.incidentId != summary.incidentId ||
                        result.summary.deliveryId != deliveryId ||
                        result.summary.attemptId != attemptId ||
                        evidenceId !in result.summary.evidenceObjectIds
                    ) {
                        persistAttachUnknown(
                            intentMetadata,
                            attaching,
                            "INCIDENT_EVIDENCE_LINK_RESPONSE_MISMATCH"
                        )
                    } else {
                        val linked = attaching.copy(stage = DriverIncidentEvidenceStage.Linked)
                        val linkedMetadata = intentMetadata.copy(
                            evidence = linked,
                            deliveryVersion = result.summary.deliveryVersion
                        )
                        if (safeUpdateRecordedEvidence(linkedMetadata) ==
                            DriverIncidentMetadataWrite.Saved
                        ) {
                            safeClearCandidate(linkedMetadata)
                            mutableState.update {
                                it.copy(
                                    summary = result.summary,
                                    evidence = linked,
                                    evidenceLifecycleStatus = "AVAILABLE",
                                    evidenceBusy = false,
                                    evidenceError = null,
                                    evidenceReviewedVersion = null,
                                    delivery = fresh.copy(version = result.summary.deliveryVersion)
                                )
                            }
                        } else {
                            evidenceFailure("INCIDENT_EVIDENCE_LINK_STATE_NOT_SAVED")
                        }
                    }
                }

                is DriverIncidentResult.StaleVersion,
                is DriverIncidentResult.Rejected -> {
                    val reviewAgain = attaching.copy(
                        stage = DriverIncidentEvidenceStage.AvailableForReview,
                        attachIdempotencyKey = null,
                        attachExpectedVersion = null,
                        attachBody = null
                    )
                    val saved =
                        safeUpdateRecordedEvidence(intentMetadata.copy(evidence = reviewAgain))
                    mutableState.update {
                        it.copy(
                            evidence = if (saved ==
                                DriverIncidentMetadataWrite.Saved
                            ) {
                                reviewAgain
                            } else {
                                attaching
                            },
                            evidenceBusy = false,
                            evidenceReviewedVersion = null,
                            evidenceError = if (result is DriverIncidentResult.StaleVersion) {
                                "STALE_VERSION"
                            } else {
                                (result as? DriverIncidentResult.Rejected)?.code
                                    ?: "EVIDENCE_LINK_REJECTED"
                            }
                        )
                    }
                }

                else -> persistAttachUnknown(intentMetadata, attaching, "EVIDENCE_LINK_UNKNOWN")
            }
        }
    }

    /** Posts only after fresh review and durable, immutable key/body persistence. */
    fun submitIncident() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val deliveryId = current.deliveryId ?: return
        val attemptId = current.attemptId ?: return
        val reviewedVersion = current.reviewedVersion ?: return
        if (!current.canSubmit || pendingCommand != null || !currentAuthority.canStart) return
        val capturedReason = current.reason.trim()
        val capturedDescription = current.description.trim()
        val capturedPlace = current.place.trim()
        val capturedType = current.type ?: return
        val requestGeneration = generation
        mutableState.update {
            it.copy(status = DriverIncidentUiStatus.CheckingCurrent, rejectionCode = null)
        }
        viewModelScope.launch {
            val detail = safeCurrentDelivery(deliveryId, currentAuthority)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val fresh = (detail as? CurrentDeliveryResult.Loaded)?.delivery
            if (fresh == null) {
                mutableState.update { it.copy(status = detail.toStatus(), reviewedVersion = null) }
                return@launch
            }
            if (!matchesTarget(fresh, attemptId)) {
                mutableState.update {
                    it.copy(
                        delivery = fresh,
                        status = DriverIncidentUiStatus.Stale,
                        reviewedVersion = null
                    )
                }
                return@launch
            }
            if (fresh.version != reviewedVersion) {
                mutableState.update {
                    it.copy(
                        delivery = fresh,
                        status = DriverIncidentUiStatus.NeedsReview,
                        reviewedVersion = null
                    )
                }
                return@launch
            }
            val key = keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
            if (key == null) {
                mutableState.update {
                    it.copy(
                        status = DriverIncidentUiStatus.Rejected,
                        rejectionCode = "IDEMPOTENCY_KEY_INVALID"
                    )
                }
                return@launch
            }
            val command = DriverIncidentCommand(
                deliveryId, attemptId, fresh.version, key, capturedReason, capturedDescription,
                capturedPlace,
                requestBodyCodec.driverIncidentBody(
                    capturedType,
                    capturedReason,
                    capturedDescription,
                    capturedPlace
                ),
                type = capturedType
            )
            val intent = DriverIncidentMetadata(
                currentAuthority.scopeIdentity, deliveryId, attemptId, fresh.version,
                capturedReason, capturedDescription, capturedPlace,
                DriverIncidentRecordStatus.Pending, command,
                current.draftId ?: UUID.randomUUID().toString(), evidence = current.evidence,
                type = capturedType
            )
            pendingCommand = command
            pendingPersisted = false
            mutableState.update {
                it.copy(status = DriverIncidentUiStatus.PersistingIntent, command = command)
            }
            if (safePersist(intent) != DriverIncidentMetadataWrite.Saved) {
                pendingCommand = null
                mutableState.update {
                    it.copy(status = DriverIncidentUiStatus.PersistenceUnavailable, command = null)
                }
                return@launch
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            pendingPersisted = true
            mutableState.update { it.copy(status = DriverIncidentUiStatus.Pending) }
            execute(command, intent, requestGeneration, currentAuthority)
        }
    }

    /** Replays the exact persisted command only after an explicit user action and fresh assignment read. */
    fun retryUnknownOutcome() {
        val currentAuthority = authority ?: return
        val command = pendingCommand ?: return
        val current = mutableState.value
        if (!pendingPersisted || current.status != DriverIncidentUiStatus.UnknownOutcome ||
            command.deliveryId != current.deliveryId || command.attemptId != current.attemptId
        ) {
            return
        }
        val requestGeneration = generation
        mutableState.update { it.copy(status = DriverIncidentUiStatus.CheckingCurrent) }
        viewModelScope.launch {
            val detail = safeCurrentDelivery(command.deliveryId, currentAuthority)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val fresh = (detail as? CurrentDeliveryResult.Loaded)?.delivery
            if (fresh == null) {
                mutableState.update { it.copy(status = DriverIncidentUiStatus.UnknownOutcome) }
                return@launch
            }
            // A retry may follow a committed terminal fact. The API validates the exact attempt and actor.
            mutableState.update {
                it.copy(delivery = fresh, status = DriverIncidentUiStatus.Pending)
            }
            val metadata = DriverIncidentMetadata(
                currentAuthority.scopeIdentity, command.deliveryId, command.attemptId,
                command.expectedVersion, command.reason, command.description, command.place,
                DriverIncidentRecordStatus.Pending, command,
                current.draftId ?: UUID.randomUUID().toString(), evidence = current.evidence,
                type = command.type
            )
            execute(command, metadata, requestGeneration, currentAuthority)
        }
    }

    private fun editDraft(
        change: DriverDeliveryIncidentUiState.() -> DriverDeliveryIncidentUiState
    ) {
        if (pendingCommand != null || mutableState.value.status in FROZEN_STATUSES ||
            mutableState.value.status == DriverIncidentUiStatus.Recorded
        ) {
            return
        }
        mutableState.update {
            change(it).copy(
                status = DriverIncidentUiStatus.EditingDraft,
                draftSaved = false,
                savedDraftVersion = null,
                reviewedVersion = null,
                rejectionCode = null
            )
        }
    }

    private suspend fun loadCurrent(
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority,
        deliveryId: String,
        review: Boolean
    ) {
        val result = safeCurrentDelivery(deliveryId, currentAuthority)
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val fresh = (result as? CurrentDeliveryResult.Loaded)?.delivery
        if (fresh == null) {
            mutableState.update {
                it.copy(
                    delivery = null,
                    status = if (pendingCommand != null) {
                        DriverIncidentUiStatus.UnknownOutcome
                    } else {
                        result.toStatus()
                    },
                    reviewedVersion = null
                )
            }
            return
        }
        val attemptId = mutableState.value.attemptId
        if (attemptId == null || (pendingCommand == null && !matchesTarget(fresh, attemptId))) {
            mutableState.update {
                it.copy(
                    delivery = fresh,
                    status = DriverIncidentUiStatus.Stale,
                    reviewedVersion = null
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                delivery = fresh,
                status = when {
                    pendingCommand != null -> DriverIncidentUiStatus.UnknownOutcome
                    it.summary != null -> DriverIncidentUiStatus.Recorded
                    review && it.draftSaved -> DriverIncidentUiStatus.ReadyForReview
                    it.draftSaved -> DriverIncidentUiStatus.NeedsReview
                    else -> DriverIncidentUiStatus.EditingDraft
                },
                reviewedVersion = if (review && it.draftSaved &&
                    pendingCommand == null
                ) {
                    fresh.version
                } else {
                    null
                }
            )
        }
    }

    private fun matchesTarget(delivery: DriverIncidentCurrentDelivery, attemptId: String): Boolean =
        delivery.deliveryId == mutableState.value.deliveryId &&
            (
                delivery.activeAttemptId?.let { it == attemptId }
                    ?: (confirmedTerminalOutcome && delivery.version >= targetVersion)
                )

    private suspend fun loadRecordedMetadata(
        currentAuthority: DriverDeliveryAuthority,
        current: DriverDeliveryIncidentUiState
    ): DriverIncidentMetadata? {
        val deliveryId = current.deliveryId ?: return null
        val attemptId = current.attemptId ?: return null
        val draftId = current.draftId ?: return null
        val incidentId = current.summary?.incidentId ?: return null
        val stored =
            safeLoad(currentAuthority.scopeIdentity) as? DriverIncidentMetadataRead.Available
                ?: return null
        return stored.metadata?.takeIf {
            it.scope == currentAuthority.scopeIdentity && it.deliveryId == deliveryId &&
                it.attemptId == attemptId && it.draftId == draftId &&
                it.status == DriverIncidentRecordStatus.RecordedWithEvidence &&
                it.command == null && it.incidentId == incidentId && it.evidence != null
        }
    }

    private suspend fun updateEvidenceState(
        currentAuthority: DriverDeliveryAuthority,
        requestGeneration: Long,
        current: DriverDeliveryIncidentUiState,
        evidence: DriverIncidentEvidenceDraft,
        update: (DriverDeliveryIncidentUiState) -> DriverDeliveryIncidentUiState
    ) {
        val metadata = loadRecordedMetadata(currentAuthority, current) ?: run {
            evidenceFailure("INCIDENT_EVIDENCE_DRAFT_UNAVAILABLE")
            return
        }
        if (safeUpdateRecordedEvidence(metadata.copy(evidence = evidence)) !=
            DriverIncidentMetadataWrite.Saved
        ) {
            evidenceFailure("INCIDENT_EVIDENCE_STATE_NOT_SAVED")
            return
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        mutableState.update { update(it).copy(evidence = evidence, evidenceBusy = false) }
    }

    private suspend fun persistUploadUnknown(
        metadata: DriverIncidentMetadata,
        pending: DriverIncidentEvidenceDraft,
        error: String
    ) {
        val unknown = pending.copy(stage = DriverIncidentEvidenceStage.UploadUnknownOutcome)
        val saved = safeUpdateRecordedEvidence(metadata.copy(evidence = unknown))
        mutableState.update {
            it.copy(
                evidence = if (saved == DriverIncidentMetadataWrite.Saved) unknown else pending,
                evidenceBusy = false,
                evidenceError = error,
                evidenceReviewedVersion = null
            )
        }
    }

    private suspend fun persistAttachUnknown(
        metadata: DriverIncidentMetadata,
        pending: DriverIncidentEvidenceDraft,
        error: String
    ) {
        val unknown = pending.copy(stage = DriverIncidentEvidenceStage.AttachUnknownOutcome)
        val saved = safeUpdateRecordedEvidence(metadata.copy(evidence = unknown))
        mutableState.update {
            it.copy(
                evidence = if (saved == DriverIncidentMetadataWrite.Saved) unknown else pending,
                evidenceBusy = false,
                evidenceError = error,
                evidenceReviewedVersion = null
            )
        }
    }

    private fun evidenceFailure(error: String) {
        mutableState.update {
            it.copy(evidenceBusy = false, evidenceError = error, evidenceReviewedVersion = null)
        }
    }

    private fun matchesEvidence(
        projection: DriverIncidentEvidenceProjection,
        incidentId: String,
        candidate: DriverIncidentEvidenceDraft
    ): Boolean = projection.subjectType == "DELIVERY_INCIDENT" &&
        projection.subjectId == incidentId &&
        (candidate.evidenceId == null || projection.evidenceId == candidate.evidenceId) &&
        projection.contentType == candidate.contentType &&
        projection.byteSize == candidate.byteSize &&
        projection.checksumSha256?.let { it == candidate.checksumSha256 } != false

    private fun DriverIncidentEvidenceStage.lifecycleLabel(): String? = when (this) {
        DriverIncidentEvidenceStage.AwaitingAvailability -> "PROCESSING"

        DriverIncidentEvidenceStage.AvailableForReview,
        DriverIncidentEvidenceStage.AttachPending,
        DriverIncidentEvidenceStage.AttachUnknownOutcome,
        DriverIncidentEvidenceStage.Linked -> "AVAILABLE"

        else -> null
    }

    private fun DriverIncidentMetadata.toSummaryOrNull(): DriverIncidentSummary? {
        val id = incidentId ?: return null
        val actor = recordedByMembershipId ?: return null
        val time = recordedAt ?: return null
        val version = deliveryVersion ?: return null
        val linkedEvidence = evidence?.takeIf { it.stage == DriverIncidentEvidenceStage.Linked }
            ?.evidenceId?.let(::listOf).orEmpty()
        return DriverIncidentSummary(
            id, deliveryId, attemptId, reason, description, place, actor, time,
            linkedEvidence, version, replayed = false, type = type, severity = severity,
            operationalExceptionId = operationalExceptionId
        )
    }

    private suspend fun execute(
        command: DriverIncidentCommand,
        metadata: DriverIncidentMetadata,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val result = try {
            gateway.recordIncident(command, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverIncidentResult.UnknownOutcome
        }
        if (!isCurrent(requestGeneration, currentAuthority)) {
            safePersist(metadata.copy(status = DriverIncidentRecordStatus.UnknownOutcome))
            return
        }
        when (result) {
            is DriverIncidentResult.Recorded -> {
                val completed = metadata.evidence?.let {
                    metadata.copy(
                        status = DriverIncidentRecordStatus.RecordedWithEvidence,
                        command = null,
                        incidentId = result.summary.incidentId,
                        recordedAt = result.summary.recordedAt,
                        recordedByMembershipId = result.summary.recordedByMembershipId,
                        deliveryVersion = result.summary.deliveryVersion,
                        type = result.summary.type,
                        severity = result.summary.severity,
                        operationalExceptionId = result.summary.operationalExceptionId
                    )
                }
                val persisted = if (completed != null) {
                    safePersistRecorded(completed) == DriverIncidentMetadataWrite.Saved
                } else {
                    safeClear(command, currentAuthority.scopeIdentity) ==
                        DriverIncidentMetadataWrite.Saved
                }
                if (persisted) {
                    pendingCommand = null
                    pendingPersisted = false
                } else {
                    safePersist(metadata.copy(status = DriverIncidentRecordStatus.UnknownOutcome))
                }
                mutableState.update {
                    it.copy(
                        status = if (persisted) {
                            DriverIncidentUiStatus.Recorded
                        } else {
                            DriverIncidentUiStatus.UnknownOutcome
                        },
                        draftId = completed?.draftId ?: it.draftId,
                        evidence = completed?.evidence ?: it.evidence,
                        summary = result.summary,
                        command = if (persisted) null else command,
                        persistenceCleanupPending = !persisted,
                        rejectionCode = null
                    )
                }
            }

            is DriverIncidentResult.Rejected -> finishKnownFailure(
                command,
                metadata,
                requestGeneration,
                currentAuthority,
                DriverIncidentUiStatus.Rejected,
                result.code
            )

            DriverIncidentResult.StaleVersion -> finishKnownFailure(
                command,
                metadata,
                requestGeneration,
                currentAuthority,
                DriverIncidentUiStatus.Stale,
                "STALE_VERSION"
            )

            DriverIncidentResult.NotFound -> finishKnownFailure(
                command,
                metadata,
                requestGeneration,
                currentAuthority,
                DriverIncidentUiStatus.NotFound,
                "DELIVERY_ATTEMPT_NOT_FOUND"
            )

            DriverIncidentResult.UnknownOutcome,
            DriverIncidentResult.Unavailable -> {
                safePersist(metadata.copy(status = DriverIncidentRecordStatus.UnknownOutcome))
                mutableState.update {
                    it.copy(status = DriverIncidentUiStatus.UnknownOutcome, command = command)
                }
            }

            DriverIncidentResult.PermissionDenied -> finishKnownFailure(
                command,
                metadata,
                requestGeneration,
                currentAuthority,
                DriverIncidentUiStatus.PermissionDenied,
                "PERMISSION_DENIED"
            )

            DriverIncidentResult.ContextInvalidated,
            DriverIncidentResult.SessionInvalidated -> {
                safePersist(metadata.copy(status = DriverIncidentRecordStatus.UnknownOutcome))
                mutableState.update {
                    it.copy(status = DriverIncidentUiStatus.UnknownOutcome, command = command)
                }
            }
        }
    }

    private suspend fun finishKnownFailure(
        command: DriverIncidentCommand,
        metadata: DriverIncidentMetadata,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority,
        status: DriverIncidentUiStatus,
        code: String?
    ) {
        val cleared =
            safeClear(command, currentAuthority.scopeIdentity) == DriverIncidentMetadataWrite.Saved
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared) {
            pendingCommand = null
            pendingPersisted = false
            val draft = metadata.copy(status = DriverIncidentRecordStatus.Draft, command = null)
            val saved = safeSaveDraft(draft) == DriverIncidentMetadataWrite.Saved
            mutableState.update {
                it.copy(
                    status = if (saved) status else DriverIncidentUiStatus.PersistenceUnavailable,
                    command = null,
                    draftSaved = saved,
                    rejectionCode = code,
                    reviewedVersion = null
                )
            }
        } else {
            safePersist(metadata.copy(status = DriverIncidentRecordStatus.UnknownOutcome))
            mutableState.update {
                it.copy(status = DriverIncidentUiStatus.UnknownOutcome, command = command)
            }
        }
    }

    private fun isCurrent(
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ): Boolean = generation == requestGeneration && authority == currentAuthority

    private suspend fun safeLoad(scope: DriverAttemptScopeIdentity): DriverIncidentMetadataRead =
        try {
            metadataStore?.load(scope) ?: DriverIncidentMetadataRead.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverIncidentMetadataRead.Unavailable
        }

    private suspend fun safeSaveDraft(
        metadata: DriverIncidentMetadata
    ): DriverIncidentMetadataWrite = try {
        metadataStore?.saveDraft(metadata) ?: DriverIncidentMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverIncidentMetadataWrite.Unavailable
    }

    private suspend fun safePersist(metadata: DriverIncidentMetadata): DriverIncidentMetadataWrite =
        try {
            metadataStore?.persistIntent(metadata) ?: DriverIncidentMetadataWrite.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverIncidentMetadataWrite.Unavailable
        }

    private suspend fun safePersistRecorded(
        metadata: DriverIncidentMetadata
    ): DriverIncidentMetadataWrite = try {
        metadataStore?.persistRecorded(metadata) ?: DriverIncidentMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverIncidentMetadataWrite.Unavailable
    }

    private suspend fun safeUpdateRecordedEvidence(
        metadata: DriverIncidentMetadata
    ): DriverIncidentMetadataWrite = try {
        metadataStore?.updateRecordedEvidence(metadata) ?: DriverIncidentMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverIncidentMetadataWrite.Unavailable
    }

    private suspend fun safeLoadCandidate(
        metadata: DriverIncidentMetadata
    ): DriverProofFileCandidate? = try {
        metadataStore?.loadCandidate(metadata)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun safeClearCandidate(metadata: DriverIncidentMetadata): Boolean = try {
        metadataStore?.clearCandidate(metadata) ?: false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun safeClear(
        command: DriverIncidentCommand,
        scope: DriverAttemptScopeIdentity
    ): DriverIncidentMetadataWrite = try {
        metadataStore?.clearIntent(scope, command.deliveryId, command.idempotencyKey)
            ?: DriverIncidentMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverIncidentMetadataWrite.Unavailable
    }

    private suspend fun safeCurrentDelivery(
        deliveryId: String,
        currentAuthority: DriverDeliveryAuthority
    ): CurrentDeliveryResult = try {
        gateway.currentDelivery(deliveryId, currentAuthority)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CurrentDeliveryResult.Unavailable
    }

    private fun CurrentDeliveryResult.toStatus(): DriverIncidentUiStatus = when (this) {
        CurrentDeliveryResult.NotFound -> DriverIncidentUiStatus.NotFound

        CurrentDeliveryResult.PermissionDenied -> DriverIncidentUiStatus.PermissionDenied

        CurrentDeliveryResult.ContextInvalidated,
        CurrentDeliveryResult.SessionInvalidated,
        CurrentDeliveryResult.Unavailable -> DriverIncidentUiStatus.Unavailable

        is CurrentDeliveryResult.Loaded -> DriverIncidentUiStatus.NeedsReview
    }

    private companion object {
        val FROZEN_STATUSES = setOf(
            DriverIncidentUiStatus.CheckingCurrent,
            DriverIncidentUiStatus.SavingDraft,
            DriverIncidentUiStatus.PersistingIntent,
            DriverIncidentUiStatus.Pending,
            DriverIncidentUiStatus.UnknownOutcome,
            DriverIncidentUiStatus.PersistenceUnavailable
        )
    }
}
