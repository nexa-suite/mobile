package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionAlternative
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionCurrentFacts
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionIntent
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionRequest
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionWork

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
