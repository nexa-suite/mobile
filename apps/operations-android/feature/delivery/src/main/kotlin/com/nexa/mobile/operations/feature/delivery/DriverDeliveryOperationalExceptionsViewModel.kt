package com.nexa.mobile.operations.feature.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionAction as OperationalExceptionAction
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionCommandStatus as ExceptionCommandStatus
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionIntentStatus as ExceptionIntentStatus
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionMutationResult as ExceptionMutationResult
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsLoadResult as ExceptionsLoadResult
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsLoadStatus as ExceptionsLoadStatus
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsUiState as ExceptionsUiState
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
    val loadStatus: ExceptionsLoadStatus =
        ExceptionsLoadStatus.NotRequested,
    val commandStatus: ExceptionCommandStatus =
        ExceptionCommandStatus.Idle,
    val commandAction: OperationalExceptionAction? = null,
    val commandExceptionId: String? = null,
    val hasRecoverableCommand: Boolean = false,
    val unresolvedCommandForOtherDelivery: Boolean = false,
    val replayed: Boolean = false,
    val rejectionCode: String? = null
) {
    fun canClaim(exception: DriverDeliveryOperationalException): Boolean =
        canIssueAction(exception) && exception.status == OPEN &&
            exception.responsibleMembershipId == null

    fun canReview(exception: DriverDeliveryOperationalException): Boolean =
        canIssueAction(exception) && exception.status == CLAIMED &&
            exception.responsibleMembershipId == currentMembershipId

    fun canResolveWarning(exception: DriverDeliveryOperationalException): Boolean =
        canIssueAction(exception) && exception.status == UNDER_REVIEW &&
            currentMembershipId != null &&
            exception.responsibleMembershipId == currentMembershipId &&
            exception.isDriverResolvableWarning()

    fun canCloseWarning(exception: DriverDeliveryOperationalException): Boolean =
        canIssueAction(exception) && exception.status == "RESOLVED" &&
            currentMembershipId != null &&
            exception.responsibleMembershipId == currentMembershipId &&
            exception.isDriverResolvableWarning()

    private fun canIssueAction(exception: DriverDeliveryOperationalException): Boolean =
        canRead && canRespond && loadStatus == ExceptionsLoadStatus.Ready &&
            !hasRecoverableCommand && !unresolvedCommandForOtherDelivery &&
            commandStatus !in setOf(
                ExceptionCommandStatus.PersistingIntent,
                ExceptionCommandStatus.Pending,
                ExceptionCommandStatus.UnknownOutcome,
                ExceptionCommandStatus.PersistenceUnavailable
            ) && snapshot?.exceptions?.any { it.id.equals(exception.id, ignoreCase = true) } == true

    override fun toString(): String =
        "ExceptionsUiState(epoch=$authorityEpoch, load=$loadStatus, action=$commandStatus, exceptions=${snapshot?.exceptions?.size ?: 0})"

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
    private val mutableState = MutableStateFlow(ExceptionsUiState())
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
        mutableState.value = ExceptionsUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canRead = currentAuthority.canRead,
            canRespond = currentAuthority.canStart,
            currentMembershipId = currentAuthority.membershipId,
            deliveryId = deliveryId,
            loadStatus = if (currentAuthority.canRead) {
                ExceptionsLoadStatus.Loading
            } else {
                ExceptionsLoadStatus.PermissionDenied
            }
        )
        if (!currentAuthority.canRead) return

        viewModelScope.launch {
            when (val read = safeLoadIntent(currentAuthority.scopeIdentity)) {
                DriverDeliveryOperationalExceptionMetadataRead.Unavailable -> {
                    metadataAvailable = false
                    mutableState.update {
                        it.copy(
                            commandStatus = ExceptionCommandStatus.PersistenceUnavailable
                        )
                    }
                }

                is DriverDeliveryOperationalExceptionMetadataRead.Available -> {
                    val intent = read.intent
                    pendingIntent = intent
                    when (intent?.status) {
                        ExceptionIntentStatus.Pending,
                        ExceptionIntentStatus.UnknownOutcome -> {
                            val unknown = intent.copy(
                                status = ExceptionIntentStatus.UnknownOutcome
                            )
                            pendingIntent = unknown
                            mutableState.update {
                                it.copy(
                                    commandStatus = if (intent.command.deliveryId == deliveryId) {
                                        ExceptionCommandStatus.UnknownOutcome
                                    } else {
                                        ExceptionCommandStatus.PersistenceUnavailable
                                    },
                                    commandAction = intent.command.action,
                                    commandExceptionId = intent.command.exceptionId,
                                    hasRecoverableCommand = intent.command.deliveryId == deliveryId,
                                    unresolvedCommandForOtherDelivery =
                                        intent.command.deliveryId != deliveryId
                                )
                            }
                        }

                        ExceptionIntentStatus.StaleVersion -> {
                            staleIntentAwaitingRefresh = intent
                            mutableState.update {
                                it.copy(
                                    commandStatus = ExceptionCommandStatus.StaleVersion,
                                    commandAction = intent.command.action,
                                    commandExceptionId = intent.command.exceptionId,
                                    hasRecoverableCommand = false,
                                    unresolvedCommandForOtherDelivery =
                                        intent.command.deliveryId != deliveryId
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
        mutableState.value = ExceptionsUiState()
    }

    fun refresh() {
        val currentAuthority = authority ?: return
        val requestGeneration = generation
        if (!currentAuthority.canRead || !isCurrent(requestGeneration, currentAuthority)) return
        mutableState.update {
            it.copy(
                loadStatus = ExceptionsLoadStatus.Loading,
                snapshot = null,
                rejectionCode = null
            )
        }
        viewModelScope.launch { loadCurrent(requestGeneration, currentAuthority) }
    }

    fun claim(exceptionId: String) = act(exceptionId, OperationalExceptionAction.Claim)

    fun sendForReview(exceptionId: String) = act(exceptionId, OperationalExceptionAction.Review)

    fun resolveWarning(exceptionId: String, resolution: String) =
        act(exceptionId, OperationalExceptionAction.ResolveWarning, resolution)

    fun closeWarning(exceptionId: String) =
        act(exceptionId, OperationalExceptionAction.CloseWarning)

    private fun act(
        exceptionId: String,
        action: OperationalExceptionAction,
        resolution: String? = null
    ) {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val snapshot = current.snapshot ?: return
        val row =
            snapshot.exceptions.firstOrNull { it.id.equals(exceptionId, ignoreCase = true) }
                ?: return
        val allowed = when (action) {
            OperationalExceptionAction.Claim -> current.canClaim(row)

            OperationalExceptionAction.Review -> current.canReview(row)

            OperationalExceptionAction.ResolveWarning -> current.canResolveWarning(
                row
            )

            OperationalExceptionAction.CloseWarning -> current.canCloseWarning(row)
        }
        if (!allowed || !metadataAvailable || staleIntentAwaitingRefresh != null ||
            !isCurrent(generation, currentAuthority)
        ) {
            return
        }

        val frozenBody = when (action) {
            OperationalExceptionAction.Claim,
            OperationalExceptionAction.Review -> driverDeliveryOperationalExceptionEmptyBody()

            OperationalExceptionAction.ResolveWarning -> try {
                driverDeliveryOperationalExceptionResolutionBody(resolution.orEmpty())
            } catch (_: IllegalArgumentException) {
                return
            }

            OperationalExceptionAction.CloseWarning -> DRIVER_OPERATIONAL_EXCEPTION_BODYLESS
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
            status = ExceptionIntentStatus.Pending
        )
        mutableState.update {
            it.copy(
                commandStatus = ExceptionCommandStatus.PersistingIntent,
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
                        commandStatus = ExceptionCommandStatus.PersistenceUnavailable,
                        hasRecoverableCommand = false
                    )
                }
                return@launch
            }
            pendingIntent = intent
            mutableState.update {
                it.copy(commandStatus = ExceptionCommandStatus.Pending)
            }
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
                ExceptionCommandStatus.UnknownOutcome,
                ExceptionCommandStatus.Claimed,
                ExceptionCommandStatus.UnderReview,
                ExceptionCommandStatus.Resolved,
                ExceptionCommandStatus.Closed,
                ExceptionCommandStatus.Rejected
            ) || !isCurrent(generation, currentAuthority)
        ) {
            return
        }
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                commandStatus = ExceptionCommandStatus.Pending,
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
            is ExceptionsLoadResult.Loaded -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (result.snapshot.deliveryId != mutableState.value.deliveryId) {
                    mutableState.update {
                        it.copy(
                            snapshot = null,
                            loadStatus = ExceptionsLoadStatus.ServiceUnavailable
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
                                    loadStatus = ExceptionsLoadStatus.Ready,
                                    commandStatus = ExceptionCommandStatus.StaleVersion,
                                    hasRecoverableCommand = false,
                                    unresolvedCommandForOtherDelivery = false,
                                    rejectionCode = null
                                )
                            }
                        }

                        else -> mutableState.update {
                            it.copy(
                                snapshot = result.snapshot,
                                loadStatus = ExceptionsLoadStatus.Ready,
                                commandStatus = ExceptionCommandStatus.PersistenceUnavailable,
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
                        loadStatus = ExceptionsLoadStatus.Ready,
                        commandStatus = when {
                            !metadataAvailable -> ExceptionCommandStatus.PersistenceUnavailable

                            pendingIntent == null &&
                                it.commandStatus ==
                                ExceptionCommandStatus.Idle ->
                                ExceptionCommandStatus.Idle

                            else -> it.commandStatus
                        },
                        hasRecoverableCommand =
                            pendingIntent?.command?.deliveryId == result.snapshot.deliveryId &&
                                pendingIntent?.status !=
                                ExceptionIntentStatus.StaleVersion,
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
                        hasRecoverableCommand =
                            pendingIntent?.command?.deliveryId == it.deliveryId &&
                                pendingIntent?.status !=
                                ExceptionIntentStatus.StaleVersion,
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
            is ExceptionMutationResult.Changed -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                val mutation = result.mutation
                val valid = mutation.deliveryId == command.deliveryId &&
                    mutation.deliveryVersion >= command.expectedDeliveryVersion &&
                    mutation.exception.id.equals(command.exceptionId, ignoreCase = true) &&
                    when (command.action) {
                        OperationalExceptionAction.Claim ->
                            mutation.exception.responsibleMembershipId ==
                                currentAuthority.membershipId &&
                                !mutation.exception.claimedAt.isNullOrBlank() &&
                                (
                                    mutation.exception.status == "CLAIMED" ||
                                        (
                                            mutation.replayed &&
                                                mutation.exception.status in
                                                LATER_EXCEPTION_STATUSES
                                            )
                                    )

                        OperationalExceptionAction.Review ->
                            mutation.exception.responsibleMembershipId ==
                                currentAuthority.membershipId &&
                                mutation.exception.underReviewByMembershipId ==
                                currentAuthority.membershipId &&
                                !mutation.exception.underReviewAt.isNullOrBlank() &&
                                (
                                    mutation.exception.status == "UNDER_REVIEW" ||
                                        (
                                            mutation.replayed &&
                                                mutation.exception.status in
                                                LATER_EXCEPTION_STATUSES
                                            )
                                    )

                        OperationalExceptionAction.ResolveWarning ->
                            mutation.exception.severity == WARNING &&
                                mutation.exception.isDriverResolvableWarning() &&
                                mutation.exception.responsibleMembershipId ==
                                currentAuthority.membershipId &&
                                mutation.exception.resolution ==
                                driverDeliveryOperationalExceptionResolutionFromBody(
                                    command.frozenBody
                                ) &&
                                mutation.exception.outcome == WARNING_CONDITION_ADDRESSED &&
                                (
                                    mutation.exception.status == "RESOLVED" ||
                                        (
                                            mutation.replayed &&
                                                mutation.exception.status == CLOSED
                                            )
                                    )

                        OperationalExceptionAction.CloseWarning ->
                            mutation.exception.severity == WARNING &&
                                mutation.exception.isDriverResolvableWarning() &&
                                mutation.exception.responsibleMembershipId ==
                                currentAuthority.membershipId &&
                                !mutation.exception.resolution.isNullOrBlank() &&
                                mutation.exception.outcome == WARNING_CONDITION_ADDRESSED &&
                                mutation.exception.status == CLOSED
                    }
                if (!valid) {
                    markUnknown(intent, requestGeneration, currentAuthority)
                    return
                }
                val cleared = safeClearIntent(intent.scope, command.idempotencyKey)
                if (cleared ==
                    DriverDeliveryOperationalExceptionMetadataWrite.Saved
                ) {
                    pendingIntent = null
                }
                val nextStatus = when (command.action) {
                    OperationalExceptionAction.Claim,
                    OperationalExceptionAction.Review,
                    OperationalExceptionAction.ResolveWarning,
                    OperationalExceptionAction.CloseWarning -> when (mutation.exception.status) {
                        CLAIMED -> ExceptionCommandStatus.Claimed
                        UNDER_REVIEW -> ExceptionCommandStatus.UnderReview
                        RESOLVED -> ExceptionCommandStatus.Resolved
                        CLOSED -> ExceptionCommandStatus.Closed
                        else -> ExceptionCommandStatus.UnknownOutcome
                    }
                }
                mutableState.update { current ->
                    val old = current.snapshot
                    current.copy(
                        snapshot = old?.copy(
                            deliveryVersion = mutation.deliveryVersion,
                            exceptions = old.exceptions.map { row ->
                                if (row.id.equals(
                                        mutation.exception.id,
                                        ignoreCase = true
                                    )
                                ) {
                                    mutation.exception
                                } else {
                                    row
                                }
                            }
                        ),
                        commandStatus = nextStatus,
                        hasRecoverableCommand =
                            cleared != DriverDeliveryOperationalExceptionMetadataWrite.Saved,
                        replayed = mutation.replayed,
                        rejectionCode = null
                    )
                }
            }

            ExceptionMutationResult.StaleVersion -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                val stale = intent.copy(
                    status = ExceptionIntentStatus.StaleVersion
                )
                val saved = safeSaveIntent(stale)
                staleIntentAwaitingRefresh = stale
                pendingIntent = stale
                if (saved != DriverDeliveryOperationalExceptionMetadataWrite.Saved &&
                    safeClearIntent(intent.scope, command.idempotencyKey) ==
                    DriverDeliveryOperationalExceptionMetadataWrite.Saved
                ) {
                    pendingIntent = null
                }
                mutableState.update {
                    it.copy(
                        snapshot = null,
                        loadStatus = ExceptionsLoadStatus.Loading,
                        commandStatus = ExceptionCommandStatus.StaleVersion,
                        hasRecoverableCommand = false,
                        rejectionCode = null
                    )
                }
                loadCurrent(requestGeneration, currentAuthority)
            }

            ExceptionMutationResult.UnknownOutcome,
            ExceptionMutationResult.NetworkUnavailable,
            ExceptionMutationResult.ServiceUnavailable ->
                markUnknown(intent, requestGeneration, currentAuthority)

            else -> {
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (intent.status ==
                    ExceptionIntentStatus.UnknownOutcome
                ) {
                    markUnknown(intent, requestGeneration, currentAuthority)
                    return
                }
                val cleared = safeClearIntent(intent.scope, command.idempotencyKey)
                if (cleared ==
                    DriverDeliveryOperationalExceptionMetadataWrite.Saved
                ) {
                    pendingIntent = null
                }
                val code = (result as? ExceptionMutationResult.Rejected)?.code
                val accessFailureStatus = when (result) {
                    ExceptionMutationResult.NotFound ->
                        ExceptionsLoadStatus.NotFound

                    ExceptionMutationResult.PermissionDenied ->
                        ExceptionsLoadStatus.PermissionDenied

                    ExceptionMutationResult.ContextInvalidated ->
                        ExceptionsLoadStatus.ContextInvalidated

                    ExceptionMutationResult.SessionInvalidated ->
                        ExceptionsLoadStatus.SessionInvalidated

                    else -> null
                }
                mutableState.update {
                    it.copy(
                        snapshot = if (accessFailureStatus != null) null else it.snapshot,
                        loadStatus = accessFailureStatus ?: it.loadStatus,
                        commandStatus = ExceptionCommandStatus.Rejected,
                        hasRecoverableCommand =
                            cleared != DriverDeliveryOperationalExceptionMetadataWrite.Saved,
                        rejectionCode = code
                    )
                }
                if (result is ExceptionMutationResult.Rejected) {
                    loadCurrent(requestGeneration, currentAuthority)
                    if (isCurrent(requestGeneration, currentAuthority) &&
                        mutableState.value.loadStatus ==
                        ExceptionsLoadStatus.Ready
                    ) {
                        mutableState.update {
                            it.copy(
                                commandStatus = ExceptionCommandStatus.Rejected,
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
        val unknown = intent.copy(
            status = ExceptionIntentStatus.UnknownOutcome
        )
        safeSaveIntent(unknown)
        pendingIntent = unknown
        mutableState.update {
            it.copy(
                commandStatus = ExceptionCommandStatus.UnknownOutcome,
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

    private suspend fun safeClearIntent(scope: DriverAttemptScopeIdentity, idempotencyKey: String) =
        try {
            metadataStore.clearIntent(scope, idempotencyKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
        }

    private suspend fun safeLoad(currentAuthority: DriverDeliveryAuthority, deliveryId: String) =
        try {
            gateway.currentExceptions(deliveryId, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExceptionsLoadResult.ServiceUnavailable
        }

    private suspend fun safeMutate(
        command: DriverDeliveryOperationalExceptionCommand,
        currentAuthority: DriverDeliveryAuthority
    ) = try {
        gateway.mutate(command, currentAuthority)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ExceptionMutationResult.UnknownOutcome
    }

    private fun ExceptionsLoadResult.toLoadStatus() = when (this) {
        ExceptionsLoadResult.NotFound -> ExceptionsLoadStatus.NotFound
        ExceptionsLoadResult.NetworkUnavailable -> ExceptionsLoadStatus.NetworkUnavailable
        ExceptionsLoadResult.ServiceUnavailable -> ExceptionsLoadStatus.ServiceUnavailable
        ExceptionsLoadResult.PermissionDenied -> ExceptionsLoadStatus.PermissionDenied
        ExceptionsLoadResult.ContextInvalidated -> ExceptionsLoadStatus.ContextInvalidated
        ExceptionsLoadResult.SessionInvalidated -> ExceptionsLoadStatus.SessionInvalidated
        is ExceptionsLoadResult.Loaded -> ExceptionsLoadStatus.Ready
    }

    private fun isCurrent(
        requestGeneration: Long,
        expectedAuthority: DriverDeliveryAuthority
    ): Boolean = generation == requestGeneration && authority == expectedAuthority &&
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
