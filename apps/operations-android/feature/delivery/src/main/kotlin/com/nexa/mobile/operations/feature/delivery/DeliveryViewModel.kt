package com.nexa.mobile.operations.feature.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DriverDeliveryLoadStatus {
    NotRequested,
    Loading,
    Ready,
    NotFound,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class DriverDeliveryCommandStatus {
    Idle,
    CheckingCurrent,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    PersistenceUnavailable,
    Started,
    Rejected,
    StaleVersion
}

enum class DriverOutcomeCommandStatus {
    Idle,
    CheckingCurrent,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    PersistenceUnavailable,
    Recorded,
    Rejected,
    StaleVersion
}

enum class DriverArrivalCommandStatus {
    Idle,
    CheckingCurrent,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    PersistenceUnavailable,
    Recorded,
    Rejected,
    StaleVersion
}

enum class DriverProofCommandStatus {
    Idle,
    CheckingCurrent,
    PersistingIntent,
    AwaitingEvidenceSelection,
    ReadyToUploadReview,
    Pending,
    UnknownOutcome,
    PersistenceUnavailable,
    WaitingForScan,
    EvidenceAvailable,
    Captured,
    Rejected,
    StaleVersion
}

data class DriverDeliveryUiState(
    val authorityEpoch: Long = 0,
    val canRead: Boolean = false,
    val canStart: Boolean = false,
    val canCaptureProof: Boolean = false,
    val canReadProofEvidence: Boolean = false,
    val deliveries: List<DriverDeliverySnapshot> = emptyList(),
    val listStatus: DriverDeliveryLoadStatus = DriverDeliveryLoadStatus.NotRequested,
    val selectedDelivery: DriverDeliverySnapshot? = null,
    val detailStatus: DriverDeliveryLoadStatus = DriverDeliveryLoadStatus.NotRequested,
    val commandStatus: DriverDeliveryCommandStatus = DriverDeliveryCommandStatus.Idle,
    val hasRecoverableStart: Boolean = false,
    val rejectionCode: String? = null,
    val outcomeCommandStatus: DriverOutcomeCommandStatus = DriverOutcomeCommandStatus.Idle,
    val hasRecoverableOutcome: Boolean = false,
    val outcomeSummary: DriverOutcomeSummary? = null,
    val outcomeRejectionCode: String? = null,
    val arrivalCommandStatus: DriverArrivalCommandStatus = DriverArrivalCommandStatus.Idle,
    val hasRecoverableArrival: Boolean = false,
    val arrivalSummary: DriverArrivalSummary? = null,
    val arrivalRejectionCode: String? = null,
    val proofCommandStatus: DriverProofCommandStatus = DriverProofCommandStatus.Idle,
    val proofId: String? = null,
    val proofAttemptId: String? = null,
    val proofEvidenceId: String? = null,
    val proofEvidenceKind: DriverProofEvidenceKind? = null,
    val proofSummary: DriverProofSummary? = null,
    val proofEvidenceSummary: DriverProofEvidenceSummary? = null,
    val hasRecoverableProof: Boolean = false,
    val proofRejectionCode: String? = null
) {
    /** Destination leaves Nexa only after current assigned detail confirms an active attempt. */
    val authorizedDirectionsDestination: String?
        get() = if (canRead && detailStatus == DriverDeliveryLoadStatus.Ready) {
            selectedDelivery?.takeIf { it.activeAttempt != null }
                ?.destination
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        } else {
            null
        }

    /** Instructions are available for a current operational assignment before its first attempt starts. */
    val authorizedInstructionsDeliveryId: String?
        get() = if (canRead && detailStatus == DriverDeliveryLoadStatus.Ready) {
            selectedDelivery?.takeIf { it.status in DRIVER_OPERATIONAL_DELIVERY_STATUSES }?.id
        } else {
            null
        }

    override fun toString(): String =
        "DriverDeliveryUiState(epoch=$authorityEpoch, list=$listStatus, detail=$detailStatus, command=$commandStatus, items=${deliveries.size})"
}

private val DRIVER_OPERATIONAL_DELIVERY_STATUSES = setOf("ASSIGNED", "DISPATCHED", "IN_TRANSIT")

/** Connected list/detail/start flow; a timeout remains unconfirmed until replay or refresh. */
class DriverDeliveryViewModel(
    private val gateway: DriverDeliveryGateway,
    private val metadataStore: DriverAttemptMetadataStore,
    private val outcomeMetadataStore: DriverOutcomeMetadataStore? = null,
    private val arrivalMetadataStore: DriverArrivalMetadataStore? = null,
    private val timeFactory: () -> String = { Instant.now().toString() },
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val proofMetadataStore: DriverProofMetadataStore? = null
) : ViewModel() {
    private val mutableState = MutableStateFlow(DriverDeliveryUiState())
    val state = mutableState.asStateFlow()

    private var authority: DriverDeliveryAuthority? = null
    private var generation = 0L
    private var pendingStart: DriverAttemptStartCommand? = null
    private var pendingStartPersisted = false
    private var pendingOutcome: DriverOutcomeCommand? = null
    private var pendingOutcomePersisted = false
    private var pendingArrival: DriverArrivalCommand? = null
    private var pendingArrivalPersisted = false
    private var pendingProof: DriverProofIntentMetadata? = null
    private var pendingProofPersisted = false
    private var reloadingReturnedProof = false

    fun activate(currentAuthority: DriverDeliveryAuthority) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        pendingStart = null
        pendingStartPersisted = false
        pendingOutcome = null
        pendingOutcomePersisted = false
        pendingArrival = null
        pendingArrivalPersisted = false
        pendingProof = null
        pendingProofPersisted = false
        mutableState.value = DriverDeliveryUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canRead = currentAuthority.canRead,
            canStart = currentAuthority.canStart,
            canCaptureProof = currentAuthority.canCaptureProof,
            canReadProofEvidence = currentAuthority.canReadProofEvidence,
            listStatus = if (currentAuthority.canRead) {
                DriverDeliveryLoadStatus.Loading
            } else {
                DriverDeliveryLoadStatus.PermissionDenied
            }
        )
        if (!currentAuthority.canRead) return
        viewModelScope.launch {
            var metadataAvailable = true
            val loadedStart = safeMetadataLoad(currentAuthority.scopeIdentity)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (loadedStart) {
                is DriverAttemptMetadataRead.Available -> {
                    val intent = loadedStart.intent
                    if (intent != null && intent.scope == currentAuthority.scopeIdentity) {
                        pendingStart = DriverAttemptStartCommand(
                            intent.deliveryId,
                            intent.expectedVersion,
                            intent.idempotencyKey
                        )
                        pendingStartPersisted = true
                        mutableState.update {
                            it.copy(
                                commandStatus = DriverDeliveryCommandStatus.UnknownOutcome,
                                hasRecoverableStart = true
                            )
                        }
                    } else if (intent != null) {
                        metadataAvailable = false
                    }
                }

                DriverAttemptMetadataRead.Unavailable -> metadataAvailable = false
            }
            var outcomeMetadataAvailable = true
            val loadedOutcome = safeOutcomeMetadataLoad(currentAuthority.scopeIdentity)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (loadedOutcome) {
                is DriverOutcomeMetadataRead.Available -> {
                    val intent = loadedOutcome.intent
                    if (intent != null && intent.scope == currentAuthority.scopeIdentity) {
                        pendingOutcome = intent.command
                        pendingOutcomePersisted = true
                        mutableState.update {
                            it.copy(
                                outcomeCommandStatus = DriverOutcomeCommandStatus.UnknownOutcome,
                                hasRecoverableOutcome = true
                            )
                        }
                    } else if (intent != null) {
                        outcomeMetadataAvailable = false
                    }
                }

                DriverOutcomeMetadataRead.Unavailable -> outcomeMetadataAvailable = false
            }
            var arrivalMetadataAvailable = true
            val loadedArrival = safeArrivalMetadataLoad(currentAuthority.scopeIdentity)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (loadedArrival) {
                is DriverArrivalMetadataRead.Available -> {
                    val intent = loadedArrival.intent
                    if (intent != null && intent.scope == currentAuthority.scopeIdentity) {
                        pendingArrival = intent.command
                        pendingArrivalPersisted = true
                        mutableState.update {
                            it.copy(
                                arrivalCommandStatus = DriverArrivalCommandStatus.UnknownOutcome,
                                hasRecoverableArrival = true
                            )
                        }
                    } else if (intent != null) {
                        arrivalMetadataAvailable = false
                    }
                }

                DriverArrivalMetadataRead.Unavailable -> arrivalMetadataAvailable = false
            }
            var proofMetadataAvailable = true
            val loadedProof = safeProofMetadataLoad(currentAuthority.scopeIdentity)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (loadedProof) {
                is DriverProofMetadataRead.Available -> {
                    val intent = loadedProof.intent
                    if (intent != null && intent.scope == currentAuthority.scopeIdentity) {
                        val recovered = intent.copy(
                            status = if (intent.stage in setOf(
                                    DriverProofIntentStage.CreatingProof,
                                    DriverProofIntentStage.UploadingEvidence,
                                    DriverProofIntentStage.AttachingEvidence
                                )
                            ) DriverProofIntentStatus.UnknownOutcome else intent.status
                        )
                        pendingProof = recovered
                        pendingProofPersisted = true
                        val recoveredStatus = when (intent.stage) {
                            DriverProofIntentStage.ProofCreated -> DriverProofCommandStatus.AwaitingEvidenceSelection
                            DriverProofIntentStage.EvidenceReadyForReview -> DriverProofCommandStatus.ReadyToUploadReview
                            DriverProofIntentStage.EvidenceAwaitingScan -> DriverProofCommandStatus.UnknownOutcome
                            DriverProofIntentStage.Captured -> DriverProofCommandStatus.Captured
                            else -> DriverProofCommandStatus.UnknownOutcome
                        }
                        mutableState.update {
                            it.copy(
                                proofCommandStatus = recoveredStatus,
                                proofId = intent.proofId,
                                proofAttemptId = intent.attemptId,
                                proofEvidenceId = intent.evidenceId,
                                proofEvidenceKind = intent.evidenceKind,
                                hasRecoverableProof = intent.stage != DriverProofIntentStage.Captured
                            )
                        }
                    } else if (intent != null) {
                        proofMetadataAvailable = false
                    }
                }

                DriverProofMetadataRead.Unavailable -> proofMetadataAvailable = false
            }
            if (!metadataAvailable) {
                mutableState.update {
                    it.copy(commandStatus = DriverDeliveryCommandStatus.PersistenceUnavailable)
                }
            }
            if (outcomeMetadataStore != null && !outcomeMetadataAvailable) {
                mutableState.update {
                    it.copy(outcomeCommandStatus = DriverOutcomeCommandStatus.PersistenceUnavailable)
                }
            }
            if (arrivalMetadataStore != null && !arrivalMetadataAvailable) {
                mutableState.update {
                    it.copy(arrivalCommandStatus = DriverArrivalCommandStatus.PersistenceUnavailable)
                }
            }
            if (proofMetadataStore != null && !proofMetadataAvailable) {
                mutableState.update {
                    it.copy(proofCommandStatus = DriverProofCommandStatus.PersistenceUnavailable)
                }
            }
            val result = safeLoad { gateway.assignedDeliveries(currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (result) {
                is DriverDeliveryLoadResult.ListLoaded -> {
                    val restored = pendingStart
                    mutableState.update {
                        it.copy(
                            deliveries = result.items,
                            listStatus = DriverDeliveryLoadStatus.Ready,
                            selectedDelivery = restored?.let { command ->
                                result.items.firstOrNull { item -> item.id == command.deliveryId }
                            } ?: pendingOutcome?.let { command ->
                                result.items.firstOrNull { item -> item.id == command.deliveryId }
                            } ?: pendingArrival?.let { command ->
                                result.items.firstOrNull { item -> item.id == command.deliveryId }
                            } ?: pendingProof?.let { intent ->
                                result.items.firstOrNull { item -> item.id == intent.deliveryId }
                            },
                            commandStatus = when {
                                restored != null -> DriverDeliveryCommandStatus.UnknownOutcome
                                !metadataAvailable ->
                                    DriverDeliveryCommandStatus.PersistenceUnavailable

                                else -> it.commandStatus
                            }
                        )
                    }
                }

                else -> mutableState.update { it.copy(listStatus = result.toLoadStatus()) }
            }
            val restoredProof = pendingProof
            if (restoredProof != null) {
                val proofDetail = safeLoad { gateway.delivery(restoredProof.deliveryId, currentAuthority) }
                if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                val currentProofDelivery = (proofDetail as? DriverDeliveryLoadResult.DetailLoaded)?.item
                if (currentProofDelivery != null && currentProofDelivery.id == restoredProof.deliveryId &&
                    currentProofDelivery.status == "DELIVERED"
                ) {
                    replaceDelivery(currentProofDelivery)
                } else {
                    mutableState.update {
                        it.copy(
                            detailStatus = proofDetail.toLoadStatus(),
                            proofCommandStatus = DriverProofCommandStatus.StaleVersion,
                            proofRejectionCode = "DELIVERY_ATTEMPT_NOT_CURRENT"
                        )
                    }
                }
            }
        }
    }

    fun invalidate() {
        generation++
        authority = null
        pendingStart = null
        pendingStartPersisted = false
        pendingOutcome = null
        pendingOutcomePersisted = false
        pendingArrival = null
        pendingArrivalPersisted = false
        pendingProof = null
        pendingProofPersisted = false
        mutableState.value = DriverDeliveryUiState()
    }

    fun selectDelivery(deliveryId: String) {
        val currentAuthority = authority ?: return
        if (!currentAuthority.canRead || mutableState.value.commandStatus in FROZEN_COMMANDS ||
            mutableState.value.outcomeCommandStatus in FROZEN_OUTCOME_COMMANDS ||
            mutableState.value.arrivalCommandStatus in FROZEN_ARRIVAL_COMMANDS
        ) return
        val requestGeneration = generation
        mutableState.update {
                it.copy(
                    selectedDelivery = it.deliveries.firstOrNull { delivery ->
                    delivery.id ==
                        deliveryId
                },
                detailStatus = DriverDeliveryLoadStatus.Loading,
                commandStatus = DriverDeliveryCommandStatus.Idle,
                hasRecoverableStart = false,
                rejectionCode = null,
                arrivalCommandStatus = DriverArrivalCommandStatus.Idle,
                hasRecoverableArrival = false,
                arrivalSummary = null,
                arrivalRejectionCode = null
            )
        }
        viewModelScope.launch {
            val result = safeLoad { gateway.delivery(deliveryId, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            applyDetailResult(result)
        }
    }

    /** Requires a live detail read before issuing a start from a potentially stale list. */
    fun beginSelectedDelivery() {
        val currentAuthority = authority ?: return
        val selectedId = mutableState.value.selectedDelivery?.id ?: return
        if (!currentAuthority.canStart ||
            mutableState.value.commandStatus in FROZEN_COMMANDS ||
            mutableState.value.outcomeCommandStatus in FROZEN_OUTCOME_COMMANDS ||
            mutableState.value.arrivalCommandStatus in FROZEN_ARRIVAL_COMMANDS ||
            pendingStart != null || pendingOutcome != null || pendingArrival != null
        ) {
            return
        }
        if (mutableState.value.selectedDelivery?.activeAttempt != null) {
            mutableState.update { it.copy(commandStatus = DriverDeliveryCommandStatus.Started) }
            return
        }
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                commandStatus = DriverDeliveryCommandStatus.CheckingCurrent,
                rejectionCode = null
            )
        }
        viewModelScope.launch {
            val detail = safeLoad { gateway.delivery(selectedId, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val current = (detail as? DriverDeliveryLoadResult.DetailLoaded)?.item
            if (current == null) {
                pendingStart = null
                mutableState.update {
                    it.copy(
                        detailStatus = detail.toLoadStatus(),
                        commandStatus = DriverDeliveryCommandStatus.Idle
                    )
                }
                return@launch
            }
            replaceDelivery(current)
            if (current.activeAttempt != null) {
                pendingStart = null
                mutableState.update { it.copy(commandStatus = DriverDeliveryCommandStatus.Started) }
                return@launch
            }
            val command = DriverAttemptStartCommand(
                deliveryId = current.id,
                expectedVersion = current.version,
                idempotencyKey = keyFactory()
            )
            pendingStart = command
            pendingStartPersisted = false
            mutableState.update {
                it.copy(
                    commandStatus = DriverDeliveryCommandStatus.PersistingIntent,
                    hasRecoverableStart = true
                )
            }
            persistBeforeStart(command, requestGeneration, currentAuthority)
        }
    }

    /** Explicitly replays the exact same frozen start identity after an unknown result. */
    fun retryUnknownStart() {
        val currentAuthority = authority ?: return
        val command = pendingStart ?: return
        if (!currentAuthority.canStart || !mutableState.value.hasRecoverableStart ||
            mutableState.value.commandStatus !in setOf(
                DriverDeliveryCommandStatus.UnknownOutcome,
                DriverDeliveryCommandStatus.PersistenceUnavailable
            )
        ) {
            return
        }
        val requestGeneration = generation
        if (pendingStartPersisted) {
            mutableState.update { it.copy(commandStatus = DriverDeliveryCommandStatus.Pending) }
            viewModelScope.launch { runStart(command, requestGeneration, currentAuthority) }
        } else {
            mutableState.update {
                it.copy(commandStatus = DriverDeliveryCommandStatus.PersistingIntent)
            }
            viewModelScope.launch {
                persistBeforeStart(command, requestGeneration, currentAuthority)
            }
        }
    }

    /** Records one outcome from live server facts; durable intent must succeed before POST. */
    fun recordOutcome(
        outcome: DriverOutcomeKind,
        deliveredQuantities: Map<String, String> = emptyMap(),
        failureReason: String? = null,
        notes: String? = null
    ) {
        val currentAuthority = authority ?: return
        val currentState = mutableState.value
        val selected = currentState.selectedDelivery ?: return
        if (!currentAuthority.canStart || !currentAuthority.canRead ||
            currentState.detailStatus != DriverDeliveryLoadStatus.Ready ||
            currentState.outcomeCommandStatus in FROZEN_OUTCOME_COMMANDS ||
            currentState.commandStatus in FROZEN_COMMANDS || pendingOutcome != null
        ) {
            return
        }
        if (outcomeMetadataStore == null) {
            mutableState.update {
                it.copy(outcomeCommandStatus = DriverOutcomeCommandStatus.PersistenceUnavailable)
            }
            return
        }
        val immutableQuantities = deliveredQuantities.toMap()
        val immutableReason = failureReason?.trim()?.takeIf(String::isNotEmpty)
        val immutableNotes = notes?.trim()?.takeIf(String::isNotEmpty)
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                outcomeCommandStatus = DriverOutcomeCommandStatus.CheckingCurrent,
                outcomeRejectionCode = null
            )
        }
        viewModelScope.launch {
            val detail = safeLoad { gateway.delivery(selected.id, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val current = (detail as? DriverDeliveryLoadResult.DetailLoaded)?.item
            if (current == null) {
                mutableState.update {
                    it.copy(
                        detailStatus = detail.toLoadStatus(),
                        outcomeCommandStatus = DriverOutcomeCommandStatus.Rejected,
                        outcomeRejectionCode = detail.toOutcomeFailureCode()
                    )
                }
                return@launch
            }
            replaceDelivery(current)
            val expectedAttemptId = selected.activeAttempt?.id
            if (current.activeAttempt == null || expectedAttemptId == null ||
                current.activeAttempt.id != expectedAttemptId
            ) {
                mutableState.update {
                    it.copy(
                        outcomeCommandStatus = DriverOutcomeCommandStatus.StaleVersion,
                        outcomeRejectionCode = "DELIVERY_ATTEMPT_NOT_CURRENT"
                    )
                }
                return@launch
            }
            val lineBuild = buildOutcomeLines(outcome, current.outcomeLines, immutableQuantities)
            val validationFailure = validateOutcomeInput(outcome, immutableReason, immutableNotes, lineBuild)
            if (validationFailure != null) {
                mutableState.update {
                    it.copy(
                        outcomeCommandStatus = DriverOutcomeCommandStatus.Rejected,
                        outcomeRejectionCode = validationFailure
                    )
                }
                return@launch
            }
            val decisions = (lineBuild as OutcomeLineBuildResult.Valid).lines
            val key = keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
            if (key == null) {
                mutableState.update {
                    it.copy(
                        outcomeCommandStatus = DriverOutcomeCommandStatus.Rejected,
                        outcomeRejectionCode = "IDEMPOTENCY_KEY_INVALID"
                    )
                }
                return@launch
            }
            val attemptedAt = timeFactory()
            val command = DriverOutcomeCommand(
                current.id,
                current.activeAttempt.id,
                current.version,
                key,
                outcome,
                immutableReason,
                immutableNotes,
                attemptedAt,
                decisions,
                frozenOutcomeBody(outcome, immutableReason, immutableNotes, attemptedAt, decisions)
            )
            pendingOutcome = command
            pendingOutcomePersisted = false
            mutableState.update {
                it.copy(
                    outcomeCommandStatus = DriverOutcomeCommandStatus.PersistingIntent,
                    hasRecoverableOutcome = true,
                    outcomeSummary = null
                )
            }
            persistBeforeOutcome(command, requestGeneration, currentAuthority)
        }
    }

    /** Replays the same frozen outcome identity after process death or an uncertain response. */
    fun retryUnknownOutcome() {
        val currentAuthority = authority ?: return
        val command = pendingOutcome ?: return
        if (!currentAuthority.canStart || !mutableState.value.hasRecoverableOutcome ||
            mutableState.value.outcomeCommandStatus !in setOf(
                DriverOutcomeCommandStatus.UnknownOutcome,
                DriverOutcomeCommandStatus.PersistenceUnavailable
            )
        ) {
            return
        }
        val requestGeneration = generation
        if (pendingOutcomePersisted) {
            mutableState.update { it.copy(outcomeCommandStatus = DriverOutcomeCommandStatus.Pending) }
            viewModelScope.launch { runOutcome(command, requestGeneration, currentAuthority) }
        } else {
            mutableState.update {
                it.copy(outcomeCommandStatus = DriverOutcomeCommandStatus.PersistingIntent)
            }
            viewModelScope.launch { persistBeforeOutcome(command, requestGeneration, currentAuthority) }
        }
    }

    /** Signals one explicit arrival for the active attempt after storing exact retry identity. */
    fun signalArrival() {
        val currentAuthority = authority ?: return
        val currentState = mutableState.value
        val selected = currentState.selectedDelivery ?: return
        val selectedAttempt = selected.activeAttempt ?: return
        if (!currentAuthority.canStart || !currentAuthority.canRead ||
            currentState.detailStatus != DriverDeliveryLoadStatus.Ready ||
            currentState.commandStatus in FROZEN_COMMANDS ||
            currentState.arrivalCommandStatus in FROZEN_ARRIVAL_COMMANDS || pendingArrival != null
        ) {
            return
        }
        if (arrivalMetadataStore == null) {
            mutableState.update {
                it.copy(arrivalCommandStatus = DriverArrivalCommandStatus.PersistenceUnavailable)
            }
            return
        }
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                arrivalCommandStatus = DriverArrivalCommandStatus.CheckingCurrent,
                arrivalRejectionCode = null,
                arrivalSummary = null
            )
        }
        viewModelScope.launch {
            val detail = safeLoad { gateway.delivery(selected.id, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val current = (detail as? DriverDeliveryLoadResult.DetailLoaded)?.item
            if (current == null) {
                mutableState.update {
                    it.copy(
                        detailStatus = detail.toLoadStatus(),
                        arrivalCommandStatus = DriverArrivalCommandStatus.Rejected,
                        arrivalRejectionCode = detail.toArrivalFailureCode()
                    )
                }
                return@launch
            }
            replaceDelivery(current)
            val active = current.activeAttempt
            if (active == null || active.id != selectedAttempt.id) {
                mutableState.update {
                    it.copy(
                        arrivalCommandStatus = DriverArrivalCommandStatus.StaleVersion,
                        arrivalRejectionCode = "DELIVERY_ATTEMPT_NOT_CURRENT"
                    )
                }
                return@launch
            }
            val existing = current.arrival?.takeIf { it.attemptId == active.id }
            if (existing != null) {
                mutableState.update {
                    it.copy(
                        arrivalCommandStatus = DriverArrivalCommandStatus.Recorded,
                        arrivalSummary = DriverArrivalSummary(
                            existing.id, current.id, active.id, existing.arrivedAt, current.version
                        ),
                        hasRecoverableArrival = false
                    )
                }
                return@launch
            }
            val key = keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
            if (key == null) {
                mutableState.update {
                    it.copy(
                        arrivalCommandStatus = DriverArrivalCommandStatus.Rejected,
                        arrivalRejectionCode = "IDEMPOTENCY_KEY_INVALID"
                    )
                }
                return@launch
            }
            val command = DriverArrivalCommand(
                deliveryId = current.id,
                attemptId = active.id,
                expectedVersion = current.version,
                idempotencyKey = key
            )
            pendingArrival = command
            pendingArrivalPersisted = false
            mutableState.update {
                it.copy(
                    arrivalCommandStatus = DriverArrivalCommandStatus.PersistingIntent,
                    hasRecoverableArrival = true,
                    arrivalSummary = null
                )
            }
            persistBeforeArrival(command, requestGeneration, currentAuthority)
        }
    }

    /** Replays exact arrival key/body/version after uncertain response or process recovery. */
    fun retryUnknownArrival() {
        val currentAuthority = authority ?: return
        val command = pendingArrival ?: return
        if (!currentAuthority.canStart || !mutableState.value.hasRecoverableArrival ||
            mutableState.value.arrivalCommandStatus !in setOf(
                DriverArrivalCommandStatus.UnknownOutcome,
                DriverArrivalCommandStatus.PersistenceUnavailable
            )
        ) {
            return
        }
        val requestGeneration = generation
        if (pendingArrivalPersisted) {
            mutableState.update { it.copy(arrivalCommandStatus = DriverArrivalCommandStatus.Pending) }
            viewModelScope.launch { runArrival(command, requestGeneration, currentAuthority) }
        } else {
            mutableState.update {
                it.copy(arrivalCommandStatus = DriverArrivalCommandStatus.PersistingIntent)
            }
            viewModelScope.launch { persistBeforeArrival(command, requestGeneration, currentAuthority) }
        }
    }

    /** Creates a server-owned pending POD only after a final delivery outcome is confirmed. */
    fun createProof(receiverName: String, notes: String? = null) {
        val currentAuthority = authority ?: return
        val currentState = mutableState.value
        val selected = currentState.selectedDelivery ?: return
        val outcome = currentState.outcomeSummary ?: return
        val receiver = receiverName.trim().takeIf(String::isNotEmpty) ?: return
        if (!currentAuthority.canCaptureProof || !currentAuthority.canRead || proofMetadataStore == null ||
            currentState.detailStatus != DriverDeliveryLoadStatus.Ready || selected.status != "DELIVERED" ||
            currentState.outcomeCommandStatus != DriverOutcomeCommandStatus.Recorded ||
            currentState.proofCommandStatus in FROZEN_PROOF_COMMANDS || pendingProof != null
        ) return
        val note = notes?.trim()?.takeIf(String::isNotEmpty)
        if (receiver.length > 160 || (note?.length ?: 0) > 2000) {
            mutableState.update {
                it.copy(proofCommandStatus = DriverProofCommandStatus.Rejected, proofRejectionCode = "POD_RECEIVER_REQUIRED")
            }
            return
        }
        val requestGeneration = generation
        mutableState.update {
            it.copy(proofCommandStatus = DriverProofCommandStatus.CheckingCurrent, proofRejectionCode = null)
        }
        viewModelScope.launch {
            val currentResult = safeLoad { gateway.delivery(selected.id, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val current = (currentResult as? DriverDeliveryLoadResult.DetailLoaded)?.item
            if (current == null || current.status != "DELIVERED") {
                mutableState.update {
                    it.copy(
                        detailStatus = currentResult.toLoadStatus(),
                        proofCommandStatus = DriverProofCommandStatus.StaleVersion,
                        proofRejectionCode = "POD_REQUIRES_FINAL_DELIVERY"
                    )
                }
                return@launch
            }
            replaceDelivery(current)
            val key = keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
            val capturedAt = timeFactory()
            val timestampValid = runCatching { Instant.parse(capturedAt) }.isSuccess
            if (key == null || !timestampValid) {
                mutableState.update {
                    it.copy(
                        proofCommandStatus = DriverProofCommandStatus.Rejected,
                        proofRejectionCode = "POD_REQUEST_INVALID"
                    )
                }
                return@launch
            }
            val body = frozenProofCreateBody(receiver, capturedAt, note)
            val intent = DriverProofIntentMetadata(
                scope = currentAuthority.scopeIdentity,
                deliveryId = current.id,
                attemptId = outcome.attemptId,
                createExpectedVersion = current.version,
                createIdempotencyKey = key,
                createBody = body,
                receiverName = receiver,
                capturedAt = capturedAt,
                notes = note,
                stage = DriverProofIntentStage.CreatingProof,
                status = DriverProofIntentStatus.Pending
            )
            pendingProof = intent
            pendingProofPersisted = false
            mutableState.update {
                it.copy(
                    proofCommandStatus = DriverProofCommandStatus.PersistingIntent,
                    hasRecoverableProof = true,
                    proofAttemptId = intent.attemptId,
                    proofSummary = null,
                    proofEvidenceSummary = null
                )
            }
            persistBeforeProofCreate(intent, requestGeneration, currentAuthority)
        }
    }

    /** Adopts an encrypted picker return only after current authority and server detail are restored. */
    fun reloadStagedProofSelection(context: DriverProofSelectionContext): Boolean {
        val currentAuthority = authority ?: return false
        val current = mutableState.value
        val selected = current.selectedDelivery ?: return false
        if (!currentAuthority.canCaptureProof || currentAuthority.scopeIdentity != context.scope ||
            selected.id != context.deliveryId || selected.status != "DELIVERED" ||
            current.detailStatus != DriverDeliveryLoadStatus.Ready
        ) return false
        val pending = pendingProof
        if (pending?.stage == DriverProofIntentStage.EvidenceReadyForReview &&
            pending.deliveryId == context.deliveryId && pending.attemptId == context.attemptId &&
            pending.proofId == context.proofId && pending.scope == context.scope &&
            current.proofCommandStatus == DriverProofCommandStatus.ReadyToUploadReview
        ) return true
        if (reloadingReturnedProof) return false
        reloadingReturnedProof = true
        val requestGeneration = generation
        viewModelScope.launch {
            try {
                val loaded = safeProofMetadataLoad(context.scope)
                if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                val intent = (loaded as? DriverProofMetadataRead.Available)?.intent
                if (intent == null || intent.scope != context.scope || intent.deliveryId != context.deliveryId ||
                    intent.attemptId != context.attemptId || intent.proofId != context.proofId ||
                    intent.stage != DriverProofIntentStage.EvidenceReadyForReview
                ) {
                    mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.PersistenceUnavailable) }
                    return@launch
                }
                pendingProof = intent
                pendingProofPersisted = true
                mutableState.update {
                    it.copy(proofCommandStatus = DriverProofCommandStatus.ReadyToUploadReview,
                        proofId = intent.proofId, proofAttemptId = intent.attemptId,
                        proofEvidenceKind = intent.evidenceKind, hasRecoverableProof = true,
                        proofRejectionCode = null)
                }
            } finally {
                reloadingReturnedProof = false
            }
        }
        return false
    }

    /** Captures only route identity. The app launches GetContent after this returns. */
    fun beginProofFileSelection(): DriverProofSelectionContext? {
        val currentAuthority = authority ?: return null
        val current = mutableState.value
        val intent = pendingProof ?: return null
        val proofId = intent.proofId ?: return null
        val delivery = current.selectedDelivery ?: return null
        if (!currentAuthority.canCaptureProof || current.detailStatus != DriverDeliveryLoadStatus.Ready ||
            current.proofCommandStatus != DriverProofCommandStatus.AwaitingEvidenceSelection ||
            intent.stage != DriverProofIntentStage.ProofCreated || delivery.id != intent.deliveryId ||
            delivery.status != "DELIVERED" || intent.scope != currentAuthority.scopeIdentity
        ) return null
        return DriverProofSelectionContext(
            authorityEpoch = currentAuthority.authorityEpoch,
            scope = currentAuthority.scopeIdentity,
            deliveryId = intent.deliveryId,
            attemptId = intent.attemptId,
            proofId = proofId
        )
    }

    /** Stages a picker result for explicit review; this method never starts network work. */
    fun acceptProofFileSelection(
        context: DriverProofSelectionContext,
        candidate: DriverProofFileCandidate,
        kind: DriverProofEvidenceKind = DriverProofEvidenceKind.PHOTO
    ) {
        val currentAuthority = authority
        val current = mutableState.value
        val intent = pendingProof
        if (currentAuthority == null || intent == null || proofMetadataStore == null ||
            currentAuthority.scopeIdentity != context.scope || intent.scope != context.scope ||
            intent.stage != DriverProofIntentStage.ProofCreated ||
            intent.deliveryId != context.deliveryId || intent.attemptId != context.attemptId ||
            intent.proofId != context.proofId || current.selectedDelivery?.id != context.deliveryId ||
            current.proofId != context.proofId || current.detailStatus != DriverDeliveryLoadStatus.Ready ||
            !currentAuthority.canCaptureProof
        ) {
            candidate.file.delete()
            return
        }
        val key = keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
        val fileToken = UUID.randomUUID().toString()
        if (key == null) {
            candidate.file.delete()
            mutableState.update {
                it.copy(proofCommandStatus = DriverProofCommandStatus.Rejected, proofRejectionCode = "IDEMPOTENCY_KEY_INVALID")
            }
            return
        }
        val staged = intent.copy(
            stage = DriverProofIntentStage.EvidenceReadyForReview,
            status = DriverProofIntentStatus.Pending,
            evidenceKind = kind,
            evidenceUploadKey = key,
            candidateFileToken = fileToken,
            candidateFilename = candidate.originalFilename,
            candidateContentType = candidate.declaredContentType,
            candidateByteSize = candidate.byteSize,
            candidateChecksumSha256 = candidate.checksumSha256
        )
        pendingProof = staged
        pendingProofPersisted = false
        mutableState.update {
            it.copy(proofCommandStatus = DriverProofCommandStatus.PersistingIntent, proofRejectionCode = null)
        }
        val requestGeneration = generation
        viewModelScope.launch {
            val saved = safeProofMetadataStage(staged, candidate)
            candidate.file.delete()
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != DriverProofMetadataWrite.Saved) {
                mutableState.update {
                    it.copy(
                        proofCommandStatus = DriverProofCommandStatus.PersistenceUnavailable,
                        hasRecoverableProof = true
                    )
                }
                return@launch
            }
            pendingProofPersisted = true
            mutableState.update {
                it.copy(
                    proofCommandStatus = DriverProofCommandStatus.ReadyToUploadReview,
                    hasRecoverableProof = true,
                    proofId = staged.proofId,
                    proofAttemptId = staged.attemptId,
                    proofEvidenceKind = staged.evidenceKind
                )
            }
        }
    }

    /** Upload is deliberately a separate user action after reactivation and review. */
    fun uploadSelectedProofEvidence() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val intent = pendingProof ?: return
        if (!currentAuthority.canCaptureProof || current.detailStatus != DriverDeliveryLoadStatus.Ready ||
            current.selectedDelivery?.id != intent.deliveryId || current.selectedDelivery.status != "DELIVERED" ||
            current.proofCommandStatus != DriverProofCommandStatus.ReadyToUploadReview ||
            intent.scope != currentAuthority.scopeIdentity || intent.stage != DriverProofIntentStage.EvidenceReadyForReview ||
            !pendingProofPersisted || intent.candidateFileToken == null || intent.evidenceKind == null ||
            intent.evidenceUploadKey == null || proofMetadataStore == null
        ) return
        val requestGeneration = generation
        val dispatchIntent = intent.copy(
            stage = DriverProofIntentStage.UploadingEvidence,
            status = DriverProofIntentStatus.Pending
        )
        pendingProof = dispatchIntent
        mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.PersistingIntent) }
        viewModelScope.launch {
            val saved = safeProofMetadataWrite(dispatchIntent)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != DriverProofMetadataWrite.Saved) {
                mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.PersistenceUnavailable) }
                return@launch
            }
            val candidate = safeProofCandidateLoad(dispatchIntent)
            if (!isCurrent(requestGeneration, currentAuthority)) {
                candidate?.file?.delete()
                return@launch
            }
            if (candidate == null || candidate.checksumSha256 != dispatchIntent.candidateChecksumSha256) {
                mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.PersistenceUnavailable) }
                return@launch
            }
            mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.Pending) }
            runProofUpload(dispatchIntent, candidate, requestGeneration, currentAuthority)
        }
    }

    /** Replays only the exact durable stage; it never creates a replacement key or body. */
    fun retryUnknownProof() {
        val currentAuthority = authority ?: return
        val intent = pendingProof ?: return
        if (!currentAuthority.canCaptureProof || intent.scope != currentAuthority.scopeIdentity ||
            !mutableState.value.hasRecoverableProof ||
            mutableState.value.proofCommandStatus !in setOf(
                DriverProofCommandStatus.UnknownOutcome,
                DriverProofCommandStatus.PersistenceUnavailable
            )
        ) return
        val requestGeneration = generation
        when (intent.stage) {
            DriverProofIntentStage.CreatingProof -> {
                mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.Pending) }
                viewModelScope.launch { runProofCreate(intent, requestGeneration, currentAuthority) }
            }
            DriverProofIntentStage.UploadingEvidence -> viewModelScope.launch {
                val candidate = safeProofCandidateLoad(intent)
                if (!isCurrent(requestGeneration, currentAuthority)) {
                    candidate?.file?.delete()
                    return@launch
                }
                if (candidate == null || candidate.checksumSha256 != intent.candidateChecksumSha256) {
                    mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.PersistenceUnavailable) }
                    return@launch
                }
                mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.Pending) }
                runProofUpload(intent, candidate, requestGeneration, currentAuthority)
            }
            DriverProofIntentStage.AttachingEvidence -> viewModelScope.launch {
                mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.Pending) }
                runProofAttach(intent, requestGeneration, currentAuthority)
            }
            DriverProofIntentStage.EvidenceAwaitingScan -> refreshProofEvidence()
            DriverProofIntentStage.ProofCreated -> mutableState.update {
                it.copy(proofCommandStatus = DriverProofCommandStatus.AwaitingEvidenceSelection)
            }
            DriverProofIntentStage.EvidenceReadyForReview -> mutableState.update {
                it.copy(proofCommandStatus = DriverProofCommandStatus.ReadyToUploadReview)
            }
            DriverProofIntentStage.Captured -> Unit
        }
    }

    /** Checks current server evidence status; a cached quarantine status is never shown as current. */
    fun refreshProofEvidence() {
        val currentAuthority = authority ?: return
        val intent = pendingProof ?: return
        val evidenceId = intent.evidenceId ?: return
        val proofId = intent.proofId ?: return
        if (!currentAuthority.canReadProofEvidence || intent.scope != currentAuthority.scopeIdentity) return
        val requestGeneration = generation
        mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.CheckingCurrent) }
        viewModelScope.launch {
            val result = try {
                gateway.proofEvidenceStatus(evidenceId, proofId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverProofEvidenceStatusResult.ServiceUnavailable
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (result) {
                is DriverProofEvidenceStatusResult.Loaded -> {
                    if (result.summary.subjectType != "PROOF_OF_DELIVERY" ||
                        result.summary.subjectId != proofId || result.summary.evidenceId != evidenceId
                    ) {
                        proofStateRejected("EVIDENCE_SUBJECT_MISMATCH")
                        return@launch
                    }
                    mutableState.update {
                        it.copy(
                            proofEvidenceSummary = result.summary,
                            proofCommandStatus = when (result.summary.lifecycleStatus) {
                                "AVAILABLE" -> DriverProofCommandStatus.EvidenceAvailable
                                "REJECTED", "DELETED" -> DriverProofCommandStatus.Rejected
                                else -> DriverProofCommandStatus.WaitingForScan
                            },
                            proofRejectionCode = result.summary.lifecycleStatus.takeIf { status ->
                                status in setOf("REJECTED", "DELETED")
                            }
                        )
                    }
                }
                DriverProofEvidenceStatusResult.NotFound -> proofStateRejected("EVIDENCE_NOT_FOUND")
                DriverProofEvidenceStatusResult.PermissionDenied -> proofStateRejected("DOCUMENT_READ_REQUIRED")
                DriverProofEvidenceStatusResult.ContextInvalidated -> proofStateRejected("ACCESS_CONTEXT_INVALID")
                DriverProofEvidenceStatusResult.SessionInvalidated -> proofStateRejected("SESSION_INVALIDATED")
                DriverProofEvidenceStatusResult.ServiceUnavailable -> proofStateRejected("EVIDENCE_STATUS_UNAVAILABLE")
            }
        }
    }

    /** Attaches only evidence whose current server projection is AVAILABLE. */
    fun attachAvailableProofEvidence() {
        val currentAuthority = authority ?: return
        val intent = pendingProof ?: return
        val evidence = mutableState.value.proofEvidenceSummary ?: return
        val selected = mutableState.value.selectedDelivery ?: return
        if (!currentAuthority.canCaptureProof || intent.scope != currentAuthority.scopeIdentity ||
            mutableState.value.proofCommandStatus != DriverProofCommandStatus.EvidenceAvailable ||
            evidence.lifecycleStatus != "AVAILABLE" || evidence.evidenceId != intent.evidenceId ||
            selected.id != intent.deliveryId || mutableState.value.detailStatus != DriverDeliveryLoadStatus.Ready ||
            intent.proofId == null || intent.evidenceKind == null || proofMetadataStore == null
        ) return
        val requestGeneration = generation
        mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.CheckingCurrent) }
        viewModelScope.launch {
            val detail = safeLoad { gateway.delivery(intent.deliveryId, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val current = (detail as? DriverDeliveryLoadResult.DetailLoaded)?.item
            if (current == null || current.status != "DELIVERED") {
                mutableState.update {
                    it.copy(
                        detailStatus = detail.toLoadStatus(),
                        proofCommandStatus = DriverProofCommandStatus.StaleVersion,
                        proofRejectionCode = "POD_REQUIRES_FINAL_DELIVERY"
                    )
                }
                return@launch
            }
            replaceDelivery(current)
            val key = keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
            if (key == null) {
                proofStateRejected("IDEMPOTENCY_KEY_INVALID")
                return@launch
            }
            val body = frozenProofAttachBody(intent.evidenceKind, evidence.evidenceId)
            val attachIntent = intent.copy(
                stage = DriverProofIntentStage.AttachingEvidence,
                status = DriverProofIntentStatus.Pending,
                attachExpectedVersion = current.version,
                attachKey = key,
                attachBody = body
            )
            pendingProof = attachIntent
            pendingProofPersisted = false
            mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.PersistingIntent) }
            val saved = safeProofMetadataWrite(attachIntent)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != DriverProofMetadataWrite.Saved) {
                mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.PersistenceUnavailable) }
                return@launch
            }
            pendingProofPersisted = true
            mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.Pending) }
            runProofAttach(attachIntent, requestGeneration, currentAuthority)
        }
    }

    private suspend fun persistBeforeProofCreate(
        intent: DriverProofIntentMetadata,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val saved = safeProofMetadataWrite(intent)
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (saved != DriverProofMetadataWrite.Saved) {
            mutableState.update {
                it.copy(proofCommandStatus = DriverProofCommandStatus.PersistenceUnavailable, hasRecoverableProof = true)
            }
            return
        }
        pendingProofPersisted = true
        mutableState.update { it.copy(proofCommandStatus = DriverProofCommandStatus.Pending) }
        runProofCreate(intent, requestGeneration, currentAuthority)
    }

    private suspend fun runProofCreate(
        intent: DriverProofIntentMetadata,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val command = DriverProofCreateCommand(
            intent.deliveryId,
            intent.attemptId,
            intent.createExpectedVersion,
            intent.createIdempotencyKey,
            intent.createBody,
            intent.receiverName,
            intent.capturedAt
        )
        val result = try {
            gateway.createProof(command, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverProofCreateResult.UnknownOutcome
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        when (result) {
            is DriverProofCreateResult.Created -> {
                val proof = result.summary
                if (proof.deliveryId != intent.deliveryId || proof.attemptId != intent.attemptId ||
                    proof.actorMembershipId != currentAuthority.membershipId ||
                    proof.status !in setOf("PENDING", "CAPTURED")
                ) {
                    markProofUnknown(intent, requestGeneration, currentAuthority)
                    return
                }
                if (proof.status == "CAPTURED") {
                    clearProofIntentAfterSuccess(intent, proof, requestGeneration, currentAuthority)
                    return
                }
                val updated = intent.copy(
                    stage = DriverProofIntentStage.ProofCreated,
                    status = DriverProofIntentStatus.Pending,
                    proofId = proof.proofId,
                    proofVersion = proof.deliveryVersion
                )
                val saved = safeProofMetadataWrite(updated)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (saved != DriverProofMetadataWrite.Saved) {
                    pendingProof = intent.copy(status = DriverProofIntentStatus.UnknownOutcome)
                    mutableState.update {
                        it.copy(
                            proofCommandStatus = DriverProofCommandStatus.UnknownOutcome,
                            hasRecoverableProof = true
                        )
                    }
                    return
                }
                pendingProof = updated
                pendingProofPersisted = true
                mutableState.update {
                    it.copy(
                        proofCommandStatus = DriverProofCommandStatus.AwaitingEvidenceSelection,
                        proofId = proof.proofId,
                        proofAttemptId = proof.attemptId,
                        proofSummary = proof,
                        hasRecoverableProof = true
                    )
                }
            }
            is DriverProofCreateResult.Rejected -> finishProofRejected(
                intent, result.code ?: "POD_CREATE_REJECTED", requestGeneration, currentAuthority
            )
            DriverProofCreateResult.StaleVersion -> finishProofRejected(
                intent, "VERSION_CONFLICT", requestGeneration, currentAuthority, stale = true
            )
            DriverProofCreateResult.NotFound -> finishProofRejected(
                intent, "DELIVERY_ATTEMPT_NOT_FOUND", requestGeneration, currentAuthority
            )
            DriverProofCreateResult.PermissionDenied -> markProofUnknown(intent, requestGeneration, currentAuthority, "PERMISSION_DENIED")
            DriverProofCreateResult.ContextInvalidated -> markProofUnknown(intent, requestGeneration, currentAuthority, "ACCESS_CONTEXT_INVALID")
            DriverProofCreateResult.SessionInvalidated -> markProofUnknown(intent, requestGeneration, currentAuthority, "SESSION_INVALIDATED")
            DriverProofCreateResult.UnknownOutcome,
            DriverProofCreateResult.ServiceUnavailable -> markProofUnknown(intent, requestGeneration, currentAuthority)
        }
    }

    private suspend fun runProofUpload(
        intent: DriverProofIntentMetadata,
        candidate: DriverProofFileCandidate,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        try {
            val verification = gateway.createProof(
                DriverProofCreateCommand(
                    intent.deliveryId, intent.attemptId, intent.createExpectedVersion,
                    intent.createIdempotencyKey, intent.createBody, intent.receiverName, intent.capturedAt
                ),
                currentAuthority
            )
            if (!isCurrent(requestGeneration, currentAuthority)) return
            val verified = (verification as? DriverProofCreateResult.Created)?.summary
            if (verified == null || verified.proofId != intent.proofId ||
                verified.deliveryId != intent.deliveryId || verified.attemptId != intent.attemptId ||
                verified.actorMembershipId != currentAuthority.membershipId ||
                verified.status != "PENDING"
            ) {
                markProofUnknown(intent, requestGeneration, currentAuthority, "POD_REVALIDATION_REQUIRED")
                return
            }
            val uploadKey = intent.evidenceUploadKey ?: run {
                markProofUnknown(intent, requestGeneration, currentAuthority, "PROOF_INTENT_INCOMPLETE")
                return
            }
            val evidenceKind = intent.evidenceKind ?: run {
                markProofUnknown(intent, requestGeneration, currentAuthority, "PROOF_INTENT_INCOMPLETE")
                return
            }
            val result = try {
                gateway.uploadProofEvidence(
                    DriverProofUploadCommand(intent.deliveryId, intent.attemptId, intent.proofId!!,
                        evidenceKind, uploadKey, candidate),
                    currentAuthority
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverProofUploadResult.UnknownOutcome
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return
            when (result) {
                is DriverProofUploadResult.Uploaded -> {
                    val evidence = result.summary
                    if (evidence.checksumSha256 != intent.candidateChecksumSha256 ||
                        evidence.byteSize != intent.candidateByteSize ||
                        evidence.contentType != intent.candidateContentType
                    ) {
                        markProofUnknown(intent, requestGeneration, currentAuthority, "IDEMPOTENCY_PAYLOAD_CONFLICT")
                        return
                    }
                    val updated = intent.copy(
                        stage = DriverProofIntentStage.EvidenceAwaitingScan,
                        status = DriverProofIntentStatus.Pending,
                        evidenceId = evidence.evidenceId,
                        candidateFileToken = null
                    )
                    val saved = safeProofMetadataWrite(updated)
                    if (!isCurrent(requestGeneration, currentAuthority)) return
                    if (saved != DriverProofMetadataWrite.Saved) {
                        markProofUnknown(intent, requestGeneration, currentAuthority)
                        return
                    }
                    safeProofCandidateClear(intent)
                    pendingProof = updated
                    pendingProofPersisted = true
                    mutableState.update {
                        it.copy(
                            proofEvidenceId = evidence.evidenceId,
                            proofEvidenceKind = evidenceKind,
                            proofEvidenceSummary = evidence,
                            proofCommandStatus = if (evidence.lifecycleStatus == "AVAILABLE") {
                                DriverProofCommandStatus.EvidenceAvailable
                            } else {
                                DriverProofCommandStatus.WaitingForScan
                            },
                            hasRecoverableProof = true
                        )
                    }
                }
                is DriverProofUploadResult.Rejected -> markProofUnknown(
                    intent, requestGeneration, currentAuthority, result.code ?: "EVIDENCE_UPLOAD_REJECTED"
                )
                DriverProofUploadResult.PermissionDenied -> markProofUnknown(intent, requestGeneration, currentAuthority, "PERMISSION_DENIED")
                DriverProofUploadResult.ContextInvalidated -> markProofUnknown(intent, requestGeneration, currentAuthority, "ACCESS_CONTEXT_INVALID")
                DriverProofUploadResult.SessionInvalidated -> markProofUnknown(intent, requestGeneration, currentAuthority, "SESSION_INVALIDATED")
                DriverProofUploadResult.NotFound,
                DriverProofUploadResult.UnknownOutcome,
                DriverProofUploadResult.ServiceUnavailable -> markProofUnknown(intent, requestGeneration, currentAuthority)
            }
        } finally {
            candidate.file.delete()
        }
    }

    private suspend fun runProofAttach(
        intent: DriverProofIntentMetadata,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val proofId = intent.proofId ?: return markProofUnknown(intent, requestGeneration, currentAuthority)
        val evidenceId = intent.evidenceId ?: return markProofUnknown(intent, requestGeneration, currentAuthority)
        val kind = intent.evidenceKind ?: return markProofUnknown(intent, requestGeneration, currentAuthority)
        val expectedVersion = intent.attachExpectedVersion ?: return markProofUnknown(intent, requestGeneration, currentAuthority)
        val key = intent.attachKey ?: return markProofUnknown(intent, requestGeneration, currentAuthority)
        val body = intent.attachBody ?: return markProofUnknown(intent, requestGeneration, currentAuthority)
        val result = try {
            gateway.attachProofEvidence(
                DriverProofAttachCommand(intent.deliveryId, intent.attemptId, proofId, evidenceId,
                    kind, expectedVersion, key, body),
                currentAuthority
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverProofAttachResult.UnknownOutcome
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        when (result) {
            is DriverProofAttachResult.Attached -> {
                val proof = result.summary
                if (proof.proofId != proofId || proof.deliveryId != intent.deliveryId ||
                    proof.attemptId != intent.attemptId || proof.actorMembershipId != currentAuthority.membershipId ||
                    proof.status != "CAPTURED" ||
                    (if (kind == DriverProofEvidenceKind.PHOTO) proof.photoEvidenceObjectId else proof.signatureEvidenceObjectId) != evidenceId
                ) {
                    markProofUnknown(intent, requestGeneration, currentAuthority)
                } else {
                    clearProofIntentAfterSuccess(intent, proof, requestGeneration, currentAuthority)
                }
            }
            DriverProofAttachResult.StaleVersion -> {
                val retained = intent.copy(
                    stage = DriverProofIntentStage.EvidenceAwaitingScan,
                    status = DriverProofIntentStatus.Pending,
                    attachExpectedVersion = null,
                    attachKey = null,
                    attachBody = null
                )
                val saved = safeProofMetadataWrite(retained)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                pendingProof = retained
                mutableState.update {
                    it.copy(
                        proofCommandStatus = if (saved == DriverProofMetadataWrite.Saved) {
                            DriverProofCommandStatus.StaleVersion
                        } else DriverProofCommandStatus.PersistenceUnavailable,
                        proofRejectionCode = "VERSION_CONFLICT"
                    )
                }
            }
            is DriverProofAttachResult.Rejected -> finishProofRejected(
                intent, result.code ?: "POD_ATTACH_REJECTED", requestGeneration, currentAuthority
            )
            DriverProofAttachResult.NotFound -> finishProofRejected(intent, "POD_NOT_FOUND", requestGeneration, currentAuthority)
            DriverProofAttachResult.PermissionDenied -> markProofUnknown(intent, requestGeneration, currentAuthority, "PERMISSION_DENIED")
            DriverProofAttachResult.ContextInvalidated -> markProofUnknown(intent, requestGeneration, currentAuthority, "ACCESS_CONTEXT_INVALID")
            DriverProofAttachResult.SessionInvalidated -> markProofUnknown(intent, requestGeneration, currentAuthority, "SESSION_INVALIDATED")
            DriverProofAttachResult.UnknownOutcome,
            DriverProofAttachResult.ServiceUnavailable -> markProofUnknown(intent, requestGeneration, currentAuthority)
        }
    }

    private suspend fun markProofUnknown(
        intent: DriverProofIntentMetadata,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority,
        rejection: String? = null
    ) {
        val unknown = intent.copy(status = DriverProofIntentStatus.UnknownOutcome)
        safeProofMetadataWrite(unknown)
        if (!isCurrent(requestGeneration, currentAuthority)) return
        pendingProof = unknown
        mutableState.update {
            it.copy(
                proofCommandStatus = DriverProofCommandStatus.UnknownOutcome,
                hasRecoverableProof = true,
                proofId = unknown.proofId,
                proofAttemptId = unknown.attemptId,
                proofEvidenceId = unknown.evidenceId,
                proofEvidenceKind = unknown.evidenceKind,
                proofRejectionCode = rejection
            )
        }
    }

    private suspend fun finishProofRejected(
        intent: DriverProofIntentMetadata,
        code: String,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority,
        stale: Boolean = false
    ) {
        val cleared = safeProofMetadataClear(currentAuthority.scopeIdentity, intent.createIdempotencyKey) ==
            DriverProofMetadataWrite.Saved
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared) {
            safeProofCandidateClear(intent)
            pendingProof = null
            pendingProofPersisted = false
        }
        mutableState.update {
            it.copy(
                proofCommandStatus = when {
                    !cleared -> DriverProofCommandStatus.PersistenceUnavailable
                    stale -> DriverProofCommandStatus.StaleVersion
                    else -> DriverProofCommandStatus.Rejected
                },
                hasRecoverableProof = !cleared,
                proofRejectionCode = code
            )
        }
    }

    private suspend fun clearProofIntentAfterSuccess(
        intent: DriverProofIntentMetadata,
        proof: DriverProofSummary,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val cleared = safeProofMetadataClear(currentAuthority.scopeIdentity, intent.createIdempotencyKey) ==
            DriverProofMetadataWrite.Saved
        if (!isCurrent(requestGeneration, currentAuthority)) return
        safeProofCandidateClear(intent)
        if (cleared) {
            pendingProof = null
            pendingProofPersisted = false
        }
        mutableState.update {
            it.copy(
                proofCommandStatus = if (cleared) DriverProofCommandStatus.Captured else DriverProofCommandStatus.PersistenceUnavailable,
                proofId = proof.proofId,
                proofAttemptId = proof.attemptId,
                proofSummary = proof,
                hasRecoverableProof = !cleared
            )
        }
    }

    private fun proofStateRejected(code: String) {
        mutableState.update {
            it.copy(proofCommandStatus = DriverProofCommandStatus.Rejected, proofRejectionCode = code)
        }
    }

    private suspend fun persistBeforeArrival(
        command: DriverArrivalCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val saved = safeArrivalMetadataWrite(
            DriverArrivalIntentMetadata(
                currentAuthority.scopeIdentity,
                command,
                DriverArrivalIntentStatus.Pending
            )
        )
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (saved != DriverArrivalMetadataWrite.Saved) {
            mutableState.update {
                it.copy(
                    arrivalCommandStatus = DriverArrivalCommandStatus.PersistenceUnavailable,
                    hasRecoverableArrival = true
                )
            }
            return
        }
        pendingArrivalPersisted = true
        mutableState.update {
            it.copy(
                arrivalCommandStatus = DriverArrivalCommandStatus.Pending,
                hasRecoverableArrival = true
            )
        }
        runArrival(command, requestGeneration, currentAuthority)
    }

    private suspend fun runArrival(
        command: DriverArrivalCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val result = try {
            gateway.signalArrival(command, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverArrivalResult.UnknownOutcome
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        when (result) {
            is DriverArrivalResult.Recorded -> {
                val cleared = clearArrivalIntent(command, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (cleared) {
                    pendingArrival = null
                    pendingArrivalPersisted = false
                }
                val summary = result.summary
                mutableState.update { current ->
                    val selected = current.selectedDelivery
                    current.copy(
                        selectedDelivery = selected?.takeIf { it.id == command.deliveryId }?.copy(
                            arrival = DriverDeliveryArrivalFact(
                                summary.eventId, summary.attemptId, summary.arrivedAt
                            ),
                            version = maxOf(selected.version, summary.deliveryVersion)
                        ) ?: selected,
                        arrivalCommandStatus = if (cleared) {
                            DriverArrivalCommandStatus.Recorded
                        } else {
                            DriverArrivalCommandStatus.PersistenceUnavailable
                        },
                        hasRecoverableArrival = !cleared,
                        arrivalSummary = summary,
                        arrivalRejectionCode = null
                    )
                }
                refresh()
            }

            is DriverArrivalResult.Rejected -> finishArrivalRejection(
                result.code ?: "DELIVERY_ARRIVAL_REJECTED", command, requestGeneration, currentAuthority
            )

            DriverArrivalResult.StaleVersion -> {
                finishArrivalRejection(
                    "CONCURRENCY_CONFLICT", command, requestGeneration, currentAuthority,
                    DriverArrivalCommandStatus.StaleVersion
                )
                if (isCurrent(requestGeneration, currentAuthority)) refresh()
            }

            DriverArrivalResult.NotFound -> {
                val cleared = clearArrivalIntent(command, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (cleared) {
                    pendingArrival = null
                    pendingArrivalPersisted = false
                }
                mutableState.update {
                    it.copy(
                        detailStatus = DriverDeliveryLoadStatus.NotFound,
                        arrivalCommandStatus = if (cleared) {
                            DriverArrivalCommandStatus.Rejected
                        } else {
                            DriverArrivalCommandStatus.PersistenceUnavailable
                        },
                        hasRecoverableArrival = !cleared,
                        arrivalRejectionCode = "DELIVERY_NOT_FOUND"
                    )
                }
            }

            DriverArrivalResult.UnknownOutcome,
            DriverArrivalResult.NetworkUnavailable,
            DriverArrivalResult.ServiceUnavailable -> markArrivalUnknown(
                command, requestGeneration, currentAuthority
            )

            DriverArrivalResult.PermissionDenied -> finishArrivalRejection(
                "PERMISSION_DENIED", command, requestGeneration, currentAuthority
            )

            DriverArrivalResult.ContextInvalidated -> finishArrivalRejection(
                "ACCESS_CONTEXT_INVALID", command, requestGeneration, currentAuthority
            )

            DriverArrivalResult.SessionInvalidated -> finishArrivalRejection(
                "SESSION_INVALIDATED", command, requestGeneration, currentAuthority
            )
        }
    }

    private suspend fun markArrivalUnknown(
        command: DriverArrivalCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        safeArrivalMetadataWrite(
            DriverArrivalIntentMetadata(
                currentAuthority.scopeIdentity, command, DriverArrivalIntentStatus.UnknownOutcome
            )
        )
        if (!isCurrent(requestGeneration, currentAuthority)) return
        mutableState.update {
            it.copy(
                arrivalCommandStatus = DriverArrivalCommandStatus.UnknownOutcome,
                hasRecoverableArrival = true
            )
        }
    }

    private suspend fun finishArrivalRejection(
        code: String,
        command: DriverArrivalCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority,
        status: DriverArrivalCommandStatus = DriverArrivalCommandStatus.Rejected
    ) {
        val cleared = clearArrivalIntent(command, currentAuthority)
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared) {
            pendingArrival = null
            pendingArrivalPersisted = false
        }
        mutableState.update {
            it.copy(
                arrivalCommandStatus = if (cleared) status else DriverArrivalCommandStatus.PersistenceUnavailable,
                hasRecoverableArrival = !cleared,
                arrivalRejectionCode = code
            )
        }
    }

    private suspend fun clearArrivalIntent(
        command: DriverArrivalCommand,
        currentAuthority: DriverDeliveryAuthority
    ): Boolean = safeArrivalMetadataClear(currentAuthority.scopeIdentity, command.idempotencyKey) ==
        DriverArrivalMetadataWrite.Saved

    private suspend fun persistBeforeOutcome(
        command: DriverOutcomeCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val saved = safeOutcomeMetadataWrite(
            DriverOutcomeIntentMetadata(
                currentAuthority.scopeIdentity,
                command,
                DriverOutcomeIntentStatus.Pending
            )
        )
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (saved != DriverOutcomeMetadataWrite.Saved) {
            mutableState.update {
                it.copy(
                    outcomeCommandStatus = DriverOutcomeCommandStatus.PersistenceUnavailable,
                    hasRecoverableOutcome = true
                )
            }
            return
        }
        pendingOutcomePersisted = true
        mutableState.update {
            it.copy(
                outcomeCommandStatus = DriverOutcomeCommandStatus.Pending,
                hasRecoverableOutcome = true
            )
        }
        runOutcome(command, requestGeneration, currentAuthority)
    }

    private suspend fun runOutcome(
        command: DriverOutcomeCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val result = try {
            gateway.recordOutcome(command, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverOutcomeResult.UnknownOutcome
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        when (result) {
            is DriverOutcomeResult.Recorded -> {
                val cleared = clearOutcomeIntent(command, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (cleared) {
                    pendingOutcome = null
                    pendingOutcomePersisted = false
                }
                mutableState.update {
                    it.copy(
                        outcomeCommandStatus = if (cleared) {
                            DriverOutcomeCommandStatus.Recorded
                        } else {
                            DriverOutcomeCommandStatus.PersistenceUnavailable
                        },
                        hasRecoverableOutcome = !cleared,
                        outcomeSummary = result.summary,
                        outcomeRejectionCode = null
                    )
                }
                refresh()
            }

            is DriverOutcomeResult.Rejected -> finishOutcomeRejection(
                result.code ?: "DELIVERY_OUTCOME_REJECTED", command, requestGeneration, currentAuthority
            )

            DriverOutcomeResult.StaleVersion -> {
                finishOutcomeRejection("CONCURRENCY_CONFLICT", command, requestGeneration, currentAuthority)
                if (isCurrent(requestGeneration, currentAuthority)) refresh()
            }

            DriverOutcomeResult.NotFound -> {
                val cleared = clearOutcomeIntent(command, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (cleared) {
                    pendingOutcome = null
                    pendingOutcomePersisted = false
                }
                mutableState.update {
                    it.copy(
                        detailStatus = DriverDeliveryLoadStatus.NotFound,
                        outcomeCommandStatus = if (cleared) {
                            DriverOutcomeCommandStatus.Rejected
                        } else {
                            DriverOutcomeCommandStatus.PersistenceUnavailable
                        },
                        hasRecoverableOutcome = !cleared,
                        outcomeRejectionCode = "DELIVERY_NOT_FOUND"
                    )
                }
            }

            DriverOutcomeResult.UnknownOutcome -> {
                safeOutcomeMetadataWrite(
                    DriverOutcomeIntentMetadata(
                        currentAuthority.scopeIdentity,
                        command,
                        DriverOutcomeIntentStatus.UnknownOutcome
                    )
                )
                if (!isCurrent(requestGeneration, currentAuthority)) return
                mutableState.update {
                    it.copy(
                        outcomeCommandStatus = DriverOutcomeCommandStatus.UnknownOutcome,
                        hasRecoverableOutcome = true
                    )
                }
            }

            DriverOutcomeResult.PermissionDenied -> finishOutcomeRejection(
                "PERMISSION_DENIED", command, requestGeneration, currentAuthority
            )

            DriverOutcomeResult.ContextInvalidated -> finishOutcomeRejection(
                "ACCESS_CONTEXT_INVALID", command, requestGeneration, currentAuthority
            )

            DriverOutcomeResult.SessionInvalidated -> finishOutcomeRejection(
                "SESSION_INVALIDATED", command, requestGeneration, currentAuthority
            )

            DriverOutcomeResult.NetworkUnavailable,
            DriverOutcomeResult.ServiceUnavailable -> {
                safeOutcomeMetadataWrite(
                    DriverOutcomeIntentMetadata(
                        currentAuthority.scopeIdentity,
                        command,
                        DriverOutcomeIntentStatus.UnknownOutcome
                    )
                )
                if (!isCurrent(requestGeneration, currentAuthority)) return
                mutableState.update {
                    it.copy(
                        outcomeCommandStatus = DriverOutcomeCommandStatus.UnknownOutcome,
                        hasRecoverableOutcome = true
                    )
                }
            }
        }
    }

    private suspend fun finishOutcomeRejection(
        code: String,
        command: DriverOutcomeCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val cleared = clearOutcomeIntent(command, currentAuthority)
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared) {
            pendingOutcome = null
            pendingOutcomePersisted = false
        }
        mutableState.update {
            it.copy(
                outcomeCommandStatus = if (cleared) {
                    DriverOutcomeCommandStatus.Rejected
                } else {
                    DriverOutcomeCommandStatus.PersistenceUnavailable
                },
                hasRecoverableOutcome = !cleared,
                outcomeRejectionCode = code
            )
        }
    }

    private suspend fun clearOutcomeIntent(
        command: DriverOutcomeCommand,
        currentAuthority: DriverDeliveryAuthority
    ): Boolean = safeOutcomeMetadataClear(currentAuthority.scopeIdentity, command.idempotencyKey) ==
        DriverOutcomeMetadataWrite.Saved

    private fun buildOutcomeLines(
        outcome: DriverOutcomeKind,
        facts: List<DriverDeliveryOutcomeLine>,
        deliveredInputs: Map<String, String>
    ): OutcomeLineBuildResult {
        val outstanding = facts.filter { it.remainingQuantity.signum() > 0 }
        return when (outcome) {
            DriverOutcomeKind.FAILED -> OutcomeLineBuildResult.Valid(emptyList())
            DriverOutcomeKind.DELIVERED -> if (outstanding.isEmpty()) {
                OutcomeLineBuildResult.Invalid("NO_OUTSTANDING_QUANTITY")
            } else {
                OutcomeLineBuildResult.Valid(outstanding.map { line ->
                    DriverOutcomeLineDecision(
                        line.fulfillmentLineId, line.skuId, line.remainingQuantity,
                        line.remainingQuantity, BigDecimal.ZERO, BigDecimal.ZERO, line.unit
                    )
                })
            }

            DriverOutcomeKind.PARTIAL -> {
                if (deliveredInputs.keys.any { id -> facts.none { it.fulfillmentLineId == id } }) {
                    return OutcomeLineBuildResult.Invalid("DELIVERY_OUTCOME_LINE_INVALID")
                }
                val decisions = mutableListOf<DriverOutcomeLineDecision>()
                for (line in outstanding) {
                    val raw = deliveredInputs[line.fulfillmentLineId]?.trim().orEmpty()
                    if (raw.isEmpty()) continue
                    val delivered = runCatching { BigDecimal(raw) }.getOrNull()
                        ?: return OutcomeLineBuildResult.Invalid("DELIVERY_OUTCOME_LINE_INVALID")
                    if (delivered.signum() <= 0 || delivered >= line.remainingQuantity) {
                        return OutcomeLineBuildResult.Invalid("DELIVERY_OUTCOME_LINE_INVALID")
                    }
                    decisions += DriverOutcomeLineDecision(
                        line.fulfillmentLineId, line.skuId, delivered, delivered,
                        BigDecimal.ZERO, BigDecimal.ZERO, line.unit
                    )
                }
                if (decisions.isEmpty()) {
                    OutcomeLineBuildResult.Invalid("PARTIAL_OUTCOME_REQUIRES_DELIVERY")
                } else {
                    OutcomeLineBuildResult.Valid(decisions)
                }
            }

            DriverOutcomeKind.REFUSED,
            DriverOutcomeKind.ABSENT -> if (outstanding.isEmpty()) {
                OutcomeLineBuildResult.Invalid("NO_OUTSTANDING_QUANTITY")
            } else {
                OutcomeLineBuildResult.Valid(outstanding.map { line ->
                    DriverOutcomeLineDecision(
                        line.fulfillmentLineId, line.skuId, line.remainingQuantity,
                        BigDecimal.ZERO, line.remainingQuantity, BigDecimal.ZERO, line.unit
                    )
                })
            }
        }
    }

    private fun validateOutcomeInput(
        outcome: DriverOutcomeKind,
        reason: String?,
        notes: String?,
        lines: OutcomeLineBuildResult
    ): String? {
        if (outcome in setOf(DriverOutcomeKind.FAILED, DriverOutcomeKind.REFUSED, DriverOutcomeKind.ABSENT) &&
            reason.isNullOrBlank()
        ) return "FAILURE_REASON_REQUIRED"
        if ((reason?.length ?: 0) > 2000 || (notes?.length ?: 0) > 2000) return "DELIVERY_OUTCOME_TEXT_TOO_LONG"
        return (lines as? OutcomeLineBuildResult.Invalid)?.code
    }

    private sealed interface OutcomeLineBuildResult {
        data class Valid(val lines: List<DriverOutcomeLineDecision>) : OutcomeLineBuildResult
        data class Invalid(val code: String) : OutcomeLineBuildResult
    }

    private fun frozenOutcomeBody(
        outcome: DriverOutcomeKind,
        reason: String?,
        notes: String?,
        attemptedAt: String,
        lines: List<DriverOutcomeLineDecision>
    ): String {
        val lineBody = lines.joinToString(prefix = "[", postfix = "]") { line ->
            "{" +
                "\"fulfillmentLineId\":${quoteJson(line.fulfillmentLineId)}," +
                "\"skuId\":${quoteJson(line.skuId)}," +
                "\"attemptedQuantity\":${line.attemptedQuantity.toPlainString()}," +
                "\"deliveredQuantity\":${line.deliveredQuantity.toPlainString()}," +
                "\"rejectedQuantity\":${line.rejectedQuantity.toPlainString()}," +
                "\"cancelledQuantity\":${line.cancelledQuantity.toPlainString()}," +
                "\"unit\":${quoteJson(line.unit)}" +
                "}"
        }
        return "{" +
            "\"outcome\":${quoteJson(outcome.name)}," +
            "\"failureReason\":${reason?.let(::quoteJson) ?: "null"}," +
            "\"notes\":${notes?.let(::quoteJson) ?: "null"}," +
            "\"attemptedAt\":${quoteJson(attemptedAt)}," +
            "\"lines\":$lineBody" +
            "}"
    }

    private fun frozenProofCreateBody(receiverName: String, capturedAt: String, notes: String?): String =
        "{" +
            "\"receiverName\":${quoteJson(receiverName)}," +
            "\"capturedAt\":${quoteJson(capturedAt)}," +
            "\"notes\":${notes?.let(::quoteJson) ?: "null"}" +
            "}"

    private fun frozenProofAttachBody(kind: DriverProofEvidenceKind, evidenceId: String): String =
        "{" +
            "\"kind\":${quoteJson(kind.name)}," +
            "\"evidenceObjectId\":${quoteJson(evidenceId)}" +
            "}"

    private fun quoteJson(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    /** Refreshes list and selected detail to reconcile an uncertain network result. */
    fun refresh() {
        val currentAuthority = authority ?: return
        if (!currentAuthority.canRead) return
        val requestGeneration = generation
        val selectedId = mutableState.value.selectedDelivery?.id
            ?: pendingArrival?.deliveryId
        if (mutableState.value.commandStatus !in setOf(
                DriverDeliveryCommandStatus.UnknownOutcome,
                DriverDeliveryCommandStatus.Pending,
                DriverDeliveryCommandStatus.CheckingCurrent
            )
        ) {
            mutableState.update { it.copy(listStatus = DriverDeliveryLoadStatus.Loading) }
        }
        viewModelScope.launch {
            val result = safeLoad { gateway.assignedDeliveries(currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (result is DriverDeliveryLoadResult.ListLoaded) {
                mutableState.update {
                    it.copy(deliveries = result.items, listStatus = DriverDeliveryLoadStatus.Ready)
                }
                if (selectedId != null && result.items.any { it.id == selectedId }) {
                    val detail = safeLoad { gateway.delivery(selectedId, currentAuthority) }
                    if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                    val current = (detail as? DriverDeliveryLoadResult.DetailLoaded)?.item
                    if (current != null) {
                        replaceDelivery(current)
                        if (current.activeAttempt != null && pendingStart == null) {
                            mutableState.update {
                                it.copy(commandStatus = DriverDeliveryCommandStatus.Started)
                            }
                        }
                    } else {
                        mutableState.update { it.copy(detailStatus = detail.toLoadStatus()) }
                    }
                }
            } else {
                mutableState.update { it.copy(listStatus = result.toLoadStatus()) }
            }
        }
    }

    private suspend fun runStart(
        command: DriverAttemptStartCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val result = try {
            gateway.startAttempt(command, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverAttemptStartResult.UnknownOutcome
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        when (result) {
            is DriverAttemptStartResult.Started -> {
                val cleared = clearIntent(command, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                replaceDelivery(result.delivery.copy(activeAttempt = result.attempt))
                mutableState.update {
                    it.copy(
                        commandStatus = if (cleared) {
                            pendingStart = null
                            pendingStartPersisted = false
                            DriverDeliveryCommandStatus.Started
                        } else {
                            DriverDeliveryCommandStatus.PersistenceUnavailable
                        },
                        hasRecoverableStart = !cleared,
                        rejectionCode = null
                    )
                }
            }

            is DriverAttemptStartResult.Rejected -> {
                val cleared = clearIntent(command, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                mutableState.update {
                    it.copy(
                        commandStatus = if (cleared) {
                            pendingStart = null
                            pendingStartPersisted = false
                            DriverDeliveryCommandStatus.Rejected
                        } else {
                            DriverDeliveryCommandStatus.PersistenceUnavailable
                        },
                        hasRecoverableStart = !cleared,
                        rejectionCode = result.code
                    )
                }
            }

            DriverAttemptStartResult.StaleVersion -> {
                val cleared = clearIntent(command, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                mutableState.update {
                    it.copy(
                        commandStatus = if (cleared) {
                            pendingStart = null
                            pendingStartPersisted = false
                            DriverDeliveryCommandStatus.StaleVersion
                        } else {
                            DriverDeliveryCommandStatus.PersistenceUnavailable
                        },
                        hasRecoverableStart = !cleared
                    )
                }
            }

            DriverAttemptStartResult.UnknownOutcome,
            DriverAttemptStartResult.NetworkUnavailable,
            DriverAttemptStartResult.ServiceUnavailable -> {
                safeMetadataWrite(
                    DriverAttemptIntentMetadata(
                        currentAuthority.scopeIdentity,
                        command.idempotencyKey,
                        command.deliveryId,
                        command.expectedVersion,
                        DriverAttemptMetadataStatus.UnknownOutcome
                    )
                )
                if (!isCurrent(requestGeneration, currentAuthority)) return
                mutableState.update {
                    it.copy(
                        commandStatus = DriverDeliveryCommandStatus.UnknownOutcome,
                        hasRecoverableStart = true
                    )
                }
            }

            DriverAttemptStartResult.NotFound -> {
                val cleared = clearIntent(command, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                mutableState.update {
                    it.copy(
                        detailStatus = DriverDeliveryLoadStatus.NotFound,
                        commandStatus = if (cleared) {
                            pendingStart = null
                            pendingStartPersisted = false
                            DriverDeliveryCommandStatus.Rejected
                        } else {
                            DriverDeliveryCommandStatus.PersistenceUnavailable
                        },
                        hasRecoverableStart = !cleared
                    )
                }
            }

            DriverAttemptStartResult.PermissionDenied -> updateStartFailure(
                DriverDeliveryLoadStatus.PermissionDenied,
                command,
                requestGeneration,
                currentAuthority
            )

            DriverAttemptStartResult.ContextInvalidated -> updateStartFailure(
                DriverDeliveryLoadStatus.ContextInvalidated,
                command,
                requestGeneration,
                currentAuthority
            )

            DriverAttemptStartResult.SessionInvalidated -> updateStartFailure(
                DriverDeliveryLoadStatus.SessionInvalidated,
                command,
                requestGeneration,
                currentAuthority
            )
        }
    }

    private suspend fun updateStartFailure(
        status: DriverDeliveryLoadStatus,
        command: DriverAttemptStartCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val cleared = clearIntent(command, currentAuthority)
        if (!isCurrent(requestGeneration, currentAuthority)) return
        mutableState.update {
            if (cleared) {
                pendingStart = null
                pendingStartPersisted = false
            }
            it.copy(
                detailStatus = status,
                commandStatus = if (cleared) {
                    DriverDeliveryCommandStatus.Rejected
                } else {
                    DriverDeliveryCommandStatus.PersistenceUnavailable
                },
                hasRecoverableStart = !cleared
            )
        }
    }

    private suspend fun persistBeforeStart(
        command: DriverAttemptStartCommand,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val saved = safeMetadataWrite(
            DriverAttemptIntentMetadata(
                currentAuthority.scopeIdentity,
                command.idempotencyKey,
                command.deliveryId,
                command.expectedVersion,
                DriverAttemptMetadataStatus.Pending
            )
        )
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (saved != DriverAttemptMetadataWrite.Saved) {
            mutableState.update {
                it.copy(
                    commandStatus = DriverDeliveryCommandStatus.PersistenceUnavailable,
                    hasRecoverableStart = true
                )
            }
            return
        }
        pendingStartPersisted = true
        mutableState.update {
            it.copy(
                commandStatus = DriverDeliveryCommandStatus.Pending,
                hasRecoverableStart = true
            )
        }
        runStart(command, requestGeneration, currentAuthority)
    }

    private suspend fun clearIntent(
        command: DriverAttemptStartCommand,
        currentAuthority: DriverDeliveryAuthority
    ): Boolean = safeMetadataClear(currentAuthority.scopeIdentity, command.idempotencyKey) ==
        DriverAttemptMetadataWrite.Saved

    private fun applyDetailResult(result: DriverDeliveryLoadResult) {
        if (result is DriverDeliveryLoadResult.DetailLoaded) {
            replaceDelivery(result.item)
            mutableState.update { it.copy(detailStatus = DriverDeliveryLoadStatus.Ready) }
        } else {
            mutableState.update { it.copy(detailStatus = result.toLoadStatus()) }
        }
    }

    private fun replaceDelivery(delivery: DriverDeliverySnapshot) {
        mutableState.update { current ->
            current.copy(
                selectedDelivery = delivery,
                deliveries = current.deliveries.map { item ->
                    if (item.id ==
                        delivery.id
                    ) {
                        delivery
                    } else {
                        item
                    }
                },
                detailStatus = DriverDeliveryLoadStatus.Ready
            )
        }
    }

    private fun isCurrent(
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ): Boolean = generation == requestGeneration && authority == currentAuthority

    private suspend fun safeLoad(
        block: suspend () -> DriverDeliveryLoadResult
    ): DriverDeliveryLoadResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryLoadResult.ServiceUnavailable
    }

    private fun DriverDeliveryLoadResult.toLoadStatus(): DriverDeliveryLoadStatus = when (this) {
        is DriverDeliveryLoadResult.ListLoaded,
        is DriverDeliveryLoadResult.DetailLoaded -> DriverDeliveryLoadStatus.Ready

        DriverDeliveryLoadResult.NotFound -> DriverDeliveryLoadStatus.NotFound

        DriverDeliveryLoadResult.NetworkUnavailable -> DriverDeliveryLoadStatus.NetworkUnavailable

        DriverDeliveryLoadResult.ServiceUnavailable -> DriverDeliveryLoadStatus.ServiceUnavailable

        DriverDeliveryLoadResult.PermissionDenied -> DriverDeliveryLoadStatus.PermissionDenied

        DriverDeliveryLoadResult.ContextInvalidated -> DriverDeliveryLoadStatus.ContextInvalidated

        DriverDeliveryLoadResult.SessionInvalidated -> DriverDeliveryLoadStatus.SessionInvalidated
    }

    private fun DriverDeliveryLoadResult.toOutcomeFailureCode(): String = when (this) {
        DriverDeliveryLoadResult.NotFound -> "DELIVERY_NOT_FOUND"
        DriverDeliveryLoadResult.PermissionDenied -> "PERMISSION_DENIED"
        DriverDeliveryLoadResult.ContextInvalidated -> "ACCESS_CONTEXT_INVALID"
        DriverDeliveryLoadResult.SessionInvalidated -> "SESSION_INVALIDATED"
        DriverDeliveryLoadResult.NetworkUnavailable -> "NETWORK_UNAVAILABLE"
        DriverDeliveryLoadResult.ServiceUnavailable -> "SERVICE_UNAVAILABLE"
        is DriverDeliveryLoadResult.DetailLoaded,
        is DriverDeliveryLoadResult.ListLoaded -> "DELIVERY_OUTCOME_REJECTED"
    }

    private companion object {
        val FROZEN_COMMANDS = setOf(
            DriverDeliveryCommandStatus.CheckingCurrent,
            DriverDeliveryCommandStatus.PersistingIntent,
            DriverDeliveryCommandStatus.Pending,
            DriverDeliveryCommandStatus.UnknownOutcome,
            DriverDeliveryCommandStatus.PersistenceUnavailable
        )
        val FROZEN_OUTCOME_COMMANDS = setOf(
            DriverOutcomeCommandStatus.CheckingCurrent,
            DriverOutcomeCommandStatus.PersistingIntent,
            DriverOutcomeCommandStatus.Pending,
            DriverOutcomeCommandStatus.UnknownOutcome,
            DriverOutcomeCommandStatus.PersistenceUnavailable
        )
        val FROZEN_ARRIVAL_COMMANDS = setOf(
            DriverArrivalCommandStatus.CheckingCurrent,
            DriverArrivalCommandStatus.PersistingIntent,
            DriverArrivalCommandStatus.Pending,
            DriverArrivalCommandStatus.UnknownOutcome,
            DriverArrivalCommandStatus.PersistenceUnavailable
        )
        val FROZEN_PROOF_COMMANDS = setOf(
            DriverProofCommandStatus.CheckingCurrent,
            DriverProofCommandStatus.PersistingIntent,
            DriverProofCommandStatus.Pending,
            DriverProofCommandStatus.UnknownOutcome,
            DriverProofCommandStatus.PersistenceUnavailable
        )
    }

    private suspend fun safeMetadataLoad(
        scope: DriverAttemptScopeIdentity
    ): DriverAttemptMetadataRead = try {
        metadataStore.loadIntent(scope)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverAttemptMetadataRead.Unavailable
    }

    private suspend fun safeMetadataWrite(
        intent: DriverAttemptIntentMetadata
    ): DriverAttemptMetadataWrite = try {
        metadataStore.saveIntent(intent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverAttemptMetadataWrite.Unavailable
    }

    private suspend fun safeMetadataClear(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverAttemptMetadataWrite = try {
        metadataStore.clearIntent(scope, idempotencyKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverAttemptMetadataWrite.Unavailable
    }

    private suspend fun safeOutcomeMetadataLoad(
        scope: DriverAttemptScopeIdentity
    ): DriverOutcomeMetadataRead = try {
        outcomeMetadataStore?.loadIntent(scope) ?: DriverOutcomeMetadataRead.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverOutcomeMetadataRead.Unavailable
    }

    private suspend fun safeOutcomeMetadataWrite(
        intent: DriverOutcomeIntentMetadata
    ): DriverOutcomeMetadataWrite = try {
        outcomeMetadataStore?.saveIntent(intent) ?: DriverOutcomeMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverOutcomeMetadataWrite.Unavailable
    }

    private suspend fun safeOutcomeMetadataClear(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverOutcomeMetadataWrite = try {
        outcomeMetadataStore?.clearIntent(scope, idempotencyKey) ?: DriverOutcomeMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverOutcomeMetadataWrite.Unavailable
    }

    private fun DriverDeliveryLoadResult.toArrivalFailureCode(): String = when (this) {
        DriverDeliveryLoadResult.NotFound -> "DELIVERY_NOT_FOUND"
        DriverDeliveryLoadResult.PermissionDenied -> "PERMISSION_DENIED"
        DriverDeliveryLoadResult.ContextInvalidated -> "ACCESS_CONTEXT_INVALID"
        DriverDeliveryLoadResult.SessionInvalidated -> "SESSION_INVALIDATED"
        DriverDeliveryLoadResult.NetworkUnavailable -> "NETWORK_UNAVAILABLE"
        DriverDeliveryLoadResult.ServiceUnavailable -> "SERVICE_UNAVAILABLE"
        is DriverDeliveryLoadResult.DetailLoaded,
        is DriverDeliveryLoadResult.ListLoaded -> "DELIVERY_ARRIVAL_REJECTED"
    }

    private suspend fun safeArrivalMetadataLoad(
        scope: DriverAttemptScopeIdentity
    ): DriverArrivalMetadataRead = try {
        arrivalMetadataStore?.loadIntent(scope) ?: DriverArrivalMetadataRead.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverArrivalMetadataRead.Unavailable
    }

    private suspend fun safeArrivalMetadataWrite(
        intent: DriverArrivalIntentMetadata
    ): DriverArrivalMetadataWrite = try {
        arrivalMetadataStore?.saveIntent(intent) ?: DriverArrivalMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverArrivalMetadataWrite.Unavailable
    }

    private suspend fun safeArrivalMetadataClear(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverArrivalMetadataWrite = try {
        arrivalMetadataStore?.clearIntent(scope, idempotencyKey) ?: DriverArrivalMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverArrivalMetadataWrite.Unavailable
    }

    private suspend fun safeProofMetadataLoad(
        scope: DriverAttemptScopeIdentity
    ): DriverProofMetadataRead = try {
        proofMetadataStore?.loadIntent(scope) ?: DriverProofMetadataRead.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverProofMetadataRead.Unavailable
    }

    private suspend fun safeProofMetadataWrite(
        intent: DriverProofIntentMetadata
    ): DriverProofMetadataWrite = try {
        proofMetadataStore?.saveIntent(intent) ?: DriverProofMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverProofMetadataWrite.Unavailable
    }

    private suspend fun safeProofMetadataStage(
        intent: DriverProofIntentMetadata,
        candidate: DriverProofFileCandidate
    ): DriverProofMetadataWrite = try {
        proofMetadataStore?.stageCandidate(intent, candidate) ?: DriverProofMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverProofMetadataWrite.Unavailable
    }

    private suspend fun safeProofCandidateLoad(
        intent: DriverProofIntentMetadata
    ): DriverProofFileCandidate? = try {
        proofMetadataStore?.loadCandidate(intent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun safeProofCandidateClear(intent: DriverProofIntentMetadata): Boolean = try {
        proofMetadataStore?.clearCandidate(intent) ?: false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun safeProofMetadataClear(
        scope: DriverAttemptScopeIdentity,
        createIdempotencyKey: String
    ): DriverProofMetadataWrite = try {
        proofMetadataStore?.clearIntent(scope, createIdempotencyKey) ?: DriverProofMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverProofMetadataWrite.Unavailable
    }
}
