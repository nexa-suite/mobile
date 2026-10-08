package com.nexa.mobile.operations.feature.access

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.feature.access.model.WorkforceContextSummary

enum class AccessStage {
    IdentityRequired,
    RestoringSession,
    Authenticating,
    ResolvingContexts,
    ContextChooser,
    WorkAuthorized,
    NoContexts,
    SessionExpired,
    LocalProtectionError
}

enum class AccessNotice {
    AuthenticationRejected,
    NetworkUnavailable,
    ServiceUnavailable,
    IntegrationUnavailable,
    SessionExpired,
    ContextListUnavailable,
    ContextSelectionRejected,
    ContextSelectionUnavailable,
    UnknownContextOutcome,
    NoContexts
}

enum class ContextChooserMode { Initial, Change }

enum class ContextChooserPhase {
    Loading,
    Choices,
    SelectionPending,
    SelectionRejected,
    ListUnavailable,
    OnlyCurrent
}

enum class ContextUnavailableReason { NoActiveContext, CurrentContextRemainsValid }

@Immutable
data class ContextChooserUiState(
    val mode: ContextChooserMode,
    val phase: ContextChooserPhase,
    val current: WorkforceContextSummary? = null,
    val choices: List<WorkforceContextSummary> = emptyList(),
    val pendingKey: String? = null,
    val unavailableReason: ContextUnavailableReason? = null
) {
    override fun toString(): String =
        "ContextChooserUiState(mode=$mode, phase=$phase, choices=${choices.size}, " +
            "pending=${pendingKey != null})"
}

@Immutable
data class AccessUiState(
    val identifier: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val stage: AccessStage = AccessStage.IdentityRequired,
    val identifierError: Boolean = false,
    val passwordError: Boolean = false,
    val notice: AccessNotice? = null,
    val chooser: ContextChooserUiState? = null,
    val activeContext: WorkforceContextSummary? = null,
    val authorityEpoch: Long = 0
) {
    override fun toString(): String =
        "AccessUiState(stage=$stage, identifierPresent=${identifier.isNotBlank()}, " +
            "password=REDACTED, notice=$notice, authorityEpoch=$authorityEpoch)"
}
