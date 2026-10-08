package com.nexa.mobile.operations.feature.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionAcknowledgementStatus as InstructionAcknowledgementStatus
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionsLoadStatus as InstructionsLoadStatus
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionsUiState as InstructionsUiState
import com.nexa.mobile.operations.feature.delivery.application.DriverDeliveryInstructionMetadataStore
import com.nexa.mobile.operations.feature.delivery.application.DriverDeliveryInstructionsGateway
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionAcknowledgementCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionAcknowledgementResult as InstructionAcknowledgementResult
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionIntentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionIntentStatus as InstructionIntentStatus
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionsLoadResult as InstructionsLoadResult
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionsSnapshot
import com.nexa.mobile.operations.feature.delivery.model.driverDeliveryInstructionAcknowledgementBody
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
    val loadStatus: InstructionsLoadStatus = InstructionsLoadStatus.NotRequested,
    val selectedInstructionIds: Set<String> = emptySet(),
    val acknowledgementStatus: InstructionAcknowledgementStatus =
        InstructionAcknowledgementStatus.Idle,
    val hasRecoverableAcknowledgement: Boolean = false,
    val unresolvedAcknowledgementForOtherDelivery: Boolean = false,
    val acknowledgementReplayed: Boolean = false,
    val rejectionCode: String? = null
) {
    val canAcknowledgeSelected: Boolean
        get() = canRead && canAcknowledge &&
            loadStatus == InstructionsLoadStatus.Ready &&
            selectedInstructionIds.isNotEmpty() && !hasRecoverableAcknowledgement &&
            !unresolvedAcknowledgementForOtherDelivery &&
            acknowledgementStatus !in setOf(
                InstructionAcknowledgementStatus.PersistingIntent,
                InstructionAcknowledgementStatus.Pending,
                InstructionAcknowledgementStatus.UnknownOutcome,
                InstructionAcknowledgementStatus.PersistenceUnavailable
            )

    override fun toString(): String =
        "InstructionsUiState(epoch=$authorityEpoch, load=$loadStatus, ack=$acknowledgementStatus, instructions=${snapshot?.instructions?.size ?: 0})"
}

