package com.nexa.mobile.operations.feature.access

import androidx.compose.runtime.Immutable

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

enum class PermissionHint { Available, Unavailable, Unknown }

@Immutable
data class WorkforceContextSummary(
    val key: String,
    val companyName: String,
    val workspaceName: String,
    val permissionHint: PermissionHint = PermissionHint.Unknown,
    val isCurrent: Boolean = false
) {
    override fun toString(): String = "WorkforceContextSummary(companyName=$companyName, " +
        "workspaceName=$workspaceName, key=REDACTED)"
}

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

sealed interface SignInResult {
    data class Authenticated(val context: WorkforceContextSummary) : SignInResult
    data object SelectionRequired : SignInResult
    data object NoWorkContext : SignInResult
    data object Rejected : SignInResult
    data object NetworkUnavailable : SignInResult
    data object ServiceUnavailable : SignInResult
    data object IntegrationUnavailable : SignInResult
}

sealed interface CurrentSessionContextResult {
    data class Available(val context: WorkforceContextSummary) : CurrentSessionContextResult
    data object Invalid : CurrentSessionContextResult
    data object Unavailable : CurrentSessionContextResult
}

sealed interface ContextListResult {
    data class Available(val contexts: List<WorkforceContextSummary>) : ContextListResult
    data object NetworkUnavailable : ContextListResult
    data object ServiceUnavailable : ContextListResult
    data object IntegrationUnavailable : ContextListResult
    data object TicketExpired : ContextListResult
    data object SessionExpired : ContextListResult
}

sealed interface ContextSelectionResult {
    data class Confirmed(val context: WorkforceContextSummary) : ContextSelectionResult
    data object Rejected : ContextSelectionResult
    data object Unavailable : ContextSelectionResult
    data object IntegrationUnavailable : ContextSelectionResult
    data object UnknownOutcome : ContextSelectionResult
    data object SessionExpired : ContextSelectionResult
}

/** Client port. Implementations must return only server-confirmed context outcomes. */
interface AccessGateway {
    suspend fun signIn(identifier: String, password: String): SignInResult
    suspend fun currentSessionContext(): CurrentSessionContextResult
    suspend fun listContexts(): ContextListResult
    suspend fun selectContext(context: WorkforceContextSummary): ContextSelectionResult
}
