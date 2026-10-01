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

enum class DriverDeliveryOperationalExceptionsLoadStatus {
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

enum class DriverDeliveryOperationalExceptionCommandStatus {
    Idle,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    PersistenceUnavailable,
    Claimed,
    UnderReview,
    Resolved,
    Closed,
    StaleVersion,
    Rejected
}

data class DriverDeliveryOperationalExceptionsUiState(
    val authorityEpoch: Long = 0,
    val canRead: Boolean = false,
    val canRespond: Boolean = false,
    val currentMembershipId: String? = null,
    val deliveryId: String? = null,
    val snapshot: DriverDeliveryOperationalExceptionsSnapshot? = null,
    val loadStatus: DriverDeliveryOperationalExceptionsLoadStatus =
        DriverDeliveryOperationalExceptionsLoadStatus.NotRequested,
    val commandStatus: DriverDeliveryOperationalExceptionCommandStatus =
        DriverDeliveryOperationalExceptionCommandStatus.Idle,
    val commandAction: DriverDeliveryOperationalExceptionAction? = null,
    val commandExceptionId: String? = null,
    val hasRecoverableCommand: Boolean = false,
    val unresolvedCommandForOtherDelivery: Boolean = false,
    val replayed: Boolean = false,
    val rejectionCode: String? = null
) {
    fun canClaim(exception: DriverDeliveryOperationalException): Boolean =
        canIssueAction(exception) && exception.status == OPEN && exception.responsibleMembershipId == null

    fun canReview(exception: DriverDeliveryOperationalException): Boolean =
        canIssueAction(exception) && exception.status == CLAIMED &&
            exception.responsibleMembershipId == currentMembershipId

    fun canResolveWarning(exception: DriverDeliveryOperationalException): Boolean =
        canIssueAction(exception) && exception.status == UNDER_REVIEW &&
            currentMembershipId != null && exception.responsibleMembershipId == currentMembershipId &&
            exception.isDriverResolvableWarning()

    fun canCloseWarning(exception: DriverDeliveryOperationalException): Boolean =
        canIssueAction(exception) && exception.status == RESOLVED &&
            currentMembershipId != null && exception.responsibleMembershipId == currentMembershipId &&
            exception.isDriverResolvableWarning()

    private fun canIssueAction(exception: DriverDeliveryOperationalException): Boolean =
        canRead && canRespond && loadStatus == DriverDeliveryOperationalExceptionsLoadStatus.Ready &&
            !hasRecoverableCommand && !unresolvedCommandForOtherDelivery &&
            commandStatus !in setOf(
                DriverDeliveryOperationalExceptionCommandStatus.PersistingIntent,
                DriverDeliveryOperationalExceptionCommandStatus.Pending,
                DriverDeliveryOperationalExceptionCommandStatus.UnknownOutcome,
                DriverDeliveryOperationalExceptionCommandStatus.PersistenceUnavailable
            ) && snapshot?.exceptions?.any { it.id.equals(exception.id, ignoreCase = true) } == true

    override fun toString(): String =
        "DriverDeliveryOperationalExceptionsUiState(epoch=$authorityEpoch, load=$loadStatus, action=$commandStatus, exceptions=${snapshot?.exceptions?.size ?: 0})"

    private companion object {
        const val OPEN = "OPEN"
        const val CLAIMED = "CLAIMED"
        const val UNDER_REVIEW = "UNDER_REVIEW"
    }
}

/** Current assigned Delivery exceptions; only supported warnings assigned to this Driver can be completed. */
class DriverDeliveryOperationalExceptionsViewModel(
    private val gateway: DriverDeliveryOperationalExceptionsGateway,
    private val metadataStore: DriverDeliveryOperationalExceptionMetadataStore,
    private val timeFactory: () -> String = { Instant.now().toString() },
    private val keyFactory: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DriverDeliveryOperationalExceptionsUiState())
    val state = mutableState.asStateFlow()