/** Current-assignment instructions and explicit, versioned critical acknowledgements. */
class DriverDeliveryInstructionsViewModel(
    private val gateway: DriverDeliveryInstructionsGateway,
    private val metadataStore: DriverDeliveryInstructionMetadataStore,
    private val timeFactory: () -> String = { Instant.now().toString() },
    private val keyFactory: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(InstructionsUiState())
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
        mutableState.value = InstructionsUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canRead = currentAuthority.canRead,
            canAcknowledge = currentAuthority.canStart,
            deliveryId = deliveryId,
            loadStatus = if (currentAuthority.canRead) {
                InstructionsLoadStatus.Loading
            } else {
                InstructionsLoadStatus.PermissionDenied
            }
        )
        if (!currentAuthority.canRead) return

        viewModelScope.launch {
            when (val loaded = safeLoadIntent(currentAuthority.scopeIdentity)) {
                DriverDeliveryInstructionMetadataRead.Unavailable -> {
                    metadataAvailable = false
                    mutableState.update {
                        it.copy(
                            acknowledgementStatus =
                                InstructionAcknowledgementStatus.PersistenceUnavailable
                        )
                    }
                }

                is DriverDeliveryInstructionMetadataRead.Available -> {
                    pendingIntent = loaded.intent
                    val intent = loaded.intent
                    if (intent != null) {
                        when (intent.status) {
                            InstructionIntentStatus.Pending,
                            InstructionIntentStatus.UnknownOutcome -> {
                                pendingIntent =
                                    intent.copy(
                                        status = InstructionIntentStatus.UnknownOutcome
                                    )
                                mutableState.update {
                                    it.copy(
                                        acknowledgementStatus = if (intent.command.deliveryId ==
                                            deliveryId
                                        ) {
                                            InstructionAcknowledgementStatus.UnknownOutcome
                                        } else {
                                            InstructionAcknowledgementStatus.PersistenceUnavailable
                                        },
                                        hasRecoverableAcknowledgement =
                                            intent.command.deliveryId == deliveryId,
                                        unresolvedAcknowledgementForOtherDelivery =
                                            intent.command.deliveryId != deliveryId
                                    )
                                }
                            }

                            InstructionIntentStatus.StaleVersion -> {
                                staleIntentAwaitingRefresh = intent
                                mutableState.update {
                                    it.copy(
                                        acknowledgementStatus =
                                            InstructionAcknowledgementStatus.StaleVersion,
                                        hasRecoverableAcknowledgement = false,
                                        unresolvedAcknowledgementForOtherDelivery =
                                            intent.command.deliveryId != deliveryId
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
        mutableState.value = InstructionsUiState()
    }

    fun refresh() {
        val currentAuthority = authority ?: return
        val requestGeneration = generation
        if (!currentAuthority.canRead || !isCurrent(requestGeneration, currentAuthority)) return
        mutableState.update {
            it.copy(
                loadStatus = InstructionsLoadStatus.Loading,
                snapshot = null,
                selectedInstructionIds = emptySet(),
                rejectionCode = null
            )
        }
        viewModelScope.launch { loadCurrent(requestGeneration, currentAuthority) }
    }

    fun setInstructionSelected(instructionId: String, selected: Boolean) {
        val current = mutableState.value
        val row =
            current.snapshot?.instructions?.firstOrNull {
                it.id.equals(instructionId, ignoreCase = true)
            }
                ?: return
        if (!row.critical || row.acknowledged || !current.canAcknowledge ||
            current.loadStatus != InstructionsLoadStatus.Ready ||
            current.hasRecoverableAcknowledgement ||
            current.unresolvedAcknowledgementForOtherDelivery ||
            staleIntentAwaitingRefresh != null
        ) {
            return
        }
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
        if (!current.canAcknowledgeSelected || !metadataAvailable ||
            staleIntentAwaitingRefresh != null ||
            !isCurrent(generation, currentAuthority)
        ) {
            return
        }
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
            status = InstructionIntentStatus.Pending
        )
        mutableState.update {
            it.copy(
                acknowledgementStatus = InstructionAcknowledgementStatus.PersistingIntent,
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
                        acknowledgementStatus =
                            InstructionAcknowledgementStatus.PersistenceUnavailable,
                        hasRecoverableAcknowledgement = false
                    )
                }
                return@launch
            }
            pendingIntent = intent
            mutableState.update {
                it.copy(
                    acknowledgementStatus = InstructionAcknowledgementStatus.Pending
                )
            }
            submit(command, intent, requestGeneration, currentAuthority)
        }
    }

    /** Replays only a persisted unknown command, after an explicit user action. */
    fun retryUnknownAcknowledgement() {
        val currentAuthority = authority ?: return
        val intent = pendingIntent ?: return
        val current = mutableState.value
        if (!current.canAcknowledge ||
            !metadataAvailable || !current.hasRecoverableAcknowledgement ||
            intent.command.deliveryId != current.deliveryId ||
            current.acknowledgementStatus !in setOf(
                InstructionAcknowledgementStatus.UnknownOutcome,
                InstructionAcknowledgementStatus.Acknowledged
            ) ||
            !isCurrent(generation, currentAuthority)
        ) {
            return
        }
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                acknowledgementStatus = InstructionAcknowledgementStatus.Pending,
                acknowledgementReplayed = false,
                rejectionCode = null
            )
        }
        viewModelScope.launch {
            submit(intent.command, intent, requestGeneration, currentAuthority)
        }
    }

    private suspend fun loadCurrent(
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        when (val result = safeLoad(currentAuthority, mutableState.value.deliveryId.orEmpty())) {
            is InstructionsLoadResult.Loaded -> {
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
                                    loadStatus = InstructionsLoadStatus.Ready,
                                    selectedInstructionIds = emptySet(),
                                    acknowledgementStatus =
                                        InstructionAcknowledgementStatus.StaleVersion,
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
                                    loadStatus = InstructionsLoadStatus.Ready,
                                    selectedInstructionIds = emptySet(),
                                    acknowledgementStatus =
                                        InstructionAcknowledgementStatus.PersistenceUnavailable,
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
                        loadStatus = InstructionsLoadStatus.Ready,
                        selectedInstructionIds = emptySet(),
                        rejectionCode = null,
                        acknowledgementStatus = if (!metadataAvailable) {
                            InstructionAcknowledgementStatus.PersistenceUnavailable
                        } else if (pendingIntent == null) {
                            if (it.acknowledgementStatus ==
                                InstructionAcknowledgementStatus.StaleVersion
                            ) {
                                InstructionAcknowledgementStatus.StaleVersion
                            } else {
                                InstructionAcknowledgementStatus.Idle
                            }
                        } else {
                            it.acknowledgementStatus
                        },
                        hasRecoverableAcknowledgement =
                            pendingIntent?.command?.deliveryId == result.snapshot.deliveryId,
                        unresolvedAcknowledgementForOtherDelivery =
                            pendingIntent != null &&
                                pendingIntent?.command?.deliveryId != result.snapshot.deliveryId
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
                        hasRecoverableAcknowledgement =
                            pendingIntent?.command?.deliveryId == it.deliveryId,
                        unresolvedAcknowledgementForOtherDelivery =
                            pendingIntent != null &&
                                pendingIntent?.command?.deliveryId != it.deliveryId
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
            is InstructionAcknowledgementResult.Acknowledged -> {
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
                                id.equals(fact.instructionId, ignoreCase = true) &&
                                    version == fact.instructionVersion
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
                        if (fact == null) {
                            row
                        } else {
                            row.copy(
                                acknowledged = true,
                                acknowledgedAt = fact.acknowledgedAt,
                                acknowledgedByMembershipId = fact.acknowledgedByMembershipId
                            )
                        }
                    }
                )
                val cleared = safeClearIntent(intent.scope, command.idempotencyKey)
                if (cleared == DriverDeliveryInstructionMetadataWrite.Saved) pendingIntent = null
                mutableState.update {
                    it.copy(
                        snapshot = acknowledgedSnapshot,
                        selectedInstructionIds = emptySet(),
                        acknowledgementStatus = InstructionAcknowledgementStatus.Acknowledged,
                        hasRecoverableAcknowledgement =
                            cleared != DriverDeliveryInstructionMetadataWrite.Saved,
                        acknowledgementReplayed = summary.replayed,
                        rejectionCode = null
                    )
                }
            }

            InstructionAcknowledgementResult.StaleVersion -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                val staleIntent = intent.copy(
                    status = InstructionIntentStatus.StaleVersion
                )
                val staleSaved = safeSaveIntent(staleIntent)
                staleIntentAwaitingRefresh = staleIntent
                pendingIntent = staleIntent
                if (staleSaved != DriverDeliveryInstructionMetadataWrite.Saved) {
                    if (safeClearIntent(intent.scope, command.idempotencyKey) ==
                        DriverDeliveryInstructionMetadataWrite.Saved
                    ) {
                        pendingIntent = null
                    }
                }
                mutableState.update {
                    it.copy(
                        snapshot = null,
                        loadStatus = InstructionsLoadStatus.Loading,
                        selectedInstructionIds = emptySet(),
                        acknowledgementStatus = InstructionAcknowledgementStatus.StaleVersion,
                        hasRecoverableAcknowledgement = false,
                        rejectionCode = null
                    )
                }
                loadCurrent(requestGeneration, currentAuthority)
            }

            InstructionAcknowledgementResult.UnknownOutcome,
            InstructionAcknowledgementResult.NetworkUnavailable,
            InstructionAcknowledgementResult.ServiceUnavailable ->
                markUnknown(intent, requestGeneration, currentAuthority)

            else -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (intent.status == InstructionIntentStatus.UnknownOutcome) {
                    markUnknown(intent, requestGeneration, currentAuthority)
                    return
                }
                val cleared = safeClearIntent(intent.scope, command.idempotencyKey)
                if (cleared == DriverDeliveryInstructionMetadataWrite.Saved) pendingIntent = null
                val code = (result as? InstructionAcknowledgementResult.Rejected)?.code
                mutableState.update {
                    it.copy(
                        acknowledgementStatus = InstructionAcknowledgementStatus.Rejected,
                        hasRecoverableAcknowledgement =
                            cleared != DriverDeliveryInstructionMetadataWrite.Saved,
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
        val unknown = intent.copy(status = InstructionIntentStatus.UnknownOutcome)
        safeSaveIntent(unknown)
        pendingIntent = unknown
        mutableState.update {
            it.copy(
                acknowledgementStatus = InstructionAcknowledgementStatus.UnknownOutcome,
                hasRecoverableAcknowledgement = true,
                selectedInstructionIds = emptySet()
            )
        }
    }

    private suspend fun safeLoadIntent(
        scope: DriverAttemptScopeIdentity
    ): DriverDeliveryInstructionMetadataRead = try {
        metadataStore.loadIntent(scope)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryInstructionMetadataRead.Unavailable
    }

    private suspend fun safeSaveIntent(
        intent: DriverDeliveryInstructionIntentMetadata
    ): DriverDeliveryInstructionMetadataWrite = try {
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
    ): InstructionsLoadResult = try {
        gateway.currentInstructions(deliveryId, currentAuthority)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InstructionsLoadResult.ServiceUnavailable
    }

    private suspend fun safeAcknowledge(
        command: DriverDeliveryInstructionAcknowledgementCommand,
        currentAuthority: DriverDeliveryAuthority
    ): InstructionAcknowledgementResult = try {
        gateway.acknowledgeCriticalInstructions(command, currentAuthority)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InstructionAcknowledgementResult.UnknownOutcome
    }

    private fun InstructionsLoadResult.toLoadStatus() = when (this) {
        InstructionsLoadResult.NotFound -> InstructionsLoadStatus.NotFound
        InstructionsLoadResult.NetworkUnavailable -> InstructionsLoadStatus.NetworkUnavailable
        InstructionsLoadResult.ServiceUnavailable -> InstructionsLoadStatus.ServiceUnavailable
        InstructionsLoadResult.PermissionDenied -> InstructionsLoadStatus.PermissionDenied
        InstructionsLoadResult.ContextInvalidated -> InstructionsLoadStatus.ContextInvalidated
        InstructionsLoadResult.SessionInvalidated -> InstructionsLoadStatus.SessionInvalidated
        is InstructionsLoadResult.Loaded -> InstructionsLoadStatus.Ready
    }

    private fun isCurrent(
        requestGeneration: Long,
        expectedAuthority: DriverDeliveryAuthority
    ): Boolean = generation == requestGeneration && authority == expectedAuthority &&
        mutableState.value.authorityEpoch == expectedAuthority.authorityEpoch
}
