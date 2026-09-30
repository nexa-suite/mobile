package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DispositionLotStatus {
    Idle,
    Loading,
    Current,
    InvalidIdentifier,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class DispositionCommandStatus {
    Editing,
    SavingNote,
    NoteSavedUnconfirmed,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    PreconditionFailed,
    Conflict,
    Rejected,
    Confirmed
}

enum class DispositionMetadataStatus { Loading, Available, Saving, Unavailable }

enum class DispositionValidationError {
    LotIdRequired,
    LotIdInvalid,
    LotMustBeReloaded,
    ActionRequired,
    ReasonRequired,
    ReasonTooLong,
    PermissionRequired,
    MetadataUnavailable,
    IntentMustBeReviewed
}

data class DispositionUiState(
    val authorityEpoch: Long = 0,
    val permissions: Set<String> = emptySet(),
    val lotIdText: String = "",
    val disposition: LotDispositionAction? = null,
    val reasonText: String = "",
    val metadata: DispositionMetadataStatus = DispositionMetadataStatus.Loading,
    val lotStatus: DispositionLotStatus = DispositionLotStatus.Idle,
    val lotFacts: DispositionLotFacts? = null,
    val commandStatus: DispositionCommandStatus = DispositionCommandStatus.Editing,
    val validationError: DispositionValidationError? = null,
    val rejectionCode: String? = null,
    val intent: DispositionIntentMetadata? = null,
    val noteSaved: Boolean = false,
    val terminalIntentRefreshed: Boolean = false,
    val intentCleanupPending: Boolean = false
) {
    val canReadLots: Boolean
        get() = permissions.any {
            it in setOf("inventory.read", "warehouse.read", "warehouse:read")
        }

    val canRecordSelectedDisposition: Boolean
        get() = disposition?.let { action ->
            when (action) {
                LotDispositionAction.RELEASE -> "inventory.release" in permissions

                LotDispositionAction.HOLD,
                LotDispositionAction.WASTE,
                LotDispositionAction.RETURN_TO_SUPPLIER -> "inventory.waste" in permissions
            }
        } == true

    val isIntentFrozen: Boolean
        get() = intent != null || commandStatus in setOf(
            DispositionCommandStatus.PersistingIntent,
            DispositionCommandStatus.Pending,
            DispositionCommandStatus.UnknownOutcome,
            DispositionCommandStatus.PreconditionFailed,
            DispositionCommandStatus.Conflict,
            DispositionCommandStatus.Rejected
        ) || intentCleanupPending

    val canSubmit: Boolean
        get() = metadata == DispositionMetadataStatus.Available &&
            lotStatus == DispositionLotStatus.Current &&
            lotFacts?.id.equals(lotIdText.trim(), ignoreCase = true) &&
            canRecordSelectedDisposition && disposition != null && reasonText.trim().isNotEmpty() &&
            reasonText.trim().length <= 2_000 && intent == null && !intentCleanupPending &&
            commandStatus in setOf(
                DispositionCommandStatus.Editing,
                DispositionCommandStatus.NoteSavedUnconfirmed
            )

    val canReplay: Boolean
        get() = metadata == DispositionMetadataStatus.Available &&
            commandStatus == DispositionCommandStatus.UnknownOutcome && intent != null &&
            intent?.let { permissionFor(it.command.disposition) in permissions } == true

    val canStartNewDecision: Boolean
        get() = metadata == DispositionMetadataStatus.Available && intent != null &&
            commandStatus in setOf(
                DispositionCommandStatus.PreconditionFailed,
                DispositionCommandStatus.Conflict,
                DispositionCommandStatus.Rejected
            ) && terminalIntentRefreshed &&
            lotFacts?.id.equals(intent?.command?.lotId, ignoreCase = true)

    override fun toString(): String =
        "DispositionUiState(epoch=$authorityEpoch, lotStatus=$lotStatus)"

    private fun permissionFor(action: LotDispositionAction): String = when (action) {
        LotDispositionAction.RELEASE -> "inventory.release"

        LotDispositionAction.HOLD,
        LotDispositionAction.WASTE,
        LotDispositionAction.RETURN_TO_SUPPLIER -> "inventory.waste"
    }
}

/** Explicitly driven disposition flow; local notes and frozen intents never change stock. */
class DispositionViewModel(
    private val gateway: DispositionGateway,
    private val metadataStore: DispositionMetadataStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispositionUiState())
    val state = mutableState.asStateFlow()

    private var authority: DispositionAuthority? = null
    private var generation = 0L
    private val metadataMutex = Mutex()

    fun activate(currentAuthority: DispositionAuthority) {
        if (authority == currentAuthority &&
            mutableState.value.metadata != DispositionMetadataStatus.Unavailable
        ) {
            return
        }
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        mutableState.value = DispositionUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            permissions = currentAuthority.permissions,
            metadata = DispositionMetadataStatus.Loading,
            lotStatus = if (currentAuthority.canReadLots) {
                DispositionLotStatus.Idle
            } else {
                DispositionLotStatus.PermissionDenied
            }
        )
        viewModelScope.launch {
            val (draftRead, intentRead) = metadataMutex.withLock {
                safeMetadataRead { metadataStore.loadDraft(currentAuthority.scope) } to
                    safeMetadataRead { metadataStore.loadIntent(currentAuthority.scope) }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val draft = (draftRead as? DispositionMetadataRead.Available)?.value
            val stored = (intentRead as? DispositionMetadataRead.Available)?.value
                ?.takeIf { it.scope == currentAuthority.scope }
            val restored = stored?.let { intent ->
                if (intent.status == DispositionIntentMetadataStatus.Pending) {
                    intent.copy(status = DispositionIntentMetadataStatus.UnknownOutcome)
                } else {
                    intent
                }
            }
            val metadataAvailable = draftRead is DispositionMetadataRead.Available &&
                intentRead is DispositionMetadataRead.Available
            mutableState.update { current ->
                val command = restored?.command
                current.copy(
                    lotIdText = command?.lotId ?: draft?.lotIdText.orEmpty(),
                    disposition = command?.disposition ?: draft?.disposition,
                    reasonText = command?.reason ?: draft?.reason.orEmpty(),
                    metadata = if (metadataAvailable) {
                        DispositionMetadataStatus.Available
                    } else {
                        DispositionMetadataStatus.Unavailable
                    },
                    commandStatus =
                        restored?.status?.toUiStatus() ?: DispositionCommandStatus.Editing,
                    intent = restored,
                    noteSaved = draft != null,
                    validationError = if (metadataAvailable) {
                        null
                    } else {
                        DispositionValidationError.MetadataUnavailable
                    }
                )
            }
            if (stored != null && stored.status == DispositionIntentMetadataStatus.Pending &&
                metadataAvailable
            ) {
                val result = metadataMutex.withLock {
                    if (isCurrent(requestGeneration, currentAuthority)) {
                        safeMetadataWrite {
                            metadataStore.saveIntent(
                                stored.copy(status = DispositionIntentMetadataStatus.UnknownOutcome)
                            )
                        }
                    } else {
                        DispositionMetadataWrite.Unavailable
                    }
                }
                if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                if (result != DispositionMetadataWrite.Saved) {
                    mutableState.update {
                        it.copy(
                            metadata = DispositionMetadataStatus.Unavailable,
                            validationError = DispositionValidationError.MetadataUnavailable
                        )
                    }
                }
            }
        }
    }

    fun deactivate() {
        generation++
        authority = null
        mutableState.value = DispositionUiState()
    }

    fun lotIdChanged(value: String) {
        val current = mutableState.value
        if (current.isIntentFrozen) return
        mutableState.value = current.copy(
            lotIdText = value,
            lotFacts = null,
            lotStatus = DispositionLotStatus.Idle,
            commandStatus = DispositionCommandStatus.Editing,
            validationError = null,
            rejectionCode = null,
            noteSaved = false
        )
    }

    fun selectDisposition(value: LotDispositionAction) {
        val current = mutableState.value
        if (current.isIntentFrozen) return
        mutableState.value = current.copy(
            disposition = value,
            commandStatus = DispositionCommandStatus.Editing,
            validationError = null,
            noteSaved = false
        )
    }

    fun reasonChanged(value: String) {
        val current = mutableState.value
        if (current.isIntentFrozen) return
        mutableState.value = current.copy(
            reasonText = value.take(MAX_REASON_LENGTH),
            commandStatus = DispositionCommandStatus.Editing,
            validationError = null,
            noteSaved = false
        )
    }

    fun loadLot() {
        val currentAuthority = authority ?: return
        val before = mutableState.value
        if (before.commandStatus in setOf(
                DispositionCommandStatus.PersistingIntent,
                DispositionCommandStatus.Pending
            )
        ) {
            return
        }
        if (!currentAuthority.canReadLots) {
            mutableState.value = before.copy(lotStatus = DispositionLotStatus.PermissionDenied)
            return
        }
        val lotId = before.lotIdText.trim()
        if (lotId.isEmpty() || !LOT_UUID.matches(lotId)) {
            val invalidLotStatus = if (lotId.isEmpty()) {
                DispositionLotStatus.Idle
            } else {
                DispositionLotStatus.InvalidIdentifier
            }
            val invalidReason = if (lotId.isEmpty()) {
                DispositionValidationError.LotIdRequired
            } else {
                DispositionValidationError.LotIdInvalid
            }
            mutableState.value = before.copy(
                lotStatus = invalidLotStatus,
                lotFacts = null,
                validationError = invalidReason
            )
            return
        }
        val requestGeneration = ++generation
        val wasTerminal = before.commandStatus in TERMINAL_INTENT_STATUSES
        mutableState.value = before.copy(
            lotStatus = DispositionLotStatus.Loading,
            lotFacts = null,
            validationError = null,
            terminalIntentRefreshed = false
        )
        viewModelScope.launch {
            val result = safeGateway { gateway.lot(lotId, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (result) {
                is DispositionGatewayResult.Lot -> {
                    if (result.facts.id.equals(lotId, ignoreCase = true)) {
                        mutableState.update {
                            it.copy(
                                lotStatus = DispositionLotStatus.Current,
                                lotFacts = result.facts,
                                terminalIntentRefreshed = wasTerminal,
                                validationError = null
                            )
                        }
                    } else {
                        setLotFailure(DispositionLotStatus.ServiceUnavailable)
                    }
                }

                DispositionGatewayResult.NetworkUnavailable -> setLotFailure(
                    DispositionLotStatus.NetworkUnavailable
                )

                DispositionGatewayResult.ServiceUnavailable,
                is DispositionGatewayResult.Rejected,
                DispositionGatewayResult.UnknownOutcome,
                DispositionGatewayResult.PreconditionFailed,
                DispositionGatewayResult.Conflict,
                is DispositionGatewayResult.Confirmed -> setLotFailure(
                    DispositionLotStatus.ServiceUnavailable
                )

                DispositionGatewayResult.PermissionDenied -> setLotFailure(
                    DispositionLotStatus.PermissionDenied
                )

                DispositionGatewayResult.ContextInvalidated -> setLotFailure(
                    DispositionLotStatus.ContextInvalidated
                )

                DispositionGatewayResult.SessionInvalidated -> setLotFailure(
                    DispositionLotStatus.SessionInvalidated
                )
            }
        }
    }

    fun saveLocalNote() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (current.isIntentFrozen) return
        if (current.metadata != DispositionMetadataStatus.Available) {
            mutableState.value =
                current.copy(validationError = DispositionValidationError.MetadataUnavailable)
            return
        }
        val draft = DispositionDraftMetadata(
            lotIdText = current.lotIdText,
            disposition = current.disposition,
            reason = current.reasonText
        )
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                metadata = DispositionMetadataStatus.Saving,
                commandStatus = DispositionCommandStatus.SavingNote
            )
        }
        viewModelScope.launch {
            val result = metadataMutex.withLock {
                if (isCurrent(requestGeneration, currentAuthority)) {
                    safeMetadataWrite { metadataStore.saveDraft(currentAuthority.scope, draft) }
                } else {
                    DispositionMetadataWrite.Unavailable
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (result == DispositionMetadataWrite.Saved) {
                mutableState.update {
                    it.copy(
                        metadata = DispositionMetadataStatus.Available,
                        commandStatus = if (it.intent == null) {
                            DispositionCommandStatus.NoteSavedUnconfirmed
                        } else {
                            it.commandStatus
                        },
                        noteSaved = true,
                        validationError = null
                    )
                }
            } else {
                mutableState.update {
                    it.copy(
                        metadata = DispositionMetadataStatus.Unavailable,
                        commandStatus = if (it.intent ==
                            null
                        ) {
                            DispositionCommandStatus.Editing
                        } else {
                            it.commandStatus
                        },
                        validationError = DispositionValidationError.MetadataUnavailable
                    )
                }
            }
        }
    }

    fun submit() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (current.intent != null || current.isIntentFrozen) return
        val facts = current.lotFacts
        if (facts == null || current.lotStatus != DispositionLotStatus.Current ||
            !facts.id.equals(current.lotIdText.trim(), ignoreCase = true)
        ) {
            mutableState.value =
                current.copy(validationError = DispositionValidationError.LotMustBeReloaded)
            return
        }
        val action = current.disposition
        if (action == null) {
            mutableState.value =
                current.copy(validationError = DispositionValidationError.ActionRequired)
            return
        }
        if (!currentAuthority.canRecord(action)) {
            mutableState.value =
                current.copy(validationError = DispositionValidationError.PermissionRequired)
            return
        }
        val reason = current.reasonText.trim()
        if (reason.isEmpty()) {
            mutableState.value =
                current.copy(validationError = DispositionValidationError.ReasonRequired)
            return
        }
        if (reason.length > MAX_REASON_LENGTH) {
            mutableState.value =
                current.copy(validationError = DispositionValidationError.ReasonTooLong)
            return
        }
        if (current.metadata != DispositionMetadataStatus.Available) {
            mutableState.value =
                current.copy(validationError = DispositionValidationError.MetadataUnavailable)
            return
        }
        val command = LotDispositionCommand(facts.id, action, reason, facts.version)
        val frozen = DispositionIntentMetadata(
            currentAuthority.scope,
            UUID.randomUUID().toString(),
            command,
            DispositionIntentMetadataStatus.Pending
        )
        val requestGeneration = ++generation
        mutableState.update {
            it.copy(
                commandStatus = DispositionCommandStatus.PersistingIntent,
                validationError = null,
                rejectionCode = null,
                intent = frozen
            )
        }
        viewModelScope.launch {
            val persisted = metadataMutex.withLock {
                if (isCurrent(requestGeneration, currentAuthority)) {
                    safeMetadataWrite { metadataStore.saveIntent(frozen) }
                } else {
                    DispositionMetadataWrite.Unavailable
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (persisted != DispositionMetadataWrite.Saved) {
                mutableState.update {
                    it.copy(
                        commandStatus = DispositionCommandStatus.Editing,
                        intent = null,
                        metadata = DispositionMetadataStatus.Unavailable,
                        validationError = DispositionValidationError.MetadataUnavailable
                    )
                }
                return@launch
            }
            mutableState.update { it.copy(commandStatus = DispositionCommandStatus.Pending) }
            dispatchFrozen(frozen, requestGeneration, currentAuthority)
        }
    }

    /** Explicit user action. Payload, version, and key are loaded from the frozen record. */
    fun replayUnknownOutcome() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val frozen = current.intent ?: return
        if (current.commandStatus != DispositionCommandStatus.UnknownOutcome ||
            frozen.scope != currentAuthority.scope ||
            !currentAuthority.canRecord(frozen.command.disposition)
        ) {
            return
        }
        val requestGeneration = ++generation
        val replay = frozen.copy(status = DispositionIntentMetadataStatus.Pending)
        mutableState.update { it.copy(commandStatus = DispositionCommandStatus.PersistingIntent) }
        viewModelScope.launch {
            val persisted = metadataMutex.withLock {
                if (isCurrent(requestGeneration, currentAuthority)) {
                    safeMetadataWrite { metadataStore.saveIntent(replay) }
                } else {
                    DispositionMetadataWrite.Unavailable
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (persisted != DispositionMetadataWrite.Saved) {
                mutableState.update {
                    it.copy(
                        commandStatus = DispositionCommandStatus.UnknownOutcome,
                        validationError = DispositionValidationError.MetadataUnavailable
                    )
                }
                return@launch
            }
            mutableState.update {
                it.copy(intent = replay, commandStatus = DispositionCommandStatus.Pending)
            }
            dispatchFrozen(replay, requestGeneration, currentAuthority)
        }
    }

    /** Clears a known rejected/stale intent only after a fresh lot read and explicit review. */
    fun startNewDecision() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val frozen = current.intent ?: return
        if (!current.canStartNewDecision || frozen.scope != currentAuthority.scope) {
            mutableState.update {
                it.copy(validationError = DispositionValidationError.IntentMustBeReviewed)
            }
            return
        }
        val requestGeneration = ++generation
        viewModelScope.launch {
            val cleared = metadataMutex.withLock {
                if (isCurrent(requestGeneration, currentAuthority)) {
                    safeMetadataWrite {
                        metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
                    }
                } else {
                    DispositionMetadataWrite.Unavailable
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (cleared == DispositionMetadataWrite.Saved) {
                mutableState.update {
                    it.copy(
                        intent = null,
                        commandStatus = DispositionCommandStatus.Editing,
                        terminalIntentRefreshed = false,
                        rejectionCode = null,
                        validationError = null
                    )
                }
            } else {
                mutableState.update {
                    it.copy(
                        metadata = DispositionMetadataStatus.Unavailable,
                        validationError = DispositionValidationError.MetadataUnavailable
                    )
                }
            }
        }
    }

    private suspend fun dispatchFrozen(
        frozen: DispositionIntentMetadata,
        requestGeneration: Long,
        currentAuthority: DispositionAuthority
    ) {
        val result = safeGateway {
            gateway.dispose(frozen.command, frozen.idempotencyKey, currentAuthority)
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        when (result) {
            is DispositionGatewayResult.Confirmed -> {
                if (!result.facts.id.equals(frozen.command.lotId, ignoreCase = true)) {
                    persistKnownOutcome(frozen, DispositionIntentMetadataStatus.Rejected)
                    mutableState.update {
                        it.copy(
                            commandStatus = DispositionCommandStatus.Rejected,
                            rejectionCode = "INVALID_RESPONSE"
                        )
                    }
                    return
                }
                val clear = metadataMutex.withLock {
                    if (isCurrent(requestGeneration, currentAuthority)) {
                        safeMetadataWrite {
                            metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
                        }
                    } else {
                        DispositionMetadataWrite.Unavailable
                    }
                }
                if (!isCurrent(requestGeneration, currentAuthority)) return
                mutableState.update {
                    it.copy(
                        lotIdText = result.facts.id,
                        lotStatus = DispositionLotStatus.Current,
                        lotFacts = result.facts,
                        commandStatus = DispositionCommandStatus.Confirmed,
                        intent = if (clear == DispositionMetadataWrite.Saved) null else frozen,
                        intentCleanupPending = clear != DispositionMetadataWrite.Saved,
                        validationError = if (clear ==
                            DispositionMetadataWrite.Saved
                        ) {
                            null
                        } else {
                            DispositionValidationError.MetadataUnavailable
                        }
                    )
                }
            }

            DispositionGatewayResult.UnknownOutcome,
            DispositionGatewayResult.NetworkUnavailable,
            DispositionGatewayResult.ServiceUnavailable -> {
                persistKnownOutcome(frozen, DispositionIntentMetadataStatus.UnknownOutcome)
                mutableState.update {
                    it.copy(commandStatus = DispositionCommandStatus.UnknownOutcome)
                }
            }

            DispositionGatewayResult.PreconditionFailed -> {
                persistKnownOutcome(frozen, DispositionIntentMetadataStatus.PreconditionFailed)
                mutableState.update {
                    it.copy(
                        commandStatus = DispositionCommandStatus.PreconditionFailed,
                        terminalIntentRefreshed = false
                    )
                }
            }

            DispositionGatewayResult.Conflict -> {
                persistKnownOutcome(frozen, DispositionIntentMetadataStatus.Conflict)
                mutableState.update {
                    it.copy(
                        commandStatus = DispositionCommandStatus.Conflict,
                        terminalIntentRefreshed = false
                    )
                }
            }

            is DispositionGatewayResult.Rejected -> {
                persistKnownOutcome(frozen, DispositionIntentMetadataStatus.Rejected)
                mutableState.update {
                    it.copy(
                        commandStatus = DispositionCommandStatus.Rejected,
                        rejectionCode = result.code,
                        terminalIntentRefreshed = false
                    )
                }
            }

            DispositionGatewayResult.PermissionDenied -> {
                persistKnownOutcome(frozen, DispositionIntentMetadataStatus.Rejected)
                mutableState.update {
                    it.copy(
                        commandStatus = DispositionCommandStatus.Rejected,
                        rejectionCode = "PERMISSION_DENIED",
                        terminalIntentRefreshed = false
                    )
                }
            }

            DispositionGatewayResult.ContextInvalidated -> {
                if (clearUnsentIntent(frozen, requestGeneration, currentAuthority) ==
                    DispositionMetadataWrite.Saved
                ) {
                    mutableState.update {
                        it.copy(
                            commandStatus = DispositionCommandStatus.Editing,
                            intent = null,
                            lotStatus = DispositionLotStatus.ContextInvalidated,
                            lotFacts = null
                        )
                    }
                } else {
                    persistKnownOutcome(frozen, DispositionIntentMetadataStatus.UnknownOutcome)
                    mutableState.update {
                        it.copy(
                            commandStatus = DispositionCommandStatus.UnknownOutcome,
                            metadata = DispositionMetadataStatus.Unavailable
                        )
                    }
                }
            }

            DispositionGatewayResult.SessionInvalidated -> {
                if (clearUnsentIntent(frozen, requestGeneration, currentAuthority) ==
                    DispositionMetadataWrite.Saved
                ) {
                    mutableState.update {
                        it.copy(
                            commandStatus = DispositionCommandStatus.Editing,
                            intent = null,
                            lotStatus = DispositionLotStatus.SessionInvalidated,
                            lotFacts = null
                        )
                    }
                } else {
                    persistKnownOutcome(frozen, DispositionIntentMetadataStatus.UnknownOutcome)
                    mutableState.update {
                        it.copy(
                            commandStatus = DispositionCommandStatus.UnknownOutcome,
                            metadata = DispositionMetadataStatus.Unavailable
                        )
                    }
                }
            }

            is DispositionGatewayResult.Lot -> {
                persistKnownOutcome(frozen, DispositionIntentMetadataStatus.UnknownOutcome)
                mutableState.update {
                    it.copy(commandStatus = DispositionCommandStatus.UnknownOutcome)
                }
            }
        }
    }

    private suspend fun persistKnownOutcome(
        frozen: DispositionIntentMetadata,
        status: DispositionIntentMetadataStatus
    ) {
        val currentAuthority = authority ?: return
        if (currentAuthority.scope != frozen.scope) return
        val updated = frozen.copy(status = status)
        metadataMutex.withLock { safeMetadataWrite { metadataStore.saveIntent(updated) } }
        if (authority == currentAuthority) mutableState.update { it.copy(intent = updated) }
    }

    private suspend fun clearUnsentIntent(
        frozen: DispositionIntentMetadata,
        requestGeneration: Long,
        currentAuthority: DispositionAuthority
    ): DispositionMetadataWrite = metadataMutex.withLock {
        if (isCurrent(requestGeneration, currentAuthority)) {
            safeMetadataWrite {
                metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
            }
        } else {
            DispositionMetadataWrite.Unavailable
        }
    }

    private fun setLotFailure(status: DispositionLotStatus) {
        mutableState.update {
            it.copy(lotStatus = status, lotFacts = null, terminalIntentRefreshed = false)
        }
    }

    private fun isCurrent(requestGeneration: Long, expected: DispositionAuthority): Boolean =
        requestGeneration == generation && authority == expected &&
            mutableState.value.authorityEpoch == expected.authorityEpoch

    private suspend fun safeGateway(
        block: suspend () -> DispositionGatewayResult
    ): DispositionGatewayResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispositionGatewayResult.ServiceUnavailable
    }

    private suspend fun <T> safeMetadataRead(
        block: suspend () -> DispositionMetadataRead<T>
    ): DispositionMetadataRead<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispositionMetadataRead.Unavailable
    }

    private suspend fun safeMetadataWrite(
        block: suspend () -> DispositionMetadataWrite
    ): DispositionMetadataWrite = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispositionMetadataWrite.Unavailable
    }

    private fun DispositionIntentMetadataStatus.toUiStatus(): DispositionCommandStatus =
        when (this) {
            DispositionIntentMetadataStatus.Pending -> DispositionCommandStatus.UnknownOutcome

            DispositionIntentMetadataStatus.UnknownOutcome ->
                DispositionCommandStatus.UnknownOutcome

            DispositionIntentMetadataStatus.PreconditionFailed ->
                DispositionCommandStatus.PreconditionFailed

            DispositionIntentMetadataStatus.Conflict -> DispositionCommandStatus.Conflict

            DispositionIntentMetadataStatus.Rejected -> DispositionCommandStatus.Rejected
        }

    private companion object {
        const val MAX_REASON_LENGTH = 2_000
        val LOT_UUID = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TERMINAL_INTENT_STATUSES = setOf(
            DispositionCommandStatus.PreconditionFailed,
            DispositionCommandStatus.Conflict,
            DispositionCommandStatus.Rejected
        )
    }
}
