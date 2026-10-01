package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable

/** A verified identity boundary for local capture. It carries no permission or server authority. */
@Immutable
data class InboundDiscrepancyAuthority(
    val scope: InboundDiscrepancyScope,
    val authorityEpoch: Long
) {
    init {
        require(authorityEpoch > 0)
    }

    override fun toString(): String = "InboundDiscrepancyAuthority(scope=REDACTED, epoch=$authorityEpoch)"
}

@Immutable
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

enum class InboundDiscrepancyKind {
    Damage,
    Leakage,
    WrongProduct,
    QuantityDifference,
    Other
}

/** Local notes only: product and lot references are deliberately plain unverified text. */
@Immutable
data class InboundDiscrepancyDraft(
    val id: String,
    val productReference: String,
    val lotOrBatchReference: String,
    val kind: InboundDiscrepancyKind,
    val reasonDetails: String,
    val expectedQuantityText: String,
    val observedQuantityText: String,
    val evidencePlan: String,
    val observationNotes: String,
    val capturedAtDeviceMillis: Long
) {
    init {
        require(id.isNotBlank() && id.length <= 160)
        require(productReference.length <= MAX_REFERENCE_LENGTH)
        require(lotOrBatchReference.length <= MAX_REFERENCE_LENGTH)
        require(reasonDetails.length <= MAX_NOTE_LENGTH)
        require(expectedQuantityText.length <= 80 && observedQuantityText.length <= 80)
        require(evidencePlan.length <= MAX_NOTE_LENGTH && observationNotes.length <= MAX_NOTE_LENGTH)
        require(capturedAtDeviceMillis > 0)
    }

    override fun toString(): String = "InboundDiscrepancyDraft(id=REDACTED, kind=$kind)"

    private companion object {
        const val MAX_REFERENCE_LENGTH = 240
        const val MAX_NOTE_LENGTH = 2_000
    }
}

enum class InboundDiscrepancyMetadataStatus { Loading, Available, Unavailable }

enum class InboundDiscrepancyValidationError {
    ProductReferenceRequired,
    LotReferenceRequired,
    ReasonRequired,
    ExpectedQuantityRequired,
    ObservedQuantityRequired,
    QuantityInvalid,
    QuantityDifferenceRequired
}

enum class InboundDiscrepancySaveNotice { SavedLocally, StoreUnavailable, ExistingDraftConflict, Discarded }

data class InboundDiscrepancyUiState(
    val authorityEpoch: Long = 0,
    val active: Boolean = false,
    val metadata: InboundDiscrepancyMetadataStatus = InboundDiscrepancyMetadataStatus.Loading,
    val draftId: String = "",
    val productReference: String = "",
    val lotOrBatchReference: String = "",
    val kind: InboundDiscrepancyKind? = null,
    val reasonDetails: String = "",
    val expectedQuantityText: String = "",
    val observedQuantityText: String = "",
    val evidencePlan: String = "",
    val observationNotes: String = "",
    val capturedAtDeviceMillis: Long? = null,
    val isSaving: Boolean = false,
    val hasSavedDraft: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val showDiscardConfirmation: Boolean = false,
    val validationError: InboundDiscrepancyValidationError? = null,
    val notice: InboundDiscrepancySaveNotice? = null
) {
    val canSave: Boolean
        get() = active && metadata == InboundDiscrepancyMetadataStatus.Available && !isSaving

    override fun toString(): String =
        "InboundDiscrepancyUiState(epoch=$authorityEpoch, active=$active, metadata=$metadata, saved=$hasSavedDraft)"
}

sealed interface InboundDiscrepancyDraftRead {
    data class Available(val draft: InboundDiscrepancyDraft?) : InboundDiscrepancyDraftRead
    data object Unavailable : InboundDiscrepancyDraftRead
}

enum class InboundDiscrepancyDraftWrite { Saved, Discarded, Unavailable, Conflict }

/** Encrypted local draft storage only. This interface does not send or resolve a discrepancy. */
interface InboundDiscrepancyDraftStore {
    suspend fun load(scope: InboundDiscrepancyScope): InboundDiscrepancyDraftRead
    suspend fun save(scope: InboundDiscrepancyScope, draft: InboundDiscrepancyDraft): InboundDiscrepancyDraftWrite
    suspend fun discard(scope: InboundDiscrepancyScope, expectedDraftId: String): InboundDiscrepancyDraftWrite
}
