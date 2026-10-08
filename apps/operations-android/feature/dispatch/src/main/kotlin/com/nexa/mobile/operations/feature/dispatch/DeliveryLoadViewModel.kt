package com.nexa.mobile.operations.feature.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.feature.dispatch.application.DeliveryLoadCommandMetadataStore
import com.nexa.mobile.operations.feature.dispatch.application.DeliveryLoadGateway
import com.nexa.mobile.operations.feature.dispatch.model.DELIVERY_LOAD_STATUSES
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoad
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadCommand
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadCommandAction
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadCommandIntentStatus
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadCommandMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadCommandMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadCompatibilityAttestation
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadDriverCandidate
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.model.deliveryLoadAssignBody
import com.nexa.mobile.operations.feature.dispatch.model.deliveryLoadCreationBody
import com.nexa.mobile.operations.feature.dispatch.model.deliveryLoadReorderBody
import com.nexa.mobile.operations.feature.dispatch.model.dispatchWindowPlanBody
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

enum class DeliveryLoadScreenStatus {
    Initial,
    Loading,
    Current,
    Empty,
    Failed,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}
enum class DeliveryLoadCommandStatus {
    Idle,
    Persisting,
    Pending,
    UnknownOutcome,
    PersistenceUnavailable,
    Changed,
    Rejected
}
enum class DeliveryLoadAttestationField {
    Capacity,
    Handling,
    Zone,
    ExclusiveTransport
}

