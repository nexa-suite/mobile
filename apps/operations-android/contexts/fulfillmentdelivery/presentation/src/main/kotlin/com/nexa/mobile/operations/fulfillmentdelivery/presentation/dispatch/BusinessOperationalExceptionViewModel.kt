package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.BusinessOperationalExceptionCommandStatus as ExceptionCommandStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.BusinessOperationalExceptionsStatus as OperationalExceptionsStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.BusinessOperationalException
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAction
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.BusinessOperationalExceptionActor
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAssigneesResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionsGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.BusinessOperationalExceptionsSnapshot
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BusinessOperationalExceptionsStatus {
    Initial,
    Loading,
    Current,
    Empty,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable,
    ContextInvalidated,
    SessionInvalidated
}
enum class BusinessOperationalExceptionCommandStatus {
    Idle,
    Persisting,
    Saving,
    UnknownOutcome,
    PersistenceUnavailable,
    Stale,
    Rejected,
    Applied
}

data class BusinessOperationalExceptionUiState(
    val authorityEpoch: Long = 0,
    val canRead: Boolean = false,
    val canCoordinate: Boolean = false,
    val currentMembershipId: String? = null,
    val snapshot: BusinessOperationalExceptionsSnapshot? = null,
    val selectedExceptionId: String? = null,
    val eligibleAssignees: List<BusinessOperationalExceptionActor> = emptyList(),
    val selectedAssigneeMembershipId: String? = null,
    val assigneesLoading: Boolean = false,
    val assigneesUnavailable: Boolean = false,
    val status: OperationalExceptionsStatus = OperationalExceptionsStatus.Initial,
    val commandStatus: ExceptionCommandStatus = ExceptionCommandStatus.Idle,
    val commandAction: BusinessOperationalExceptionAction? = null,
    val commandExceptionId: String? = null,
    val pendingCommand: BusinessOperationalExceptionCommand? = null,
    val rejectionCode: String? = null,
    val replayed: Boolean = false
) {
    val selectedException: BusinessOperationalException?
        get() = snapshot?.exceptions?.firstOrNull {
            it.id.equals(selectedExceptionId, ignoreCase = true)
        }

    val hasUnresolvedCommand: Boolean
        get() = commandStatus in setOf(
            ExceptionCommandStatus.Persisting,
            ExceptionCommandStatus.Saving,
            ExceptionCommandStatus.UnknownOutcome,
            ExceptionCommandStatus.PersistenceUnavailable
        )

    fun canClaim(row: BusinessOperationalException): Boolean =
        canCoordinate && status == OperationalExceptionsStatus.Current &&
            !hasUnresolvedCommand && row.status == "OPEN" &&
            (
                row.coordinationOwnerMembershipId == null ||
                    row.coordinationOwnerMembershipId == currentMembershipId
                )

    fun canReassign(row: BusinessOperationalException): Boolean =
        canCoordinate && status == OperationalExceptionsStatus.Current &&
            !hasUnresolvedCommand && row.status != "CLOSED"

    fun canFollowUp(row: BusinessOperationalException): Boolean =
        canCoordinate && status == OperationalExceptionsStatus.Current &&
            !hasUnresolvedCommand && row.status in setOf("CLAIMED", "UNDER_REVIEW")

    fun canResolve(row: BusinessOperationalException): Boolean =
        canCoordinate && status == OperationalExceptionsStatus.Current &&
            !hasUnresolvedCommand && row.status == "UNDER_REVIEW" && row.isCoordinatableWarning()

    fun canClose(row: BusinessOperationalException): Boolean =
        canCoordinate && status == OperationalExceptionsStatus.Current &&
            !hasUnresolvedCommand && row.status == "RESOLVED" && row.isCoordinatableWarning()

    override fun toString(): String =
        "BusinessOperationalExceptionUiState(epoch=$authorityEpoch, status=$status, " +
            "command=$commandStatus, exceptions=${snapshot?.exceptions?.size ?: 0})"
}

