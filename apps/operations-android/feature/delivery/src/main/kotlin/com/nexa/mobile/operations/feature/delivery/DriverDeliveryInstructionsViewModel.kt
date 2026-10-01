package com.nexa.mobile.operations.feature.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DriverDeliveryInstructionsLoadStatus {
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

enum class DriverDeliveryInstructionAcknowledgementStatus {
    Idle,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    PersistenceUnavailable,
    Acknowledged,
    Rejected,
    StaleVersion
}

data class DriverDeliveryInstructionsUiState(
    val authorityEpoch: Long = 0,
    val canRead: Boolean = false,
    val canAcknowledge: Boolean = false,
    val deliveryId: String? = null,
    val snapshot: DriverDeliveryInstructionsSnapshot? = null,
    val loadStatus: DriverDeliveryInstructionsLoadStatus = DriverDeliveryInstructionsLoadStatus.NotRequested,
    val selectedInstructionIds: Set<String> = emptySet(),
    val acknowledgementStatus: DriverDeliveryInstructionAcknowledgementStatus =
        DriverDeliveryInstructionAcknowledgementStatus.Idle,
    val hasRecoverableAcknowledgement: Boolean = false,
    val unresolvedAcknowledgementForOtherDelivery: Boolean = false,
    val acknowledgementReplayed: Boolean = false,
    val rejectionCode: String? = null
) {
    val canAcknowledgeSelected: Boolean
        get() = canRead && canAcknowledge && loadStatus == DriverDeliveryInstructionsLoadStatus.Ready &&
            selectedInstructionIds.isNotEmpty() && !hasRecoverableAcknowledgement &&
            !unresolvedAcknowledgementForOtherDelivery &&
            acknowledgementStatus !in setOf(
                DriverDeliveryInstructionAcknowledgementStatus.PersistingIntent,
                DriverDeliveryInstructionAcknowledgementStatus.Pending,
                DriverDeliveryInstructionAcknowledgementStatus.UnknownOutcome,
                DriverDeliveryInstructionAcknowledgementStatus.PersistenceUnavailable
            )

    override fun toString(): String =
        "DriverDeliveryInstructionsUiState(epoch=$authorityEpoch, load=$loadStatus, ack=$acknowledgementStatus, instructions=${snapshot?.instructions?.size ?: 0})"
}

/** Current-assignment instructions and explicit, versioned critical acknowledgements. */
class DriverDeliveryInstructionsViewModel(
    private val gateway: DriverDeliveryInstructionsGateway,
    private val metadataStore: DriverDeliveryInstructionMetadataStore,
    private val timeFactory: () -> String = { Instant.now().toString() },
    private val keyFactory: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DriverDeliveryInstructionsUiState())
    val state = mutableState.asStateFlow()

    private var authority: DriverDeliveryAuthority? = null
    private var generation = 0L
    private var pendingIntent: DriverDeliveryInstructionIntentMetadata? = null
    private var metadataAvailable = true
    private var staleIntentAwaitingRefresh: DriverDeliveryInstructionIntentMetadata? = null

    fun activate(currentAuthority: DriverDeliveryAuthority, deliveryId: String) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        pendingIntent = null
        staleIntentAwaitingRefresh = null
        metadataAvailable = true
        mutableState.value = DriverDeliveryInstructionsUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canRead = currentAuthority.canRead,
            canAcknowledge = currentAuthority.canStart,
            deliveryId = deliveryId,
            loadStatus = if (currentAuthority.canRead) {
                DriverDeliveryInstructionsLoadStatus.Loading
            } else {
                DriverDeliveryInstructionsLoadStatus.PermissionDenied
            }
        )
        if (!currentAuthority.canRead) return

        viewModelScope.launch {
            when (val loaded = safeLoadIntent(currentAuthority.scopeIdentity)) {
                DriverDeliveryInstructionMetadataRead.Unavailable -> {
                    metadataAvailable = false
                    mutableState.update {
                        it.copy(acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.PersistenceUnavailable)
                    }
                }

                is DriverDeliveryInstructionMetadataRead.Available -> {
                    pendingIntent = loaded.intent
                    val intent = loaded.intent
                    if (intent != null) {
                        when (intent.status) {
                            DriverDeliveryInstructionIntentStatus.Pending,
                            DriverDeliveryInstructionIntentStatus.UnknownOutcome -> {
                                pendingIntent = intent.copy(status = DriverDeliveryInstructionIntentStatus.UnknownOutcome)
                                mutableState.update {
                                    it.copy(
                                        acknowledgementStatus = if (intent.command.deliveryId == deliveryId) {
                                            DriverDeliveryInstructionAcknowledgementStatus.UnknownOutcome
                                        } else {
                                            DriverDeliveryInstructionAcknowledgementStatus.PersistenceUnavailable
                                        },
                                        hasRecoverableAcknowledgement = intent.command.deliveryId == deliveryId,
                                        unresolvedAcknowledgementForOtherDelivery = intent.command.deliveryId != deliveryId
                                    )
                                }
                            }

                            DriverDeliveryInstructionIntentStatus.StaleVersion -> {
                                staleIntentAwaitingRefresh = intent
                                mutableState.update {
                                    it.copy(
                                        acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.StaleVersion,
                                        hasRecoverableAcknowledgement = false,
                                        unresolvedAcknowledgementForOtherDelivery = intent.command.deliveryId != deliveryId
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            loadCurrent(requestGeneration, currentAuthority)
        }
    }

    fun invalidate() {
        generation++
        authority = null
        pendingIntent = null
        staleIntentAwaitingRefresh = null
        metadataAvailable = true
        mutableState.value = DriverDeliveryInstructionsUiState()
    }

    fun refresh() {
        val currentAuthority = authority ?: return
        val requestGeneration = generation
        if (!currentAuthority.canRead || !isCurrent(requestGeneration, currentAuthority)) return
        mutableState.update {
            it.copy(
                loadStatus = DriverDeliveryInstructionsLoadStatus.Loading,
                snapshot = null,
                selectedInstructionIds = emptySet(),
                rejectionCode = null
            )
        }
        viewModelScope.launch { loadCurrent(requestGeneration, currentAuthority) }
    }

    fun setInstructionSelected(instructionId: String, selected: Boolean) {
        val current = mutableState.value
        val row = current.snapshot?.instructions?.firstOrNull { it.id.equals(instructionId, ignoreCase = true) }
            ?: return
        if (!row.critical || row.acknowledged || !current.canAcknowledge ||
            current.loadStatus != DriverDeliveryInstructionsLoadStatus.Ready ||
            current.hasRecoverableAcknowledgement || current.unresolvedAcknowledgementForOtherDelivery ||
            staleIntentAwaitingRefresh != null
        ) return
        mutableState.update { state ->
            val ids = state.selectedInstructionIds.toMutableSet()
            if (selected) ids.add(row.id) else ids.remove(row.id)
            state.copy(selectedInstructionIds = ids)
        }
    }

    fun acknowledgeSelected() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val snapshot = current.snapshot ?: return
        if (!current.canAcknowledgeSelected || !metadataAvailable || staleIntentAwaitingRefresh != null ||
            !isCurrent(generation, currentAuthority)
        ) return
        val chosen = snapshot.instructions.filter {
            it.critical && !it.acknowledged &&
                current.selectedInstructionIds.any { id -> id.equals(it.id, ignoreCase = true) }
        }
        if (chosen.isEmpty()) return
        val versions = chosen.associate { it.id to it.instructionVersion }
        val command = DriverDeliveryInstructionAcknowledgementCommand(
            deliveryId = snapshot.deliveryId,
            instructionSetVersion = snapshot.instructionSetVersion,
            instructionVersions = versions,
            idempotencyKey = keyFactory(),
            frozenBody = driverDeliveryInstructionAcknowledgementBody(versions.keys)
        )
        val intent = DriverDeliveryInstructionIntentMetadata(
            scope = currentAuthority.scopeIdentity,
            command = command,
            initiatedByMembershipId = currentAuthority.membershipId,
            initiatedAt = timeFactory(),
            status = DriverDeliveryInstructionIntentStatus.Pending
        )
        mutableState.update {
            it.copy(
                acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.PersistingIntent,
                hasRecoverableAcknowledgement = false,
                acknowledgementReplayed = false,
                rejectionCode = null
            )
        }
        val requestGeneration = generation
        viewModelScope.launch {
            val saved = safeSaveIntent(intent)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != DriverDeliveryInstructionMetadataWrite.Saved) {
                metadataAvailable = saved != DriverDeliveryInstructionMetadataWrite.Unavailable
                mutableState.update {
                    it.copy(
                        acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.PersistenceUnavailable,
                        hasRecoverableAcknowledgement = false
                    )
                }
                return@launch
            }
            pendingIntent = intent
            mutableState.update {
                it.copy(acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.Pending)
            }
            submit(command, intent, requestGeneration, currentAuthority)
        }
    }

    /** Replays only a persisted unknown command, after an explicit user action. */
    fun retryUnknownAcknowledgement() {
        val currentAuthority = authority ?: return
        val intent = pendingIntent ?: return
        val current = mutableState.value
        if (!current.canAcknowledge || !metadataAvailable || !current.hasRecoverableAcknowledgement ||
            intent.command.deliveryId != current.deliveryId ||
            current.acknowledgementStatus !in setOf(
                DriverDeliveryInstructionAcknowledgementStatus.UnknownOutcome,
                DriverDeliveryInstructionAcknowledgementStatus.Acknowledged
            ) ||
            !isCurrent(generation, currentAuthority)
        ) return
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.Pending,
                acknowledgementReplayed = false,
                rejectionCode = null
            )
        }
        viewModelScope.launch { submit(intent.command, intent, requestGeneration, currentAuthority) }
    }

    private suspend fun loadCurrent(
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        when (val result = safeLoad(currentAuthority, mutableState.value.deliveryId.orEmpty())) {
            is DriverDeliveryInstructionsLoadResult.Loaded -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                val staleIntent = staleIntentAwaitingRefresh
                if (staleIntent != null) {
                    when (safeClearIntent(staleIntent.scope, staleIntent.command.idempotencyKey)) {
                        DriverDeliveryInstructionMetadataWrite.Saved -> {
                            if (pendingIntent == staleIntent) pendingIntent = null
                            staleIntentAwaitingRefresh = null
                            metadataAvailable = true
                            mutableState.update {
                                it.copy(
                                    snapshot = result.snapshot,
                                    loadStatus = DriverDeliveryInstructionsLoadStatus.Ready,
                                    selectedInstructionIds = emptySet(),
                                    acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.StaleVersion,
                                    hasRecoverableAcknowledgement = false,
                                    unresolvedAcknowledgementForOtherDelivery = false,
                                    rejectionCode = null
                                )
                            }
                        }

                        else -> {
                            mutableState.update {
                                it.copy(
                                    snapshot = result.snapshot,
                                    loadStatus = DriverDeliveryInstructionsLoadStatus.Ready,
                                    selectedInstructionIds = emptySet(),
                                    acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.PersistenceUnavailable,
                                    hasRecoverableAcknowledgement = false,
                                    rejectionCode = null
                                )
                            }
                        }
                    }
                    return
                }
                mutableState.update {
                    it.copy(
                        snapshot = result.snapshot,
                        loadStatus = DriverDeliveryInstructionsLoadStatus.Ready,
                        selectedInstructionIds = emptySet(),
                        rejectionCode = null,
                        acknowledgementStatus = if (!metadataAvailable) {
                            DriverDeliveryInstructionAcknowledgementStatus.PersistenceUnavailable
                        } else if (pendingIntent == null) {
                            if (it.acknowledgementStatus == DriverDeliveryInstructionAcknowledgementStatus.StaleVersion) {
                                DriverDeliveryInstructionAcknowledgementStatus.StaleVersion
                            } else {
                                DriverDeliveryInstructionAcknowledgementStatus.Idle
                            }
                        } else {
                            it.acknowledgementStatus
                        },
                        hasRecoverableAcknowledgement = pendingIntent?.command?.deliveryId == result.snapshot.deliveryId,
                        unresolvedAcknowledgementForOtherDelivery =
                            pendingIntent != null && pendingIntent?.command?.deliveryId != result.snapshot.deliveryId
                    )
                }
            }

            else -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                mutableState.update {
                    it.copy(
                        snapshot = null,
                        loadStatus = result.toLoadStatus(),
                        selectedInstructionIds = emptySet(),
                        hasRecoverableAcknowledgement = pendingIntent?.command?.deliveryId == it.deliveryId,
                        unresolvedAcknowledgementForOtherDelivery =
                            pendingIntent != null && pendingIntent?.command?.deliveryId != it.deliveryId
                    )
                }
            }
        }
    }

    private suspend fun submit(
        command: DriverDeliveryInstructionAcknowledgementCommand,
        intent: DriverDeliveryInstructionIntentMetadata,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        if (!isCurrent(requestGeneration, currentAuthority)) return
        when (val result = safeAcknowledge(command, currentAuthority)) {
            is DriverDeliveryInstructionAcknowledgementResult.Acknowledged -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                val summary = result.summary
                val currentSnapshot = mutableState.value.snapshot
                val valid = currentSnapshot != null && summary.deliveryId == command.deliveryId &&
                    summary.instructionSetVersion == command.instructionSetVersion &&
                    summary.acknowledgements.map { it.instructionId.lowercase() }.toSet() ==
                    command.instructionIds.map(String::lowercase).toSet() &&
                    summary.acknowledgements.all { fact ->
                        fact.acknowledgedByMembershipId == currentAuthority.membershipId &&
                            command.instructionVersions.entries.any { (id, version) ->
                                id.equals(fact.instructionId, ignoreCase = true) && version == fact.instructionVersion
                            }
                    }
                if (!valid) {
                    markUnknown(intent, requestGeneration, currentAuthority)
                    return
                }
                val facts = summary.acknowledgements.associateBy { it.instructionId.lowercase() }
                val acknowledgedSnapshot = currentSnapshot.copy(
                    instructions = currentSnapshot.instructions.map { row ->
                        val fact = facts[row.id.lowercase()]
                        if (fact == null) row else row.copy(
                            acknowledged = true,
                            acknowledgedAt = fact.acknowledgedAt,
                            acknowledgedByMembershipId = fact.acknowledgedByMembershipId
                        )
                    }
                )
                val cleared = safeClearIntent(intent.scope, command.idempotencyKey)
                if (cleared == DriverDeliveryInstructionMetadataWrite.Saved) pendingIntent = null
                mutableState.update {
                    it.copy(
                        snapshot = acknowledgedSnapshot,
                        selectedInstructionIds = emptySet(),
                        acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.Acknowledged,
                        hasRecoverableAcknowledgement = cleared != DriverDeliveryInstructionMetadataWrite.Saved,
                        acknowledgementReplayed = summary.replayed,
                        rejectionCode = null
                    )
                }
            }

            DriverDeliveryInstructionAcknowledgementResult.StaleVersion -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                val staleIntent = intent.copy(status = DriverDeliveryInstructionIntentStatus.StaleVersion)
                val staleSaved = safeSaveIntent(staleIntent)
                staleIntentAwaitingRefresh = staleIntent
                pendingIntent = staleIntent
                if (staleSaved != DriverDeliveryInstructionMetadataWrite.Saved) {
                    if (safeClearIntent(intent.scope, command.idempotencyKey) == DriverDeliveryInstructionMetadataWrite.Saved) {
                        pendingIntent = null
                    }
                }
                mutableState.update {
                    it.copy(
                        snapshot = null,
                        loadStatus = DriverDeliveryInstructionsLoadStatus.Loading,
                        selectedInstructionIds = emptySet(),
                        acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.StaleVersion,
                        hasRecoverableAcknowledgement = false,
                        rejectionCode = null
                    )
                }
                loadCurrent(requestGeneration, currentAuthority)
            }

            DriverDeliveryInstructionAcknowledgementResult.UnknownOutcome,
            DriverDeliveryInstructionAcknowledgementResult.NetworkUnavailable,
            DriverDeliveryInstructionAcknowledgementResult.ServiceUnavailable ->
                markUnknown(intent, requestGeneration, currentAuthority)

            else -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (intent.status == DriverDeliveryInstructionIntentStatus.UnknownOutcome) {
                    markUnknown(intent, requestGeneration, currentAuthority)
                    return
                }
                val cleared = safeClearIntent(intent.scope, command.idempotencyKey)
                if (cleared == DriverDeliveryInstructionMetadataWrite.Saved) pendingIntent = null
                val code = (result as? DriverDeliveryInstructionAcknowledgementResult.Rejected)?.code
                mutableState.update {
                    it.copy(
                        acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.Rejected,
                        hasRecoverableAcknowledgement = cleared != DriverDeliveryInstructionMetadataWrite.Saved,
                        rejectionCode = code,
                        selectedInstructionIds = emptySet()
                    )
                }
            }
        }
    }

    private suspend fun markUnknown(
        intent: DriverDeliveryInstructionIntentMetadata,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val unknown = intent.copy(status = DriverDeliveryInstructionIntentStatus.UnknownOutcome)
        safeSaveIntent(unknown)
        pendingIntent = unknown
        mutableState.update {
            it.copy(
                acknowledgementStatus = DriverDeliveryInstructionAcknowledgementStatus.UnknownOutcome,
                hasRecoverableAcknowledgement = true,
                selectedInstructionIds = emptySet()
            )
        }
    }

    private suspend fun safeLoadIntent(scope: DriverAttemptScopeIdentity): DriverDeliveryInstructionMetadataRead =
        try {
            metadataStore.loadIntent(scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverDeliveryInstructionMetadataRead.Unavailable
        }

    private suspend fun safeSaveIntent(intent: DriverDeliveryInstructionIntentMetadata): DriverDeliveryInstructionMetadataWrite =
        try {
            metadataStore.saveIntent(intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverDeliveryInstructionMetadataWrite.Unavailable
        }

    private suspend fun safeClearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverDeliveryInstructionMetadataWrite = try {
        metadataStore.clearIntent(scope, idempotencyKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryInstructionMetadataWrite.Unavailable
    }

    private suspend fun safeLoad(
        currentAuthority: DriverDeliveryAuthority,
        deliveryId: String
    ): DriverDeliveryInstructionsLoadResult = try {
        gateway.currentInstructions(deliveryId, currentAuthority)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryInstructionsLoadResult.ServiceUnavailable
    }

    private suspend fun safeAcknowledge(
        command: DriverDeliveryInstructionAcknowledgementCommand,
        currentAuthority: DriverDeliveryAuthority
    ): DriverDeliveryInstructionAcknowledgementResult = try {
        gateway.acknowledgeCriticalInstructions(command, currentAuthority)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryInstructionAcknowledgementResult.UnknownOutcome
    }

    private fun DriverDeliveryInstructionsLoadResult.toLoadStatus() = when (this) {
        DriverDeliveryInstructionsLoadResult.NotFound -> DriverDeliveryInstructionsLoadStatus.NotFound
        DriverDeliveryInstructionsLoadResult.NetworkUnavailable -> DriverDeliveryInstructionsLoadStatus.NetworkUnavailable
        DriverDeliveryInstructionsLoadResult.ServiceUnavailable -> DriverDeliveryInstructionsLoadStatus.ServiceUnavailable
        DriverDeliveryInstructionsLoadResult.PermissionDenied -> DriverDeliveryInstructionsLoadStatus.PermissionDenied
        DriverDeliveryInstructionsLoadResult.ContextInvalidated -> DriverDeliveryInstructionsLoadStatus.ContextInvalidated
        DriverDeliveryInstructionsLoadResult.SessionInvalidated -> DriverDeliveryInstructionsLoadStatus.SessionInvalidated
        is DriverDeliveryInstructionsLoadResult.Loaded -> DriverDeliveryInstructionsLoadStatus.Ready
    }

    private fun isCurrent(requestGeneration: Long, expectedAuthority: DriverDeliveryAuthority): Boolean =
        generation == requestGeneration && authority == expectedAuthority &&
            mutableState.value.authorityEpoch == expectedAuthority.authorityEpoch
}