data class DeliveryLoadUiState(
    val authorityEpoch: Long = 0,
    val driverMode: Boolean = false,
    val membershipId: String? = null,
    val permissions: Set<String> = emptySet(),
    val status: DeliveryLoadScreenStatus = DeliveryLoadScreenStatus.Initial,
    val loads: List<DeliveryLoad> = emptyList(),
    val readyCandidates: List<DispatchReadiness> = emptyList(),
    val drivers: List<DeliveryLoadDriverCandidate> = emptyList(),
    val selectedFulfillmentIds: List<String> = emptyList(),
    val windowPlanFulfillmentId: String? = null,
    val windowPlanStart: String = "",
    val windowPlanEnd: String = "",
    val windowPlanReason: String = "",
    val selectedDriverMembershipId: String? = null,
    val createReason: String = "",
    val attestation: DeliveryLoadCompatibilityAttestation = DeliveryLoadCompatibilityAttestation(
        capacitySufficient = false,
        handlingCompatible = false,
        zoneReasonable = false,
        noExclusiveTransportRestriction = false
    ),
    val commandStatus: DeliveryLoadCommandStatus = DeliveryLoadCommandStatus.Idle,
    val commandAction: DeliveryLoadCommandAction? = null,
    val hasRecoverableCommand: Boolean = false,
    val unresolvedCommandForOtherMode: Boolean = false,
    val failureCode: String? = null
) {
    val canCreate: Boolean
        get() = !driverMode && status == DeliveryLoadScreenStatus.Current &&
            permissions.contains(DISPATCH_READ) &&
            permissions.any { it in DISPATCH_SCHEDULE_PERMISSIONS } &&
            selectedFulfillmentIds.size in 2..20 &&
            selectedFulfillmentIds.all { id ->
                readyCandidates.any {
                    it.fulfillmentId == id &&
                        it.windowStart != null && it.windowEnd != null &&
                        it.windowSource in setOf("COMMERCIAL", "DISPATCH_PLAN")
                }
            } &&
            createReason.isNotBlank() &&
            createReason.trim().length <= 500 && attestation.complete &&
            canStartCommand()

    val canPlanWindow: Boolean
        get() {
            val candidate =
                readyCandidates.firstOrNull { it.fulfillmentId == windowPlanFulfillmentId }
                    ?: return false
            val start =
                runCatching { Instant.parse(windowPlanStart.trim()) }.getOrNull() ?: return false
            val end =
                runCatching { Instant.parse(windowPlanEnd.trim()) }.getOrNull() ?: return false
            return !driverMode && status == DeliveryLoadScreenStatus.Current &&
                permissions.contains(DISPATCH_READ) &&
                permissions.any { it in DISPATCH_SCHEDULE_PERMISSIONS } &&
                candidate.ready && candidate.fulfillmentStatus == "READY_FOR_DISPATCH" &&
                candidate.windowSource == null && candidate.windowStart == null &&
                candidate.windowEnd == null &&
                start.isBefore(end) && windowPlanReason.isNotBlank() &&
                windowPlanReason.trim().length <= 2000 &&
                canStartCommand()
        }

    fun canAssign(load: DeliveryLoad): Boolean = !driverMode && canStartCommand() &&
        permissions.any { it in DISPATCH_ASSIGN_PERMISSIONS } && load.status == "DRAFT" &&
        selectedDriverMembershipId != null &&
        drivers.any { it.membershipId == selectedDriverMembershipId }

    fun canOffer(load: DeliveryLoad): Boolean = !driverMode && canStartCommand() &&
        permissions.any { it in DISPATCH_SCHEDULE_PERMISSIONS } && load.status == "ASSIGNED"

    fun canConfirmHandoff(load: DeliveryLoad): Boolean = !driverMode && canStartCommand() &&
        permissions.any { it in DISPATCH_SCHEDULE_PERMISSIONS } &&
        load.status in setOf("OFFERED", "DRIVER_ACCEPTED")

    fun canAccept(load: DeliveryLoad): Boolean = driverMode && canStartCommand() &&
        permissions.contains(DISPATCH_START_ROUTE) &&
        load.assignedDriverMembershipId == membershipId &&
        load.status in setOf("OFFERED", "HANDOFF_CONFIRMED")

    fun canSelectWindowPlan(candidate: DispatchReadiness): Boolean = !driverMode &&
        status == DeliveryLoadScreenStatus.Current && permissions.contains(DISPATCH_READ) &&
        permissions.any { it in DISPATCH_SCHEDULE_PERMISSIONS } && candidate.ready &&
        candidate.fulfillmentStatus == "READY_FOR_DISPATCH" && candidate.windowStart == null &&
        candidate.windowEnd == null && candidate.windowSource == null && canStartCommand()

    private fun canStartCommand(): Boolean =
        !hasRecoverableCommand && !unresolvedCommandForOtherMode &&
            commandStatus !in setOf(
                DeliveryLoadCommandStatus.Persisting,
                DeliveryLoadCommandStatus.Pending,
                DeliveryLoadCommandStatus.UnknownOutcome,
                DeliveryLoadCommandStatus.PersistenceUnavailable
            )

    override fun toString(): String =
        "DeliveryLoadUiState(mode=${if (driverMode) "driver" else "dispatch"}, status=$status, loads=${loads.size}, command=$commandStatus)"

    private companion object {
        const val DISPATCH_READ = "dispatch.read"
        val DISPATCH_SCHEDULE_PERMISSIONS = setOf("dispatch.schedule", "logistics:write")
        val DISPATCH_ASSIGN_PERMISSIONS = setOf("dispatch.assign", "logistics:write")
        const val DISPATCH_START_ROUTE = "dispatch.start_route"
    }
}

