package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coordinates cycle-count drafts and explicit, versioned commands. It never treats local data as stock authority. */
class CycleCountViewModel(
    private val gateway: CycleCountGateway,
    private val metadataStore: CycleCountMetadataStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(CycleCountUiState())
    val state: StateFlow<CycleCountUiState> = mutableState.asStateFlow()

    private var activeAuthority: CycleCountAuthority? = null
    private var activation = 0L
    private var metadataJob: Job? = null
    private var lotJob: Job? = null
    private var draftRevision = 0L

    fun activate(authority: CycleCountAuthority) {
        if (activeAuthority == authority) return
        activation += 1
        val currentActivation = activation
        activeAuthority = authority
        metadataJob?.cancel()
        lotJob?.cancel()
        mutableState.value = CycleCountUiState(
            authorityEpoch = authority.authorityEpoch,
            canWriteCounts = WRITE_PERMISSION in authority.permissions,
            canApplyInventoryCorrection = authority.permissions.containsAll(CORRECTION_PERMISSIONS)
        )
        metadataJob = viewModelScope.launch { restoreWork(authority, currentActivation) }
        loadLotsPage(authority, currentActivation, page = 0, replace = true)
    }

    fun deactivate() {
        activation += 1
        activeAuthority = null
        metadataJob?.cancel()
        lotJob?.cancel()
        metadataJob = null
        lotJob = null
        mutableState.value = CycleCountUiState()
    }

    fun reloadLots() {
        val authority = activeAuthority ?: return
        val currentActivation = activation
        loadLotsPage(authority, currentActivation, page = 0, replace = true)
    }

    /** Loads the next server page; already loaded facts remain visible while it runs. */
    fun loadMoreLots() {
        val authority = activeAuthority ?: return
        val current = mutableState.value
        if (!current.hasMoreLots || current.lotLookup == CycleCountLookupStatus.Loading) return
        loadLotsPage(authority, activation, page = current.lotPage + 1, replace = false)
    }

    fun selectLot(lotId: String) {
        val current = mutableState.value
        if (current.isFrozen || (current.recordedCount?.status == REQUESTED_STATUS && current.appliedCorrection == null)) return
        if (current.lots.none { it.id == lotId }) return
        updateDraft(selectedLotId = lotId, observedQuantityText = current.observedQuantityText)
    }

    fun observeQuantityChanged(value: String) {
        val current = mutableState.value
        if (current.isFrozen || (current.recordedCount?.status == REQUESTED_STATUS && current.appliedCorrection == null)) return
        updateDraft(selectedLotId = current.selectedLotId, observedQuantityText = value)
    }

    /** Writes the immutable intent before dispatching. A restored/ambiguous command is never sent automatically. */
    fun recordCount() {
        val authority = activeAuthority ?: return
        val current = mutableState.value
        val selectedLot = current.selectedLot ?: return
        if (!current.canRecord || current.recordedCount?.status == REQUESTED_STATUS) return
        val observed = current.observedQuantityText.toBigDecimalOrNull()
        if (observed == null || !observed.isValidStockQuantity()) {
            mutableState.value = current.copy(notice = CycleCountNotice.InvalidQuantity)
            return
        }
        val intent = CycleCountIntent(
            scope = authority.scope,
            idempotencyKey = UUID.randomUUID().toString(),
            lot = selectedLot,
            observedQuantityText = observed.toPlainString(),
            frozenBody = countBody(observed, selectedLot.unit),
            status = CycleCountIntentStatus.Pending
        )
        persistAndRecord(intent, authority, activation)
    }

    /** Explicit operator action to retry the same frozen command after an ambiguous result. */
    fun retryCountUnknownOutcome() {
        val authority = activeAuthority ?: return
        val current = mutableState.value
        val intent = current.frozenCountIntent ?: return
        if (!current.metadataAvailable || !current.canWriteCounts || intent.scope != authority.scope) return
        if (current.countCommand !in RETRYABLE_COMMAND_STATES) return
        dispatchCount(intent.copy(status = CycleCountIntentStatus.UnknownOutcome), authority, activation)
    }

    /** Explicitly applies a server-recorded variance with the count's exact captured lot version. */
    fun applyCorrection() {
        val authority = activeAuthority ?: return
        val current = mutableState.value
        val count = current.recordedCount ?: return
        if (!current.canApplyCorrection || count.status != REQUESTED_STATUS ||
            !authority.permissions.containsAll(CORRECTION_PERMISSIONS)
        ) return
        val intent = CycleCountCorrectionIntent(
            scope = authority.scope,
            idempotencyKey = UUID.randomUUID().toString(),
            count = count,
            expectedLotVersion = count.lotVersion,
            status = CycleCountIntentStatus.Pending
        )
        persistAndApplyCorrection(intent, authority, activation)
    }

    /** Explicit operator action to replay the same correction key, body and version after ambiguity. */
    fun retryCorrectionUnknownOutcome() {
        val authority = activeAuthority ?: return
        val current = mutableState.value
        val intent = current.frozenCorrectionIntent ?: return
        if (!current.metadataAvailable || !current.canApplyInventoryCorrection || intent.scope != authority.scope) return
        if (current.correctionCommand !in RETRYABLE_COMMAND_STATES) return
        dispatchCorrection(intent.copy(status = CycleCountIntentStatus.UnknownOutcome), authority, activation)
    }

    /** Discards a definitively stale count snapshot, then reads current server lots again. */
    fun refreshStaleCount() {
        val authority = activeAuthority ?: return
        val current = mutableState.value
        val count = current.recordedCount ?: return
        if (current.notice != CycleCountNotice.StaleCount || current.correctionCommand != CycleCountCommandStatus.PreconditionFailed) return
        val currentActivation = activation
        viewModelScope.launch {
            if (!isCurrent(authority, currentActivation)) return@launch
            val result = safeMetadataWrite { metadataStore.clearStaleCount(authority.scope, count.id) }
            if (!isCurrent(authority, currentActivation)) return@launch
            if (result != CycleCountMetadataWrite.Saved) {
                mutableState.value = mutableState.value.copy(
                    metadataAvailable = false,
                    notice = CycleCountNotice.MetadataUnavailable
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                countCommand = CycleCountCommandStatus.Editing,
                frozenCountIntent = null,
                recordedCount = null,
                selectedLotId = null,
                observedQuantityText = "",
                correctionCommand = CycleCountCommandStatus.Editing,
                frozenCorrectionIntent = null,
                appliedCorrection = null,
                notice = null
            )
            reloadLots()
        }
    }

    private fun updateDraft(selectedLotId: String?, observedQuantityText: String) {
        val authority = activeAuthority ?: return
        val current = mutableState.value
        if (!current.metadataAvailable || current.frozenCountIntent != null || current.frozenCorrectionIntent != null) {
            return
        }
        val changed = selectedLotId != current.selectedLotId || observedQuantityText != current.observedQuantityText
        if (!changed) return
        val nextCount = CycleCountCommandStatus.Editing
        val next = current.copy(
            selectedLotId = selectedLotId,
            observedQuantityText = observedQuantityText,
            countCommand = nextCount,
            recordedCount = null,
            correctionCommand = CycleCountCommandStatus.Editing,
            appliedCorrection = null,
            notice = null
        )
        mutableState.value = next
        val revision = ++draftRevision
        val currentActivation = activation
        viewModelScope.launch {
            if (revision != draftRevision || !isCurrent(authority, currentActivation)) return@launch
            val outcome = safeMetadataWrite {
                metadataStore.saveDraft(
                    CycleCountStoredWork(
                        scope = authority.scope,
                        selectedLotId = selectedLotId,
                        observedQuantityText = observedQuantityText
                    )
                )
            }
            if (!isCurrent(authority, currentActivation) || revision != draftRevision) return@launch
            mutableState.value = when (outcome) {
                CycleCountMetadataWrite.Saved -> mutableState.value.copy(metadataAvailable = true)
                CycleCountMetadataWrite.Unavailable -> mutableState.value.copy(
                    metadataAvailable = false,
                    notice = CycleCountNotice.MetadataUnavailable
                )
            }
        }
    }

    private suspend fun restoreWork(authority: CycleCountAuthority, expectedActivation: Long) {
        val loaded = try {
            metadataStore.load(authority.scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CycleCountMetadataRead.Unavailable
        }
        if (!isCurrent(authority, expectedActivation)) return
        when (loaded) {
            CycleCountMetadataRead.Unavailable -> mutableState.value = mutableState.value.copy(
                metadataAvailable = false,
                notice = CycleCountNotice.MetadataUnavailable
            )
            is CycleCountMetadataRead.Available -> {
                val work = loaded.value
                if (work == null) {
                    mutableState.value = mutableState.value.copy(metadataAvailable = true)
                    return
                }
                if (work.scope != authority.scope || !work.isValid()) {
                    mutableState.value = mutableState.value.copy(
                        metadataAvailable = false,
                        notice = CycleCountNotice.MetadataUnavailable
                    )
                    return
                }
                val countIntent = work.countIntent?.copy(status = CycleCountIntentStatus.UnknownOutcome)
                val correctionIntent = work.correctionIntent?.copy(status = CycleCountIntentStatus.UnknownOutcome)
                if (countIntent != null && work.countIntent?.status == CycleCountIntentStatus.Pending) {
                    safeMetadataWrite { metadataStore.markCountUnknown(authority.scope, countIntent.idempotencyKey) }
                }
                if (correctionIntent != null && work.correctionIntent?.status == CycleCountIntentStatus.Pending) {
                    safeMetadataWrite { metadataStore.markCorrectionUnknown(authority.scope, correctionIntent.idempotencyKey) }
                }
                if (!isCurrent(authority, expectedActivation)) return
                mutableState.value = mutableState.value.copy(
                    metadataAvailable = true,
                    selectedLotId = work.selectedLotId,
                    observedQuantityText = work.observedQuantityText,
                    countCommand = when {
                        countIntent != null -> CycleCountCommandStatus.UnknownOutcome
                        work.recordedCount != null -> CycleCountCommandStatus.Recorded
                        else -> CycleCountCommandStatus.Editing
                    },
                    frozenCountIntent = countIntent,
                    recordedCount = work.recordedCount,
                    correctionCommand = when {
                        correctionIntent != null -> CycleCountCommandStatus.UnknownOutcome
                        work.appliedCorrection != null -> CycleCountCommandStatus.Applied
                        else -> CycleCountCommandStatus.Editing
                    },
                    frozenCorrectionIntent = correctionIntent,
                    appliedCorrection = work.appliedCorrection
                )
            }
        }
    }

    private fun persistAndRecord(intent: CycleCountIntent, authority: CycleCountAuthority, expectedActivation: Long) {
        mutableState.value = mutableState.value.copy(
            countCommand = CycleCountCommandStatus.PersistingIntent,
            frozenCountIntent = intent,
            notice = null
        )
        viewModelScope.launch {
            val persisted = safeMetadataWrite { metadataStore.freezeCount(intent) }
            if (!isCurrent(authority, expectedActivation)) return@launch
            if (persisted != CycleCountMetadataWrite.Saved) {
                mutableState.value = mutableState.value.copy(
                    countCommand = CycleCountCommandStatus.Editing,
                    frozenCountIntent = null,
                    metadataAvailable = false,
                    notice = CycleCountNotice.MetadataUnavailable
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(countCommand = CycleCountCommandStatus.Pending)
            dispatchCount(intent, authority, expectedActivation)
        }
    }

    private fun dispatchCount(intent: CycleCountIntent, authority: CycleCountAuthority, expectedActivation: Long) {
        if (!authority.permissions.contains(WRITE_PERMISSION) || intent.scope != authority.scope) return
        mutableState.value = mutableState.value.copy(
            countCommand = CycleCountCommandStatus.Pending,
            frozenCountIntent = intent,
            notice = null
        )
        viewModelScope.launch {
            val result = try {
                gateway.record(intent, authority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CycleCountResult.UnknownOutcome
            }
            if (!isCurrent(authority, expectedActivation)) return@launch
            handleCountResult(intent, authority, expectedActivation, result)
        }
    }

    private suspend fun handleCountResult(
        intent: CycleCountIntent,
        authority: CycleCountAuthority,
        expectedActivation: Long,
        result: CycleCountResult
    ) {
        when (result) {
            is CycleCountResult.Recorded -> {
                if (!result.count.matches(intent)) {
                    markCountUnknown(intent, authority, expectedActivation)
                    return
                }
                val persisted = safeMetadataWrite {
                    metadataStore.completeCount(authority.scope, intent.idempotencyKey, result.count)
                }
                if (!isCurrent(authority, expectedActivation)) return
                if (persisted != CycleCountMetadataWrite.Saved) {
                    markCountUnknown(intent, authority, expectedActivation)
                    return
                }
                mutableState.value = mutableState.value.copy(
                    countCommand = CycleCountCommandStatus.Recorded,
                    frozenCountIntent = null,
                    recordedCount = result.count,
                    correctionCommand = CycleCountCommandStatus.Editing,
                    frozenCorrectionIntent = null,
                    appliedCorrection = null,
                    notice = if (result.count.status == RECORDED_STATUS) CycleCountNotice.CountMatchesStock else null
                )
            }
            is CycleCountResult.Applied -> markCountUnknown(intent, authority, expectedActivation)
            CycleCountResult.UnknownOutcome, CycleCountResult.NetworkUnavailable,
            CycleCountResult.ServiceUnavailable -> markCountUnknown(intent, authority, expectedActivation)
            CycleCountResult.PreconditionFailed -> {
                val cleared = safeMetadataWrite { metadataStore.clearCountIntent(authority.scope, intent.idempotencyKey) }
                if (!isCurrent(authority, expectedActivation)) return
                mutableState.value = mutableState.value.copy(
                    countCommand = if (cleared == CycleCountMetadataWrite.Saved) CycleCountCommandStatus.Editing
                    else CycleCountCommandStatus.PreconditionFailed,
                    frozenCountIntent = if (cleared == CycleCountMetadataWrite.Saved) null else intent,
                    metadataAvailable = cleared == CycleCountMetadataWrite.Saved,
                    notice = if (cleared == CycleCountMetadataWrite.Saved) CycleCountNotice.PreconditionFailed
                    else CycleCountNotice.MetadataUnavailable
                )
                if (cleared == CycleCountMetadataWrite.Saved) reloadLots()
            }
            is CycleCountResult.Rejected -> {
                val cleared = safeMetadataWrite { metadataStore.clearCountIntent(authority.scope, intent.idempotencyKey) }
                if (!isCurrent(authority, expectedActivation)) return
                mutableState.value = mutableState.value.copy(
                    countCommand = if (cleared == CycleCountMetadataWrite.Saved) CycleCountCommandStatus.Editing
                    else CycleCountCommandStatus.Rejected,
                    frozenCountIntent = if (cleared == CycleCountMetadataWrite.Saved) null else intent,
                    metadataAvailable = cleared == CycleCountMetadataWrite.Saved,
                    notice = if (cleared == CycleCountMetadataWrite.Saved) CycleCountNotice.InvalidQuantity
                    else CycleCountNotice.MetadataUnavailable
                )
            }
            CycleCountResult.Conflict -> mutableState.value = mutableState.value.copy(
                countCommand = CycleCountCommandStatus.Conflict,
                notice = CycleCountNotice.Conflict
            )
            CycleCountResult.PermissionDenied -> mutableState.value = mutableState.value.copy(
                countCommand = CycleCountCommandStatus.PermissionDenied,
                notice = CycleCountNotice.PermissionDenied
            )
            CycleCountResult.ContextInvalidated -> mutableState.value = mutableState.value.copy(
                countCommand = CycleCountCommandStatus.ContextInvalidated,
                notice = CycleCountNotice.ContextInvalidated
            )
            CycleCountResult.SessionInvalidated -> mutableState.value = mutableState.value.copy(
                countCommand = CycleCountCommandStatus.SessionInvalidated,
                notice = CycleCountNotice.SessionInvalidated
            )
        }
    }

    private suspend fun markCountUnknown(
        intent: CycleCountIntent,
        authority: CycleCountAuthority,
        expectedActivation: Long
    ) {
        val persisted = safeMetadataWrite { metadataStore.markCountUnknown(authority.scope, intent.idempotencyKey) }
        if (!isCurrent(authority, expectedActivation)) return
        mutableState.value = mutableState.value.copy(
            countCommand = CycleCountCommandStatus.UnknownOutcome,
            frozenCountIntent = intent.copy(status = CycleCountIntentStatus.UnknownOutcome),
            metadataAvailable = persisted == CycleCountMetadataWrite.Saved,
            notice = if (persisted == CycleCountMetadataWrite.Saved) CycleCountNotice.NetworkUnavailable
            else CycleCountNotice.MetadataUnavailable
        )
    }

    private fun persistAndApplyCorrection(
        intent: CycleCountCorrectionIntent,
        authority: CycleCountAuthority,
        expectedActivation: Long
    ) {
        mutableState.value = mutableState.value.copy(
            correctionCommand = CycleCountCommandStatus.PersistingIntent,
            frozenCorrectionIntent = intent,
            notice = null
        )
        viewModelScope.launch {
            val persisted = safeMetadataWrite { metadataStore.freezeCorrection(intent) }
            if (!isCurrent(authority, expectedActivation)) return@launch
            if (persisted != CycleCountMetadataWrite.Saved) {
                mutableState.value = mutableState.value.copy(
                    correctionCommand = CycleCountCommandStatus.Editing,
                    frozenCorrectionIntent = null,
                    metadataAvailable = false,
                    notice = CycleCountNotice.MetadataUnavailable
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(correctionCommand = CycleCountCommandStatus.Pending)
            dispatchCorrection(intent, authority, expectedActivation)
        }
    }

    private fun dispatchCorrection(
        intent: CycleCountCorrectionIntent,
        authority: CycleCountAuthority,
        expectedActivation: Long
    ) {
        if (!authority.permissions.containsAll(CORRECTION_PERMISSIONS) || intent.scope != authority.scope) return
        mutableState.value = mutableState.value.copy(
            correctionCommand = CycleCountCommandStatus.Pending,
            frozenCorrectionIntent = intent,
            notice = null
        )
        viewModelScope.launch {
            val result = try {
                gateway.applyCorrection(intent, authority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CycleCountResult.UnknownOutcome
            }
            if (!isCurrent(authority, expectedActivation)) return@launch
            handleCorrectionResult(intent, authority, expectedActivation, result)
        }
    }

    private suspend fun handleCorrectionResult(
        intent: CycleCountCorrectionIntent,
        authority: CycleCountAuthority,
        expectedActivation: Long,
        result: CycleCountResult
    ) {
        when (result) {
            is CycleCountResult.Applied -> {
                if (!result.correction.matches(intent)) {
                    markCorrectionUnknown(intent, authority, expectedActivation)
                    return
                }
                val persisted = safeMetadataWrite {
                    metadataStore.completeCorrection(authority.scope, intent.idempotencyKey, result.correction)
                }
                if (!isCurrent(authority, expectedActivation)) return
                if (persisted != CycleCountMetadataWrite.Saved) {
                    markCorrectionUnknown(intent, authority, expectedActivation)
                    return
                }
                mutableState.value = mutableState.value.copy(
                    correctionCommand = CycleCountCommandStatus.Applied,
                    frozenCorrectionIntent = null,
                    appliedCorrection = result.correction,
                    notice = null
                )
                reloadLots()
            }
            is CycleCountResult.Recorded -> markCorrectionUnknown(intent, authority, expectedActivation)
            CycleCountResult.UnknownOutcome, CycleCountResult.NetworkUnavailable,
            CycleCountResult.ServiceUnavailable -> markCorrectionUnknown(intent, authority, expectedActivation)
            CycleCountResult.PreconditionFailed -> {
                val cleared = safeMetadataWrite {
                    metadataStore.clearCorrectionIntent(authority.scope, intent.idempotencyKey)
                }
                if (!isCurrent(authority, expectedActivation)) return
                mutableState.value = mutableState.value.copy(
                    correctionCommand = if (cleared == CycleCountMetadataWrite.Saved) CycleCountCommandStatus.PreconditionFailed
                    else CycleCountCommandStatus.UnknownOutcome,
                    frozenCorrectionIntent = if (cleared == CycleCountMetadataWrite.Saved) null
                    else intent.copy(status = CycleCountIntentStatus.UnknownOutcome),
                    metadataAvailable = cleared == CycleCountMetadataWrite.Saved,
                    notice = if (cleared == CycleCountMetadataWrite.Saved) CycleCountNotice.StaleCount
                    else CycleCountNotice.MetadataUnavailable
                )
            }
            is CycleCountResult.Rejected -> {
                val cleared = safeMetadataWrite {
                    metadataStore.clearCorrectionIntent(authority.scope, intent.idempotencyKey)
                }
                if (!isCurrent(authority, expectedActivation)) return
                mutableState.value = mutableState.value.copy(
                    correctionCommand = if (cleared == CycleCountMetadataWrite.Saved) CycleCountCommandStatus.Editing
                    else CycleCountCommandStatus.Rejected,
                    frozenCorrectionIntent = if (cleared == CycleCountMetadataWrite.Saved) null else intent,
                    metadataAvailable = cleared == CycleCountMetadataWrite.Saved,
                    notice = if (cleared == CycleCountMetadataWrite.Saved) CycleCountNotice.Conflict
                    else CycleCountNotice.MetadataUnavailable
                )
            }
            CycleCountResult.Conflict -> mutableState.value = mutableState.value.copy(
                correctionCommand = CycleCountCommandStatus.Conflict,
                notice = CycleCountNotice.Conflict
            )
            CycleCountResult.PermissionDenied -> mutableState.value = mutableState.value.copy(
                correctionCommand = CycleCountCommandStatus.PermissionDenied,
                notice = CycleCountNotice.PermissionDenied
            )
            CycleCountResult.ContextInvalidated -> mutableState.value = mutableState.value.copy(
                correctionCommand = CycleCountCommandStatus.ContextInvalidated,
                notice = CycleCountNotice.ContextInvalidated
            )
            CycleCountResult.SessionInvalidated -> mutableState.value = mutableState.value.copy(
                correctionCommand = CycleCountCommandStatus.SessionInvalidated,
                notice = CycleCountNotice.SessionInvalidated
            )
        }
    }

    private suspend fun markCorrectionUnknown(
        intent: CycleCountCorrectionIntent,
        authority: CycleCountAuthority,
        expectedActivation: Long
    ) {
        val persisted = safeMetadataWrite {
            metadataStore.markCorrectionUnknown(authority.scope, intent.idempotencyKey)
        }
        if (!isCurrent(authority, expectedActivation)) return
        mutableState.value = mutableState.value.copy(
            correctionCommand = CycleCountCommandStatus.UnknownOutcome,
            frozenCorrectionIntent = intent.copy(status = CycleCountIntentStatus.UnknownOutcome),
            metadataAvailable = persisted == CycleCountMetadataWrite.Saved,
            notice = if (persisted == CycleCountMetadataWrite.Saved) CycleCountNotice.NetworkUnavailable
            else CycleCountNotice.MetadataUnavailable
        )
    }

    private fun loadLotsPage(authority: CycleCountAuthority, expectedActivation: Long, page: Int, replace: Boolean) {
        lotJob?.cancel()
        mutableState.value = mutableState.value.copy(
            lotLookup = CycleCountLookupStatus.Loading,
            lots = if (replace) emptyList() else mutableState.value.lots,
            lotPage = if (replace) -1 else mutableState.value.lotPage,
            lotTotal = if (replace) 0 else mutableState.value.lotTotal,
            selectedLotId = mutableState.value.selectedLotId,
            notice = if (replace) mutableState.value.notice else mutableState.value.notice
        )
        lotJob = viewModelScope.launch {
            val result = try {
                gateway.lots(authority, page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CycleCountLookupResult.ServiceUnavailable
            }
            if (!isCurrent(authority, expectedActivation)) return@launch
            val latest = mutableState.value
            mutableState.value = when (result) {
                is CycleCountLookupResult.Lots -> {
                    val merged = if (replace) result.items else (latest.lots + result.items).distinctBy(CycleCountLot::id)
                    latest.copy(
                        lots = merged,
                        lotPage = result.page,
                        lotTotal = result.total,
                        lotLookup = if (merged.isEmpty()) CycleCountLookupStatus.Empty else CycleCountLookupStatus.Ready,
                        selectedLotId = latest.selectedLotId,
                        notice = latest.notice
                    )
                }
                CycleCountLookupResult.NetworkUnavailable -> latest.copy(lotLookup = CycleCountLookupStatus.NetworkUnavailable)
                CycleCountLookupResult.ServiceUnavailable -> latest.copy(lotLookup = CycleCountLookupStatus.ServiceUnavailable)
                CycleCountLookupResult.PermissionDenied -> latest.copy(lotLookup = CycleCountLookupStatus.PermissionDenied)
                CycleCountLookupResult.ContextInvalidated -> latest.copy(lotLookup = CycleCountLookupStatus.ContextInvalidated)
                CycleCountLookupResult.SessionInvalidated -> latest.copy(lotLookup = CycleCountLookupStatus.SessionInvalidated)
            }
        }
    }

    private suspend fun safeMetadataWrite(block: suspend () -> CycleCountMetadataWrite): CycleCountMetadataWrite = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CycleCountMetadataWrite.Unavailable
    }

    private suspend fun isCurrent(authority: CycleCountAuthority, expectedActivation: Long): Boolean =
        activation == expectedActivation && activeAuthority == authority

    private fun CycleCountStoredWork.isValid(): Boolean {
        val intentQuantity = countIntent?.observedQuantityText?.toBigDecimalOrNull()
        return observedQuantityText.length <= 64 &&
            (observedQuantityText.isEmpty() || observedQuantityText.toBigDecimalOrNull()?.isValidStockQuantity() == true) &&
            (countIntent == null || (countIntent.scope == scope && countIntent.lot.id.isUuid() &&
                intentQuantity?.isValidStockQuantity() == true &&
                countIntent.frozenBody == countBody(requireNotNull(intentQuantity), countIntent.lot.unit))) &&
            (recordedCount == null || (recordedCount.lotId.isUuid() && recordedCount.actorMembershipId.isUuid())) &&
            (correctionIntent == null || (correctionIntent.scope == scope && correctionIntent.count.id.isUuid())) &&
            (appliedCorrection == null || (appliedCorrection.lotId.isUuid() && appliedCorrection.actorMembershipId.isUuid()))
    }

    private fun CycleCountRecord.matches(intent: CycleCountIntent): Boolean =
        lotId == intent.lot.id && warehouseId == intent.lot.warehouseId && zoneId == intent.lot.zoneId &&
            lotVersion == intent.lot.version &&
            expectedQuantityText.toBigDecimalOrNull()?.compareTo(intent.lot.onHandText.toBigDecimal()) == 0 &&
            observedQuantityText.toBigDecimalOrNull()?.compareTo(intent.observedQuantityText.toBigDecimal()) == 0 &&
            unit.equals(intent.lot.unit, ignoreCase = true) && actorMembershipId == intent.scope.membershipId &&
            (status == RECORDED_STATUS || status == REQUESTED_STATUS)

    private fun CycleCountCorrection.matches(intent: CycleCountCorrectionIntent): Boolean =
        cycleCountId == intent.count.id && lotId == intent.count.lotId && warehouseId == intent.count.warehouseId &&
            zoneId == intent.count.zoneId && lotVersionBefore == intent.expectedLotVersion &&
            lotVersionAfter == intent.expectedLotVersion + 1 &&
            quantityBeforeText.toBigDecimalOrNull()?.compareTo(intent.count.expectedQuantityText.toBigDecimal()) == 0 &&
            quantityAfterText.toBigDecimalOrNull()?.compareTo(intent.count.observedQuantityText.toBigDecimal()) == 0 &&
            unit.equals(intent.count.unit, ignoreCase = true) && actorMembershipId == intent.scope.membershipId

    private fun BigDecimal.isValidStockQuantity(): Boolean {
        if (signum() < 0) return false
        val normalized = stripTrailingZeros()
        return maxOf(0, normalized.scale()) <= MAX_DECIMAL_SCALE &&
            maxOf(0, normalized.precision() - normalized.scale()) <= MAX_DECIMAL_INTEGER_DIGITS
    }

    private fun countBody(quantity: BigDecimal, unit: String): String =
        "{\"observedQuantity\":${quantity.toPlainString()},\"unit\":\"$unit\"}"

    private fun String.isUuid(): Boolean = try {
        UUID.fromString(this).toString().equals(this, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

    private companion object {
        const val WRITE_PERMISSION = "warehouse:write"
        const val RECORDED_STATUS = "RECORDED"
        const val REQUESTED_STATUS = "REQUESTED"
        const val MAX_DECIMAL_SCALE = 4
        const val MAX_DECIMAL_INTEGER_DIGITS = 15
        val CORRECTION_PERMISSIONS = setOf("inventory.adjust", WRITE_PERMISSION)
        val RETRYABLE_COMMAND_STATES = setOf(
            CycleCountCommandStatus.UnknownOutcome,
            CycleCountCommandStatus.PermissionDenied
        )
    }
}