/** Permission-scoped list and lifecycle client for cross-functional Operational Exceptions. */
class BusinessOperationalExceptionsViewModel(
    private val gateway: BusinessOperationalExceptionsGateway,
    private val metadata: BusinessOperationalExceptionMetadataStore,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val requestBodyCodec: DispatchRequestBodyCodec
) : ViewModel() {
    private val mutableState = MutableStateFlow(BusinessOperationalExceptionUiState())
    val state = mutableState.asStateFlow()

    private var authority: BusinessOperationalExceptionAuthority? = null
    private var generation = 0L
    private var assigneeGeneration = 0L
    private var pendingCommand: BusinessOperationalExceptionCommand? = null
    private var metadataAvailable = true

    fun activate(currentAuthority: BusinessOperationalExceptionAuthority) {
        if (authority == currentAuthority && mutableState.value.status !in INVALIDATED) return
        authority = currentAuthority
        val request = ++generation
        pendingCommand = null
        metadataAvailable = true
        val scope = currentAuthority.scope
        mutableState.value = BusinessOperationalExceptionUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canRead = currentAuthority.canRead,
            canCoordinate = currentAuthority.canCoordinate,
            currentMembershipId = scope?.membershipId,
            status = if (currentAuthority.canRead) {
                OperationalExceptionsStatus.Loading
            } else {
                OperationalExceptionsStatus.PermissionDenied
            }
        )
        if (!currentAuthority.canRead || scope == null) return
        viewModelScope.launch {
            val restored = safe { metadata.load(scope) }
            when (restored) {
                null, BusinessOperationalExceptionMetadataRead.Unavailable -> {
                    metadataAvailable = false
                    mutableState.update {
                        it.copy(
                            commandStatus = ExceptionCommandStatus.PersistenceUnavailable
                        )
                    }
                }

                is BusinessOperationalExceptionMetadataRead.Available -> {
                    val command = restored.intent?.command
                    if (command != null &&
                        (command.scope != scope || !requestBodyCodec.isValid(command))
                    ) {
                        metadataAvailable = false
                        mutableState.update {
                            it.copy(
                                commandStatus = ExceptionCommandStatus.PersistenceUnavailable
                            )
                        }
                    } else if (command != null) {
                        val unknown = command.copy(
                            status = BusinessOperationalExceptionIntentStatus.UnknownOutcome
                        )
                        pendingCommand = unknown
                        if (command.status == BusinessOperationalExceptionIntentStatus.Pending &&
                            safe { metadata.save(BusinessOperationalExceptionIntent(unknown)) } !=
                            BusinessOperationalExceptionMetadataWrite.Saved
                        ) {
                            metadataAvailable = false
                            mutableState.update {
                                it.copy(
                                    commandStatus = ExceptionCommandStatus.PersistenceUnavailable
                                )
                            }
                        } else {
                            mutableState.update {
                                it.copy(
                                    commandStatus = ExceptionCommandStatus.UnknownOutcome,
                                    commandAction = command.action,
                                    commandExceptionId = command.exceptionId,
                                    pendingCommand = unknown
                                )
                            }
                        }
                    }
                }
            }
            if (isCurrent(request, currentAuthority)) loadCurrent(request, currentAuthority)
        }
    }

    fun invalidate() {
        generation++
        authority = null
        pendingCommand = null
        metadataAvailable = true
        mutableState.value = BusinessOperationalExceptionUiState()
    }

    fun refresh() {
        val current = authority ?: return
        if (!current.canRead || !isCurrent(generation, current)) return
        val request = generation
        mutableState.update {
            it.copy(status = OperationalExceptionsStatus.Loading, snapshot = null)
        }
        viewModelScope.launch { loadCurrent(request, current) }
    }

    fun selectException(exceptionId: String) {
        if (mutableState.value.snapshot?.exceptions?.none { it.id.equals(exceptionId, true) } ==
            true
        ) {
            return
        }
        val currentAuthority = authority ?: return
        val request = generation
        assigneeGeneration++
        mutableState.update {
            it.copy(
                selectedExceptionId = exceptionId,
                eligibleAssignees = emptyList(),
                selectedAssigneeMembershipId = null,
                assigneesLoading = true,
                assigneesUnavailable = false
            )
        }
        viewModelScope.launch {
            loadAssignees(request, assigneeGeneration, currentAuthority, exceptionId)
        }
    }

    fun selectAssignee(membershipId: String) {
        if (mutableState.value.eligibleAssignees.none { it.membershipId == membershipId }) return
        mutableState.update { it.copy(selectedAssigneeMembershipId = membershipId) }
    }

    fun clearSelection() {
        assigneeGeneration++
        mutableState.update {
            it.copy(
                selectedExceptionId = null,
                eligibleAssignees = emptyList(),
                selectedAssigneeMembershipId = null,
                assigneesLoading = false,
                assigneesUnavailable = false
            )
        }
    }

    fun claim(reason: String) = act(BusinessOperationalExceptionAction.CLAIM, reason = reason)
    fun reassign(responsibleMembershipId: String, reason: String) = act(
        BusinessOperationalExceptionAction.REASSIGN,
        responsibleMembershipId = responsibleMembershipId,
        reason = reason
    )
    fun followUp(reason: String, note: String) =
        act(BusinessOperationalExceptionAction.FOLLOW_UP, reason = reason, followUpNote = note)
    fun resolve(reason: String) = act(BusinessOperationalExceptionAction.RESOLVE, reason = reason)
    fun close(reason: String) = act(BusinessOperationalExceptionAction.CLOSE, reason = reason)

    fun retryUnknownOutcome() {
        val currentAuthority = authority ?: return
        val command = pendingCommand ?: return
        if (mutableState.value.commandStatus !=
            ExceptionCommandStatus.UnknownOutcome ||
            !isCurrent(generation, currentAuthority) || command.scope != currentAuthority.scope
        ) {
            return
        }
        execute(command, currentAuthority, generation, persistFirst = false)
    }

    private fun act(
        action: BusinessOperationalExceptionAction,
        responsibleMembershipId: String? = null,
        reason: String,
        followUpNote: String? = null
    ) {
        val currentAuthority = authority ?: return
        val state = mutableState.value
        val row = state.selectedException ?: return
        if (action == BusinessOperationalExceptionAction.REASSIGN &&
            (
                responsibleMembershipId == null ||
                    state.eligibleAssignees.none { it.membershipId == responsibleMembershipId }
                )
        ) {
            return
        }
        val allowed = when (action) {
            BusinessOperationalExceptionAction.CLAIM -> state.canClaim(row)
            BusinessOperationalExceptionAction.REASSIGN -> state.canReassign(row)
            BusinessOperationalExceptionAction.FOLLOW_UP -> state.canFollowUp(row)
            BusinessOperationalExceptionAction.RESOLVE -> state.canResolve(row)
            BusinessOperationalExceptionAction.CLOSE -> state.canClose(row)
        }
        if (!allowed || !metadataAvailable || pendingCommand != null ||
            !isCurrent(generation, currentAuthority)
        ) {
            return
        }
        val frozenBody = try {
            requestBodyCodec.businessOperationalExceptionRequestBody(
                action,
                responsibleMembershipId,
                followUpNote,
                reason
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        val scope = currentAuthority.scope ?: return
        val command = BusinessOperationalExceptionCommand(
            scope = scope,
            action = action,
            exceptionId = row.id,
            expectedDeliveryVersion = row.deliveryVersion,
            idempotencyKey = keyFactory(),
            frozenBody = frozenBody,
            status = BusinessOperationalExceptionIntentStatus.Pending
        )
        execute(command, currentAuthority, generation, persistFirst = true)
    }

    private fun execute(
        command: BusinessOperationalExceptionCommand,
        currentAuthority: BusinessOperationalExceptionAuthority,
        request: Long,
        persistFirst: Boolean
    ) {
        viewModelScope.launch {
            if (persistFirst) {
                mutableState.update {
                    it.copy(
                        commandStatus = ExceptionCommandStatus.Persisting,
                        commandAction = command.action,
                        commandExceptionId = command.exceptionId
                    )
                }
                when (safe { metadata.save(BusinessOperationalExceptionIntent(command)) }) {
                    BusinessOperationalExceptionMetadataWrite.Saved -> Unit

                    BusinessOperationalExceptionMetadataWrite.Conflict,
                    BusinessOperationalExceptionMetadataWrite.Unavailable, null -> {
                        if (isCurrent(request, currentAuthority)) {
                            metadataAvailable = false
                            mutableState.update {
                                it.copy(
                                    commandStatus = ExceptionCommandStatus.PersistenceUnavailable
                                )
                            }
                        }
                        return@launch
                    }
                }
                pendingCommand = command
            }
            if (!isCurrent(request, currentAuthority)) return@launch
            mutableState.update {
                it.copy(
                    commandStatus = ExceptionCommandStatus.Saving,
                    pendingCommand = command
                )
            }
            val result = safeGateway { gateway.mutate(command, currentAuthority) }
            if (!isCurrent(request, currentAuthority)) return@launch
            when (result) {
                is BusinessOperationalExceptionsGatewayResult.Changed -> {
                    if (result.exception.id != command.exceptionId ||
                        result.exception.deliveryVersion != result.deliveryVersion
                    ) {
                        preserveUnknown(command)
                        return@launch
                    }
                    when (safe { metadata.clear(command.scope, command.idempotencyKey) }) {
                        BusinessOperationalExceptionMetadataWrite.Saved -> {
                            pendingCommand = null
                            mutableState.update {
                                it.copy(
                                    commandStatus = ExceptionCommandStatus.Applied,
                                    replayed = result.replayed,
                                    pendingCommand = null
                                )
                            }
                            loadCurrent(request, currentAuthority)
                        }

                        else -> preserveUnknown(command)
                    }
                }

                is BusinessOperationalExceptionsGatewayResult.Failed -> {
                    if (result.unknownOutcome) {
                        preserveUnknown(command)
                    } else {
                        safe { metadata.clear(command.scope, command.idempotencyKey) }
                        pendingCommand = null
                        mutableState.update {
                            it.copy(
                                commandStatus = if (result.code ==
                                    "STALE_VERSION"
                                ) {
                                    ExceptionCommandStatus.Stale
                                } else {
                                    ExceptionCommandStatus.Rejected
                                },
                                rejectionCode = result.code,
                                pendingCommand = null
                            )
                        }
                        loadCurrent(request, currentAuthority)
                    }
                }

                is BusinessOperationalExceptionsGatewayResult.Current -> preserveUnknown(command)
            }
        }
    }

    private suspend fun preserveUnknown(command: BusinessOperationalExceptionCommand) {
        val unknown = command.copy(status = BusinessOperationalExceptionIntentStatus.UnknownOutcome)
        if (safe { metadata.save(BusinessOperationalExceptionIntent(unknown)) } !=
            BusinessOperationalExceptionMetadataWrite.Saved
        ) {
            metadataAvailable = false
            mutableState.update {
                it.copy(
                    commandStatus = ExceptionCommandStatus.PersistenceUnavailable
                )
            }
        } else {
            pendingCommand = unknown
            mutableState.update {
                it.copy(
                    commandStatus = ExceptionCommandStatus.UnknownOutcome,
                    pendingCommand = unknown,
                    commandAction = command.action,
                    commandExceptionId = command.exceptionId
                )
            }
        }
    }

    private suspend fun loadCurrent(
        request: Long,
        currentAuthority: BusinessOperationalExceptionAuthority
    ) {
        when (val result = safeGateway { gateway.current(currentAuthority) }) {
            is BusinessOperationalExceptionsGatewayResult.Current -> {
                if (!isCurrent(request, currentAuthority)) return
                val selected = mutableState.value.selectedExceptionId
                    ?.takeIf { id -> result.snapshot.exceptions.any { it.id.equals(id, true) } }
                    ?: result.snapshot.exceptions.firstOrNull()?.id
                mutableState.update {
                    it.copy(
                        snapshot = result.snapshot,
                        selectedExceptionId = selected,
                        status = if (result.snapshot.exceptions.isEmpty()) {
                            OperationalExceptionsStatus.Empty
                        } else {
                            OperationalExceptionsStatus.Current
                        },
                        rejectionCode = null
                    )
                }
                if (selected != null) {
                    assigneeGeneration++
                    loadAssignees(request, assigneeGeneration, currentAuthority, selected)
                }
            }

            is BusinessOperationalExceptionsGatewayResult.Failed -> if (isCurrent(
                    request,
                    currentAuthority
                )
            ) {
                val status = when (result.code) {
                    "ACCESS_CONTEXT_INVALID" -> OperationalExceptionsStatus.ContextInvalidated
                    "SESSION_INVALID" -> OperationalExceptionsStatus.SessionInvalidated
                    "FORBIDDEN", "PERMISSION_DENIED" -> OperationalExceptionsStatus.PermissionDenied
                    "NETWORK_UNAVAILABLE" -> OperationalExceptionsStatus.NetworkUnavailable
                    else -> OperationalExceptionsStatus.ServiceUnavailable
                }
                mutableState.update {
                    it.copy(status = status, snapshot = null, rejectionCode = result.code)
                }
            }

            is BusinessOperationalExceptionsGatewayResult.Changed -> if (isCurrent(
                    request,
                    currentAuthority
                )
            ) {
                mutableState.update {
                    it.copy(
                        status = OperationalExceptionsStatus.ServiceUnavailable,
                        snapshot = null
                    )
                }
            }
        }
    }

    private fun isCurrent(request: Long, expected: BusinessOperationalExceptionAuthority) =
        generation == request && authority == expected

    private suspend fun loadAssignees(
        request: Long,
        assigneeRequest: Long,
        expected: BusinessOperationalExceptionAuthority,
        exceptionId: String
    ) {
        if (!expected.canRead || !isCurrent(request, expected)) return
        when (val result = safe { gateway.assignees(exceptionId, expected) }) {
            is BusinessOperationalExceptionAssigneesResult.Loaded -> {
                if (isCurrent(request, expected) && assigneeGeneration == assigneeRequest &&
                    mutableState.value.selectedExceptionId == exceptionId
                ) {
                    mutableState.update {
                        it.copy(
                            eligibleAssignees = result.values,
                            assigneesLoading = false,
                            assigneesUnavailable = false,
                            selectedAssigneeMembershipId = null
                        )
                    }
                }
            }

            is BusinessOperationalExceptionAssigneesResult.Failed,
            null -> if (isCurrent(request, expected) && assigneeGeneration == assigneeRequest) {
                mutableState.update {
                    it.copy(
                        eligibleAssignees = emptyList(),
                        assigneesLoading = false,
                        assigneesUnavailable = true
                    )
                }
            }
        }
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun safeGateway(
        block: suspend () -> BusinessOperationalExceptionsGatewayResult
    ) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        BusinessOperationalExceptionsGatewayResult.Failed(
            "NETWORK_UNAVAILABLE",
            unknownOutcome = true
        )
    }

    private companion object {
        val INVALIDATED =
            setOf(
                OperationalExceptionsStatus.ContextInvalidated,
                OperationalExceptionsStatus.SessionInvalidated
            )
    }
}

private fun BusinessOperationalException.isCoordinatableWarning(): Boolean =
    severity == "WARNING" && type in setOf("DELAY", "INCOMPLETE_INSTRUCTION")
