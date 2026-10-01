package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TemperatureMetadataStatus { Loading, Available, Unavailable }
enum class TemperatureCommandStatus { Editing, PersistingIntent, Pending, UnknownOutcome, Confirmed, Rejected }
enum class TemperatureValidationError {
    SubjectRequired,
    UnitRequired,
    ValueRequired,
    ValueInvalid,
    TimeRequired,
    TimeInvalid,
    MetadataUnavailable
}

data class TemperatureEvidenceUiState(
    val authorityEpoch: Long = 0,
    val canLookUpSubjects: Boolean = false,
    val canRecord: Boolean = false,
    val subjectType: TemperatureEvidenceSubjectType = TemperatureEvidenceSubjectType.LOT,
    val subjectId: String = "",
    val selectedSubjectLabel: String? = null,
    val subjects: List<TemperatureEvidenceSubject> = emptyList(),
    val lookup: TemperatureLookupStatus = TemperatureLookupStatus.NotRequested,
    val valueText: String = "",
    val unit: TemperatureEvidenceUnit = TemperatureEvidenceUnit.CELSIUS,
    val occurredAtText: String = "",
    val metadata: TemperatureMetadataStatus = TemperatureMetadataStatus.Loading,
    val command: TemperatureCommandStatus = TemperatureCommandStatus.Editing,
    val validationError: TemperatureValidationError? = null,
    val notice: TemperatureSubmitNotice? = null,
    val rejectionCode: String? = null,
    val confirmed: TemperatureEvidenceFacts? = null,
    val intentCleanupPending: Boolean = false
) {
    val isFrozen: Boolean
        get() = command in setOf(
            TemperatureCommandStatus.PersistingIntent,
            TemperatureCommandStatus.Pending,
            TemperatureCommandStatus.UnknownOutcome
        ) || intentCleanupPending

    override fun toString(): String =
        "TemperatureEvidenceUiState(epoch=$authorityEpoch, command=$command, " +
            "metadata=$metadata, lookup=$lookup, subjects=${subjects.size})"
}

enum class TemperatureSubmitNotice {
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    IntentMetadataUnavailable
}

