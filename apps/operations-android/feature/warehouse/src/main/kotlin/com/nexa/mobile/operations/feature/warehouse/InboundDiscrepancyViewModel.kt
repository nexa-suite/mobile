package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Captures an explicitly non-authoritative local note; it has no receiving or stock gateway. */
class InboundDiscrepancyViewModel(
    private val drafts: InboundDiscrepancyDraftStore,
    private val newDraftId: () -> String = { UUID.randomUUID().toString() },
    private val deviceClockMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private val mutableState = MutableStateFlow(InboundDiscrepancyUiState())
    val state = mutableState.asStateFlow()

    private var authority: InboundDiscrepancyAuthority? = null
    private var generation = 0L
    private val storeMutex = Mutex()

    fun activate(currentAuthority: InboundDiscrepancyAuthority) {
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
            val result = storeMutex.withLock { safeStoreRead { drafts.load(currentAuthority.scope) } }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            mutableState.value = when (result) {
                InboundDiscrepancyDraftRead.Unavailable -> mutableState.value.copy(
                    metadata = InboundDiscrepancyMetadataStatus.Unavailable
                )
                is InboundDiscrepancyDraftRead.Available -> result.draft?.let { draft ->
                    if (!draft.isValidStored()) {
                        mutableState.value.copy(metadata = InboundDiscrepancyMetadataStatus.Unavailable)
                    } else {
                        draft.toUiState(currentAuthority.authorityEpoch)
                    }
                } ?: mutableState.value.copy(metadata = InboundDiscrepancyMetadataStatus.Available)
            }
        }
    }

    fun deactivate() {
        generation++
        authority = null
        mutableState.value = InboundDiscrepancyUiState()
    }

    fun productReferenceChanged(value: String) = edit { it.copy(productReference = value.take(MAX_REFERENCE_LENGTH)) }
    fun lotOrBatchReferenceChanged(value: String) = edit { it.copy(lotOrBatchReference = value.take(MAX_REFERENCE_LENGTH)) }
    fun kindChanged(value: InboundDiscrepancyKind) = edit { it.copy(kind = value) }
    fun reasonDetailsChanged(value: String) = edit { it.copy(reasonDetails = value.take(MAX_NOTE_LENGTH)) }
    fun expectedQuantityChanged(value: String) = edit { it.copy(expectedQuantityText = value.take(MAX_QUANTITY_LENGTH)) }
    fun observedQuantityChanged(value: String) = edit { it.copy(observedQuantityText = value.take(MAX_QUANTITY_LENGTH)) }
    fun evidencePlanChanged(value: String) = edit { it.copy(evidencePlan = value.take(MAX_NOTE_LENGTH)) }
    fun observationNotesChanged(value: String) = edit { it.copy(observationNotes = value.take(MAX_NOTE_LENGTH)) }

    fun saveDraft() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!current.canSave || current.validationError != null) return
        val validation = validate(current)
        if (validation != null) {
            mutableState.update { it.copy(validationError = validation, notice = null) }
            return
        }
        val draft = current.toDraft(current.capturedAtDeviceMillis ?: deviceClockMillis())
        val requestGeneration = generation
        mutableState.update { it.copy(isSaving = true, validationError = null, notice = null) }
        viewModelScope.launch {
            val result = storeMutex.withLock {
                safeStoreWrite { drafts.save(currentAuthority.scope, draft) }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            mutableState.update {
                it.copy(
                    isSaving = false,
                    hasSavedDraft = result == InboundDiscrepancyDraftWrite.Saved,
                    hasUnsavedChanges = result != InboundDiscrepancyDraftWrite.Saved,
                    capturedAtDeviceMillis = if (result == InboundDiscrepancyDraftWrite.Saved) {
                        draft.capturedAtDeviceMillis
                    } else {
                        it.capturedAtDeviceMillis
                    },
                    notice = when (result) {
                        InboundDiscrepancyDraftWrite.Saved -> InboundDiscrepancySaveNotice.SavedLocally
                        InboundDiscrepancyDraftWrite.Conflict -> InboundDiscrepancySaveNotice.ExistingDraftConflict
                        InboundDiscrepancyDraftWrite.Unavailable,
                        InboundDiscrepancyDraftWrite.Discarded -> InboundDiscrepancySaveNotice.StoreUnavailable
                    }
                )
            }
        }
    }

    fun requestDiscard() {
        if (mutableState.value.hasSavedDraft && !mutableState.value.isSaving) {
            mutableState.update { it.copy(showDiscardConfirmation = true) }
        }
    }

    fun cancelDiscard() = mutableState.update { it.copy(showDiscardConfirmation = false) }

    fun confirmDiscard() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!current.hasSavedDraft || current.draftId.isBlank() || current.isSaving) return
        val expectedId = current.draftId
        val requestGeneration = generation
        mutableState.update { it.copy(showDiscardConfirmation = false, isSaving = true, notice = null) }
        viewModelScope.launch {
            val result = storeMutex.withLock {
                safeStoreWrite { drafts.discard(currentAuthority.scope, expectedId) }
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

    private fun edit(transform: (InboundDiscrepancyUiState) -> InboundDiscrepancyUiState) {
        mutableState.update {
            if (it.active && it.metadata == InboundDiscrepancyMetadataStatus.Available && !it.isSaving) {
                transform(it).copy(validationError = null, notice = null, hasUnsavedChanges = true)
            } else {
                it
            }
        }
    }

    private fun validate(current: InboundDiscrepancyUiState): InboundDiscrepancyValidationError? {
        if (current.productReference.isBlank()) return InboundDiscrepancyValidationError.ProductReferenceRequired
        if (current.lotOrBatchReference.isBlank()) return InboundDiscrepancyValidationError.LotReferenceRequired
        val kind = current.kind ?: return InboundDiscrepancyValidationError.ReasonRequired
        if (kind == InboundDiscrepancyKind.Other && current.reasonDetails.isBlank()) {
            return InboundDiscrepancyValidationError.ReasonRequired
        }
        if (kind == InboundDiscrepancyKind.QuantityDifference) {
            if (current.expectedQuantityText.isBlank()) return InboundDiscrepancyValidationError.ExpectedQuantityRequired
            if (current.observedQuantityText.isBlank()) return InboundDiscrepancyValidationError.ObservedQuantityRequired
            val expected = current.expectedQuantityText.trim().takeIf(DECIMAL_LEXEME::matches)?.let(::BigDecimal)
            val observed = current.observedQuantityText.trim().takeIf(DECIMAL_LEXEME::matches)?.let(::BigDecimal)
            if (expected == null || observed == null || expected.signum() < 0 || observed.signum() < 0) {
                return InboundDiscrepancyValidationError.QuantityInvalid
            }
            if (expected.compareTo(observed) == 0) return InboundDiscrepancyValidationError.QuantityDifferenceRequired
        }
        return null
    }

    private fun InboundDiscrepancyUiState.toDraft(capturedAt: Long) = InboundDiscrepancyDraft(
        id = draftId,
        productReference = productReference.trim(),
        lotOrBatchReference = lotOrBatchReference.trim(),
        kind = requireNotNull(kind),
        reasonDetails = reasonDetails.trim(),
        expectedQuantityText = expectedQuantityText.trim(),
        observedQuantityText = observedQuantityText.trim(),
        evidencePlan = evidencePlan.trim(),
        observationNotes = observationNotes.trim(),
        capturedAtDeviceMillis = capturedAt
    )

    private fun InboundDiscrepancyDraft.toUiState(epoch: Long) = InboundDiscrepancyUiState(
        authorityEpoch = epoch,
        active = true,
        metadata = InboundDiscrepancyMetadataStatus.Available,
        draftId = id,
        productReference = productReference,
        lotOrBatchReference = lotOrBatchReference,
        kind = kind,
        reasonDetails = reasonDetails,
        expectedQuantityText = expectedQuantityText,
        observedQuantityText = observedQuantityText,
        evidencePlan = evidencePlan,
        observationNotes = observationNotes,
        capturedAtDeviceMillis = capturedAtDeviceMillis,
        hasSavedDraft = true
    )

    private fun InboundDiscrepancyDraft.isValidStored(): Boolean =
        id.isNotBlank() && productReference.length <= MAX_REFERENCE_LENGTH &&
            lotOrBatchReference.length <= MAX_REFERENCE_LENGTH && reasonDetails.length <= MAX_NOTE_LENGTH &&
            expectedQuantityText.length <= MAX_QUANTITY_LENGTH && observedQuantityText.length <= MAX_QUANTITY_LENGTH &&
            evidencePlan.length <= MAX_NOTE_LENGTH && observationNotes.length <= MAX_NOTE_LENGTH && capturedAtDeviceMillis > 0

    private suspend fun safeStoreRead(block: suspend () -> InboundDiscrepancyDraftRead) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InboundDiscrepancyDraftRead.Unavailable
    }

    private suspend fun safeStoreWrite(block: suspend () -> InboundDiscrepancyDraftWrite) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InboundDiscrepancyDraftWrite.Unavailable
    }

    private fun isCurrent(requestGeneration: Long, expectedAuthority: InboundDiscrepancyAuthority): Boolean =
        generation == requestGeneration && authority == expectedAuthority && mutableState.value.active &&
            mutableState.value.authorityEpoch == expectedAuthority.authorityEpoch

    private companion object {
        const val MAX_REFERENCE_LENGTH = 240
        const val MAX_NOTE_LENGTH = 2_000
        const val MAX_QUANTITY_LENGTH = 80
        val DECIMAL_LEXEME = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    }
}
