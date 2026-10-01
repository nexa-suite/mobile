package com.nexa.mobile.operations.feature.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DispatchOutgoingGoodsViewModel(
    private val gateway: DispatchOutgoingGoodsGateway,
    private val metadata: DispatchOutgoingGoodsMetadataStore,
    private val now: () -> Instant = Instant::now,
    private val newCommandKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchOutgoingGoodsUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: DispatchAuthorityContext? = null
    private var generation = 0L
    private var pendingIntent: DispatchOutgoingGoodsIntent? = null

    fun activate(fulfillment: DispatchReadiness, context: DispatchAuthorityContext) {
        if (activeContext == context && mutableState.value.fulfillment?.fulfillmentId == fulfillment.fulfillmentId &&
            mutableState.value.status !in INVALIDATED_STATUS
        ) return
        generation++
        activeContext = context
        pendingIntent = null
        mutableState.value = DispatchOutgoingGoodsUiState(
            authorityEpoch = context.authorityEpoch,
            fulfillment = fulfillment
        )
        refresh()
    }

    fun refresh() {
        val context = activeContext ?: return
        val fulfillment = mutableState.value.fulfillment ?: return
        val scope = context.scopeIdentity() ?: run {
            fail(DispatchOutgoingGoodsStatus.ContextInvalidated)
            return
        }
        if (!context.hasCurrentManagePermission()) {
            fail(DispatchOutgoingGoodsStatus.PermissionDenied)
            return
        }
        val request = ++generation
        mutableState.value = DispatchOutgoingGoodsUiState(
            authorityEpoch = context.authorityEpoch,
            fulfillment = fulfillment,
            status = DispatchOutgoingGoodsStatus.Loading
        )
        viewModelScope.launch {
            val stored = safeMetadataCall { metadata.loadIntent(scope, fulfillment.fulfillmentId) }
            if (!isCurrent(request, context, fulfillment.fulfillmentId)) return@launch
            val intent = when (stored) {
                is DispatchOutgoingGoodsMetadataRead.Available -> stored.intent
                DispatchOutgoingGoodsMetadataRead.Unavailable -> {
                    fail(DispatchOutgoingGoodsStatus.ServiceUnavailable)
                    return@launch
                }
                null -> {
                    fail(DispatchOutgoingGoodsStatus.ServiceUnavailable)
                    return@launch
                }
            }
            if (intent != null && intent.scope != scope) {
                pendingIntent = intent
                fail(DispatchOutgoingGoodsStatus.Stale, hasPending = true)
                return@launch
            }
            pendingIntent = intent

            val result = safeGatewayCall { gateway.load(fulfillment, context) }
            if (!isCurrent(request, context, fulfillment.fulfillmentId)) return@launch
            when (result) {
                is DispatchOutgoingGoodsGatewayResult.Snapshot -> {
                    val snapshot = result.value
                    if (!snapshotMatches(snapshot, fulfillment)) {
                        fail(if (intent?.command?.type == DispatchOutgoingGoodsCommandType.ResolveDiscrepancy)
                            DispatchOutgoingGoodsStatus.UnknownOutcome else DispatchOutgoingGoodsStatus.Stale,
                            hasPending = intent != null)
                    } else if (intent != null && !intent.matchesCurrent(fulfillment, snapshot.allocation)) {
                        fail(DispatchOutgoingGoodsStatus.Stale, hasPending = true)
                    } else if (intent != null) {
                        mutableState.value = DispatchOutgoingGoodsUiState(
                            authorityEpoch = context.authorityEpoch,
                            fulfillment = fulfillment,
                            status = DispatchOutgoingGoodsStatus.UnknownOutcome,
                            allocation = snapshot.allocation.withObservations(intent.command.observations),
                            currentCheck = snapshot.currentCheck,
                            observedAt = now(),
                            hasPendingCommand = true
                        )
                    } else {
                        mutableState.value = DispatchOutgoingGoodsUiState(
                            authorityEpoch = context.authorityEpoch,
                            fulfillment = fulfillment,
                            status = DispatchOutgoingGoodsStatus.Current,
                            allocation = snapshot.allocation,
                            currentCheck = snapshot.currentCheck,
                            observedAt = now()
                        )
                    }
                }

                DispatchOutgoingGoodsGatewayResult.UnknownOutcome ->
                    fail(DispatchOutgoingGoodsStatus.UnknownOutcome, hasPending = intent != null)

                DispatchOutgoingGoodsGatewayResult.NetworkUnavailable ->
                    fail(DispatchOutgoingGoodsStatus.NetworkUnavailable, hasPending = intent != null)

                DispatchOutgoingGoodsGatewayResult.ServiceUnavailable ->
                    fail(DispatchOutgoingGoodsStatus.ServiceUnavailable, hasPending = intent != null)

                DispatchOutgoingGoodsGatewayResult.PermissionDenied ->
                    fail(DispatchOutgoingGoodsStatus.PermissionDenied, hasPending = intent != null)

                DispatchOutgoingGoodsGatewayResult.Stale ->
                    fail(if (intent?.command?.type == DispatchOutgoingGoodsCommandType.ResolveDiscrepancy)
                        DispatchOutgoingGoodsStatus.UnknownOutcome else DispatchOutgoingGoodsStatus.Stale,
                        hasPending = intent != null)

                DispatchOutgoingGoodsGatewayResult.Conflict ->
                    fail(DispatchOutgoingGoodsStatus.Conflict, hasPending = intent != null)

                DispatchOutgoingGoodsGatewayResult.ContextInvalidated -> invalidateContext()
                DispatchOutgoingGoodsGatewayResult.SessionInvalidated -> invalidateSession()
                is DispatchOutgoingGoodsGatewayResult.Recorded ->
                    fail(DispatchOutgoingGoodsStatus.ServiceUnavailable, hasPending = intent != null)
                is DispatchOutgoingGoodsGatewayResult.Resolved ->
                    fail(DispatchOutgoingGoodsStatus.ServiceUnavailable, hasPending = intent != null)
            }
        }
    }

    fun changeObservedLot(physicalAllocationLineId: String, value: String) {
        updateLine(physicalAllocationLineId) { it.copy(observedLotId = value.trim()) }
    }

    fun changeObservedQuantity(physicalAllocationLineId: String, value: String) {
        updateLine(physicalAllocationLineId) { it.copy(observedQuantity = value.trim()) }
    }

    fun changeResolutionReason(value: String) {
        val current = mutableState.value
        if (current.status != DispatchOutgoingGoodsStatus.Current || current.hasPendingCommand) return
        mutableState.value = current.copy(resolutionReason = value.take(1000))
    }

    fun record() {
        if (pendingIntent != null) return
        val current = mutableState.value
        val fulfillment = current.fulfillment ?: return
        val allocation = current.allocation ?: return
        if (!current.canSubmit) return
        val observations = allocation.lines.map { line ->
            val quantity = line.observedQuantity.toBigDecimalOrNull() ?: return
            val lotId = line.observedLotId.takeIf { it.isNotBlank() }
            DispatchOutgoingGoodsObservation(line.physicalAllocationLineId, lotId, quantity)
        }
        val context = activeContext ?: return
        val scope = context.scopeIdentity() ?: return
        if (!context.hasCurrentManagePermission()) {
            fail(DispatchOutgoingGoodsStatus.PermissionDenied)
            return
        }
        val body = observations.toRequestBody(allocation.id, allocation.version)
        val command = DispatchOutgoingGoodsCommand(
            fulfillmentId = fulfillment.fulfillmentId,
            expectedFulfillmentVersion = fulfillment.fulfillmentVersion,
            physicalAllocationId = allocation.id,
            physicalAllocationVersion = allocation.version,
            observations = observations,
            idempotencyKey = newCommandKey(),
            exactRequestBody = body
        )
        val intent = DispatchOutgoingGoodsIntent(scope, command)
        val request = ++generation
        mutableState.value = current.copy(
            status = DispatchOutgoingGoodsStatus.Submitting,
            hasPendingCommand = true
        )
        viewModelScope.launch {
            when (safeMetadataCall { metadata.saveIntent(intent) }) {
                DispatchOutgoingGoodsMetadataWrite.Saved -> {
                    if (!isCurrent(request, context, fulfillment.fulfillmentId)) return@launch
                    pendingIntent = intent
                    send(intent, context, fulfillment, allocation)
                }

                DispatchOutgoingGoodsMetadataWrite.Conflict,
                DispatchOutgoingGoodsMetadataWrite.Stale -> {
                    if (isCurrent(request, context, fulfillment.fulfillmentId)) {
                        fail(DispatchOutgoingGoodsStatus.Conflict)
                    }
                }

                DispatchOutgoingGoodsMetadataWrite.Unavailable,
                null -> {
                    if (isCurrent(request, context, fulfillment.fulfillmentId)) {
                        fail(DispatchOutgoingGoodsStatus.ServiceUnavailable)
                    }
                }
            }
        }
    }

    fun resolveDiscrepancy() {
        val current = mutableState.value
        if (!current.canResolveDiscrepancy) return
        val fulfillment = current.fulfillment ?: return
        val allocation = current.allocation ?: return
        val check = current.currentCheck ?: return
        val discrepancy = check.discrepancy ?: return
        val reason = current.resolutionReason.trim()
        val context = activeContext ?: return
        val scope = context.scopeIdentity() ?: return
        if (!context.hasCurrentManagePermission() ||
            discrepancy.physicalAllocationId != allocation.id ||
            discrepancy.physicalAllocationVersion != allocation.version
        ) {
            fail(DispatchOutgoingGoodsStatus.Stale)
            return
        }
        val key = newCommandKey()
        val body = listOf(
            "{\"physicalAllocationId\":\"${allocation.id}\"",
            "\"physicalAllocationVersion\":${allocation.version}",
            "\"discrepancyCheckId\":\"${discrepancy.id}\"",
            "\"matchingCheckId\":\"${check.id}\"",
            "\"reason\":\"${reason.jsonEscape()}\"}"
        ).joinToString(",")
        val command = DispatchOutgoingGoodsCommand(
            fulfillmentId = fulfillment.fulfillmentId,
            expectedFulfillmentVersion = fulfillment.fulfillmentVersion,
            physicalAllocationId = allocation.id,
            physicalAllocationVersion = allocation.version,
            observations = emptyList(),
            idempotencyKey = key,
            exactRequestBody = body,
            type = DispatchOutgoingGoodsCommandType.ResolveDiscrepancy,
            discrepancyCheckId = discrepancy.id,
            matchingCheckId = check.id,
            reason = reason
        )
        val intent = DispatchOutgoingGoodsIntent(scope, command)
        val request = ++generation
        mutableState.value = current.copy(status = DispatchOutgoingGoodsStatus.Submitting, hasPendingCommand = true)
        viewModelScope.launch {
            when (safeMetadataCall { metadata.saveIntent(intent) }) {
                DispatchOutgoingGoodsMetadataWrite.Saved -> {
                    if (!isCurrent(request, context, fulfillment.fulfillmentId)) return@launch
                    pendingIntent = intent
                    send(intent, context, fulfillment, allocation)
                }
                DispatchOutgoingGoodsMetadataWrite.Conflict,
                DispatchOutgoingGoodsMetadataWrite.Stale -> if (isCurrent(request, context, fulfillment.fulfillmentId)) {
                    fail(DispatchOutgoingGoodsStatus.Conflict)
                }
                DispatchOutgoingGoodsMetadataWrite.Unavailable,
                null -> if (isCurrent(request, context, fulfillment.fulfillmentId)) {
                    fail(DispatchOutgoingGoodsStatus.ServiceUnavailable)
                }
            }
        }
    }

    /** Replays only the frozen encrypted body, versions, scope, and key after an explicit tap. */
    fun retryUnknownOutcome() {
        val context = activeContext ?: return
        val fulfillment = mutableState.value.fulfillment ?: return
        val allocation = mutableState.value.allocation
        val intent = pendingIntent ?: return
        if (mutableState.value.status != DispatchOutgoingGoodsStatus.UnknownOutcome ||
            !context.hasCurrentManagePermission() ||
            intent.scope != context.scopeIdentity() ||
            (intent.command.type == DispatchOutgoingGoodsCommandType.RecordCheck &&
                (allocation == null || !intent.matchesCurrent(fulfillment, allocation))) ||
            (intent.command.type == DispatchOutgoingGoodsCommandType.ResolveDiscrepancy &&
                intent.command.fulfillmentId != fulfillment.fulfillmentId) ||
            intent.command.exactRequestBody != intent.command.toRequestBody()
        ) return
        send(intent, context, fulfillment, allocation)
    }

    fun invalidateContext() {
        generation++
        activeContext = null
        mutableState.value = DispatchOutgoingGoodsUiState(
            authorityEpoch = mutableState.value.authorityEpoch,
            status = DispatchOutgoingGoodsStatus.ContextInvalidated
        )
    }

    fun invalidateSession() {
        generation++
        activeContext = null
        mutableState.value = DispatchOutgoingGoodsUiState(
            authorityEpoch = mutableState.value.authorityEpoch,
            status = DispatchOutgoingGoodsStatus.SessionInvalidated
        )
    }

    fun deactivate() {
        generation++
        activeContext = null
        mutableState.value = DispatchOutgoingGoodsUiState()
    }

    private fun send(
        intent: DispatchOutgoingGoodsIntent,
        context: DispatchAuthorityContext,
        fulfillment: DispatchReadiness,
        allocation: DispatchOutgoingGoodsAllocation?
    ) {
        val command = intent.command
        if (!context.hasCurrentManagePermission() || command.fulfillmentId != fulfillment.fulfillmentId ||
            (command.type == DispatchOutgoingGoodsCommandType.RecordCheck &&
                (allocation == null || !intent.matchesCurrent(fulfillment, allocation)))
        ) {
            fail(DispatchOutgoingGoodsStatus.Stale, hasPending = true)
            return
        }
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            status = DispatchOutgoingGoodsStatus.Submitting,
            hasPendingCommand = true
        )
        viewModelScope.launch {
            val result = safeGatewayCall {
                if (command.type == DispatchOutgoingGoodsCommandType.ResolveDiscrepancy) {
                    gateway.resolveDiscrepancy(fulfillment, command, context)
                } else {
                    gateway.record(fulfillment, requireNotNull(allocation), command, context)
                }
            }
            if (!isCurrent(request, context, fulfillment.fulfillmentId)) return@launch
            when (result) {
                is DispatchOutgoingGoodsGatewayResult.Recorded -> {
                    val check = result.value
                    if (!check.current || check.fulfillmentId != command.fulfillmentId ||
                        check.fulfillmentVersion != command.expectedFulfillmentVersion ||
                        check.physicalAllocationId != command.physicalAllocationId ||
                        check.physicalAllocationVersion != command.physicalAllocationVersion
                    ) {
                        fail(DispatchOutgoingGoodsStatus.UnknownOutcome, hasPending = true)
                    } else {
                        val cleared = safeMetadataCall {
                            metadata.clearIntent(
                                intent.scope,
                                command.fulfillmentId,
                                command.idempotencyKey
                            )
                        } == DispatchOutgoingGoodsMetadataWrite.Saved
                        if (cleared) pendingIntent = null
                        mutableState.value = mutableState.value.copy(
                            status = DispatchOutgoingGoodsStatus.Current,
                            currentCheck = check,
                            observedAt = now(),
                            hasPendingCommand = !cleared
                        )
                    }
                }

                is DispatchOutgoingGoodsGatewayResult.Resolved -> {
                    val resolution = result.value
                    if (resolution.fulfillmentId != command.fulfillmentId ||
                        resolution.fulfillmentVersion != command.expectedFulfillmentVersion ||
                        resolution.physicalAllocationId != command.physicalAllocationId ||
                        resolution.physicalAllocationVersion != command.physicalAllocationVersion ||
                        resolution.discrepancyCheckId != command.discrepancyCheckId ||
                        resolution.matchingCheckId != command.matchingCheckId ||
                        resolution.actorMembershipId != context.identity?.membershipId
                    ) {
                        fail(DispatchOutgoingGoodsStatus.UnknownOutcome, hasPending = true)
                    } else {
                        val cleared = safeMetadataCall {
                            metadata.clearIntent(intent.scope, command.fulfillmentId, command.idempotencyKey)
                        } == DispatchOutgoingGoodsMetadataWrite.Saved
                        if (cleared) pendingIntent = null
                        val latest = mutableState.value
                        mutableState.value = latest.copy(
                            status = DispatchOutgoingGoodsStatus.Current,
                            currentCheck = if (resolution.current) latest.currentCheck?.copy(
                                openDiscrepancy = false,
                                discrepancy = null
                            ) else latest.currentCheck,
                            currentResolution = resolution,
                            hasPendingCommand = !cleared
                        )
                    }
                }

                DispatchOutgoingGoodsGatewayResult.UnknownOutcome -> setPendingStatus(
                    DispatchOutgoingGoodsStatus.UnknownOutcome
                )

                DispatchOutgoingGoodsGatewayResult.NetworkUnavailable -> setPendingStatus(
                    DispatchOutgoingGoodsStatus.NetworkUnavailable
                )

                DispatchOutgoingGoodsGatewayResult.ServiceUnavailable -> setPendingStatus(
                    DispatchOutgoingGoodsStatus.ServiceUnavailable
                )

                DispatchOutgoingGoodsGatewayResult.PermissionDenied ->
                    fail(DispatchOutgoingGoodsStatus.PermissionDenied, hasPending = true)

                DispatchOutgoingGoodsGatewayResult.Stale ->
                    fail(DispatchOutgoingGoodsStatus.Stale, hasPending = true)

                DispatchOutgoingGoodsGatewayResult.Conflict ->
                    fail(DispatchOutgoingGoodsStatus.Conflict, hasPending = true)

                DispatchOutgoingGoodsGatewayResult.ContextInvalidated -> invalidateContext()
                DispatchOutgoingGoodsGatewayResult.SessionInvalidated -> invalidateSession()
                is DispatchOutgoingGoodsGatewayResult.Snapshot ->
                    fail(DispatchOutgoingGoodsStatus.ServiceUnavailable, hasPending = true)
            }
        }
    }

    private fun updateLine(
        id: String,
        update: (DispatchOutgoingGoodsLine) -> DispatchOutgoingGoodsLine
    ) {
        val current = mutableState.value
        if (current.status != DispatchOutgoingGoodsStatus.Current || current.hasPendingCommand) return
        val allocation = current.allocation ?: return
        mutableState.value = current.copy(
            allocation = allocation.copy(lines = allocation.lines.map {
                if (it.physicalAllocationLineId == id) update(it) else it
            })
        )
    }

    private fun setPendingStatus(status: DispatchOutgoingGoodsStatus) {
        mutableState.value = mutableState.value.copy(status = status, hasPendingCommand = true)
    }

    private fun fail(status: DispatchOutgoingGoodsStatus, hasPending: Boolean = false) {
        mutableState.value = DispatchOutgoingGoodsUiState(
            authorityEpoch = mutableState.value.authorityEpoch,
            fulfillment = mutableState.value.fulfillment,
            status = status,
            hasPendingCommand = hasPending
        )
    }

    private suspend fun safeGatewayCall(block: suspend () -> DispatchOutgoingGoodsGatewayResult) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchOutgoingGoodsGatewayResult.ServiceUnavailable
    }

    private suspend fun <T> safeMetadataCall(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun DispatchAuthorityContext.hasCurrentManagePermission(): Boolean {
        val identity = identity ?: return false
        return authorityEpoch > 0 && listOf(
            identity.userId,
            identity.tenantId,
            identity.workspaceId,
            identity.membershipId
        ).none(String::isBlank) && "fulfillment.manage" in identity.permissions
    }

    private fun DispatchAuthorityContext.scopeIdentity(): DispatchOutgoingGoodsScopeIdentity? {
        val identity = identity ?: return null
        if (authorityEpoch <= 0 || listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).any(String::isBlank)
        ) return null
        return DispatchOutgoingGoodsScopeIdentity(
            identity.userId,
            identity.tenantId,
            identity.workspaceId,
            identity.membershipId
        )
    }

    private fun snapshotMatches(
        snapshot: DispatchOutgoingGoodsSnapshot,
        fulfillment: DispatchReadiness
    ): Boolean = snapshot.allocation.id == fulfillment.physicalAllocationId &&
        snapshot.allocation.version == fulfillment.physicalAllocationVersion &&
        snapshot.currentCheck?.let {
            it.fulfillmentId != fulfillment.fulfillmentId ||
                it.fulfillmentVersion > fulfillment.fulfillmentVersion ||
                it.physicalAllocationId != snapshot.allocation.id ||
                it.physicalAllocationVersion != snapshot.allocation.version
        } != true

    private fun DispatchOutgoingGoodsIntent.matchesCurrent(
        fulfillment: DispatchReadiness,
        allocation: DispatchOutgoingGoodsAllocation
    ): Boolean = command.fulfillmentId == fulfillment.fulfillmentId &&
        command.expectedFulfillmentVersion == fulfillment.fulfillmentVersion &&
        command.physicalAllocationId == allocation.id &&
        command.physicalAllocationVersion == allocation.version

    private fun DispatchOutgoingGoodsAllocation.withObservations(
        observations: List<DispatchOutgoingGoodsObservation>
    ): DispatchOutgoingGoodsAllocation {
        val byLine = observations.associateBy { it.physicalAllocationLineId }
        return copy(lines = lines.map { line ->
            val observed = byLine[line.physicalAllocationLineId] ?: return@map line
            line.copy(
                observedLotId = observed.observedLotId.orEmpty(),
                observedQuantity = observed.observedQuantity.toPlainString()
            )
        })
    }

    private fun DispatchOutgoingGoodsCommand.toRequestBody(): String =
        if (type == DispatchOutgoingGoodsCommandType.ResolveDiscrepancy) {
            "{\"physicalAllocationId\":\"$physicalAllocationId\",\"physicalAllocationVersion\":$physicalAllocationVersion," +
                "\"discrepancyCheckId\":\"${discrepancyCheckId.orEmpty()}\",\"matchingCheckId\":\"${matchingCheckId.orEmpty()}\"," +
                "\"reason\":\"${reason.orEmpty().jsonEscape()}\"}"
        } else {
            observations.toRequestBody(physicalAllocationId, physicalAllocationVersion)
        }

    private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")

    private fun List<DispatchOutgoingGoodsObservation>.toRequestBody(
        allocationId: String,
        allocationVersion: Long
    ): String = buildString {
        append("{\"physicalAllocationId\":\"").append(allocationId)
            .append("\",\"physicalAllocationVersion\":").append(allocationVersion)
            .append(",\"observations\":[")
        this@toRequestBody.forEachIndexed { index, observation ->
            if (index > 0) append(',')
            append("{\"physicalAllocationLineId\":\"").append(observation.physicalAllocationLineId)
                .append("\",\"observedLotId\":")
            val lotId = observation.observedLotId
            if (lotId == null) append("null") else append('"').append(lotId).append('"')
            append(",\"observedQuantity\":").append(observation.observedQuantity.toPlainString()).append('}')
        }
        append("]}")
    }

    private fun isCurrent(request: Long, context: DispatchAuthorityContext, fulfillmentId: String): Boolean =
        request == generation && activeContext == context &&
            mutableState.value.authorityEpoch == context.authorityEpoch &&
            mutableState.value.fulfillment?.fulfillmentId == fulfillmentId

    private companion object {
        val INVALIDATED_STATUS = setOf(
            DispatchOutgoingGoodsStatus.ContextInvalidated,
            DispatchOutgoingGoodsStatus.SessionInvalidated
        )
    }
}