/** Groups only current, server-ready Fulfillment candidates and hands whole Loads to their assigned Driver. */
class DeliveryLoadViewModel(
    private val gateway: DeliveryLoadGateway,
    private val metadata: DeliveryLoadCommandMetadataStore,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DeliveryLoadUiState())
    val state = mutableState.asStateFlow()

    private var context: DispatchAuthorityContext? = null
    private var scope: DeliveryLoadScopeIdentity? = null
    private var generation = 0L
    private var pending: DeliveryLoadCommand? = null
    private var metadataAvailable = true

    fun activate(currentContext: DispatchAuthorityContext, driverMode: Boolean) {
        val identity = currentContext.identity ?: run {
            invalidateContext()
            return
        }
        val commandScope = DeliveryLoadScopeIdentity(
            identity.userId,
            identity.tenantId,
            identity.workspaceId,
            identity.membershipId
        )
        if (context == currentContext && scope == commandScope &&
            state.value.driverMode == driverMode &&
            state.value.status in
            setOf(
                DeliveryLoadScreenStatus.Loading,
                DeliveryLoadScreenStatus.Current,
                DeliveryLoadScreenStatus.Empty
            )
        ) {
            return
        }

        generation++
        val request = generation
        context = currentContext
        scope = commandScope
        pending = null
        metadataAvailable = true
        mutableState.value = DeliveryLoadUiState(
            authorityEpoch = currentContext.authorityEpoch,
            driverMode = driverMode,
            membershipId = identity.membershipId,
            permissions = identity.permissions,
            status = DeliveryLoadScreenStatus.Loading
        )
        viewModelScope.launch {
            when (val stored = safeRead(commandScope)) {
                DeliveryLoadCommandMetadataRead.Unavailable -> {
                    metadataAvailable = false
                    mutableState.update {
                        it.copy(commandStatus = DeliveryLoadCommandStatus.PersistenceUnavailable)
                    }
                }

                is DeliveryLoadCommandMetadataRead.Available -> {
                    pending =
                        stored.command?.copy(
                            status = DeliveryLoadCommandIntentStatus.UnknownOutcome
                        )
                    val intent = pending
                    if (intent != null) {
                        mutableState.update {
                            it.copy(
                                commandStatus = if (intent.driverCommand == driverMode) {
                                    DeliveryLoadCommandStatus.UnknownOutcome
                                } else {
                                    DeliveryLoadCommandStatus.PersistenceUnavailable
                                },
                                commandAction = intent.action,
                                hasRecoverableCommand = intent.driverCommand == driverMode,
                                unresolvedCommandForOtherMode = intent.driverCommand != driverMode
                            )
                        }
                    }
                }
            }
            if (isCurrent(request, currentContext)) loadCurrent(request, currentContext, driverMode)
        }
    }

    fun invalidateContext() {
        generation++
        context = null
        scope = null
        pending = null
        metadataAvailable = true
        mutableState.value =
            DeliveryLoadUiState(status = DeliveryLoadScreenStatus.ContextInvalidated)
    }

    fun invalidateSession() {
        generation++
        context = null
        scope = null
        pending = null
        metadataAvailable = true
        mutableState.value =
            DeliveryLoadUiState(status = DeliveryLoadScreenStatus.SessionInvalidated)
    }

    fun deactivate() {
        generation++
        context = null
        scope = null
        pending = null
        mutableState.value = DeliveryLoadUiState()
    }

    fun refresh() {
        val currentContext = context ?: return
        val request = ++generation
        val driverMode = state.value.driverMode
        mutableState.update {
            it.copy(status = DeliveryLoadScreenStatus.Loading, loads = emptyList())
        }
        viewModelScope.launch { loadCurrent(request, currentContext, driverMode) }
    }

    fun toggleFulfillment(fulfillmentId: String) {
        val current = state.value
        if (current.driverMode || current.status != DeliveryLoadScreenStatus.Current ||
            current.readyCandidates.none { it.fulfillmentId == fulfillmentId }
        ) {
            return
        }
        val selected = current.selectedFulfillmentIds.toMutableList()
        if (fulfillmentId in selected) {
            selected.remove(fulfillmentId)
        } else {
            if (selected.size >= 20) return
            selected.add(fulfillmentId)
        }
        mutableState.update { it.copy(selectedFulfillmentIds = selected) }
    }

    fun moveStop(fulfillmentId: String, delta: Int) {
        if (delta !in setOf(-1, 1)) return
        val selected = state.value.selectedFulfillmentIds.toMutableList()
        val index = selected.indexOf(fulfillmentId)
        val target = index + delta
        if (index < 0 || target !in selected.indices) return
        selected.add(target, selected.removeAt(index))
        mutableState.update { it.copy(selectedFulfillmentIds = selected) }
    }

    fun setDriver(membershipId: String?) {
        if (membershipId != null &&
            state.value.drivers.none { it.membershipId == membershipId }
        ) {
            return
        }
        mutableState.update { it.copy(selectedDriverMembershipId = membershipId) }
    }

    fun setCreateReason(reason: String) {
        mutableState.update { it.copy(createReason = reason.take(500)) }
    }

    fun selectWindowPlan(fulfillmentId: String?) {
        if (fulfillmentId != null && state.value.readyCandidates.none {
                it.fulfillmentId == fulfillmentId && it.windowSource == null &&
                    it.windowStart == null &&
                    it.windowEnd == null
            }
        ) {
            return
        }
        mutableState.update { it.copy(windowPlanFulfillmentId = fulfillmentId) }
    }

    fun setWindowPlanStart(value: String) {
        mutableState.update { it.copy(windowPlanStart = value.take(80)) }
    }
    fun setWindowPlanEnd(value: String) {
        mutableState.update { it.copy(windowPlanEnd = value.take(80)) }
    }
    fun setWindowPlanReason(value: String) {
        mutableState.update { it.copy(windowPlanReason = value.take(2000)) }
    }

    fun planWindow() {
        val current = state.value
        val currentContext = context ?: return
        val fulfillmentId = current.windowPlanFulfillmentId ?: return
        if (!current.canPlanWindow || !metadataAvailable) return
        val candidate =
            current.readyCandidates.firstOrNull { it.fulfillmentId == fulfillmentId } ?: return
        val body =
            runCatching {
                dispatchWindowPlanBody(
                    current.windowPlanStart,
                    current.windowPlanEnd,
                    current.windowPlanReason
                )
            }
                .getOrNull() ?: return
        issue(
            DeliveryLoadCommandAction.PLAN_WINDOW,
            null,
            body,
            currentContext,
            fulfillmentId,
            candidate.fulfillmentVersion
        )
    }

    fun setAttestation(field: DeliveryLoadAttestationField, checked: Boolean) {
        mutableState.update { current ->
            val old = current.attestation
            val next = when (field) {
                DeliveryLoadAttestationField.Capacity -> old.copy(capacitySufficient = checked)

                DeliveryLoadAttestationField.Handling -> old.copy(handlingCompatible = checked)

                DeliveryLoadAttestationField.Zone -> old.copy(zoneReasonable = checked)

                DeliveryLoadAttestationField.ExclusiveTransport -> old.copy(
                    noExclusiveTransportRestriction = checked
                )
            }
            current.copy(attestation = next)
        }
    }

    fun createLoad() {
        val current = state.value
        val currentContext = context ?: return
        if (!current.canCreate || !metadataAvailable) return
        val selected = current.selectedFulfillmentIds.mapNotNull { id ->
            current.readyCandidates.firstOrNull { it.fulfillmentId == id }
        }
        if (selected.size != current.selectedFulfillmentIds.size ||
            selected.any { !it.isCreateCandidate() }
        ) {
            return
        }
        val body = try {
            deliveryLoadCreationBody(
                selected,
                current.selectedFulfillmentIds,
                current.createReason,
                current.attestation
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        issue(DeliveryLoadCommandAction.CREATE, null, body, currentContext)
    }

    fun assign(loadId: String) {
        val current = state.value
        val currentContext = context ?: return
        val load = current.loads.firstOrNull { it.id == loadId } ?: return
        if (!current.canAssign(load)) return
        val body = try {
            deliveryLoadAssignBody(current.selectedDriverMembershipId.orEmpty())
        } catch (
            _: IllegalArgumentException
        ) {
            return
        }
        issue(DeliveryLoadCommandAction.ASSIGN, load, body, currentContext)
    }

    fun offer(loadId: String) = issueForLoad(loadId, DeliveryLoadCommandAction.OFFER)
    fun confirmHandoff(loadId: String) =
        issueForLoad(loadId, DeliveryLoadCommandAction.CONFIRM_HANDOFF)
    fun accept(loadId: String) = issueForLoad(loadId, DeliveryLoadCommandAction.ACCEPT)

    fun reorder(loadId: String, stopOrder: List<String>, reason: String) {
        val current = state.value
        val currentContext = context ?: return
        val load = current.loads.firstOrNull { it.id == loadId } ?: return
        if (current.driverMode || current.permissions.none {
                it in DISPATCH_SCHEDULE_PERMISSIONS
            } ||
            !current.canStartCommandForCall()
        ) {
            return
        }
        if (stopOrder.size != load.stops.size ||
            stopOrder.toSet() != load.stops.map { it.fulfillmentId }.toSet()
        ) {
            return
        }
        val body = try {
            deliveryLoadReorderBody(stopOrder, reason)
        } catch (
            _: IllegalArgumentException
        ) {
            return
        }
        issue(DeliveryLoadCommandAction.REORDER, load, body, currentContext)
    }

    fun retrySameCommand() {
        val currentContext = context ?: return
        val command = pending ?: return
        val current = state.value
        if (!metadataAvailable || !current.hasRecoverableCommand ||
            command.driverCommand != current.driverMode ||
            current.status !in
            setOf(DeliveryLoadScreenStatus.Current, DeliveryLoadScreenStatus.Empty) ||
            current.commandStatus !in
            setOf(
                DeliveryLoadCommandStatus.UnknownOutcome,
                DeliveryLoadCommandStatus.Changed
            ) ||
            !isCurrent(generation, currentContext)
        ) {
            return
        }
        mutableState.update {
            it.copy(commandStatus = DeliveryLoadCommandStatus.Pending, failureCode = null)
        }
        val request = generation
        viewModelScope.launch {
            submit(
                command.copy(status = DeliveryLoadCommandIntentStatus.UnknownOutcome),
                request,
                currentContext
            )
        }
    }

    private fun issueForLoad(loadId: String, action: DeliveryLoadCommandAction) {
        val currentContext = context ?: return
        val current = state.value
        val load = current.loads.firstOrNull { it.id == loadId } ?: return
        val allowed = when (action) {
            DeliveryLoadCommandAction.OFFER -> current.canOffer(load)
            DeliveryLoadCommandAction.CONFIRM_HANDOFF -> current.canConfirmHandoff(load)
            DeliveryLoadCommandAction.ACCEPT -> current.canAccept(load)
            else -> false
        }
        if (!allowed) return
        issue(action, load, null, currentContext)
    }

    private fun issue(
        action: DeliveryLoadCommandAction,
        load: DeliveryLoad?,
        body: String?,
        currentContext: DispatchAuthorityContext,
        targetId: String? = null,
        targetVersion: Long? = null
    ) {
        val currentScope = scope ?: return
        if (!metadataAvailable || state.value.unresolvedCommandForOtherMode) return
        val command = DeliveryLoadCommand(
            scope = currentScope,
            driverCommand = action == DeliveryLoadCommandAction.ACCEPT,
            action = action,
            loadId = targetId ?: load?.id,
            expectedVersion = targetVersion ?: load?.version,
            idempotencyKey = keyFactory(),
            frozenBody = body,
            status = DeliveryLoadCommandIntentStatus.Pending
        )
        mutableState.update {
            it.copy(
                commandStatus = DeliveryLoadCommandStatus.Persisting,
                commandAction = action,
                hasRecoverableCommand = false,
                failureCode = null
            )
        }
        val request = generation
        viewModelScope.launch {
            when (safeSave(command)) {
                DeliveryLoadCommandMetadataWrite.Saved -> {
                    pending = command
                    mutableState.update {
                        it.copy(commandStatus = DeliveryLoadCommandStatus.Pending)
                    }
                    submit(command, request, currentContext)
                }

                else -> {
                    metadataAvailable = false
                    mutableState.update {
                        it.copy(commandStatus = DeliveryLoadCommandStatus.PersistenceUnavailable)
                    }
                }
            }
        }
    }

    private suspend fun submit(
        command: DeliveryLoadCommand,
        request: Long,
        currentContext: DispatchAuthorityContext
    ) {
        if (!isCurrent(request, currentContext)) return
        when (val result = safeMutate(command, currentContext)) {
            is DeliveryLoadGatewayResult.Changed -> {
                if (!isCurrent(request, currentContext)) return
                if (!result.load.matches(command, state.value.membershipId)) {
                    markUnknown(command, request, currentContext)
                    return
                }
                val cleared = safeClear(command.scope, command.idempotencyKey)
                if (cleared == DeliveryLoadCommandMetadataWrite.Saved) pending = null
                mutableState.update { current ->
                    val loads = current.loads.filterNot { it.id == result.load.id } + result.load
                    current.copy(
                        loads = loads.sortedBy(DeliveryLoad::id),
                        commandStatus = DeliveryLoadCommandStatus.Changed,
                        hasRecoverableCommand = cleared != DeliveryLoadCommandMetadataWrite.Saved,
                        failureCode = null
                    )
                }
            }

            is DeliveryLoadGatewayResult.Failed -> if (result.unknownOutcome) {
                markUnknown(command, request, currentContext, result.code)
            } else {
                if (!isCurrent(request, currentContext)) return
                val cleared = safeClear(command.scope, command.idempotencyKey)
                if (cleared == DeliveryLoadCommandMetadataWrite.Saved) pending = null
                mutableState.update {
                    it.copy(
                        commandStatus = DeliveryLoadCommandStatus.Rejected,
                        hasRecoverableCommand = cleared != DeliveryLoadCommandMetadataWrite.Saved,
                        failureCode = result.code
                    )
                }
            }

            is DeliveryLoadGatewayResult.WindowPlanned -> {
                if (command.action != DeliveryLoadCommandAction.PLAN_WINDOW ||
                    command.loadId != result.fulfillmentId
                ) {
                    markUnknown(command, request, currentContext)
                    return
                }
                val cleared = safeClear(command.scope, command.idempotencyKey)
                if (cleared == DeliveryLoadCommandMetadataWrite.Saved) pending = null
                mutableState.update {
                    it.copy(
                        commandStatus = DeliveryLoadCommandStatus.Changed,
                        hasRecoverableCommand = cleared != DeliveryLoadCommandMetadataWrite.Saved,
                        windowPlanFulfillmentId = null,
                        failureCode = null
                    )
                }
                loadCurrent(request, currentContext, state.value.driverMode)
            }

            is DeliveryLoadGatewayResult.Loaded -> markUnknown(command, request, currentContext)
        }
    }

    private suspend fun markUnknown(
        command: DeliveryLoadCommand,
        request: Long,
        currentContext: DispatchAuthorityContext,
        code: String? = null
    ) {
        if (!isCurrent(request, currentContext)) return
        val unknown = command.copy(status = DeliveryLoadCommandIntentStatus.UnknownOutcome)
        safeSave(unknown)
        pending = unknown
        mutableState.update {
            it.copy(
                commandStatus = DeliveryLoadCommandStatus.UnknownOutcome,
                hasRecoverableCommand = true,
                failureCode = code
            )
        }
        loadCurrent(request, currentContext, state.value.driverMode)
    }

    private suspend fun loadCurrent(
        request: Long,
        currentContext: DispatchAuthorityContext,
        driverMode: Boolean
    ) {
        when (val result = safeCurrent(currentContext, driverMode)) {
            is DeliveryLoadGatewayResult.Loaded -> {
                if (!isCurrent(request, currentContext)) return
                val ids = result.loads.map { it.id.lowercase() }
                val candidateIds = result.readyCandidates.map { it.fulfillmentId.lowercase() }
                val malformed =
                    ids.size != ids.toSet().size ||
                        candidateIds.size != candidateIds.toSet().size ||
                        result.readyCandidates.any { !it.isCreateCandidate() }
                if (malformed) {
                    mutableState.update {
                        it.copy(
                            status = DeliveryLoadScreenStatus.Failed,
                            loads = emptyList(),
                            readyCandidates = emptyList()
                        )
                    }
                    return
                }
                mutableState.update {
                    it.copy(
                        status = if (result.loads.isEmpty() && result.readyCandidates.isEmpty()) {
                            DeliveryLoadScreenStatus.Empty
                        } else {
                            DeliveryLoadScreenStatus.Current
                        },
                        loads = result.loads,
                        readyCandidates = result.readyCandidates,
                        drivers = result.drivers,
                        selectedFulfillmentIds = it.selectedFulfillmentIds.filter { id ->
                            result.readyCandidates.any { candidate ->
                                candidate.fulfillmentId == id
                            }
                        },
                        windowPlanFulfillmentId = it.windowPlanFulfillmentId?.takeIf { id ->
                            result.readyCandidates.any { candidate ->
                                candidate.fulfillmentId == id &&
                                    candidate.ready &&
                                    candidate.fulfillmentStatus == "READY_FOR_DISPATCH" &&
                                    candidate.windowStart == null && candidate.windowEnd == null &&
                                    candidate.windowSource == null
                            }
                        },
                        selectedDriverMembershipId = it.selectedDriverMembershipId?.takeIf { id ->
                            result.drivers.any { candidate -> candidate.membershipId == id }
                        }
                    )
                }
            }

            is DeliveryLoadGatewayResult.Failed -> {
                if (!isCurrent(request, currentContext)) return
                val accessFailure = result.code in ACCESS_INVALID_CODES
                mutableState.update {
                    it.copy(
                        status = when (result.code) {
                            "ACCESS_CONTEXT_INVALID" -> DeliveryLoadScreenStatus.ContextInvalidated
                            "SESSION_INVALIDATED" -> DeliveryLoadScreenStatus.SessionInvalidated
                            "FORBIDDEN" -> DeliveryLoadScreenStatus.PermissionDenied
                            else -> DeliveryLoadScreenStatus.Failed
                        },
                        loads = emptyList(),
                        readyCandidates = emptyList(),
                        drivers = emptyList(),
                        failureCode = result.code
                    )
                }
                if (accessFailure) {
                    context = null
                    pending = null
                }
            }

            is DeliveryLoadGatewayResult.Changed -> {
                if (!isCurrent(request, currentContext)) return
                mutableState.update {
                    it.copy(
                        status = DeliveryLoadScreenStatus.Failed,
                        failureCode = "INVALID_RESPONSE"
                    )
                }
            }

            is DeliveryLoadGatewayResult.WindowPlanned -> {
                if (!isCurrent(request, currentContext)) return
                mutableState.update {
                    it.copy(
                        status = DeliveryLoadScreenStatus.Failed,
                        failureCode = "INVALID_RESPONSE"
                    )
                }
            }
        }
    }

    private suspend fun safeCurrent(currentContext: DispatchAuthorityContext, driverMode: Boolean) =
        try {
            gateway.current(currentContext, driverMode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DeliveryLoadGatewayResult.Failed("SERVICE_UNAVAILABLE")
        }

    private suspend fun safeMutate(
        command: DeliveryLoadCommand,
        currentContext: DispatchAuthorityContext
    ) = try {
        gateway.mutate(command, currentContext)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DeliveryLoadGatewayResult.Failed("UNKNOWN_OUTCOME", true)
    }

    private suspend fun safeRead(currentScope: DeliveryLoadScopeIdentity) = try {
        metadata.load(currentScope)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DeliveryLoadCommandMetadataRead.Unavailable
    }

    private suspend fun safeSave(command: DeliveryLoadCommand) = try {
        metadata.save(command)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DeliveryLoadCommandMetadataWrite.Unavailable
    }

    private suspend fun safeClear(currentScope: DeliveryLoadScopeIdentity, key: String) = try {
        metadata.clear(currentScope, key)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DeliveryLoadCommandMetadataWrite.Unavailable
    }

    private fun isCurrent(request: Long, expectedContext: DispatchAuthorityContext) =
        generation == request && context == expectedContext &&
            state.value.authorityEpoch == expectedContext.authorityEpoch

    private companion object {
        val DISPATCH_SCHEDULE_PERMISSIONS = setOf("dispatch.schedule", "logistics:write")
        val ACCESS_INVALID_CODES = setOf("ACCESS_CONTEXT_INVALID", "SESSION_INVALIDATED")
    }
}

private fun DispatchReadiness.isCreateCandidate(): Boolean =
    subjectKind == "PREPARED_FULFILLMENT" && ready && fulfillmentStatus == "READY_FOR_DISPATCH" &&
        fulfillmentVersion >= 0 && physicalAllocationVersion >= 0 && deliveryId == null &&
        reasons.isEmpty()

private fun DeliveryLoadCommand.canRetryAs(mode: Boolean) = driverCommand == mode

private fun DeliveryLoadUiState.canStartCommandForCall() =
    !hasRecoverableCommand && !unresolvedCommandForOtherMode && commandStatus !in setOf(
        DeliveryLoadCommandStatus.Persisting,
        DeliveryLoadCommandStatus.Pending,
        DeliveryLoadCommandStatus.UnknownOutcome,
        DeliveryLoadCommandStatus.PersistenceUnavailable
    )

private fun DeliveryLoad.matches(command: DeliveryLoadCommand, membershipId: String?): Boolean {
    if (command.action == DeliveryLoadCommandAction.CREATE && command.loadId != null) return false
    if (command.loadId != null && id != command.loadId) return false
    val expectedVersion = command.expectedVersion
    if (expectedVersion != null && version < expectedVersion) return false
    return when (command.action) {
        DeliveryLoadCommandAction.CREATE -> {
            val body = command.frozenBody ?: return false
            val decoded =
                runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                    ?: return false
            val expectedIds = runCatching {
                decoded["stopOrder"]?.let { element ->
                    (element as? kotlinx.serialization.json.JsonArray)?.map {
                        it.toString().removeSurrounding("\"")
                    }
                }
            }.getOrNull() ?: return false
            status in DELIVERY_LOAD_STATUSES && orderedStops.map { it.fulfillmentId } == expectedIds
        }

        DeliveryLoadCommandAction.REORDER -> {
            val decoded =
                runCatching {
                    Json.parseToJsonElement(command.frozenBody ?: "") as? JsonObject
                }.getOrNull()
                    ?: return false
            val expected = (decoded["stopOrder"] as? kotlinx.serialization.json.JsonArray)
                ?.map { it.toString().removeSurrounding("\"") } ?: return false
            orderedStops.map { it.fulfillmentId } == expected
        }

        DeliveryLoadCommandAction.ASSIGN -> {
            val body =
                runCatching {
                    Json.parseToJsonElement(command.frozenBody ?: "") as? JsonObject
                }.getOrNull()
                    ?: return false
            val expected =
                body["driverMembershipId"]?.toString()?.removeSurrounding("\"") ?: return false
            assignedDriverMembershipId == expected && status in setOf(
                "ASSIGNED",
                "OFFERED",
                "HANDOFF_CONFIRMED",
                "DRIVER_ACCEPTED",
                "RESPONSIBILITY_TRANSFERRED"
            )
        }

        DeliveryLoadCommandAction.OFFER ->
            status in
                setOf(
                    "OFFERED",
                    "HANDOFF_CONFIRMED",
                    "DRIVER_ACCEPTED",
                    "RESPONSIBILITY_TRANSFERRED"
                )

        DeliveryLoadCommandAction.CONFIRM_HANDOFF ->
            status in
                setOf("HANDOFF_CONFIRMED", "RESPONSIBILITY_TRANSFERRED")

        DeliveryLoadCommandAction.ACCEPT ->
            assignedDriverMembershipId == membershipId &&
                status in setOf("DRIVER_ACCEPTED", "RESPONSIBILITY_TRANSFERRED")

        DeliveryLoadCommandAction.PLAN_WINDOW -> false
    }
}
