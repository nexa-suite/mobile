package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionAlternative
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionCurrentFacts
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionIntent
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionRequest
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork

enum class LotSubstitutionCommandStatus {
    Editing,
    LoadingAlternatives,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Requested,
    Rejected,
    Stale,
    Conflict,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    ServiceUnavailable
}

enum class LotSubstitutionLookupStatus {
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

@Immutable
data class LotSubstitutionUiState(
    val authorityEpoch: Long = 0,
    val work: LotSubstitutionWork? = null,
    val alternatives: List<LotSubstitutionAlternative> = emptyList(),
    val alternativeLookup: LotSubstitutionLookupStatus = LotSubstitutionLookupStatus.NotRequested,
    val selectedAlternativeId: String? = null,
    val reasonText: String = "",
    val metadataAvailable: Boolean = false,
    val status: LotSubstitutionCommandStatus = LotSubstitutionCommandStatus.Editing,
    val request: LotSubstitutionRequest? = null,
    val frozenIntent: LotSubstitutionIntent? = null,
    val notice: String? = null,
    val hasMoreAlternatives: Boolean = false,
    val canRequest: Boolean = false,
    val currentAllocation: LotSubstitutionCurrentFacts? = null
) {
    val selectedAlternative: LotSubstitutionAlternative?
        get() = alternatives.singleOrNull { it.id == selectedAlternativeId }

    override fun toString(): String =
        "LotSubstitutionUiState(epoch=$authorityEpoch, alternatives=${alternatives.size}, status=$status)"
}
