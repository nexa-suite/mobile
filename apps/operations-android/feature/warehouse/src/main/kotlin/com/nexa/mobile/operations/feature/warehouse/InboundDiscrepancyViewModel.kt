package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyValidationError as DiscrepancyValidationError
import com.nexa.mobile.operations.feature.warehouse.application.InboundDiscrepancyDraftStore
import com.nexa.mobile.operations.feature.warehouse.application.InboundDiscrepancyEvidenceArtifactStore
import com.nexa.mobile.operations.feature.warehouse.application.InboundDiscrepancyGateway
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyArtifactIdentity
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyArtifactRead
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyArtifactWrite
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyAuthority
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyCreateCommand
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyDraft
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyDraftRead
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyDraftWrite
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyEvidenceCandidate
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyEvidenceStatusResult
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyKind
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyMutationResult
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyPendingAction
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancySelectionContext
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyStartContext
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancySubmitCommand
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyUploadCommand
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates draft persistence and explicit, server-authoritative receiving commands. */
class InboundDiscrepancyViewModel(
    private val gateway: InboundDiscrepancyGateway,
    private val drafts: InboundDiscrepancyDraftStore,
    private val artifacts: InboundDiscrepancyEvidenceArtifactStore,
    private val newDraftId: () -> String = { UUID.randomUUID().toString() },
    private val newCommandKey: () -> String = { UUID.randomUUID().toString() },
    private val deviceClockMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private val mutableState = MutableStateFlow(InboundDiscrepancyUiState())
    val state = mutableState.asStateFlow()

    private var authority: InboundDiscrepancyAuthority? = null
    private var generation = 0L
    private val storeMutex = Mutex()
    private val commandMutex = Mutex()

    fun activate(
        currentAuthority: InboundDiscrepancyAuthority,
        startContext: InboundDiscrepancyStartContext? = null
    ) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        mutableState.value = InboundDiscrepancyUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            active = true,
            metadata = InboundDiscrepancyMetadataStatus.Loading,
            draftId = newDraftId()
        )
        viewModelScope.launch {
            val stored = storeMutex.withLock {
                safeDraftRead { drafts.load(currentAuthority.scope) }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (stored) {
                InboundDiscrepancyDraftRead.Unavailable -> mutableState.update {
                    it.copy(metadata = InboundDiscrepancyMetadataStatus.Unavailable)
                }

                is InboundDiscrepancyDraftRead.Available -> {
                    val draft = stored.draft
                    if (draft != null && !draft.isValidStored()) {
                        mutableState.update {
                            it.copy(metadata = InboundDiscrepancyMetadataStatus.Unavailable)
                        }
                        return@launch
                    }
                    val next = draft?.toUiState(currentAuthority.authorityEpoch)
                        ?: InboundDiscrepancyUiState(
                            authorityEpoch = currentAuthority.authorityEpoch,
                            active = true,
                            metadata = InboundDiscrepancyMetadataStatus.Available,
                            draftId = newDraftId()
                        ).withStartContext(startContext)
                    mutableState.value = next.copy(
                        metadata = InboundDiscrepancyMetadataStatus.Available,
                        active = true,
                        authorityEpoch = currentAuthority.authorityEpoch
                    )
                    draft?.let { storedDraft ->
                        storedDraft.caseId?.let { caseId ->
                            reloadStagedEvidence(
                                InboundDiscrepancySelectionContext(
                                    currentAuthority.authorityEpoch,
                                    currentAuthority.scope,
                                    storedDraft.warehouseId,
                                    caseId
                                ),
                                requestGeneration,
                                currentAuthority
                            )
                        }
                    }
                }
            }
        }
    }

    fun deactivate() {
        generation++
        authority = null
        mutableState.value = InboundDiscrepancyUiState()
    }

    fun warehouseChanged(value: String) = edit {
        it.copy(warehouseId = value.take(MAX_REFERENCE_LENGTH))
    }
    fun expectedSkuChanged(value: String) =
        edit { it.copy(expectedSkuId = value.take(MAX_REFERENCE_LENGTH)) }
    fun observedSkuChanged(value: String) =
        edit { it.copy(observedSkuId = value.take(MAX_REFERENCE_LENGTH)) }
    fun expectedBatchChanged(value: String) =
        edit { it.copy(expectedBatchReference = value.take(MAX_REFERENCE_LENGTH)) }
    fun observedBatchChanged(value: String) =
        edit { it.copy(observedBatchReference = value.take(MAX_REFERENCE_LENGTH)) }
    fun kindChanged(value: InboundDiscrepancyKind) = edit { it.copy(kind = value) }
    fun reasonDetailsChanged(value: String) =
        edit { it.copy(reasonDetails = value.take(MAX_NOTE_LENGTH)) }
    fun expectedQuantityChanged(value: String) =
        edit { it.copy(expectedQuantityText = value.take(MAX_QUANTITY_LENGTH)) }
    fun observedQuantityChanged(value: String) =
        edit { it.copy(observedQuantityText = value.take(MAX_QUANTITY_LENGTH)) }
    fun unitChanged(value: String) = edit { it.copy(unit = value.take(32)) }
    fun observationNotesChanged(value: String) =
        edit { it.copy(observationNotes = value.take(MAX_NOTE_LENGTH)) }

    fun saveDraft() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!current.canSave || current.caseId != null) return
        val validation = validate(current, forSubmission = false)
        if (validation != null) {
            mutableState.update { it.copy(validationError = validation, notice = null) }
            return
        }
        persistDraft(
            currentAuthority,
            current.toDraft(current.capturedAtDeviceMillis ?: deviceClockMillis())
        )
    }

    fun createCase() {
        val current = mutableState.value
        if (!current.canCreateCase || current.pendingAction != null) return
        val validation = validate(current, forSubmission = true)
        if (validation != null) {
            mutableState.update { it.copy(validationError = validation, notice = null) }
            return
        }
        val frozen = current.toDraft(
            current.capturedAtDeviceMillis ?: deviceClockMillis()
        ).let { draft ->
            if (draft.createIdempotencyKey != null && draft.createBody != null) {
                draft
            } else {
                draft.copy(
                    createIdempotencyKey = newCommandKey(),
                    createBody = current.createBody(),
                    pendingAction = InboundDiscrepancyPendingAction.CreateCase
                )
            }
        }
        performCreate(frozen, retry = false)
    }

    fun retryPendingAction() {
        val current = mutableState.value
        val saved = current.toDraft(current.capturedAtDeviceMillis ?: deviceClockMillis())
        when (saved.pendingAction) {
            InboundDiscrepancyPendingAction.CreateCase -> performCreate(saved, retry = true)
            InboundDiscrepancyPendingAction.UploadEvidence -> uploadEvidence(retry = true)
            InboundDiscrepancyPendingAction.SubmitForReview -> submitForReview(retry = true)
            null -> Unit
        }
    }

    fun selectionContextForCurrentCase(): InboundDiscrepancySelectionContext? {
        val current = mutableState.value
        val currentAuthority = authority ?: return null
        val caseId = current.caseId ?: return null
        if (!current.canSelectEvidence) return null
        return InboundDiscrepancySelectionContext(
            currentAuthority.authorityEpoch,
            currentAuthority.scope,
            current.warehouseId,
            caseId
        )
    }

    /* Re-adopts only artifact staged for exact scope,
     warehouse and case captured before picker launch. */

    /** Retains a returned native selection under its original identity for later authorized review. */
    suspend fun stageReturnedSelection(
        context: InboundDiscrepancySelectionContext,
        candidate: InboundDiscrepancyEvidenceCandidate
    ): InboundDiscrepancyArtifactWrite = try {
        artifacts.stageReturnedSelection(context, candidate)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InboundDiscrepancyArtifactWrite.Unavailable
    }

    suspend fun reloadStagedEvidence(context: InboundDiscrepancySelectionContext): Boolean {
        val currentAuthority = authority ?: return false
        return reloadStagedEvidence(context, generation, currentAuthority)
    }

    private suspend fun reloadStagedEvidence(
        context: InboundDiscrepancySelectionContext,
        requestGeneration: Long,
        currentAuthority: InboundDiscrepancyAuthority
    ): Boolean {
        if (!isCurrent(requestGeneration, currentAuthority) ||
            context.scope != currentAuthority.scope
        ) {
            return false
        }
        val current = mutableState.value
        if (current.metadata != InboundDiscrepancyMetadataStatus.Available ||
            current.warehouseId != context.warehouseId || current.caseId != context.caseId
        ) {
            return false
        }
        val identity =
            InboundDiscrepancyArtifactIdentity(context.scope, context.warehouseId, context.caseId)
        val result = try {
            artifacts.load(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            InboundDiscrepancyArtifactRead.Unavailable
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return false
        val artifact =
            (result as? InboundDiscrepancyArtifactRead.Available)?.artifact ?: return false
        if (mutableState.value.caseId != context.caseId ||
            mutableState.value.warehouseId != context.warehouseId
        ) {
            return false
        }
        mutableState.update { it.copy(artifact = artifact) }
        return true
    }

    fun uploadEvidence(retry: Boolean = false) {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!current.active || current.caseStatus != "PENDING_EVIDENCE" ||
            current.evidenceId != null ||
            current.artifact == null || current.isSaving ||
            (!retry && current.pendingAction != null) ||
            (retry && current.pendingAction != InboundDiscrepancyPendingAction.UploadEvidence)
        ) {
            return
        }
        val caseId = current.caseId ?: return
        val key =
            current.toDraft(current.capturedAtDeviceMillis ?: deviceClockMillis()).evidenceUploadKey
                ?: newCommandKey()
        val frozen = current.toDraft(current.capturedAtDeviceMillis ?: deviceClockMillis()).copy(
            evidenceUploadKey = key,
            pendingAction = InboundDiscrepancyPendingAction.UploadEvidence
        )
        val currentGeneration = generation
        mutableState.update {
            it.copy(
                isSaving = true,
                flow = InboundDiscrepancyFlowStatus.UploadingEvidence,
                notice = null
            )
        }
        viewModelScope.launch {
            commandMutex.withLock {
                if (!persistBeforeMutation(
                        currentAuthority,
                        frozen,
                        currentGeneration
                    )
                ) {
                    return@withLock
                }
                val identity =
                    InboundDiscrepancyArtifactIdentity(
                        currentAuthority.scope,
                        frozen.warehouseId,
                        caseId
                    )
                val candidate = try {
                    artifacts.openForUpload(identity)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (candidate == null) {
                    applyIfCurrent(currentGeneration, currentAuthority) {
                        it.copy(
                            isSaving = false,
                            flow = InboundDiscrepancyFlowStatus.PendingEvidence,
                            notice = InboundDiscrepancySaveNotice.EvidenceRejected
                        )
                    }
                    return@withLock
                }
                val result = try {
                    gateway.uploadEvidence(
                        InboundDiscrepancyUploadCommand(caseId, key, candidate),
                        currentAuthority
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    InboundDiscrepancyMutationResult.UnknownOutcome
                } finally {
                    artifacts.releaseUploadCandidate(candidate)
                }
                when (result) {
                    is InboundDiscrepancyMutationResult.EvidenceUploaded -> {
                        if (result.value.subjectId != caseId ||
                            result.value.subjectType != "INBOUND_RECEIVING_DISCREPANCY"
                        ) {
                            applyIfCurrent(currentGeneration, currentAuthority) {
                                it.copy(
                                    isSaving = false,
                                    flow = InboundDiscrepancyFlowStatus.UnknownOutcome,
                                    notice = InboundDiscrepancySaveNotice.UnknownOutcome
                                )
                            }
                        } else {
                            val confirmed = frozen.copy(
                                evidenceId = result.value.id,
                                evidenceStatus = result.value.lifecycleStatus,
                                pendingAction = null
                            )
                            applyConfirmedDraft(currentGeneration, currentAuthority, confirmed) {
                                it.copy(
                                    isSaving = false,
                                    evidenceId = result.value.id,
                                    evidenceStatus = result.value.lifecycleStatus,
                                    flow = result.value.lifecycleStatus.toEvidenceFlow(),
                                    pendingAction = null,
                                    notice = result.value.lifecycleStatus.toEvidenceNotice()
                                )
                            }
                        }
                    }

                    else -> applyMutationFailure(
                        currentGeneration,
                        currentAuthority,
                        result,
                        InboundDiscrepancyPendingAction.UploadEvidence
                    )
                }
            }
        }
    }

    fun refreshEvidence() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!current.canRefreshEvidence) return
        val caseId = current.caseId ?: return
        val evidenceId = current.evidenceId ?: return
        val currentGeneration = generation
        mutableState.update { it.copy(isSaving = true, notice = null) }
        viewModelScope.launch {
            val result = try {
                gateway.evidenceStatus(evidenceId, caseId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                InboundDiscrepancyEvidenceStatusResult.ServiceUnavailable
            }
            if (!isCurrent(currentGeneration, currentAuthority)) return@launch
            when (result) {
                is InboundDiscrepancyEvidenceStatusResult.Loaded -> {
                    if (result.value.id != evidenceId || result.value.subjectId != caseId ||
                        result.value.subjectType != "INBOUND_RECEIVING_DISCREPANCY"
                    ) {
                        mutableState.update {
                            it.copy(
                                isSaving = false,
                                flow = InboundDiscrepancyFlowStatus.ServiceUnavailable
                            )
                        }
                        return@launch
                    }
                    val draft = mutableState.value.toDraft(deviceClockMillis()).copy(
                        evidenceStatus = result.value.lifecycleStatus,
                        pendingAction = null
                    )
                    applyConfirmedDraft(currentGeneration, currentAuthority, draft) {
                        it.copy(
                            isSaving = false,
                            evidenceStatus = result.value.lifecycleStatus,
                            flow = result.value.lifecycleStatus.toEvidenceFlow(),
                            notice = result.value.lifecycleStatus.toEvidenceNotice(),
                            pendingAction = null
                        )
                    }
                    if (result.value.lifecycleStatus == "AVAILABLE") {
                        runCatching {
                            artifacts.clear(
                                InboundDiscrepancyArtifactIdentity(
                                    currentAuthority.scope,
                                    mutableState.value.warehouseId,
                                    caseId
                                )
                            )
                        }
                        mutableState.update { it.copy(artifact = null) }
                    }
                }

                is InboundDiscrepancyEvidenceStatusResult.Rejected -> mutableState.update {
                    it.copy(
                        isSaving = false,
                        flow = InboundDiscrepancyFlowStatus.Rejected,
                        rejectionCode = result.code
                    )
                }

                InboundDiscrepancyEvidenceStatusResult.ServiceUnavailable -> mutableState.update {
                    it.copy(
                        isSaving = false,
                        flow = InboundDiscrepancyFlowStatus.ServiceUnavailable,
                        notice = InboundDiscrepancySaveNotice.ServiceUnavailable
                    )
                }

                InboundDiscrepancyEvidenceStatusResult.PermissionDenied -> mutableState.update {
                    it.copy(
                        isSaving = false,
                        flow = InboundDiscrepancyFlowStatus.Unauthorized,
                        notice = InboundDiscrepancySaveNotice.PermissionDenied
                    )
                }

                InboundDiscrepancyEvidenceStatusResult.ContextInvalidated -> mutableState.update {
                    it.copy(
                        isSaving = false,
                        active = false,
                        flow = InboundDiscrepancyFlowStatus.Stale,
                        notice = InboundDiscrepancySaveNotice.ContextInvalidated
                    )
                }

                InboundDiscrepancyEvidenceStatusResult.SessionInvalidated -> mutableState.update {
                    it.copy(
                        isSaving = false,
                        active = false,
                        flow = InboundDiscrepancyFlowStatus.Stale,
                        notice = InboundDiscrepancySaveNotice.SessionInvalidated
                    )
                }
            }
        }
    }

    fun submitForReview(retry: Boolean = false) {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!current.active || current.caseId == null || current.caseStatus != "PENDING_EVIDENCE" ||
            current.evidenceId == null ||
            current.evidenceStatus != "AVAILABLE" || current.isSaving ||
            (!retry && current.pendingAction != null) ||
            (retry && current.pendingAction != InboundDiscrepancyPendingAction.SubmitForReview)
        ) {
            return
        }
        val evidenceId = current.evidenceId ?: return
        val caseId = current.caseId ?: return
        val prior = current.toDraft(current.capturedAtDeviceMillis ?: deviceClockMillis())
        val frozen = if (retry) {
            prior
        } else {
            prior.copy(
                submitIdempotencyKey = newCommandKey(),
                submitBody = "{\"evidenceObjectId\":\"${evidenceId.jsonEscaped()}\"}",
                pendingAction = InboundDiscrepancyPendingAction.SubmitForReview
            )
        }
        if (frozen.submitIdempotencyKey == null || frozen.submitBody == null) return
        val currentGeneration = generation
        mutableState.update {
            it.copy(
                isSaving = true,
                flow = InboundDiscrepancyFlowStatus.SubmittingForReview,
                notice = null
            )
        }
        viewModelScope.launch {
            commandMutex.withLock {
                if (!persistBeforeMutation(
                        currentAuthority,
                        frozen,
                        currentGeneration
                    )
                ) {
                    return@withLock
                }
                val result = try {
                    gateway.submitForReview(
                        InboundDiscrepancySubmitCommand(
                            caseId,
                            evidenceId,
                            frozen.caseVersion ?: return@withLock,
                            requireNotNull(frozen.submitIdempotencyKey),
                            requireNotNull(frozen.submitBody)
                        ),
                        currentAuthority
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    InboundDiscrepancyMutationResult.UnknownOutcome
                }
                when (result) {
                    is InboundDiscrepancyMutationResult.CaseConfirmed -> {
                        val value = result.value
                        if (value.id != caseId || value.status != "READY_FOR_REVIEW" ||
                            value.evidenceObjectId != evidenceId ||
                            value.submittedByMembershipId != currentAuthority.scope.membershipId
                        ) {
                            applyMutationFailure(
                                currentGeneration,
                                currentAuthority,
                                InboundDiscrepancyMutationResult.UnknownOutcome,
                                InboundDiscrepancyPendingAction.SubmitForReview
                            )
                        } else {
                            val confirmed = frozen.copy(
                                caseVersion = value.version,
                                caseStatus = value.status,
                                pendingAction = null
                            )
                            applyConfirmedDraft(currentGeneration, currentAuthority, confirmed) {
                                it.copy(
                                    isSaving = false,
                                    caseVersion = value.version,
                                    caseStatus = value.status,
                                    flow = InboundDiscrepancyFlowStatus.ReadyForReview,
                                    pendingAction = null,
                                    notice = InboundDiscrepancySaveNotice.ReviewRequested
                                )
                            }
                        }
                    }

                    else -> applyMutationFailure(
                        currentGeneration,
                        currentAuthority,
                        result,
                        InboundDiscrepancyPendingAction.SubmitForReview
                    )
                }
            }
        }
    }

    fun requestDiscard() {
        val state = mutableState.value
        if (state.hasSavedDraft && state.caseId == null && state.pendingAction == null &&
            !state.isSaving
        ) {
            mutableState.update { it.copy(showDiscardConfirmation = true) }
        }
    }

    fun cancelDiscard() = mutableState.update { it.copy(showDiscardConfirmation = false) }

    fun confirmDiscard() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!current.hasSavedDraft || current.draftId.isBlank() || current.caseId != null ||
            current.isSaving
        ) {
            return
        }
        val expectedId = current.draftId
        val requestGeneration = generation
        mutableState.update {
            it.copy(showDiscardConfirmation = false, isSaving = true, notice = null)
        }
        viewModelScope.launch {
            val result = storeMutex.withLock {
                try {
                    drafts.discard(currentAuthority.scope, expectedId)
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (
                    _: Exception
                ) {
                    InboundDiscrepancyDraftWrite.Unavailable
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            mutableState.value = when (result) {
                InboundDiscrepancyDraftWrite.Discarded -> InboundDiscrepancyUiState(
                    authorityEpoch = currentAuthority.authorityEpoch,
                    active = true,
                    metadata = InboundDiscrepancyMetadataStatus.Available,
                    draftId = newDraftId(),
                    notice = InboundDiscrepancySaveNotice.Discarded
                )

                InboundDiscrepancyDraftWrite.Conflict -> mutableState.value.copy(
                    isSaving = false,
                    notice = InboundDiscrepancySaveNotice.ExistingDraftConflict
                )

                InboundDiscrepancyDraftWrite.Unavailable,
                InboundDiscrepancyDraftWrite.Saved -> mutableState.value.copy(
                    isSaving = false,
                    notice = InboundDiscrepancySaveNotice.StoreUnavailable
                )
            }
        }
    }

    private fun performCreate(frozen: InboundDiscrepancyDraft, retry: Boolean) {
        val currentAuthority = authority ?: return
        val createIdempotencyKey = frozen.createIdempotencyKey ?: return
        val createBody = frozen.createBody ?: return
        if (retry && frozen.pendingAction != InboundDiscrepancyPendingAction.CreateCase) return
        if (!retry && frozen.pendingAction != InboundDiscrepancyPendingAction.CreateCase) return
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                createIdempotencyKey = createIdempotencyKey,
                createBody = createBody,
                pendingAction = frozen.pendingAction,
                isSaving = true,
                flow = InboundDiscrepancyFlowStatus.CreatingCase,
                validationError = null,
                notice = null
            )
        }
        viewModelScope.launch {
            commandMutex.withLock {
                if (!persistBeforeMutation(
                        currentAuthority,
                        frozen,
                        requestGeneration
                    )
                ) {
                    return@withLock
                }
                val result = try {
                    gateway.createCase(
                        InboundDiscrepancyCreateCommand(
                            createIdempotencyKey,
                            createBody
                        ),
                        currentAuthority
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    InboundDiscrepancyMutationResult.UnknownOutcome
                }
                when (result) {
                    is InboundDiscrepancyMutationResult.CaseConfirmed -> {
                        val value = result.value
                        if (value.warehouseId != frozen.warehouseId ||
                            value.status != "PENDING_EVIDENCE" ||
                            value.recordedByMembershipId != currentAuthority.scope.membershipId
                        ) {
                            applyMutationFailure(
                                requestGeneration,
                                currentAuthority,
                                InboundDiscrepancyMutationResult.UnknownOutcome,
                                InboundDiscrepancyPendingAction.CreateCase
                            )
                        } else {
                            val confirmed = frozen.copy(
                                caseId = value.id,
                                caseVersion = value.version,
                                caseStatus = value.status,
                                pendingAction = null
                            )
                            applyConfirmedDraft(requestGeneration, currentAuthority, confirmed) {
                                it.copy(
                                    isSaving =
                                    false,
                                    caseId = value.id, caseVersion = value.version,
                                    caseStatus =
                                        value.status,
                                    flow = InboundDiscrepancyFlowStatus.PendingEvidence,
                                    hasSavedDraft =
                                    true,
                                    hasUnsavedChanges = false, pendingAction = null,
                                    notice = InboundDiscrepancySaveNotice.CaseRecorded
                                )
                            }
                        }
                    }

                    else -> applyMutationFailure(
                        requestGeneration,
                        currentAuthority,
                        result,
                        InboundDiscrepancyPendingAction.CreateCase
                    )
                }
            }
        }
    }

    private fun persistDraft(
        currentAuthority: InboundDiscrepancyAuthority,
        draft: InboundDiscrepancyDraft
    ) {
        val requestGeneration = generation
        mutableState.update { it.copy(isSaving = true, validationError = null, notice = null) }
        viewModelScope.launch {
            val result = storeMutex.withLock {
                safeDraftWrite { drafts.save(currentAuthority.scope, draft) }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            mutableState.update {
                it.copy(
                    isSaving = false,
                    hasSavedDraft =
                        result == InboundDiscrepancyDraftWrite.Saved,
                    hasUnsavedChanges = result != InboundDiscrepancyDraftWrite.Saved,
                    capturedAtDeviceMillis = if (result ==
                        InboundDiscrepancyDraftWrite.Saved
                    ) {
                        draft.capturedAtDeviceMillis
                    } else {
                        it.capturedAtDeviceMillis
                    },
                    notice = when (result) {
                        InboundDiscrepancyDraftWrite.Saved ->
                            InboundDiscrepancySaveNotice.SavedLocally

                        InboundDiscrepancyDraftWrite.Conflict ->
                            InboundDiscrepancySaveNotice.ExistingDraftConflict

                        InboundDiscrepancyDraftWrite.Unavailable,
                        InboundDiscrepancyDraftWrite.Discarded ->
                            InboundDiscrepancySaveNotice.StoreUnavailable
                    }
                )
            }
        }
    }

    private suspend fun persistBeforeMutation(
        currentAuthority: InboundDiscrepancyAuthority,
        draft: InboundDiscrepancyDraft,
        requestGeneration: Long
    ): Boolean {
        val result = storeMutex.withLock {
            safeDraftWrite { drafts.save(currentAuthority.scope, draft) }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return false
        if (result == InboundDiscrepancyDraftWrite.Saved) {
            mutableState.update { it.copy(hasSavedDraft = true, hasUnsavedChanges = false) }
            return true
        }
        mutableState.update {
            it.copy(
                isSaving = false,
                flow = InboundDiscrepancyFlowStatus.ServiceUnavailable,
                notice = if (result == InboundDiscrepancyDraftWrite.Conflict) {
                    InboundDiscrepancySaveNotice.ExistingDraftConflict
                } else {
                    InboundDiscrepancySaveNotice.StoreUnavailable
                }
            )
        }
        return false
    }

    private suspend fun applyConfirmedDraft(
        requestGeneration: Long,
        currentAuthority: InboundDiscrepancyAuthority,
        draft: InboundDiscrepancyDraft,
        transform: (InboundDiscrepancyUiState) -> InboundDiscrepancyUiState
    ) {
        val saved = storeMutex.withLock {
            safeDraftWrite { drafts.save(currentAuthority.scope, draft) }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (saved == InboundDiscrepancyDraftWrite.Saved) {
            mutableState.update(transform)
        } else {
            mutableState.update {
                it.copy(
                    isSaving = false,
                    flow = InboundDiscrepancyFlowStatus.ServiceUnavailable,
                    notice = InboundDiscrepancySaveNotice.StoreUnavailable
                )
            }
        }
    }

    private fun applyMutationFailure(
        requestGeneration: Long,
        currentAuthority: InboundDiscrepancyAuthority,
        result: InboundDiscrepancyMutationResult,
        pendingAction: InboundDiscrepancyPendingAction
    ) {
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val current = mutableState.value
        val flowAndNotice = when (result) {
            is InboundDiscrepancyMutationResult.Rejected ->
                InboundDiscrepancyFlowStatus.Rejected to
                    InboundDiscrepancySaveNotice.EvidenceRejected

            InboundDiscrepancyMutationResult.PreconditionFailed ->
                InboundDiscrepancyFlowStatus.Stale to
                    InboundDiscrepancySaveNotice.Stale

            InboundDiscrepancyMutationResult.Conflict ->
                InboundDiscrepancyFlowStatus.Stale to
                    InboundDiscrepancySaveNotice.Stale

            InboundDiscrepancyMutationResult.UnknownOutcome ->
                InboundDiscrepancyFlowStatus.UnknownOutcome to
                    InboundDiscrepancySaveNotice.UnknownOutcome

            InboundDiscrepancyMutationResult.NetworkUnavailable ->
                InboundDiscrepancyFlowStatus.ServiceUnavailable to
                    InboundDiscrepancySaveNotice.NetworkUnavailable

            InboundDiscrepancyMutationResult.ServiceUnavailable ->
                InboundDiscrepancyFlowStatus.ServiceUnavailable to
                    InboundDiscrepancySaveNotice.ServiceUnavailable

            InboundDiscrepancyMutationResult.PermissionDenied ->
                InboundDiscrepancyFlowStatus.Unauthorized to
                    InboundDiscrepancySaveNotice.PermissionDenied

            InboundDiscrepancyMutationResult.ContextInvalidated ->
                InboundDiscrepancyFlowStatus.Stale to
                    InboundDiscrepancySaveNotice.ContextInvalidated

            InboundDiscrepancyMutationResult.SessionInvalidated ->
                InboundDiscrepancyFlowStatus.Stale to
                    InboundDiscrepancySaveNotice.SessionInvalidated

            else ->
                InboundDiscrepancyFlowStatus.ServiceUnavailable to
                    InboundDiscrepancySaveNotice.ServiceUnavailable
        }
        val preservePending = result in setOf(
            InboundDiscrepancyMutationResult.UnknownOutcome,
            InboundDiscrepancyMutationResult.NetworkUnavailable,
            InboundDiscrepancyMutationResult.ServiceUnavailable
        )
        val updated = current.copy(
            isSaving = false,
            flow = flowAndNotice.first,
            notice = flowAndNotice.second,
            pendingAction = if (preservePending) pendingAction else null,
            rejectionCode = (result as? InboundDiscrepancyMutationResult.Rejected)?.code
        )
        mutableState.value = updated
        if (!preservePending) return
        viewModelScope.launch {
            val draft = updated.toDraft(updated.capturedAtDeviceMillis ?: deviceClockMillis())
            storeMutex.withLock { safeDraftWrite { drafts.save(currentAuthority.scope, draft) } }
        }
    }

    private fun edit(transform: (InboundDiscrepancyUiState) -> InboundDiscrepancyUiState) {
        mutableState.update {
            if (it.active && it.metadata == InboundDiscrepancyMetadataStatus.Available &&
                it.caseId == null && it.pendingAction == null && !it.isSaving
            ) {
                transform(it).copy(
                    validationError = null,
                    notice = null,
                    hasUnsavedChanges = true,
                    createIdempotencyKey = null,
                    createBody = null,
                    flow = InboundDiscrepancyFlowStatus.Editing
                )
            } else {
                it
            }
        }
    }

    private fun validate(
        current: InboundDiscrepancyUiState,
        forSubmission: Boolean
    ): DiscrepancyValidationError? {
        if (!forSubmission) return null
        if (current.warehouseId.isBlank()) return DiscrepancyValidationError.WarehouseRequired
        if (!UUID_PATTERN.matches(
                current.warehouseId
            )
        ) {
            return DiscrepancyValidationError.WarehouseInvalid
        }
        if (current.observedSkuId.isBlank()) return DiscrepancyValidationError.ObservedSkuRequired
        if (!UUID_PATTERN.matches(
                current.observedSkuId
            )
        ) {
            return DiscrepancyValidationError.ObservedSkuInvalid
        }
        if (current.expectedSkuId.isNotBlank() && !UUID_PATTERN.matches(current.expectedSkuId)) {
            return DiscrepancyValidationError.ExpectedSkuInvalid
        }
        if (current.expectedBatchReference.length > MAX_REFERENCE_LENGTH ||
            current.observedBatchReference.length > MAX_REFERENCE_LENGTH
        ) {
            return DiscrepancyValidationError.BatchReferenceInvalid
        }
        val kind = current.kind ?: return DiscrepancyValidationError.ReasonRequired
        if (kind == InboundDiscrepancyKind.Other && current.reasonDetails.isBlank()) {
            return DiscrepancyValidationError.ReasonRequired
        }
        if (current.unit.isBlank()) return DiscrepancyValidationError.UnitRequired
        if (forSubmission || kind == InboundDiscrepancyKind.QuantityDifference) {
            val expected = current.expectedQuantityText.trim().toBigDecimalOrNull()
                ?.takeIf { DECIMAL_LEXEME.matches(current.expectedQuantityText.trim()) }
                ?: return if (current.expectedQuantityText.isBlank()) {
                    DiscrepancyValidationError.ExpectedQuantityRequired
                } else {
                    DiscrepancyValidationError.QuantityInvalid
                }
            val observed = current.observedQuantityText.trim().toBigDecimalOrNull()
                ?.takeIf { DECIMAL_LEXEME.matches(current.observedQuantityText.trim()) }
                ?: return if (current.observedQuantityText.isBlank()) {
                    DiscrepancyValidationError.ObservedQuantityRequired
                } else {
                    DiscrepancyValidationError.QuantityInvalid
                }
            if (expected.signum() < 0 || observed.signum() < 0 ||
                expected.scale().coerceAtLeast(0) > 4 ||
                observed.scale().coerceAtLeast(0) > 4
            ) {
                return DiscrepancyValidationError.QuantityInvalid
            }
            if (kind == InboundDiscrepancyKind.QuantityDifference &&
                expected.compareTo(observed) == 0
            ) {
                return DiscrepancyValidationError.QuantityDifferenceRequired
            }
        }
        return null
    }

    private fun InboundDiscrepancyUiState.createBody(): String = buildString {
        append('{')
        append("\"warehouseId\":\"").append(warehouseId.jsonEscaped()).append("\",")
        append("\"expectedSkuId\":").append(
            expectedSkuId.trim().takeIf(String::isNotEmpty)?.let {
                "\"${it.jsonEscaped()}\""
            }
                ?: "null"
        ).append(',')
        append("\"observedSkuId\":\"").append(observedSkuId.trim().jsonEscaped()).append("\",")
        append("\"expectedBatchReference\":").append(
            expectedBatchReference.trim().takeIf(String::isNotEmpty)?.let {
                "\"${it.jsonEscaped()}\""
            }
                ?: "null"
        ).append(',')
        append("\"observedBatchReference\":").append(
            observedBatchReference.trim().takeIf(String::isNotEmpty)?.let {
                "\"${it.jsonEscaped()}\""
            }
                ?: "null"
        ).append(',')
        append("\"expectedQuantity\":").append(expectedQuantityText.trim()).append(',')
        append("\"observedQuantity\":").append(observedQuantityText.trim()).append(',')
        append("\"unit\":\"").append(unit.trim().jsonEscaped()).append("\",")
        val reason = buildString {
            append(requireNotNull(kind).apiReason)
            if (reasonDetails.isNotBlank()) append(": ").append(reasonDetails.trim())
        }
        append("\"reason\":\"").append(reason.jsonEscaped()).append("\",")
        append("\"observationNotes\":").append(
            observationNotes.trim().takeIf(String::isNotEmpty)?.let {
                "\"${it.jsonEscaped()}\""
            }
                ?: "null"
        )
        append('}')
    }

    private fun InboundDiscrepancyUiState.toDraft(capturedAt: Long) = InboundDiscrepancyDraft(
        id = draftId,
        warehouseId = warehouseId.trim(),
        expectedSkuId = expectedSkuId.trim(),
        observedSkuId = observedSkuId.trim(),
        observedSkuLabel = observedSkuLabel,
        expectedBatchReference = expectedBatchReference.trim(),
        observedBatchReference = observedBatchReference.trim(),
        expectedQuantityText = expectedQuantityText.trim(),
        observedQuantityText = observedQuantityText.trim(),
        unit = unit.trim(),
        kind = kind ?: InboundDiscrepancyKind.Other,
        reasonDetails = reasonDetails.trim(),
        observationNotes = observationNotes.trim(),
        capturedAtDeviceMillis = capturedAt,
        createIdempotencyKey = createIdempotencyKey,
        createBody = createBody,
        caseId = caseId,
        caseVersion = caseVersion,
        caseStatus = caseStatus,
        evidenceUploadKey = evidenceUploadKey,
        evidenceId = evidenceId,
        evidenceStatus = evidenceStatus,
        submitIdempotencyKey = submitIdempotencyKey,
        submitBody = submitBody,
        pendingAction = pendingAction
    )

    private fun InboundDiscrepancyDraft.toUiState(epoch: Long): InboundDiscrepancyUiState {
        val storedEvidenceStatus = this.evidenceStatus
        return InboundDiscrepancyUiState(
            authorityEpoch = epoch,
            active = true,
            metadata = InboundDiscrepancyMetadataStatus.Available,
            draftId = id,
            warehouseId = warehouseId,
            expectedSkuId = expectedSkuId,
            observedSkuId = observedSkuId,
            observedSkuLabel = observedSkuLabel,
            expectedBatchReference = expectedBatchReference,
            observedBatchReference = observedBatchReference,
            expectedQuantityText = expectedQuantityText,
            observedQuantityText = observedQuantityText,
            unit = unit,
            kind = kind,
            reasonDetails = reasonDetails,
            observationNotes = observationNotes,
            capturedAtDeviceMillis = capturedAtDeviceMillis,
            flow = when {
                pendingAction != null -> InboundDiscrepancyFlowStatus.UnknownOutcome

                caseStatus == "READY_FOR_REVIEW" -> InboundDiscrepancyFlowStatus.ReadyForReview

                storedEvidenceStatus == "AVAILABLE" ->
                    InboundDiscrepancyFlowStatus.EvidenceAvailable

                storedEvidenceStatus != null -> storedEvidenceStatus.toEvidenceFlow()

                caseId != null -> InboundDiscrepancyFlowStatus.PendingEvidence

                else -> InboundDiscrepancyFlowStatus.Editing
            },
            caseId = caseId,
            caseVersion = caseVersion,
            caseStatus = caseStatus,
            evidenceId = evidenceId,
            evidenceStatus = evidenceStatus,
            hasSavedDraft = true,
            hasUnsavedChanges = false,
            createIdempotencyKey = createIdempotencyKey,
            createBody = createBody,
            evidenceUploadKey = evidenceUploadKey,
            submitIdempotencyKey = submitIdempotencyKey,
            submitBody = submitBody,
            pendingAction = pendingAction
        )
    }

    private fun InboundDiscrepancyDraft.isValidStored(): Boolean = try {
        val storedCaseVersion = this.caseVersion
        id.isNotBlank() && id.length <= MAX_REFERENCE_LENGTH &&
            warehouseId.length <= MAX_REFERENCE_LENGTH &&
            expectedSkuId.length <= MAX_REFERENCE_LENGTH &&
            observedSkuId.length <= MAX_REFERENCE_LENGTH &&
            (observedSkuLabel?.length ?: 0) <= MAX_NOTE_LENGTH &&
            expectedBatchReference.length <= MAX_REFERENCE_LENGTH &&
            observedBatchReference.length <= MAX_REFERENCE_LENGTH &&
            expectedQuantityText.length <= MAX_QUANTITY_LENGTH &&
            observedQuantityText.length <= MAX_QUANTITY_LENGTH &&
            unit.length <= 32 && reasonDetails.length <= MAX_NOTE_LENGTH &&
            observationNotes.length <= MAX_NOTE_LENGTH &&
            capturedAtDeviceMillis > 0 && (createBody?.length ?: 0) <= MAX_COMMAND_LENGTH &&
            (submitBody?.length ?: 0) <= MAX_COMMAND_LENGTH &&
            (storedCaseVersion == null || storedCaseVersion >= 0)
    } catch (_: Exception) {
        false
    }

    private fun InboundDiscrepancyUiState.withStartContext(
        start: InboundDiscrepancyStartContext?
    ): InboundDiscrepancyUiState = if (start == null) {
        this
    } else {
        copy(
            warehouseId = start.warehouseId,
            observedSkuId = start.observedSkuId.orEmpty(),
            observedSkuLabel = start.observedSkuLabel,
            observedBatchReference = start.observedBatchReference.orEmpty(),
            observedQuantityText = start.observedQuantityText.orEmpty(),
            unit = start.unit.orEmpty()
        )
    }

    private suspend fun safeDraftRead(
        action: suspend () -> InboundDiscrepancyDraftRead
    ): InboundDiscrepancyDraftRead = try {
        action()
    } catch (
        cancelled: CancellationException
    ) {
        throw cancelled
    } catch (_: Exception) {
        InboundDiscrepancyDraftRead.Unavailable
    }

    private suspend fun safeDraftWrite(
        action: suspend () -> InboundDiscrepancyDraftWrite
    ): InboundDiscrepancyDraftWrite = try {
        action()
    } catch (
        cancelled: CancellationException
    ) {
        throw cancelled
    } catch (_: Exception) {
        InboundDiscrepancyDraftWrite.Unavailable
    }

    private fun applyIfCurrent(
        requestGeneration: Long,
        currentAuthority: InboundDiscrepancyAuthority,
        transform: (InboundDiscrepancyUiState) -> InboundDiscrepancyUiState
    ) {
        if (isCurrent(requestGeneration, currentAuthority)) mutableState.update(transform)
    }

    private fun isCurrent(requestGeneration: Long, expected: InboundDiscrepancyAuthority): Boolean =
        generation == requestGeneration && authority == expected && mutableState.value.active &&
            mutableState.value.authorityEpoch == expected.authorityEpoch

    private fun String.jsonEscaped(): String = buildString(length) {
        for (character in this@jsonEscaped) {
            when (character) {
                '\\' -> append("\\\\")

                '"' -> append("\\\"")

                '\b' -> append("\\b")

                '\u000C' -> append("\\f")

                '\n' -> append("\\n")

                '\r' -> append("\\r")

                '\t' -> append("\\t")

                else ->
                    if (character.code <
                        0x20
                    ) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
            }
        }
    }

    private fun String.toEvidenceFlow(): InboundDiscrepancyFlowStatus = when (this) {
        "AVAILABLE" -> InboundDiscrepancyFlowStatus.EvidenceAvailable
        "REJECTED" -> InboundDiscrepancyFlowStatus.Rejected
        else -> InboundDiscrepancyFlowStatus.EvidenceAwaitingScan
    }

    private fun String.toEvidenceNotice(): InboundDiscrepancySaveNotice = when (this) {
        "AVAILABLE" -> InboundDiscrepancySaveNotice.EvidenceUploaded
        "REJECTED" -> InboundDiscrepancySaveNotice.EvidenceRejected
        else -> InboundDiscrepancySaveNotice.EvidenceAwaitingScan
    }

    private companion object {
        const val MAX_REFERENCE_LENGTH = 240
        const val MAX_QUANTITY_LENGTH = 80
        const val MAX_NOTE_LENGTH = 2_000
        const val MAX_COMMAND_LENGTH = 8_000
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val DECIMAL_LEXEME = Regex("(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,4})?")
    }
}