    private var authority: DriverDeliveryAuthority? = null
    private var generation = 0L
    private var pendingIntent: DriverDeliveryOperationalExceptionIntent? = null
    private var metadataAvailable = true
    private var staleIntentAwaitingRefresh: DriverDeliveryOperationalExceptionIntent? = null

    fun activate(currentAuthority: DriverDeliveryAuthority, deliveryId: String) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        pendingIntent = null
        staleIntentAwaitingRefresh = null
        metadataAvailable = true
        mutableState.value = DriverDeliveryOperationalExceptionsUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canRead = currentAuthority.canRead,
            canRespond = currentAuthority.canStart,
            currentMembershipId = currentAuthority.membershipId,
            deliveryId = deliveryId,
            loadStatus = if (currentAuthority.canRead) {
                DriverDeliveryOperationalExceptionsLoadStatus.Loading
            } else {
                DriverDeliveryOperationalExceptionsLoadStatus.PermissionDenied
            }
        )
        if (!currentAuthority.canRead) return

        viewModelScope.launch {
            when (val read = safeLoadIntent(currentAuthority.scopeIdentity)) {
                DriverDeliveryOperationalExceptionMetadataRead.Unavailable -> {
                    metadataAvailable = false
                    mutableState.update {
                        it.copy(commandStatus = DriverDeliveryOperationalExceptionCommandStatus.PersistenceUnavailable)
                    }
                }

                is DriverDeliveryOperationalExceptionMetadataRead.Available -> {
                    val intent = read.intent
                    pendingIntent = intent
                    when (intent?.status) {
                        DriverDeliveryOperationalExceptionIntentStatus.Pending,
                        DriverDeliveryOperationalExceptionIntentStatus.UnknownOutcome -> {
                            val unknown = intent.copy(status = DriverDeliveryOperationalExceptionIntentStatus.UnknownOutcome)
                            pendingIntent = unknown
                            mutableState.update {
                                it.copy(
                                    commandStatus = if (intent.command.deliveryId == deliveryId) {
                                        DriverDeliveryOperationalExceptionCommandStatus.UnknownOutcome
                                    } else {
                                        DriverDeliveryOperationalExceptionCommandStatus.PersistenceUnavailable
                                    },
                                    commandAction = intent.command.action,
                                    commandExceptionId = intent.command.exceptionId,
                                    hasRecoverableCommand = intent.command.deliveryId == deliveryId,
                                    unresolvedCommandForOtherDelivery = intent.command.deliveryId != deliveryId
                                )
                            }
                        }

                        DriverDeliveryOperationalExceptionIntentStatus.StaleVersion -> {
                            staleIntentAwaitingRefresh = intent
                            mutableState.update {
                                it.copy(
                                    commandStatus = DriverDeliveryOperationalExceptionCommandStatus.StaleVersion,
                                    commandAction = intent.command.action,
                                    commandExceptionId = intent.command.exceptionId,
                                    hasRecoverableCommand = false,
                                    unresolvedCommandForOtherDelivery = intent.command.deliveryId != deliveryId
                                )
                            }
                        }

                        null -> Unit
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
        mutableState.value = DriverDeliveryOperationalExceptionsUiState()
    }

    fun refresh() {
        val currentAuthority = authority ?: return
        val requestGeneration = generation
        if (!currentAuthority.canRead || !isCurrent(requestGeneration, currentAuthority)) return
        mutableState.update {
            it.copy(
                loadStatus = DriverDeliveryOperationalExceptionsLoadStatus.Loading,
                snapshot = null,
                rejectionCode = null
            )
        }
        viewModelScope.launch { loadCurrent(requestGeneration, currentAuthority) }
    }

    fun claim(exceptionId: String) = act(exceptionId, DriverDeliveryOperationalExceptionAction.Claim)

    fun sendForReview(exceptionId: String) = act(exceptionId, DriverDeliveryOperationalExceptionAction.Review)

    fun resolveWarning(exceptionId: String, resolution: String) =
        act(exceptionId, DriverDeliveryOperationalExceptionAction.ResolveWarning, resolution)

    fun closeWarning(exceptionId: String) = act(exceptionId, DriverDeliveryOperationalExceptionAction.CloseWarning)

    private fun act(
        exceptionId: String,
        action: DriverDeliveryOperationalExceptionAction,
        resolution: String? = null
    ) {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val snapshot = current.snapshot ?: return
        val row = snapshot.exceptions.firstOrNull { it.id.equals(exceptionId, ignoreCase = true) } ?: return
        val allowed = when (action) {
            DriverDeliveryOperationalExceptionAction.Claim -> current.canClaim(row)
            DriverDeliveryOperationalExceptionAction.Review -> current.canReview(row)
            DriverDeliveryOperationalExceptionAction.ResolveWarning -> current.canResolveWarning(row)
            DriverDeliveryOperationalExceptionAction.CloseWarning -> current.canCloseWarning(row)
        }
        if (!allowed || !metadataAvailable || staleIntentAwaitingRefresh != null ||
            !isCurrent(generation, currentAuthority)
        ) return

        val frozenBody = when (action) {
            DriverDeliveryOperationalExceptionAction.Claim,
            DriverDeliveryOperationalExceptionAction.Review -> driverDeliveryOperationalExceptionEmptyBody()

            DriverDeliveryOperationalExceptionAction.ResolveWarning -> try {
                driverDeliveryOperationalExceptionResolutionBody(resolution.orEmpty())
            } catch (_: IllegalArgumentException) {
                return
            }

            DriverDeliveryOperationalExceptionAction.CloseWarning -> DRIVER_OPERATIONAL_EXCEPTION_BODYLESS
        }

        val command = DriverDeliveryOperationalExceptionCommand(
            deliveryId = snapshot.deliveryId,
            exceptionId = row.id,
            action = action,
            expectedDeliveryVersion = snapshot.deliveryVersion,
            idempotencyKey = keyFactory(),
            frozenBody = frozenBody
        )
        val intent = DriverDeliveryOperationalExceptionIntent(
            scope = currentAuthority.scopeIdentity,
            command = command,
            initiatedByMembershipId = currentAuthority.membershipId,
            initiatedAt = timeFactory(),
            status = DriverDeliveryOperationalExceptionIntentStatus.Pending
        )
        mutableState.update {
            it.copy(
                commandStatus = DriverDeliveryOperationalExceptionCommandStatus.PersistingIntent,
                commandAction = action,
                commandExceptionId = row.id,
                hasRecoverableCommand = false,
                replayed = false,
                rejectionCode = null
            )
        }
        val requestGeneration = generation
        viewModelScope.launch {
            val written = safeSaveIntent(intent)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (written != DriverDeliveryOperationalExceptionMetadataWrite.Saved) {
                metadataAvailable = false
                mutableState.update {
                    it.copy(
                        commandStatus = DriverDeliveryOperationalExceptionCommandStatus.PersistenceUnavailable,
                        hasRecoverableCommand = false
                    )
                }
                return@launch
            }
            pendingIntent = intent
            mutableState.update { it.copy(commandStatus = DriverDeliveryOperationalExceptionCommandStatus.Pending) }
            submit(intent, requestGeneration, currentAuthority)
        }
    }

    /** Manually replays only the stored command and key after an uncertain result. */
    fun retrySameCommand() {
        val currentAuthority = authority ?: return
        val intent = pendingIntent ?: return
        val current = mutableState.value
        if (!current.canRespond || !metadataAvailable || !current.hasRecoverableCommand ||
            intent.command.deliveryId != current.deliveryId ||
            current.commandStatus !in setOf(
                DriverDeliveryOperationalExceptionCommandStatus.UnknownOutcome,
                DriverDeliveryOperationalExceptionCommandStatus.Claimed,
                DriverDeliveryOperationalExceptionCommandStatus.UnderReview,
                DriverDeliveryOperationalExceptionCommandStatus.Resolved,
                DriverDeliveryOperationalExceptionCommandStatus.Closed,
                DriverDeliveryOperationalExceptionCommandStatus.Rejected
            ) || !isCurrent(generation, currentAuthority)
        ) return
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                commandStatus = DriverDeliveryOperationalExceptionCommandStatus.Pending,
                replayed = false,
                rejectionCode = null
            )
        }
        viewModelScope.launch { submit(intent, requestGeneration, currentAuthority) }
    }

    private suspend fun loadCurrent(
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        when (val result = safeLoad(currentAuthority, mutableState.value.deliveryId.orEmpty())) {
            is DriverDeliveryOperationalExceptionsLoadResult.Loaded -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (result.snapshot.deliveryId != mutableState.value.deliveryId) {
                    mutableState.update {
                        it.copy(
                            snapshot = null,
                            loadStatus = DriverDeliveryOperationalExceptionsLoadStatus.ServiceUnavailable
                        )
                    }
                    return
                }
                val stale = staleIntentAwaitingRefresh
                if (stale != null && stale.command.deliveryId == result.snapshot.deliveryId) {
                    when (safeClearIntent(stale.scope, stale.command.idempotencyKey)) {
                        DriverDeliveryOperationalExceptionMetadataWrite.Saved -> {
                            if (pendingIntent == stale) pendingIntent = null
                            staleIntentAwaitingRefresh = null
                            metadataAvailable = true
                            mutableState.update {
                                it.copy(
                                    snapshot = result.snapshot,
                                    loadStatus = DriverDeliveryOperationalExceptionsLoadStatus.Ready,
                                    commandStatus = DriverDeliveryOperationalExceptionCommandStatus.StaleVersion,
                                    hasRecoverableCommand = false,
                                    unresolvedCommandForOtherDelivery = false,
                                    rejectionCode = null
                                )
                            }
                        }

                        else -> mutableState.update {
                            it.copy(
                                snapshot = result.snapshot,
                                loadStatus = DriverDeliveryOperationalExceptionsLoadStatus.Ready,
                                commandStatus = DriverDeliveryOperationalExceptionCommandStatus.PersistenceUnavailable,
                                hasRecoverableCommand = false,
                                rejectionCode = null
                            )
                        }
                    }
                    return
                }
                mutableState.update {
                    it.copy(
                        snapshot = result.snapshot,
                        loadStatus = DriverDeliveryOperationalExceptionsLoadStatus.Ready,
                        commandStatus = when {
                            !metadataAvailable -> DriverDeliveryOperationalExceptionCommandStatus.PersistenceUnavailable
                            pendingIntent == null && it.commandStatus == DriverDeliveryOperationalExceptionCommandStatus.Idle ->
                                DriverDeliveryOperationalExceptionCommandStatus.Idle
                            else -> it.commandStatus
                        },
                        hasRecoverableCommand = pendingIntent?.command?.deliveryId == result.snapshot.deliveryId &&
                            pendingIntent?.status != DriverDeliveryOperationalExceptionIntentStatus.StaleVersion,
                        unresolvedCommandForOtherDelivery = pendingIntent != null &&
                            pendingIntent?.command?.deliveryId != result.snapshot.deliveryId,
                        rejectionCode = null
                    )
                }
            }

            else -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                mutableState.update {
                    it.copy(
                        snapshot = null,
                        loadStatus = result.toLoadStatus(),
                            hasRecoverableCommand = pendingIntent?.command?.deliveryId == it.deliveryId &&
                                pendingIntent?.status != DriverDeliveryOperationalExceptionIntentStatus.StaleVersion,
                        unresolvedCommandForOtherDelivery = pendingIntent != null &&
                            pendingIntent?.command?.deliveryId != it.deliveryId
                    )
                }
            }
        }
    }

    private suspend fun submit(
        intent: DriverDeliveryOperationalExceptionIntent,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val command = intent.command
        if (!isCurrent(requestGeneration, currentAuthority)) return
        when (val result = safeMutate(command, currentAuthority)) {
            is DriverDeliveryOperationalExceptionMutationResult.Changed -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                val mutation = result.mutation
                val valid = mutation.deliveryId == command.deliveryId &&
                    mutation.deliveryVersion >= command.expectedDeliveryVersion &&
                    mutation.exception.id.equals(command.exceptionId, ignoreCase = true) &&
                    when (command.action) {
                        DriverDeliveryOperationalExceptionAction.Claim ->
                            mutation.exception.responsibleMembershipId == currentAuthority.membershipId &&
                                !mutation.exception.claimedAt.isNullOrBlank() &&
                                (mutation.exception.status == "CLAIMED" ||
                                    mutation.replayed && mutation.exception.status in LATER_EXCEPTION_STATUSES)

                        DriverDeliveryOperationalExceptionAction.Review ->
                            mutation.exception.responsibleMembershipId == currentAuthority.membershipId &&
                                mutation.exception.underReviewByMembershipId == currentAuthority.membershipId &&
                                !mutation.exception.underReviewAt.isNullOrBlank() &&
                                (mutation.exception.status == "UNDER_REVIEW" ||
                                    mutation.replayed && mutation.exception.status in LATER_EXCEPTION_STATUSES)

                        DriverDeliveryOperationalExceptionAction.ResolveWarning ->
                            mutation.exception.severity == WARNING &&
                                mutation.exception.isDriverResolvableWarning() &&
                                mutation.exception.responsibleMembershipId == currentAuthority.membershipId &&
                                mutation.exception.resolution ==
                                    driverDeliveryOperationalExceptionResolutionFromBody(command.frozenBody) &&
                                mutation.exception.outcome == WARNING_CONDITION_ADDRESSED &&
                                (mutation.exception.status == RESOLVED ||
                                    mutation.replayed && mutation.exception.status == CLOSED)

                        DriverDeliveryOperationalExceptionAction.CloseWarning ->
                            mutation.exception.severity == WARNING &&
                                mutation.exception.isDriverResolvableWarning() &&
                                mutation.exception.responsibleMembershipId == currentAuthority.membershipId &&
                                !mutation.exception.resolution.isNullOrBlank() &&
                                mutation.exception.outcome == WARNING_CONDITION_ADDRESSED &&
                                mutation.exception.status == CLOSED
                    }
                if (!valid) {
                    markUnknown(intent, requestGeneration, currentAuthority)
                    return
                }
                val cleared = safeClearIntent(intent.scope, command.idempotencyKey)
                if (cleared == DriverDeliveryOperationalExceptionMetadataWrite.Saved) pendingIntent = null
                val nextStatus = when (command.action) {
                    DriverDeliveryOperationalExceptionAction.Claim,
                    DriverDeliveryOperationalExceptionAction.Review,
                    DriverDeliveryOperationalExceptionAction.ResolveWarning,
                    DriverDeliveryOperationalExceptionAction.CloseWarning -> when (mutation.exception.status) {
                        CLAIMED -> DriverDeliveryOperationalExceptionCommandStatus.Claimed
                        UNDER_REVIEW -> DriverDeliveryOperationalExceptionCommandStatus.UnderReview
                        RESOLVED -> DriverDeliveryOperationalExceptionCommandStatus.Resolved
                        CLOSED -> DriverDeliveryOperationalExceptionCommandStatus.Closed
                        else -> DriverDeliveryOperationalExceptionCommandStatus.UnknownOutcome
                    }
                }
                mutableState.update { current ->
                    val old = current.snapshot
                    current.copy(
                        snapshot = old?.copy(
                            deliveryVersion = mutation.deliveryVersion,
                            exceptions = old.exceptions.map { row ->
                                if (row.id.equals(mutation.exception.id, ignoreCase = true)) mutation.exception else row
                            }
                        ),
                        commandStatus = nextStatus,
                        hasRecoverableCommand = cleared != DriverDeliveryOperationalExceptionMetadataWrite.Saved,
                        replayed = mutation.replayed,
                        rejectionCode = null
                    )
                }
            }

            DriverDeliveryOperationalExceptionMutationResult.StaleVersion -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                val stale = intent.copy(status = DriverDeliveryOperationalExceptionIntentStatus.StaleVersion)
                val saved = safeSaveIntent(stale)
                staleIntentAwaitingRefresh = stale
                pendingIntent = stale
                if (saved != DriverDeliveryOperationalExceptionMetadataWrite.Saved &&
                    safeClearIntent(intent.scope, command.idempotencyKey) == DriverDeliveryOperationalExceptionMetadataWrite.Saved
                ) pendingIntent = null
                mutableState.update {
                    it.copy(
                        snapshot = null,
                        loadStatus = DriverDeliveryOperationalExceptionsLoadStatus.Loading,
                        commandStatus = DriverDeliveryOperationalExceptionCommandStatus.StaleVersion,
                        hasRecoverableCommand = false,
                        rejectionCode = null
                    )
                }
                loadCurrent(requestGeneration, currentAuthority)
            }

            DriverDeliveryOperationalExceptionMutationResult.UnknownOutcome,
            DriverDeliveryOperationalExceptionMutationResult.NetworkUnavailable,
            DriverDeliveryOperationalExceptionMutationResult.ServiceUnavailable ->
                markUnknown(intent, requestGeneration, currentAuthority)

            else -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (intent.status == DriverDeliveryOperationalExceptionIntentStatus.UnknownOutcome) {
                    markUnknown(intent, requestGeneration, currentAuthority)
                    return
                }
                val cleared = safeClearIntent(intent.scope, command.idempotencyKey)
                if (cleared == DriverDeliveryOperationalExceptionMetadataWrite.Saved) pendingIntent = null
                val code = (result as? DriverDeliveryOperationalExceptionMutationResult.Rejected)?.code
                val accessFailureStatus = when (result) {
                    DriverDeliveryOperationalExceptionMutationResult.NotFound ->
                        DriverDeliveryOperationalExceptionsLoadStatus.NotFound

                    DriverDeliveryOperationalExceptionMutationResult.PermissionDenied ->
                        DriverDeliveryOperationalExceptionsLoadStatus.PermissionDenied

                    DriverDeliveryOperationalExceptionMutationResult.ContextInvalidated ->
                        DriverDeliveryOperationalExceptionsLoadStatus.ContextInvalidated

                    DriverDeliveryOperationalExceptionMutationResult.SessionInvalidated ->
                        DriverDeliveryOperationalExceptionsLoadStatus.SessionInvalidated

                    else -> null
                }
                mutableState.update {
                    it.copy(
                        snapshot = if (accessFailureStatus != null) null else it.snapshot,
                        loadStatus = accessFailureStatus ?: it.loadStatus,
                        commandStatus = DriverDeliveryOperationalExceptionCommandStatus.Rejected,
                        hasRecoverableCommand = cleared != DriverDeliveryOperationalExceptionMetadataWrite.Saved,
                        rejectionCode = code
                    )
                }
                if (result is DriverDeliveryOperationalExceptionMutationResult.Rejected) {
                    loadCurrent(requestGeneration, currentAuthority)
                    if (isCurrent(requestGeneration, currentAuthority) &&
                        mutableState.value.loadStatus == DriverDeliveryOperationalExceptionsLoadStatus.Ready
                    ) {
                        mutableState.update {
                            it.copy(
                                commandStatus = DriverDeliveryOperationalExceptionCommandStatus.Rejected,
                                rejectionCode = code
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun markUnknown(
        intent: DriverDeliveryOperationalExceptionIntent,
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority
    ) {
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val unknown = intent.copy(status = DriverDeliveryOperationalExceptionIntentStatus.UnknownOutcome)
        safeSaveIntent(unknown)
        pendingIntent = unknown
        mutableState.update {
            it.copy(
                commandStatus = DriverDeliveryOperationalExceptionCommandStatus.UnknownOutcome,
                hasRecoverableCommand = true
            )
        }
    }

    private suspend fun safeLoadIntent(scope: DriverAttemptScopeIdentity) = try {
        metadataStore.loadIntent(scope)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryOperationalExceptionMetadataRead.Unavailable
    }

    private suspend fun safeSaveIntent(intent: DriverDeliveryOperationalExceptionIntent) = try {
        metadataStore.saveIntent(intent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
    }

    private suspend fun safeClearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ) = try {
        metadataStore.clearIntent(scope, idempotencyKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
    }

    private suspend fun safeLoad(
        currentAuthority: DriverDeliveryAuthority,
        deliveryId: String
    ) = try {
        gateway.currentExceptions(deliveryId, currentAuthority)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryOperationalExceptionsLoadResult.ServiceUnavailable
    }

    private suspend fun safeMutate(
        command: DriverDeliveryOperationalExceptionCommand,
        currentAuthority: DriverDeliveryAuthority
    ) = try {
        gateway.mutate(command, currentAuthority)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverDeliveryOperationalExceptionMutationResult.UnknownOutcome
    }

    private fun DriverDeliveryOperationalExceptionsLoadResult.toLoadStatus() = when (this) {
        DriverDeliveryOperationalExceptionsLoadResult.NotFound -> DriverDeliveryOperationalExceptionsLoadStatus.NotFound
        DriverDeliveryOperationalExceptionsLoadResult.NetworkUnavailable -> DriverDeliveryOperationalExceptionsLoadStatus.NetworkUnavailable
        DriverDeliveryOperationalExceptionsLoadResult.ServiceUnavailable -> DriverDeliveryOperationalExceptionsLoadStatus.ServiceUnavailable
        DriverDeliveryOperationalExceptionsLoadResult.PermissionDenied -> DriverDeliveryOperationalExceptionsLoadStatus.PermissionDenied
        DriverDeliveryOperationalExceptionsLoadResult.ContextInvalidated -> DriverDeliveryOperationalExceptionsLoadStatus.ContextInvalidated
        DriverDeliveryOperationalExceptionsLoadResult.SessionInvalidated -> DriverDeliveryOperationalExceptionsLoadStatus.SessionInvalidated
        is DriverDeliveryOperationalExceptionsLoadResult.Loaded -> DriverDeliveryOperationalExceptionsLoadStatus.Ready
    }

    private fun isCurrent(requestGeneration: Long, expectedAuthority: DriverDeliveryAuthority): Boolean =
        generation == requestGeneration && authority == expectedAuthority &&
            mutableState.value.authorityEpoch == expectedAuthority.authorityEpoch

    private companion object {
        const val WARNING = "WARNING"
        const val WARNING_CONDITION_ADDRESSED = "WARNING_CONDITION_ADDRESSED"
        const val CLAIMED = "CLAIMED"
        const val UNDER_REVIEW = "UNDER_REVIEW"
        const val RESOLVED = "RESOLVED"
        const val CLOSED = "CLOSED"
        val LATER_EXCEPTION_STATUSES = setOf(UNDER_REVIEW, RESOLVED, CLOSED)
    }
}

private fun DriverDeliveryOperationalException.isDriverResolvableWarning(): Boolean =
    type in setOf("DELAY", "INCOMPLETE_INSTRUCTION") && severity == "WARNING"
