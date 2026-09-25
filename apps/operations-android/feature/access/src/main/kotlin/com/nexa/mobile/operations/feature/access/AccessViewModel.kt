package com.nexa.mobile.operations.feature.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AccessViewModel(
    private val gateway: AccessGateway,
    initialState: AccessUiState = AccessUiState()
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState)
    val state = mutableState.asStateFlow()

    private var requestGeneration = 0L

    fun identifierChanged(value: String) {
        mutableState.update { it.copy(identifier = value, identifierError = false, notice = null) }
    }

    fun passwordChanged(value: String) {
        mutableState.update { it.copy(password = value, passwordError = false, notice = null) }
    }

    fun togglePasswordVisibility() {
        mutableState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun signIn() {
        val snapshot = mutableState.value
        val identifier = snapshot.identifier.trim()
        val password = snapshot.password
        val identifierError = identifier.isEmpty()
        val passwordError = password.isEmpty()
        if (identifierError || passwordError) {
            mutableState.update {
                it.copy(
                    identifierError = identifierError,
                    passwordError = passwordError,
                    notice = null
                )
            }
            return
        }

        val generation = ++requestGeneration
        mutableState.update {
            it.copy(
                identifier = identifier,
                password = "",
                passwordVisible = false,
                stage = AccessStage.Authenticating,
                identifierError = false,
                passwordError = false,
                notice = null,
                chooser = null,
                activeContext = null
            )
        }
        viewModelScope.launch {
            val result = runCatching { gateway.signIn(identifier, password) }
                .getOrElse { SignInResult.ServiceUnavailable }
            if (generation != requestGeneration) return@launch
            when (result) {
                is SignInResult.Authenticated -> authorizeContext(result.context)

                SignInResult.SelectionRequired ->
                    loadContexts(ContextChooserMode.Initial, null, generation)

                SignInResult.NoWorkContext -> mutableState.value = AccessUiState(
                    stage = AccessStage.NoContexts,
                    notice = AccessNotice.NoContexts,
                    authorityEpoch = mutableState.value.authorityEpoch + 1
                )

                SignInResult.Rejected -> updateStage(
                    AccessStage.IdentityRequired,
                    AccessNotice.AuthenticationRejected
                )

                SignInResult.NetworkUnavailable -> updateStage(
                    AccessStage.IdentityRequired,
                    AccessNotice.NetworkUnavailable
                )

                SignInResult.ServiceUnavailable -> updateStage(
                    AccessStage.IdentityRequired,
                    AccessNotice.ServiceUnavailable
                )

                SignInResult.IntegrationUnavailable -> updateStage(
                    AccessStage.IdentityRequired,
                    AccessNotice.IntegrationUnavailable
                )
            }
        }
    }

    fun resolveCurrentSessionContext() {
        if (mutableState.value.stage == AccessStage.ResolvingContexts) return
        val generation = ++requestGeneration
        mutableState.value = mutableState.value.copy(
            stage = AccessStage.ResolvingContexts,
            chooser = null,
            activeContext = null,
            notice = null
        )
        viewModelScope.launch {
            val result = runCatching { gateway.currentSessionContext() }
                .getOrElse { CurrentSessionContextResult.Unavailable }
            if (generation != requestGeneration) return@launch
            when (result) {
                is CurrentSessionContextResult.Available -> authorizeContext(result.context)

                CurrentSessionContextResult.Invalid -> failClosed(
                    AccessNotice.UnknownContextOutcome
                )

                CurrentSessionContextResult.Unavailable -> updateStage(
                    AccessStage.IdentityRequired,
                    AccessNotice.ServiceUnavailable
                )
            }
        }
    }

    fun openInitialContextChooser() {
        val generation = ++requestGeneration
        mutableState.update {
            it.copy(
                stage = AccessStage.ResolvingContexts,
                notice = null,
                chooser = ContextChooserUiState(
                    mode = ContextChooserMode.Initial,
                    phase = ContextChooserPhase.Loading
                ),
                activeContext = null
            )
        }
        viewModelScope.launch { loadContexts(ContextChooserMode.Initial, null, generation) }
    }

    fun beginContextChange() {
        val current = mutableState.value.activeContext ?: return
        val generation = ++requestGeneration
        mutableState.update {
            it.copy(
                stage = AccessStage.ContextChooser,
                notice = null,
                chooser = ContextChooserUiState(
                    mode = ContextChooserMode.Change,
                    phase = ContextChooserPhase.Loading,
                    current = current.copy(isCurrent = true)
                )
            )
        }
        viewModelScope.launch { loadContexts(ContextChooserMode.Change, current, generation) }
    }

    fun selectContext(key: String) {
        val snapshot = mutableState.value
        val chooser = snapshot.chooser ?: return
        if (snapshot.stage != AccessStage.ContextChooser ||
            chooser.phase !in setOf(
                ContextChooserPhase.Choices,
                ContextChooserPhase.SelectionRejected
            )
        ) {
            return
        }
        val target = chooser.choices.firstOrNull { it.key == key } ?: return
        if (target.isCurrent) return
        val generation = ++requestGeneration
        mutableState.update {
            it.copy(
                notice = null,
                chooser = chooser.copy(
                    phase = ContextChooserPhase.SelectionPending,
                    pendingKey = key
                )
            )
        }
        viewModelScope.launch {
            val result = runCatching { gateway.selectContext(target) }
                .getOrElse { ContextSelectionResult.UnknownOutcome }
            if (generation != requestGeneration) return@launch
            when (result) {
                is ContextSelectionResult.Confirmed -> {
                    if (result.context.key != target.key) {
                        failClosed(AccessNotice.UnknownContextOutcome)
                    } else {
                        authorizeContext(result.context)
                    }
                }

                ContextSelectionResult.Rejected -> failClosed(
                    AccessNotice.ContextSelectionRejected
                )

                ContextSelectionResult.Unavailable -> failClosed(
                    AccessNotice.ContextSelectionUnavailable
                )

                ContextSelectionResult.IntegrationUnavailable -> failClosed(
                    AccessNotice.IntegrationUnavailable
                )

                ContextSelectionResult.UnknownOutcome -> failClosed(
                    AccessNotice.UnknownContextOutcome
                )

                ContextSelectionResult.SessionExpired -> failClosed(
                    AccessNotice.ContextSelectionUnavailable
                )
            }
        }
    }

    fun backFromContextChooser() {
        val snapshot = mutableState.value
        val chooser = snapshot.chooser ?: return
        if (chooser.mode != ContextChooserMode.Change ||
            chooser.phase == ContextChooserPhase.SelectionPending
        ) {
            return
        }
        mutableState.update {
            it.copy(
                stage = AccessStage.WorkAuthorized,
                chooser = null,
                notice = null
            )
        }
    }

    fun retryContextList() {
        val snapshot = mutableState.value
        val chooser = snapshot.chooser ?: return
        if (chooser.phase !in
            setOf(ContextChooserPhase.ListUnavailable, ContextChooserPhase.SelectionRejected)
        ) {
            return
        }
        val generation = ++requestGeneration
        mutableState.update {
            it.copy(chooser = chooser.copy(phase = ContextChooserPhase.Loading), notice = null)
        }
        viewModelScope.launch { loadContexts(chooser.mode, chooser.current, generation) }
    }

    fun sessionInvalidated(expired: Boolean = false) {
        requestGeneration++
        val old = mutableState.value
        mutableState.value = AccessUiState(
            stage = if (expired) AccessStage.SessionExpired else AccessStage.IdentityRequired,
            notice = if (expired) AccessNotice.SessionExpired else null,
            authorityEpoch = old.authorityEpoch + 1
        )
    }

    fun contextInvalidated() {
        requestGeneration++
        val old = mutableState.value
        mutableState.value = AccessUiState(
            stage = AccessStage.IdentityRequired,
            notice = AccessNotice.UnknownContextOutcome,
            authorityEpoch = old.authorityEpoch + 1
        )
    }

    fun localProtectionError() {
        requestGeneration++
        val old = mutableState.value
        mutableState.value = AccessUiState(
            stage = AccessStage.LocalProtectionError,
            authorityEpoch = old.authorityEpoch + 1
        )
    }

    private suspend fun loadContexts(
        mode: ContextChooserMode,
        current: WorkforceContextSummary?,
        generation: Long
    ) {
        mutableState.update { it.copy(stage = AccessStage.ResolvingContexts) }
        val result = runCatching { gateway.listContexts() }
            .getOrElse { ContextListResult.ServiceUnavailable }
        if (generation != requestGeneration) return
        when (result) {
            is ContextListResult.Available -> handleContexts(
                result.contexts,
                mode,
                current,
                generation
            )

            ContextListResult.NetworkUnavailable -> showListUnavailable(
                mode,
                current,
                AccessNotice.NetworkUnavailable
            )

            ContextListResult.ServiceUnavailable -> showListUnavailable(
                mode,
                current,
                AccessNotice.ServiceUnavailable
            )

            ContextListResult.IntegrationUnavailable -> showListUnavailable(
                mode,
                current,
                AccessNotice.IntegrationUnavailable
            )

            ContextListResult.TicketExpired -> failClosed(AccessNotice.ContextListUnavailable)

            ContextListResult.SessionExpired -> invalidateSession()
        }
    }

    private suspend fun handleContexts(
        contexts: List<WorkforceContextSummary>,
        mode: ContextChooserMode,
        current: WorkforceContextSummary?,
        generation: Long
    ) {
        if (generation != requestGeneration) return
        val choices = contexts.map { it.copy(isCurrent = current?.key == it.key) }
        when {
            choices.isEmpty() -> {
                mutableState.value = mutableState.value.copy(
                    stage = if (current ==
                        null
                    ) {
                        AccessStage.NoContexts
                    } else {
                        AccessStage.ContextChooser
                    },
                    chooser = if (mode == ContextChooserMode.Change) {
                        ContextChooserUiState(
                            mode = mode,
                            phase = ContextChooserPhase.OnlyCurrent,
                            current = current?.copy(isCurrent = true)
                        )
                    } else {
                        null
                    },
                    activeContext = current,
                    notice = if (current == null) AccessNotice.NoContexts else null
                )
            }

            mode == ContextChooserMode.Change && choices.size == 1 &&
                current?.key == choices.single().key -> {
                mutableState.update {
                    it.copy(
                        stage = AccessStage.ContextChooser,
                        activeContext = current.copy(isCurrent = true),
                        chooser = ContextChooserUiState(
                            mode = mode,
                            phase = ContextChooserPhase.OnlyCurrent,
                            current = current.copy(isCurrent = true),
                            choices = choices
                        )
                    )
                }
            }

            else -> mutableState.update {
                it.copy(
                    stage = AccessStage.ContextChooser,
                    activeContext = current,
                    chooser = ContextChooserUiState(
                        mode = mode,
                        phase = ContextChooserPhase.Choices,
                        current = current?.copy(isCurrent = true),
                        choices = choices
                    )
                )
            }
        }
    }

    private fun showListUnavailable(
        mode: ContextChooserMode,
        current: WorkforceContextSummary?,
        notice: AccessNotice
    ) {
        mutableState.update {
            it.copy(
                stage = AccessStage.ContextChooser,
                activeContext = current,
                notice = notice,
                chooser = ContextChooserUiState(
                    mode = mode,
                    phase = ContextChooserPhase.ListUnavailable,
                    current = current?.copy(isCurrent = true),
                    unavailableReason = if (current == null) {
                        ContextUnavailableReason.NoActiveContext
                    } else {
                        ContextUnavailableReason.CurrentContextRemainsValid
                    }
                )
            )
        }
    }

    private fun failClosed(notice: AccessNotice) {
        val old = mutableState.value
        requestGeneration++
        mutableState.value = AccessUiState(
            stage = AccessStage.IdentityRequired,
            notice = notice,
            authorityEpoch = old.authorityEpoch + 1
        )
    }

    private fun authorizeContext(context: WorkforceContextSummary) {
        mutableState.value = AccessUiState(
            stage = AccessStage.WorkAuthorized,
            activeContext = context.copy(isCurrent = true),
            authorityEpoch = mutableState.value.authorityEpoch + 1
        )
    }

    private fun invalidateSession() {
        sessionInvalidated(expired = true)
    }

    private fun updateStage(stage: AccessStage, notice: AccessNotice) {
        mutableState.update { it.copy(stage = stage, notice = notice, chooser = null) }
    }
}
