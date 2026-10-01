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

data class DriverDeliveryUiState(
    val authorityEpoch: Long = 0,
    val canRead: Boolean = false,
    val canStart: Boolean = false,
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
    val arrivalRejectionCode: String? = null
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

    override fun toString(): String =
        "DriverDeliveryUiState(epoch=$authorityEpoch, list=$listStatus, detail=$detailStatus, command=$commandStatus, items=${deliveries.size})"
}

/** Connected list/detail/start flow; a timeout remains unconfirmed until replay or refresh. */
class DriverDeliveryViewModel(
    private val gateway: DriverDeliveryGateway,
    private val metadataStore: DriverAttemptMetadataStore,
    private val outcomeMetadataStore: DriverOutcomeMetadataStore? = null,
    private val arrivalMetadataStore: DriverArrivalMetadataStore? = null,
    private val timeFactory: () -> String = { Instant.now().toString() },
    private val keyFactory: () -> String = { UUID.randomUUID().toString() }
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
        mutableState.value = DriverDeliveryUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canRead = currentAuthority.canRead,
            canStart = currentAuthority.canStart,
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
}
