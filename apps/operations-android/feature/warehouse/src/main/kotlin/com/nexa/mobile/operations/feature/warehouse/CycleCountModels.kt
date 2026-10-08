package com.nexa.mobile.operations.feature.warehouse

import com.nexa.mobile.operations.feature.warehouse.model.CycleCountCorrection
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountCorrectionIntent
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountIntent
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountLot
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountRecord

enum class CycleCountLookupStatus {
    NotRequested,
    Loading,
    Ready,
    Empty,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class CycleCountCommandStatus {
    Editing,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Recorded,
    Applied,
    Rejected,
    PreconditionFailed,
    Conflict,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class CycleCountNotice {
    InvalidQuantity,
    MetadataUnavailable,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    PreconditionFailed,
    Conflict,
    CountMatchesStock,
    CorrectionNotRequested,
    CurrentFactsUnavailable,
    QuantityUnitMismatch,
    StaleCount
}

data class CycleCountUiState(
    val authorityEpoch: Long = 0,
    val canWriteCounts: Boolean = false,
    val canApplyInventoryCorrection: Boolean = false,
    val lots: List<CycleCountLot> = emptyList(),
    val lotPage: Int = -1,
    val lotTotal: Long = 0,
    val lotLookup: CycleCountLookupStatus = CycleCountLookupStatus.NotRequested,
    val selectedLotId: String? = null,
    val observedQuantityText: String = "",
    val metadataAvailable: Boolean = false,
    val countCommand: CycleCountCommandStatus = CycleCountCommandStatus.Editing,
    val frozenCountIntent: CycleCountIntent? = null,
    val recordedCount: CycleCountRecord? = null,
    val correctionCommand: CycleCountCommandStatus = CycleCountCommandStatus.Editing,
    val frozenCorrectionIntent: CycleCountCorrectionIntent? = null,
    val appliedCorrection: CycleCountCorrection? = null,
    val notice: CycleCountNotice? = null
) {
    val selectedLot: CycleCountLot? get() = lots.singleOrNull { it.id == selectedLotId }
    val hasMoreLots: Boolean get() = lotPage >= 0 && lots.size.toLong() < lotTotal
    val canRecord: Boolean get() = canWriteCounts && selectedLot != null && metadataAvailable &&
        countCommand == CycleCountCommandStatus.Editing &&
        correctionCommand == CycleCountCommandStatus.Editing &&
        (recordedCount?.status != "REQUESTED" || appliedCorrection != null)
    val canApplyCorrection: Boolean get() = canApplyInventoryCorrection &&
        recordedCount?.status == "REQUESTED" &&
        metadataAvailable &&
        correctionCommand == CycleCountCommandStatus.Editing && appliedCorrection == null
    val isFrozen: Boolean get() = countCommand in
        setOf(
            CycleCountCommandStatus.PersistingIntent,
            CycleCountCommandStatus.Pending,
            CycleCountCommandStatus.UnknownOutcome
        ) ||
        correctionCommand in setOf(
            CycleCountCommandStatus.PersistingIntent,
            CycleCountCommandStatus.Pending,
            CycleCountCommandStatus.UnknownOutcome
        )
    override fun toString(): String =
        "CycleCountUiState(epoch=$authorityEpoch, lots=${lots.size}, command=$countCommand)"
}
