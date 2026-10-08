package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.TemperatureEvidenceGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.TemperatureEvidenceMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceDraft
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceFacts
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceIntent
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidencePayload
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhoto
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoSelection
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubject
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubjectType
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceUnit
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperaturePhotoResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureSubmitResult
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

enum class TemperatureMetadataStatus {
    Loading,
    Available,
    Unavailable
}
enum class TemperatureCommandStatus {
    Editing,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Confirmed,
    Rejected,
    Stale
}
enum class TemperatureValidationError {
    SubjectRequired,
    UnitRequired,
    ValueRequired,
    ValueInvalid,
    TimeRequired,
    TimeInvalid,
    MetadataUnavailable,
    CurrentSubjectRequired,
    QuantityInvalid,
    QuantityExceedsLot,
    ReasonTooLong,
    SourceEvidenceRequired,
    SourceEvidenceInvalid,
    PhotoNotAvailable
}

data class TemperatureEvidenceUiState(
    val authorityEpoch: Long = 0,
    val canLookUpSubjects: Boolean = false,
    val canRecord: Boolean = false,
    val subjectType: TemperatureEvidenceSubjectType = TemperatureEvidenceSubjectType.LOT,
    val subjectId: String = "",
    val selectedSubjectLabel: String? = null,
    val selectedSubject: TemperatureEvidenceSubject? = null,
    val subjects: List<TemperatureEvidenceSubject> = emptyList(),
    val lookup: TemperatureLookupStatus = TemperatureLookupStatus.NotRequested,
    val valueText: String = "",
    val unit: TemperatureEvidenceUnit = TemperatureEvidenceUnit.CELSIUS,
    val occurredAtText: String = "",
    val affectedQuantityText: String = "",
    val reasonText: String = "",
    val sourceEvidenceIdText: String = "",
    val sourceEvidence: TemperatureEvidenceFacts? = null,
    val sourceLookup: TemperatureLookupStatus = TemperatureLookupStatus.NotRequested,
    val photo: TemperatureEvidencePhoto? = null,
    val photoWarehouseId: String? = null,
    val photoStatus: TemperaturePhotoStatus = TemperaturePhotoStatus.None,
    val photoFailureCode: String? = null,
    val photoEvidenceObjectId: String? = null,
    val snapshotLoading: Boolean = false,
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
    private var subjectGeneration = 0L
    private var sourceGeneration = 0L
    private var photoGeneration = 0L
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
                    subjectType =
                        restored?.subjectType ?: draft?.subjectType ?: current.subjectType,
                    subjectId = restored?.subjectId ?: draft?.subjectId.orEmpty(),
                    valueText = restored?.value ?: draft?.value.orEmpty(),
                    unit = restored?.unit ?: draft?.unit ?: current.unit,
                    occurredAtText = restored?.occurredAt ?: draft?.occurredAt.orEmpty(),
                    affectedQuantityText = restored?.affectedQuantity
                        ?: draft?.affectedQuantity.orEmpty(),
                    reasonText = restored?.reason ?: draft?.reason.orEmpty(),
                    sourceEvidenceIdText = restored?.sourceEvidenceId
                        ?: draft?.sourceEvidenceId.orEmpty(),
                    photoEvidenceObjectId = restored?.evidenceObjectId ?: draft?.evidenceObjectId,
                    photoStatus = if (restored?.evidenceObjectId != null ||
                        draft?.evidenceObjectId != null
                    ) {
                        TemperaturePhotoStatus.Checking
                    } else {
                        TemperaturePhotoStatus.None
                    },
                    selectedSubjectLabel = null,
                    selectedSubject = null,
                    metadata = if (available) {
                        TemperatureMetadataStatus.Available
                    } else {
                        TemperatureMetadataStatus.Unavailable
                    },
                    command = if (restoredIntent != null) {
                        TemperatureCommandStatus.UnknownOutcome
                    } else {
                        TemperatureCommandStatus.Editing
                    },
                    validationError = if (available) {
                        null
                    } else {
                        TemperatureValidationError.MetadataUnavailable
                    },
                    notice = if (available) {
                        null
                    } else {
                        TemperatureSubmitNotice.IntentMetadataUnavailable
                    }
                )
            }
            if (!available) return@launch
            val current = mutableState.value
            if (current.subjectId.isNotBlank() && restoredIntent == null) {
                current.subjects.firstOrNull { it.id == current.subjectId }
                    ?.let(::selectSubject)
            }
            if (current.sourceEvidenceIdText.isNotBlank() && restoredIntent == null) {
                loadSourceEvidence()
            }
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
        subjectGeneration++
        sourceGeneration++
        photoGeneration++
        authority = null
        intent = null
        mutableState.value = TemperatureEvidenceUiState()
    }

    fun subjectTypeChanged(type: TemperatureEvidenceSubjectType) {
        if (mutableState.value.isFrozen ||
            mutableState.value.command != TemperatureCommandStatus.Editing
        ) {
            return
        }
        mutableState.update {
            it.copy(
                subjectType = type,
                subjectId = "",
                selectedSubjectLabel = null,
                selectedSubject = null,
                subjects = emptyList(),
                affectedQuantityText = "",
                sourceEvidenceIdText = "",
                sourceEvidence = null,
                sourceLookup = TemperatureLookupStatus.NotRequested,
                photo = null,
                photoWarehouseId = null,
                photoEvidenceObjectId = null,
                photoStatus = TemperaturePhotoStatus.None,
                photoFailureCode = null,
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
        ) {
            return
        }
        val subjectChanged = current.subjectId != subject.id
        subjectGeneration++
        val request = subjectGeneration
        val requestGeneration = generation
        val currentAuthority = authority ?: return
        val restoredPhotoId = current.photoEvidenceObjectId.takeIf {
            current.subjectId == subject.id
        }
        mutableState.update {
            it.copy(
                subjectId = subject.id,
                selectedSubjectLabel = subject.primaryLabel,
                selectedSubject = null,
                affectedQuantityText = if (subjectChanged) "" else it.affectedQuantityText,
                sourceEvidence = null,
                sourceLookup = TemperatureLookupStatus.NotRequested,
                photo = null,
                photoWarehouseId = null,
                photoEvidenceObjectId = restoredPhotoId,
                photoStatus = if (restoredPhotoId != null) {
                    TemperaturePhotoStatus.Checking
                } else {
                    TemperaturePhotoStatus.None
                },
                photoFailureCode = null,
                lookup = TemperatureLookupStatus.Loading,
                validationError = null
            )
        }
        viewModelScope.launch {
            val result = try {
                gateway.subject(subject.type, subject.id, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TemperatureLookupResult.ServiceUnavailable
            }
            if (request != subjectGeneration || !isCurrent(requestGeneration, currentAuthority)) {
                return@launch
            }
            val fresh = (result as? TemperatureLookupResult.Subjects)?.items
                ?.singleOrNull { it.id == subject.id && it.type == subject.type }
            if (fresh == null || (
                    fresh.type == TemperatureEvidenceSubjectType.LOT &&
                        (fresh.warehouseId == null || fresh.lotVersion == null)
                    )
            ) {
                when (result) {
                    TemperatureLookupResult.Stale -> mutableState.update {
                        it.copy(lookup = TemperatureLookupStatus.Stale, selectedSubject = null)
                    }

                    is TemperatureLookupResult.Rejected -> mutableState.update {
                        it.copy(
                            lookup = TemperatureLookupStatus.Rejected,
                            rejectionCode = result.code,
                            selectedSubject = null
                        )
                    }

                    TemperatureLookupResult.PermissionDenied -> lookupFailed(
                        TemperatureLookupStatus.PermissionDenied
                    )

                    TemperatureLookupResult.ContextInvalidated -> lookupFailed(
                        TemperatureLookupStatus.ContextInvalidated
                    )

                    TemperatureLookupResult.SessionInvalidated -> lookupFailed(
                        TemperatureLookupStatus.SessionInvalidated
                    )

                    TemperatureLookupResult.NetworkUnavailable -> lookupFailed(
                        TemperatureLookupStatus.NetworkUnavailable
                    )

                    else -> lookupFailed(TemperatureLookupStatus.ServiceUnavailable)
                }
                return@launch
            }
            mutableState.update { current ->
                current.copy(
                    subjects = current.subjects.map { if (it.id == fresh.id) fresh else it },
                    selectedSubject = fresh,
                    selectedSubjectLabel = fresh.primaryLabel,
                    lookup = TemperatureLookupStatus.Ready
                )
            }
            persistDraft()
            val selected = mutableState.value
            if (selected.sourceEvidenceIdText.isNotBlank()) loadSourceEvidence()
            if (selected.photoEvidenceObjectId != null) refreshPhotoStatus()
        }
    }

    fun subjectIdChanged(value: String) {
        if (mutableState.value.isFrozen ||
            mutableState.value.command != TemperatureCommandStatus.Editing
        ) {
            return
        }
        mutableState.update {
            it.copy(
                subjectId = value,
                selectedSubjectLabel = it.subjects.firstOrNull { subject -> subject.id == value }
                    ?.primaryLabel,
                selectedSubject = null,
                sourceEvidence = null,
                sourceLookup = TemperatureLookupStatus.NotRequested,
                photo = null,
                photoWarehouseId = null,
                photoEvidenceObjectId = null,
                photoStatus = TemperaturePhotoStatus.None,
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

    fun affectedQuantityChanged(value: String) {
        edit { it.copy(affectedQuantityText = value, validationError = null) }
    }

    fun reasonChanged(value: String) {
        edit { it.copy(reasonText = value, validationError = null) }
    }

    fun sourceEvidenceIdChanged(value: String) {
        edit {
            it.copy(
                sourceEvidenceIdText = value,
                sourceEvidence = null,
                sourceLookup = TemperatureLookupStatus.NotRequested,
                validationError = null
            )
        }
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
            if (requestLookup != lookupGeneration ||
                !isCurrent(requestGeneration, currentAuthority)
            ) {
                return@launch
            }
            when (result) {
                is TemperatureLookupResult.Subjects -> {
                    val selected = result.items.firstOrNull {
                        it.id == mutableState.value.subjectId && it.type == type
                    }
                    mutableState.update {
                        it.copy(
                            subjects = result.items,
                            selectedSubjectLabel = selected?.primaryLabel,
                            selectedSubject = null,
                            lookup = if (result.items.isEmpty()) {
                                TemperatureLookupStatus.Empty
                            } else {
                                TemperatureLookupStatus.Ready
                            }
                        )
                    }
                    if (selected != null && mutableState.value.command ==
                        TemperatureCommandStatus.Editing
                    ) {
                        selectSubject(selected)
                    }
                }

                is TemperatureLookupResult.EvidenceSnapshot,
                is TemperatureLookupResult.Rejected -> lookupFailed(
                    TemperatureLookupStatus.ServiceUnavailable
                )

                TemperatureLookupResult.Stale -> lookupFailed(TemperatureLookupStatus.Stale)

                TemperatureLookupResult.NetworkUnavailable -> lookupFailed(
                    TemperatureLookupStatus.NetworkUnavailable
                )

                TemperatureLookupResult.ServiceUnavailable -> lookupFailed(
                    TemperatureLookupStatus.ServiceUnavailable
                )

                TemperatureLookupResult.PermissionDenied -> lookupFailed(
                    TemperatureLookupStatus.PermissionDenied
                )

                TemperatureLookupResult.ContextInvalidated -> lookupFailed(
                    TemperatureLookupStatus.ContextInvalidated
                )

                TemperatureLookupResult.SessionInvalidated -> lookupFailed(
                    TemperatureLookupStatus.SessionInvalidated
                )
            }
        }
    }

    fun photoSelectionContext(): TemperatureEvidencePhotoSelection? {
        val currentAuthority = authority ?: return null
        val current = mutableState.value
        val subject = current.selectedSubject ?: return null
        val warehouseId = subject.warehouseId
            ?: subject.id.takeIf { subject.type == TemperatureEvidenceSubjectType.WAREHOUSE }
            ?: return null
        if (!currentAuthority.canRecord || current.command != TemperatureCommandStatus.Editing ||
            current.isFrozen || current.metadata != TemperatureMetadataStatus.Available
        ) {
            return null
        }
        return TemperatureEvidencePhotoSelection(
            currentAuthority.scope,
            currentAuthority.authorityEpoch,
            subject.type,
            subject.id,
            warehouseId,
            subject.lotVersion
        )
    }

    fun isCurrentPhotoSelection(selection: TemperatureEvidencePhotoSelection): Boolean {
        val currentAuthority = authority ?: return false
        val current = mutableState.value
        val subject = current.selectedSubject ?: return false
        val warehouseId = subject.warehouseId
            ?: subject.id.takeIf { subject.type == TemperatureEvidenceSubjectType.WAREHOUSE }
            ?: return false
        return selection.scope == currentAuthority.scope &&
            selection.authorityEpoch == currentAuthority.authorityEpoch &&
            selection.subjectType == subject.type && selection.subjectId == subject.id &&
            selection.warehouseId == warehouseId &&
            selection.expectedLotVersion == subject.lotVersion &&
            current.command == TemperatureCommandStatus.Editing && !current.isFrozen &&
            current.metadata == TemperatureMetadataStatus.Available
    }

    fun uploadPhoto(
        candidate: TemperatureEvidencePhotoCandidate,
        selection: TemperatureEvidencePhotoSelection
    ) {
        val currentAuthority = authority ?: return
        if (!isCurrentPhotoSelection(selection)) {
            mutableState.update {
                it.copy(
                    photoStatus = TemperaturePhotoStatus.ContextInvalidated,
                    photoFailureCode = "CONTEXT_INVALIDATED"
                )
            }
            return
        }
        photoGeneration++
        val request = photoGeneration
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                photo = null,
                photoWarehouseId = selection.warehouseId,
                photoEvidenceObjectId = null,
                photoStatus = TemperaturePhotoStatus.Uploading,
                photoFailureCode = null
            )
        }
        viewModelScope.launch {
            val result = try {
                gateway.uploadPhoto(
                    selection,
                    candidate,
                    newIdempotencyKey(),
                    currentAuthority
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TemperaturePhotoResult.UnknownOutcome
            }
            if (request != photoGeneration || !isCurrent(requestGeneration, currentAuthority) ||
                !isCurrentPhotoSelection(selection)
            ) {
                return@launch
            }
            when (result) {
                is TemperaturePhotoResult.Evidence -> {
                    if (!result.photo.matchesWarehouse(selection.warehouseId)) {
                        setPhotoFailure(
                            TemperaturePhotoStatus.Rejected,
                            "EVIDENCE_SUBJECT_MISMATCH"
                        )
                    } else {
                        mutableState.update {
                            it.copy(
                                photo = result.photo,
                                photoEvidenceObjectId = result.photo.id,
                                photoStatus = TemperaturePhotoStatus.Checking
                            )
                        }
                        persistDraft()
                        checkPhotoStatus(selection, result.photo.id, currentAuthority, request)
                    }
                }

                is TemperaturePhotoResult.Rejected -> setPhotoFailure(
                    TemperaturePhotoStatus.Rejected,
                    result.code
                )

                TemperaturePhotoResult.UnknownOutcome -> setPhotoFailure(
                    TemperaturePhotoStatus.UnknownOutcome
                )

                TemperaturePhotoResult.NetworkUnavailable -> setPhotoFailure(
                    TemperaturePhotoStatus.NetworkUnavailable
                )

                TemperaturePhotoResult.ServiceUnavailable -> setPhotoFailure(
                    TemperaturePhotoStatus.ServiceUnavailable
                )

                TemperaturePhotoResult.PermissionDenied -> setPhotoFailure(
                    TemperaturePhotoStatus.PermissionDenied,
                    "PERMISSION_DENIED"
                )

                TemperaturePhotoResult.ContextInvalidated -> setPhotoFailure(
                    TemperaturePhotoStatus.ContextInvalidated,
                    "CONTEXT_INVALIDATED"
                )

                TemperaturePhotoResult.SessionInvalidated -> setPhotoFailure(
                    TemperaturePhotoStatus.SessionInvalidated,
                    "SESSION_INVALIDATED"
                )
            }
        }
    }

    fun refreshPhotoStatus() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val selection = photoSelectionContext() ?: return
        val evidenceId = current.photoEvidenceObjectId ?: current.photo?.id ?: return
        if (current.command != TemperatureCommandStatus.Editing || current.isFrozen) return
        photoGeneration++
        val request = photoGeneration
        mutableState.update { it.copy(photoStatus = TemperaturePhotoStatus.Checking) }
        viewModelScope.launch {
            checkPhotoStatus(selection, evidenceId, currentAuthority, request)
        }
    }

    fun loadSourceEvidence() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val subject = current.selectedSubject ?: return
        if (subject.type != TemperatureEvidenceSubjectType.LOT ||
            current.sourceEvidenceIdText.isBlank() || current.isFrozen
        ) {
            mutableState.update {
                it.copy(validationError = TemperatureValidationError.SourceEvidenceRequired)
            }
            return
        }
        sourceGeneration++
        val request = sourceGeneration
        val requestGeneration = generation
        val evidenceId = current.sourceEvidenceIdText.trim()
        mutableState.update {
            it.copy(sourceLookup = TemperatureLookupStatus.Loading, sourceEvidence = null)
        }
        viewModelScope.launch {
            val result = try {
                gateway.snapshot(evidenceId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TemperatureLookupResult.ServiceUnavailable
            }
            if (request != sourceGeneration || !isCurrent(requestGeneration, currentAuthority) ||
                mutableState.value.selectedSubject?.warehouseId != subject.warehouseId
            ) {
                return@launch
            }
            when (result) {
                is TemperatureLookupResult.EvidenceSnapshot -> {
                    val facts = result.facts
                    val eligible = facts.id == evidenceId &&
                        facts.subjectType == TemperatureEvidenceSubjectType.WAREHOUSE &&
                        facts.warehouseId == subject.warehouseId &&
                        facts.status.equals("OUT_OF_RANGE", ignoreCase = true) &&
                        facts.exceptionId != null &&
                        facts.exceptionStatus.equals("OPEN", ignoreCase = true)
                    if (eligible) {
                        mutableState.update {
                            it.copy(
                                sourceEvidence = facts,
                                sourceLookup = TemperatureLookupStatus.Ready,
                                validationError = null
                            )
                        }
                        persistDraft()
                    } else {
                        mutableState.update {
                            it.copy(
                                sourceEvidence = null,
                                sourceLookup = TemperatureLookupStatus.Rejected,
                                validationError = TemperatureValidationError.SourceEvidenceInvalid
                            )
                        }
                    }
                }

                TemperatureLookupResult.Stale -> mutableState.update {
                    it.copy(sourceLookup = TemperatureLookupStatus.Stale)
                }

                is TemperatureLookupResult.Rejected -> mutableState.update {
                    it.copy(
                        sourceLookup = TemperatureLookupStatus.Rejected,
                        rejectionCode = result.code
                    )
                }

                TemperatureLookupResult.PermissionDenied -> mutableState.update {
                    it.copy(sourceLookup = TemperatureLookupStatus.PermissionDenied)
                }

                TemperatureLookupResult.ContextInvalidated -> mutableState.update {
                    it.copy(sourceLookup = TemperatureLookupStatus.ContextInvalidated)
                }

                TemperatureLookupResult.SessionInvalidated -> mutableState.update {
                    it.copy(sourceLookup = TemperatureLookupStatus.SessionInvalidated)
                }

                TemperatureLookupResult.NetworkUnavailable -> mutableState.update {
                    it.copy(sourceLookup = TemperatureLookupStatus.NetworkUnavailable)
                }

                is TemperatureLookupResult.Subjects,
                TemperatureLookupResult.ServiceUnavailable -> mutableState.update {
                    it.copy(sourceLookup = TemperatureLookupStatus.ServiceUnavailable)
                }
            }
        }
    }

    fun refreshConfirmedSnapshot() {
        val currentAuthority = authority ?: return
        val evidenceId = mutableState.value.confirmed?.id ?: return
        val request = ++sourceGeneration
        val requestGeneration = generation
        mutableState.update { it.copy(snapshotLoading = true) }
        viewModelScope.launch {
            val result = try {
                gateway.snapshot(evidenceId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TemperatureLookupResult.ServiceUnavailable
            }
            if (request != sourceGeneration || !isCurrent(requestGeneration, currentAuthority)) {
                return@launch
            }
            when (result) {
                is TemperatureLookupResult.EvidenceSnapshot -> {
                    if (result.facts.id == evidenceId) {
                        mutableState.update {
                            it.copy(confirmed = result.facts, snapshotLoading = false)
                        }
                    } else {
                        mutableState.update { it.copy(snapshotLoading = false) }
                    }
                }

                TemperatureLookupResult.SessionInvalidated -> mutableState.update {
                    it.copy(
                        snapshotLoading = false,
                        notice = TemperatureSubmitNotice.SessionInvalidated
                    )
                }

                TemperatureLookupResult.ContextInvalidated -> mutableState.update {
                    it.copy(
                        snapshotLoading = false,
                        notice = TemperatureSubmitNotice.ContextInvalidated
                    )
                }

                else -> mutableState.update { it.copy(snapshotLoading = false) }
            }
        }
    }

    private suspend fun checkPhotoStatus(
        selection: TemperatureEvidencePhotoSelection,
        evidenceId: String,
        currentAuthority: TemperatureEvidenceAuthority,
        request: Long
    ) {
        val result = try {
            gateway.photoStatus(evidenceId, selection.warehouseId, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperaturePhotoResult.ServiceUnavailable
        }
        if (request != photoGeneration || !isCurrentPhotoSelection(selection)) return
        when (result) {
            is TemperaturePhotoResult.Evidence -> {
                val photo = result.photo
                if (photo.id != evidenceId || !photo.matchesWarehouse(selection.warehouseId)) {
                    setPhotoFailure(TemperaturePhotoStatus.Rejected, "EVIDENCE_SUBJECT_MISMATCH")
                } else {
                    mutableState.update {
                        it.copy(
                            photo = photo,
                            photoEvidenceObjectId = photo.id,
                            photoWarehouseId = selection.warehouseId,
                            photoStatus = if (photo.lifecycleStatus.equals("AVAILABLE", true)) {
                                TemperaturePhotoStatus.Available
                            } else {
                                TemperaturePhotoStatus.AwaitingAvailability
                            },
                            photoFailureCode = null
                        )
                    }
                    persistDraft()
                }
            }

            is TemperaturePhotoResult.Rejected -> setPhotoFailure(
                TemperaturePhotoStatus.Rejected,
                result.code
            )

            TemperaturePhotoResult.UnknownOutcome -> setPhotoFailure(
                TemperaturePhotoStatus.UnknownOutcome
            )

            TemperaturePhotoResult.NetworkUnavailable -> setPhotoFailure(
                TemperaturePhotoStatus.NetworkUnavailable
            )

            TemperaturePhotoResult.ServiceUnavailable -> setPhotoFailure(
                TemperaturePhotoStatus.ServiceUnavailable
            )

            TemperaturePhotoResult.PermissionDenied -> setPhotoFailure(
                TemperaturePhotoStatus.PermissionDenied
            )

            TemperaturePhotoResult.ContextInvalidated -> setPhotoFailure(
                TemperaturePhotoStatus.ContextInvalidated
            )

            TemperaturePhotoResult.SessionInvalidated -> setPhotoFailure(
                TemperaturePhotoStatus.SessionInvalidated
            )
        }
    }

    private fun TemperatureEvidencePhoto.matchesWarehouse(warehouseId: String): Boolean =
        subjectType.equals("WAREHOUSE", ignoreCase = true) && subjectId == warehouseId

    private fun setPhotoFailure(status: TemperaturePhotoStatus, code: String? = null) {
        mutableState.update { it.copy(photoStatus = status, photoFailureCode = code) }
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
        ) {
            return
        }
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
        ) {
            return
        }
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
        if (!mutableState.value.intentCleanupPending ||
            frozen.scope != currentAuthority.scope
        ) {
            return
        }
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
                TemperatureCommandStatus.Rejected,
                TemperatureCommandStatus.Stale
            )
        ) {
            return
        }
        intent = null
        mutableState.update {
            it.copy(
                subjectId = "",
                selectedSubjectLabel = null,
                selectedSubject = null,
                valueText = "",
                affectedQuantityText = "",
                reasonText = "",
                sourceEvidenceIdText = "",
                sourceEvidence = null,
                sourceLookup = TemperatureLookupStatus.NotRequested,
                photo = null,
                photoWarehouseId = null,
                photoEvidenceObjectId = null,
                photoStatus = TemperaturePhotoStatus.None,
                photoFailureCode = null,
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

            TemperatureSubmitResult.Stale -> finishDefinite(
                requestGeneration,
                currentAuthority,
                frozen,
                TemperatureCommandStatus.Stale
            ) {
                it.copy(rejectionCode = "INVENTORY_LOT_CONCURRENCY_CONFLICT", notice = null)
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
                notice = if (marked == TemperatureMetadataWrite.Saved) {
                    notice
                } else {
                    TemperatureSubmitNotice.IntentMetadataUnavailable
                }
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
        if (result != TemperatureMetadataWrite.Saved &&
            isCurrent(requestGeneration, currentAuthority)
        ) {
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
        if (mutableState.value.isFrozen ||
            mutableState.value.command != TemperatureCommandStatus.Editing
        ) {
            return
        }
        mutableState.update(transform)
        persistDraft()
    }

    private fun TemperatureEvidenceUiState.toDraft(): TemperatureEvidenceDraft? {
        if (subjectId.isBlank()) return null
        return TemperatureEvidenceDraft(
            subjectType = subjectType,
            subjectId = subjectId,
            value = valueText,
            unit = unit,
            occurredAt = occurredAtText,
            affectedQuantity = affectedQuantityText,
            reason = reasonText,
            sourceEvidenceId = sourceEvidenceIdText,
            evidenceObjectId = photoEvidenceObjectId
        )
    }

    private fun parsePayload(current: TemperatureEvidenceUiState): ParsedPayload {
        if (current.subjectId.isBlank()) {
            return ParsedPayload(
                error = TemperatureValidationError.SubjectRequired
            )
        }
        val subject = current.selectedSubject?.takeIf {
            it.id == current.subjectId && it.type == current.subjectType
        } ?: return ParsedPayload(error = TemperatureValidationError.CurrentSubjectRequired)
        if (subject.type == TemperatureEvidenceSubjectType.LOT &&
            (subject.warehouseId == null || subject.lotVersion == null)
        ) {
            return ParsedPayload(error = TemperatureValidationError.CurrentSubjectRequired)
        }
        if (current.valueText.isBlank()) {
            return ParsedPayload(
                error = TemperatureValidationError.ValueRequired
            )
        }
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
        val affectedQuantity = current.affectedQuantityText.trim().takeIf(String::isNotEmpty)
            ?.let { text ->
                val quantity = text.toBigDecimalOrNull()
                    ?: return ParsedPayload(error = TemperatureValidationError.QuantityInvalid)
                if (quantity.signum() <= 0) {
                    return ParsedPayload(error = TemperatureValidationError.QuantityInvalid)
                }
                if (subject.type != TemperatureEvidenceSubjectType.LOT ||
                    (subject.physicalRemaining != null && quantity > subject.physicalRemaining)
                ) {
                    return ParsedPayload(error = TemperatureValidationError.QuantityExceedsLot)
                }
                quantity.toPlainString()
            }
        val reason = current.reasonText.trim().takeIf(String::isNotEmpty)
        if (reason != null && reason.length > 2048) {
            return ParsedPayload(error = TemperatureValidationError.ReasonTooLong)
        }
        val sourceId = current.sourceEvidenceIdText.trim().takeIf(String::isNotEmpty)
        val sourceFacts = current.sourceEvidence
        if (sourceId != null && (
                subject.type != TemperatureEvidenceSubjectType.LOT ||
                    sourceFacts?.id != sourceId ||
                    sourceFacts.subjectType != TemperatureEvidenceSubjectType.WAREHOUSE ||
                    sourceFacts.warehouseId != subject.warehouseId ||
                    !sourceFacts.status.equals("OUT_OF_RANGE", ignoreCase = true) ||
                    !sourceFacts.exceptionStatus.equals("OPEN", ignoreCase = true)
                )
        ) {
            return ParsedPayload(
                error = if (sourceFacts == null) {
                    TemperatureValidationError.SourceEvidenceRequired
                } else {
                    TemperatureValidationError.SourceEvidenceInvalid
                }
            )
        }
        val photoId = current.photoEvidenceObjectId
        if (photoId != null && (
                current.photoStatus != TemperaturePhotoStatus.Available ||
                    current.photoWarehouseId != (subject.warehouseId ?: subject.id) ||
                    current.photo?.matchesWarehouse(current.photoWarehouseId.orEmpty()) != true
                )
        ) {
            return ParsedPayload(error = TemperatureValidationError.PhotoNotAvailable)
        }
        return ParsedPayload(
            payload = TemperatureEvidencePayload(
                subjectType = current.subjectType,
                subjectId = current.subjectId,
                value = BigDecimal(current.valueText).toPlainString(),
                unit = current.unit,
                occurredAt = occurredAt.toString(),
                evidenceObjectId = photoId,
                expectedLotVersion = subject.lotVersion.takeIf {
                    subject.type == TemperatureEvidenceSubjectType.LOT
                },
                affectedQuantity = affectedQuantity,
                reason = reason,
                sourceEvidenceId = sourceId
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

    private suspend fun safeMetadataWrite(
        block: suspend () -> TemperatureMetadataWrite
    ): TemperatureMetadataWrite = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TemperatureMetadataWrite.Unavailable
    }

    private fun lookupFailed(status: TemperatureLookupStatus) {
        mutableState.update {
            it.copy(lookup = status, subjects = emptyList(), selectedSubjectLabel = null)
        }
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
        mutableState.update {
            it.copy(command = TemperatureCommandStatus.UnknownOutcome, notice = notice)
        }
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
