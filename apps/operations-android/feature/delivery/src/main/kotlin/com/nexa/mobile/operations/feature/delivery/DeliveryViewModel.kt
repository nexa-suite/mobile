package com.nexa.mobile.operations.feature.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val rejectionCode: String? = null
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
    private val keyFactory: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DriverDeliveryUiState())
    val state = mutableState.asStateFlow()

    private var authority: DriverDeliveryAuthority? = null
    private var generation = 0L
    private var pendingStart: DriverAttemptStartCommand? = null
    private var pendingStartPersisted = false

    fun activate(currentAuthority: DriverDeliveryAuthority) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        pendingStart = null
        pendingStartPersisted = false
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
            when (val loaded = safeMetadataLoad(currentAuthority.scopeIdentity)) {
                is DriverAttemptMetadataRead.Available -> {
                    val intent = loaded.intent
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
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (!metadataAvailable) {
                mutableState.update {
                    it.copy(commandStatus = DriverDeliveryCommandStatus.PersistenceUnavailable)
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
        mutableState.value = DriverDeliveryUiState()
    }

    fun selectDelivery(deliveryId: String) {
        val currentAuthority = authority ?: return
        if (!currentAuthority.canRead || mutableState.value.commandStatus in FROZEN_COMMANDS) return
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
                rejectionCode = null
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
            mutableState.value.commandStatus in FROZEN_COMMANDS || pendingStart != null
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

    /** Refreshes list and selected detail to reconcile an uncertain network result. */
    fun refresh() {
        val currentAuthority = authority ?: return
        if (!currentAuthority.canRead) return
        val requestGeneration = generation
        val selectedId = mutableState.value.selectedDelivery?.id
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

    private companion object {
        val FROZEN_COMMANDS = setOf(
            DriverDeliveryCommandStatus.CheckingCurrent,
            DriverDeliveryCommandStatus.PersistingIntent,
            DriverDeliveryCommandStatus.Pending,
            DriverDeliveryCommandStatus.UnknownOutcome,
            DriverDeliveryCommandStatus.PersistenceUnavailable
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
}