/** Connected manual evidence flow. A staged reading is never a stock decision. */
class TemperatureEvidenceViewModel(
    private val gateway: TemperatureEvidenceGateway,
    private val metadataStore: TemperatureEvidenceMetadataStore,
    private val now: () -> Instant = Instant::now,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(TemperatureEvidenceUiState())
    val state = mutableState.asStateFlow()

    private var authority: TemperatureEvidenceAuthority? = null
    private var generation = 0L
    private var lookupGeneration = 0L
    private var intent: TemperatureEvidenceIntent? = null
    private val metadataMutex = Mutex()

    fun activate(currentAuthority: TemperatureEvidenceAuthority) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        intent = null
        mutableState.value = TemperatureEvidenceUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canLookUpSubjects = currentAuthority.canLookUpSubjects,
            canRecord = currentAuthority.canRecord,
            occurredAtText = now().toString(),
            metadata = TemperatureMetadataStatus.Loading,
            lookup = if (currentAuthority.canLookUpSubjects) {
                TemperatureLookupStatus.Loading
            } else {
                TemperatureLookupStatus.PermissionDenied
            }
        )
        reloadSubjects()
        viewModelScope.launch {
            val (draftRead, intentRead) = metadataMutex.withLock {
                safeMetadataRead { metadataStore.loadDraft(currentAuthority.scope) } to
                    safeMetadataRead { metadataStore.loadIntent(currentAuthority.scope) }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val storedIntent = (intentRead as? TemperatureMetadataRead.Available)
                ?.value
                ?.takeIf { it.scope == currentAuthority.scope && it.idempotencyKey.isNotBlank() }
            val restoredIntent = storedIntent?.copy(status = TemperatureIntentStatus.UnknownOutcome)
            intent = restoredIntent
            val draft = (draftRead as? TemperatureMetadataRead.Available)?.value
            val available = draftRead is TemperatureMetadataRead.Available &&
                intentRead is TemperatureMetadataRead.Available
            val restored = restoredIntent?.payload
            mutableState.update { current ->
                current.copy(
                    subjectType = restored?.subjectType ?: draft?.subjectType ?: current.subjectType,
                    subjectId = restored?.subjectId ?: draft?.subjectId.orEmpty(),
                    valueText = restored?.value ?: draft?.value.orEmpty(),
                    unit = restored?.unit ?: draft?.unit ?: current.unit,
                    occurredAtText = restored?.occurredAt ?: draft?.occurredAt.orEmpty(),
                    selectedSubjectLabel = null,
                    metadata = if (available) TemperatureMetadataStatus.Available
                    else TemperatureMetadataStatus.Unavailable,
                    command = if (restoredIntent != null) {
                        TemperatureCommandStatus.UnknownOutcome
                    } else {
                        TemperatureCommandStatus.Editing
                    },
                    validationError = if (available) null
                    else TemperatureValidationError.MetadataUnavailable,
                    notice = if (available) null else TemperatureSubmitNotice.IntentMetadataUnavailable
                )
            }
            if (!available) return@launch
            if (storedIntent != null && storedIntent.status == TemperatureIntentStatus.Pending) {
                val marked = withMetadataLock(requestGeneration, currentAuthority) {
                    metadataStore.markUnknownOutcome(
                        currentAuthority.scope,
                        storedIntent.idempotencyKey
                    )
                }
                if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                if (marked != TemperatureMetadataWrite.Saved) {
                    mutableState.update {
                        it.copy(
                            metadata = TemperatureMetadataStatus.Unavailable,
                            notice = TemperatureSubmitNotice.IntentMetadataUnavailable
                        )
                    }
                }
            }
            persistDraftIfAvailable(requestGeneration, currentAuthority)
        }
    }

    fun deactivate() {
        generation++
        lookupGeneration++
        authority = null
        intent = null
        mutableState.value = TemperatureEvidenceUiState()
    }

    fun subjectTypeChanged(type: TemperatureEvidenceSubjectType) {
        if (mutableState.value.isFrozen || mutableState.value.command != TemperatureCommandStatus.Editing) {
            return
        }
        mutableState.update {
            it.copy(
                subjectType = type,
                subjectId = "",
                selectedSubjectLabel = null,
                subjects = emptyList(),
                lookup = TemperatureLookupStatus.NotRequested,
                validationError = null
            )
        }
        reloadSubjects()
        persistDraft()
    }

    fun selectSubject(subject: TemperatureEvidenceSubject) {
        val current = mutableState.value
        if (current.isFrozen || current.command != TemperatureCommandStatus.Editing ||
            subject.type != current.subjectType
        ) return
        mutableState.update {
            it.copy(subjectId = subject.id, selectedSubjectLabel = subject.primaryLabel, validationError = null)
        }
        persistDraft()
    }

    fun subjectIdChanged(value: String) {
        if (mutableState.value.isFrozen || mutableState.value.command != TemperatureCommandStatus.Editing) return
        mutableState.update {
            it.copy(
                subjectId = value,
                selectedSubjectLabel = it.subjects.firstOrNull { subject -> subject.id == value }
                    ?.primaryLabel,
                validationError = null
            )
        }
        persistDraft()
    }

    fun valueChanged(value: String) {
        edit { it.copy(valueText = value, validationError = null) }
    }

    fun unitChanged(value: TemperatureEvidenceUnit) {
        edit { it.copy(unit = value, validationError = null) }
    }

    fun occurredAtChanged(value: String) {
        edit { it.copy(occurredAtText = value, validationError = null) }
    }

    fun reloadSubjects() {
        val currentAuthority = authority ?: return
        if (!currentAuthority.canLookUpSubjects) {
            mutableState.update { it.copy(lookup = TemperatureLookupStatus.PermissionDenied) }
            return
        }
        lookupGeneration++
        val requestGeneration = generation
        val requestLookup = lookupGeneration
        val type = mutableState.value.subjectType
        mutableState.update { it.copy(lookup = TemperatureLookupStatus.Loading) }
        viewModelScope.launch {
            val result = try {
                gateway.subjects(type, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TemperatureLookupResult.ServiceUnavailable
            }
            if (requestLookup != lookupGeneration || !isCurrent(requestGeneration, currentAuthority)) {
                return@launch
            }
            when (result) {
                is TemperatureLookupResult.Subjects -> {
                    val selected = result.items.firstOrNull { it.id == mutableState.value.subjectId }
                    mutableState.update {
                        it.copy(
                            subjects = result.items,
                            selectedSubjectLabel = selected?.primaryLabel,
                            lookup = if (result.items.isEmpty()) TemperatureLookupStatus.Empty
                            else TemperatureLookupStatus.Ready
                        )
                    }
                }

                TemperatureLookupResult.NetworkUnavailable -> lookupFailed(TemperatureLookupStatus.NetworkUnavailable)
                TemperatureLookupResult.ServiceUnavailable -> lookupFailed(TemperatureLookupStatus.ServiceUnavailable)
                TemperatureLookupResult.PermissionDenied -> lookupFailed(TemperatureLookupStatus.PermissionDenied)
                TemperatureLookupResult.ContextInvalidated -> lookupFailed(TemperatureLookupStatus.ContextInvalidated)
                TemperatureLookupResult.SessionInvalidated -> lookupFailed(TemperatureLookupStatus.SessionInvalidated)
            }
        }
    }

    /** Saves only editable, unconfirmed fields. It never calls the API. */
    fun saveDraft() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (current.command != TemperatureCommandStatus.Editing || current.isFrozen) return
        val draft = current.toDraft() ?: return
        val requestGeneration = generation
        viewModelScope.launch {
            val result = withMetadataLock(requestGeneration, currentAuthority) {
                metadataStore.saveDraft(currentAuthority.scope, draft)
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (result == TemperatureMetadataWrite.Saved) {
                mutableState.update { it.copy(metadata = TemperatureMetadataStatus.Available) }
            } else {
                metadataUnavailable()
            }
        }
    }

    /** Persist the exact idempotent command before making its first network attempt. */
    fun stageAndRecord() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!currentAuthority.canRecord || current.command != TemperatureCommandStatus.Editing ||
            current.metadata != TemperatureMetadataStatus.Available
        ) return
        val parsed = parsePayload(current)
        if (parsed.error != null) {
            mutableState.update { it.copy(validationError = parsed.error) }
            return
        }
        val payload = checkNotNull(parsed.payload)
        val frozen = TemperatureEvidenceIntent(
            currentAuthority.scope,
            newIdempotencyKey(),
            payload,
            TemperatureIntentStatus.Pending
        )
        val requestGeneration = generation
        mutableState.update {
            it.copy(command = TemperatureCommandStatus.PersistingIntent, validationError = null)
        }
        viewModelScope.launch {
            val saved = withMetadataLock(requestGeneration, currentAuthority) {
                metadataStore.saveIntent(frozen)
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != TemperatureMetadataWrite.Saved) {
                mutableState.update {
                    it.copy(
                        command = TemperatureCommandStatus.Editing,
                        metadata = TemperatureMetadataStatus.Unavailable,
                        validationError = TemperatureValidationError.MetadataUnavailable,
                        notice = TemperatureSubmitNotice.IntentMetadataUnavailable
                    )
                }
                return@launch
            }
            intent = frozen
            mutableState.update { it.copy(command = TemperatureCommandStatus.Pending) }
            executeFrozenIntent(requestGeneration, currentAuthority, frozen)
        }
    }

    /** The only retry is an explicit user replay of the frozen key and body. */
    fun retryUnknownOutcome() {
        val currentAuthority = authority ?: return
        val frozen = intent ?: return
        if (mutableState.value.command != TemperatureCommandStatus.UnknownOutcome ||
            frozen.scope != currentAuthority.scope || !currentAuthority.canRecord
        ) return
        val requestGeneration = generation
        mutableState.update { it.copy(command = TemperatureCommandStatus.Pending, notice = null) }
        viewModelScope.launch {
            val saved = withMetadataLock(requestGeneration, currentAuthority) {
                metadataStore.saveIntent(frozen.copy(status = TemperatureIntentStatus.Pending))
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != TemperatureMetadataWrite.Saved) {
                setUnknownNotice(TemperatureSubmitNotice.IntentMetadataUnavailable)
                return@launch
            }
            val pending = frozen.copy(status = TemperatureIntentStatus.Pending)
            intent = pending
            executeFrozenIntent(requestGeneration, currentAuthority, pending)
        }
    }

    fun retryIntentCleanup() {
        val currentAuthority = authority ?: return
        val frozen = intent ?: return
        if (!mutableState.value.intentCleanupPending || frozen.scope != currentAuthority.scope) return
        val requestGeneration = generation
        viewModelScope.launch {
            val cleared = withMetadataLock(requestGeneration, currentAuthority) {
                metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (cleared == TemperatureMetadataWrite.Saved) {
                intent = null
                mutableState.update { it.copy(intentCleanupPending = false) }
            }
        }
    }

    fun startAnotherReading() {
        if (mutableState.value.isFrozen ||
            mutableState.value.command !in setOf(
                TemperatureCommandStatus.Confirmed,
                TemperatureCommandStatus.Rejected
            )
        ) return
        intent = null
        mutableState.update {
            it.copy(
                subjectId = "",
                selectedSubjectLabel = null,
                valueText = "",
                occurredAtText = now().toString(),
                command = TemperatureCommandStatus.Editing,
                confirmed = null,
                rejectionCode = null,
                notice = null
            )
        }
        persistDraft()
    }

    private suspend fun executeFrozenIntent(
        requestGeneration: Long,
        currentAuthority: TemperatureEvidenceAuthority,
        frozen: TemperatureEvidenceIntent
    ) {
        val result = try {
            gateway.record(frozen.payload, frozen.idempotencyKey, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperatureSubmitResult.UnknownOutcome
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        when (result) {
            is TemperatureSubmitResult.Confirmed -> finishDefinite(
                requestGeneration,
                currentAuthority,
                frozen,
                TemperatureCommandStatus.Confirmed
            ) {
                it.copy(confirmed = result.facts, notice = null, rejectionCode = null)
            }

            is TemperatureSubmitResult.Rejected -> finishDefinite(
                requestGeneration,
                currentAuthority,
                frozen,
                TemperatureCommandStatus.Rejected
            ) {
                it.copy(rejectionCode = result.code, notice = null)
            }

            TemperatureSubmitResult.UnknownOutcome -> markUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TemperatureSubmitNotice.NetworkUnavailable
            )

            TemperatureSubmitResult.ServiceUnavailable -> markUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TemperatureSubmitNotice.ServiceUnavailable
            )

            TemperatureSubmitResult.PermissionDenied -> markUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TemperatureSubmitNotice.PermissionDenied
            )

            TemperatureSubmitResult.ContextInvalidated -> markUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TemperatureSubmitNotice.ContextInvalidated
            )

            TemperatureSubmitResult.SessionInvalidated -> markUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TemperatureSubmitNotice.SessionInvalidated
            )
        }
    }

    private suspend fun finishDefinite(
        requestGeneration: Long,
        currentAuthority: TemperatureEvidenceAuthority,
        frozen: TemperatureEvidenceIntent,
        command: TemperatureCommandStatus,
        update: (TemperatureEvidenceUiState) -> TemperatureEvidenceUiState
    ) {
        val cleared = withMetadataLock(requestGeneration, currentAuthority) {
            metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared == TemperatureMetadataWrite.Saved) {
            intent = null
            mutableState.update { update(it).copy(command = command, intentCleanupPending = false) }
        } else {
            intent = frozen
            mutableState.update {
                update(it).copy(
                    command = command,
                    intentCleanupPending = true,
                    notice = TemperatureSubmitNotice.IntentMetadataUnavailable
                )
            }
        }
    }

    private suspend fun markUnknown(
        requestGeneration: Long,
        currentAuthority: TemperatureEvidenceAuthority,
        frozen: TemperatureEvidenceIntent,
        notice: TemperatureSubmitNotice
    ) {
        val marked = withMetadataLock(requestGeneration, currentAuthority) {
            metadataStore.markUnknownOutcome(currentAuthority.scope, frozen.idempotencyKey)
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val unknown = frozen.copy(status = TemperatureIntentStatus.UnknownOutcome)
        intent = unknown
        mutableState.update {
            it.copy(
                command = TemperatureCommandStatus.UnknownOutcome,
                notice = if (marked == TemperatureMetadataWrite.Saved) notice
                else TemperatureSubmitNotice.IntentMetadataUnavailable
            )
        }
    }

    private suspend fun withMetadataLock(
        requestGeneration: Long,
        expectedAuthority: TemperatureEvidenceAuthority,
        block: suspend () -> TemperatureMetadataWrite
    ): TemperatureMetadataWrite = metadataMutex.withLock {
        if (!isCurrent(requestGeneration, expectedAuthority)) {
            TemperatureMetadataWrite.Unavailable
        } else {
            safeMetadataWrite(block)
        }
    }

    private suspend fun persistDraftIfAvailable(
        requestGeneration: Long,
        currentAuthority: TemperatureEvidenceAuthority
    ) {
        val draft = mutableState.value.toDraft() ?: return
        val result = withMetadataLock(requestGeneration, currentAuthority) {
            metadataStore.saveDraft(currentAuthority.scope, draft)
        }
        if (result != TemperatureMetadataWrite.Saved && isCurrent(requestGeneration, currentAuthority)) {
            metadataUnavailable()
        }
    }

    private fun persistDraft() {
        val currentAuthority = authority ?: return
        val currentGeneration = generation
        val draft = mutableState.value.toDraft() ?: return
        viewModelScope.launch {
            val result = withMetadataLock(currentGeneration, currentAuthority) {
                metadataStore.saveDraft(currentAuthority.scope, draft)
            }
            if (!isCurrent(currentGeneration, currentAuthority)) return@launch
            if (result != TemperatureMetadataWrite.Saved) metadataUnavailable()
        }
    }

    private fun edit(transform: (TemperatureEvidenceUiState) -> TemperatureEvidenceUiState) {
        if (mutableState.value.isFrozen || mutableState.value.command != TemperatureCommandStatus.Editing) {
            return
        }
        mutableState.update(transform)
        persistDraft()
    }

    private fun TemperatureEvidenceUiState.toDraft(): TemperatureEvidenceDraft? {
        if (subjectId.isBlank()) return null
        return TemperatureEvidenceDraft(subjectType, subjectId, valueText, unit, occurredAtText)
    }

    private fun parsePayload(
        current: TemperatureEvidenceUiState
    ): ParsedPayload {
        if (current.subjectId.isBlank()) return ParsedPayload(error = TemperatureValidationError.SubjectRequired)
        if (current.valueText.isBlank()) return ParsedPayload(error = TemperatureValidationError.ValueRequired)
        try {
            BigDecimal(current.valueText)
        } catch (_: NumberFormatException) {
            return ParsedPayload(error = TemperatureValidationError.ValueInvalid)
        }
        if (current.occurredAtText.isBlank()) {
            return ParsedPayload(error = TemperatureValidationError.TimeRequired)
        }
        val occurredAt = try {
            OffsetDateTime.parse(current.occurredAtText).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(current.occurredAtText)
            } catch (_: DateTimeParseException) {
                return ParsedPayload(error = TemperatureValidationError.TimeInvalid)
            }
        }
        return ParsedPayload(
            payload = TemperatureEvidencePayload(
                current.subjectType,
                current.subjectId,
                BigDecimal(current.valueText).toPlainString(),
                current.unit,
                occurredAt.toString()
            )
        )
    }

    private suspend fun <T> safeMetadataRead(
        block: suspend () -> TemperatureMetadataRead<T>
    ): TemperatureMetadataRead<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TemperatureMetadataRead.Unavailable
    }

    private suspend fun safeMetadataWrite(block: suspend () -> TemperatureMetadataWrite):
        TemperatureMetadataWrite = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TemperatureMetadataWrite.Unavailable
    }

    private fun lookupFailed(status: TemperatureLookupStatus) {
        mutableState.update { it.copy(lookup = status, subjects = emptyList(), selectedSubjectLabel = null) }
    }

    private fun metadataUnavailable() {
        mutableState.update {
            it.copy(
                metadata = TemperatureMetadataStatus.Unavailable,
                validationError = TemperatureValidationError.MetadataUnavailable,
                notice = TemperatureSubmitNotice.IntentMetadataUnavailable
            )
        }
    }

    private fun setUnknownNotice(notice: TemperatureSubmitNotice) {
        mutableState.update { it.copy(command = TemperatureCommandStatus.UnknownOutcome, notice = notice) }
    }

    private fun isCurrent(
        expectedGeneration: Long,
        expectedAuthority: TemperatureEvidenceAuthority
    ): Boolean = generation == expectedGeneration && authority == expectedAuthority

    private data class ParsedPayload(
        val payload: TemperatureEvidencePayload? = null,
        val error: TemperatureValidationError? = null
    )
}
