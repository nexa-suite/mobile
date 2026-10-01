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

class DispatchTemperatureViewModel(
    private val gateway: DispatchTemperatureGateway,
    private val metadata: DispatchTemperatureMetadataStore,
    private val now: () -> Instant = Instant::now,
    private val newCommandKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchTemperatureUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: DispatchAuthorityContext? = null
    private var activeFulfillmentId: String? = null
    private var pendingIntent: DispatchTemperatureIntent? = null
    private var generation = 0L

    fun activate(fulfillmentId: String, context: DispatchAuthorityContext) {
        if (activeFulfillmentId == fulfillmentId && activeContext == context &&
            mutableState.value.status !in INVALIDATED
        ) return
        generation++
        val request = generation
        activeFulfillmentId = fulfillmentId
        activeContext = context
        pendingIntent = null
        mutableState.value = DispatchTemperatureUiState(
            authorityEpoch = context.authorityEpoch,
            fulfillmentId = fulfillmentId,
            canRecord = context.hasFulfillmentManagePermission()
        )
        viewModelScope.launch {
            val scope = context.scopeIdentity()
            if (scope == null) {
                if (request == generation) invalidateContext()
                return@launch
            }
            when (val stored = safe { metadata.loadIntent(scope, fulfillmentId) }) {
                is DispatchTemperatureMetadataRead.Available -> {
                    val intent = stored.intent
                    if (intent != null && (intent.scope != scope || !intent.command.isValid())) {
                        if (request == generation) fail(DispatchTemperatureStatus.ServiceUnavailable)
                        return@launch
                    }
                    if (request == generation && activeContext == context) {
                        pendingIntent = intent
                        mutableState.value = mutableState.value.copy(
                            metadataReady = true,
                            hasPendingCommand = intent != null,
                            pendingCommand = intent?.command,
                            mutationStatus = if (intent == null) DispatchTemperatureMutationStatus.Idle
                            else DispatchTemperatureMutationStatus.UnknownOutcome,
                            mutationLotId = intent?.command?.lotId
                        )
                        refresh()
                    }
                }
                DispatchTemperatureMetadataRead.Unavailable,
                null -> {
                    if (request == generation && activeContext == context) {
                        mutableState.value = mutableState.value.copy(metadataReady = false)
                        refresh()
                    }
                }
            }
        }
    }

    /** Refresh only the server view. It deliberately preserves any unresolved exact command. */
    fun refresh() {
        val context = activeContext ?: return
        val fulfillmentId = activeFulfillmentId ?: return
        when (context.readPermissionHint()) {
            PermissionHint.Unknown -> {
                fail(DispatchTemperatureStatus.PermissionUnknown)
                return
            }
            PermissionHint.Unavailable -> {
                fail(DispatchTemperatureStatus.PermissionDenied)
                return
            }
            PermissionHint.Available -> Unit
        }
        if (mutableState.value.mutationStatus == DispatchTemperatureMutationStatus.Submitting) return
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            status = DispatchTemperatureStatus.Loading,
            readiness = null,
            observedAt = null
        )
        viewModelScope.launch {
            when (val result = safe { gateway.current(fulfillmentId, context) }) {
                is DispatchTemperatureGatewayResult.Current -> {
                    if (!result.readiness.fulfillmentId.equals(fulfillmentId, ignoreCase = true) ||
                        result.readiness.temperatureRequiredForFulfillment
                    ) {
                        failIfCurrent(request, context, DispatchTemperatureStatus.ServiceUnavailable)
                    } else if (isCurrent(request, context)) {
                        mutableState.value = mutableState.value.copy(
                            status = DispatchTemperatureStatus.Current,
                            readiness = result.readiness,
                            observedAt = now(),
                            canRecord = context.hasFulfillmentManagePermission()
                        )
                    }
                }
                DispatchTemperatureGatewayResult.OutsideRangeBackendContractGap,
                DispatchTemperatureGatewayResult.UnknownOutcome,
                DispatchTemperatureGatewayResult.ServiceUnavailable,
                is DispatchTemperatureGatewayResult.Recorded ->
                    failIfCurrent(request, context, DispatchTemperatureStatus.ServiceUnavailable)
                DispatchTemperatureGatewayResult.NetworkUnavailable ->
                    failIfCurrent(request, context, DispatchTemperatureStatus.NetworkUnavailable)
                DispatchTemperatureGatewayResult.PermissionDenied ->
                    failIfCurrent(request, context, DispatchTemperatureStatus.PermissionDenied)
                DispatchTemperatureGatewayResult.ContextInvalidated -> invalidateContextIfCurrent(request)
                DispatchTemperatureGatewayResult.SessionInvalidated -> invalidateSessionIfCurrent(request)
                DispatchTemperatureGatewayResult.Stale,
                DispatchTemperatureGatewayResult.Conflict,
                null -> failIfCurrent(request, context, DispatchTemperatureStatus.ServiceUnavailable)
            }
        }
    }

    fun updateValue(lotId: String, valueCelsius: String) {
        val state = mutableState.value
        if (state.status != DispatchTemperatureStatus.Current || state.hasPendingCommand ||
            state.readiness?.lots?.none { it.lotId == lotId } != false
        ) return
        mutableState.value = state.copy(valuesCelsius = state.valuesCelsius + (lotId to valueCelsius))
    }

    fun record(lotId: String) {
        val context = activeContext ?: return
        val scope = context.scopeIdentity() ?: return invalidateContext()
        val readiness = mutableState.value.readiness ?: return
        val current = mutableState.value
        if (current.status != DispatchTemperatureStatus.Current || !current.metadataReady ||
            current.hasPendingCommand || pendingIntent != null ||
            current.mutationStatus == DispatchTemperatureMutationStatus.Submitting ||
            !context.hasFulfillmentManagePermission()
        ) return
        val lot = readiness.lots.firstOrNull { it.lotId == lotId } ?: return
        if (!lot.supportsInRangeEvidence) return
        val value = current.valuesCelsius[lotId]?.trim()?.toBigDecimalOrNull() ?: run {
            mutableState.value = current.copy(
                mutationStatus = DispatchTemperatureMutationStatus.ServiceUnavailable,
                mutationLotId = lotId
            )
            return
        }
        if (!lot.isWithinRange(value)) {
            mutableState.value = current.copy(
                mutationStatus = DispatchTemperatureMutationStatus.OutsideRangeBackendContractGap,
                mutationLotId = lotId
            )
            return
        }
        val partial = DispatchTemperatureCommand(
            fulfillmentId = readiness.fulfillmentId,
            expectedFulfillmentVersion = readiness.fulfillmentVersion,
            lotId = lotId,
            valueCelsius = value,
            occurredAt = now(),
            idempotencyKey = newCommandKey(),
            exactRequestBody = ""
        )
        val command = partial.copy(exactRequestBody = partial.buildRequestBody())
        if (!command.isValid()) return
        val intent = DispatchTemperatureIntent(scope, command)
        val request = ++generation
        mutableState.value = current.copy(
            mutationStatus = DispatchTemperatureMutationStatus.Submitting,
            mutationLotId = lotId,
            hasPendingCommand = true,
            pendingCommand = command
        )
        viewModelScope.launch {
            when (safe { metadata.saveIntent(intent) }) {
                DispatchTemperatureMetadataWrite.Saved -> {
                    if (!isCurrent(request, context)) return@launch
                    pendingIntent = intent
                    send(intent, context, request)
                }
                DispatchTemperatureMetadataWrite.Conflict,
                DispatchTemperatureMetadataWrite.Stale -> {
                    if (isCurrent(request, context)) {
                        mutableState.value = mutableState.value.copy(
                            mutationStatus = DispatchTemperatureMutationStatus.Conflict,
                            hasPendingCommand = false,
                            pendingCommand = null
                        )
                    }
                }
                DispatchTemperatureMetadataWrite.Unavailable,
                null -> {
                    if (isCurrent(request, context)) {
                        mutableState.value = mutableState.value.copy(
                            mutationStatus = DispatchTemperatureMutationStatus.ServiceUnavailable,
                            hasPendingCommand = false,
                            pendingCommand = null
                        )
                    }
                }
            }
        }
    }

    /** Replays the frozen body/key/version only; it never creates a new reading. */
    fun retryUnknownOutcome() {
        val context = activeContext ?: return
        val scope = context.scopeIdentity() ?: return invalidateContext()
        val intent = pendingIntent ?: return
        if (!mutableState.value.canRetryUnknownOutcome || intent.status != DispatchTemperatureIntentStatus.UnknownOutcome ||
            intent.scope != scope || !intent.command.isValid() ||
            intent.command.fulfillmentId != activeFulfillmentId
        ) return
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            mutationStatus = DispatchTemperatureMutationStatus.Submitting,
            mutationLotId = intent.command.lotId,
            hasPendingCommand = true,
            pendingCommand = intent.command
        )
        viewModelScope.launch {
            when (safe { metadata.saveIntent(intent) }) {
                DispatchTemperatureMetadataWrite.Saved -> {
                    if (!isCurrent(request, context)) return@launch
                    send(intent, context, request)
                }
                DispatchTemperatureMetadataWrite.Conflict,
                DispatchTemperatureMetadataWrite.Stale -> if (isCurrent(request, context)) {
                    mutableState.value = mutableState.value.copy(
                        mutationStatus = DispatchTemperatureMutationStatus.Conflict
                    )
                }
                DispatchTemperatureMetadataWrite.Unavailable,
                null -> if (isCurrent(request, context)) {
                    mutableState.value = mutableState.value.copy(
                        mutationStatus = DispatchTemperatureMutationStatus.ServiceUnavailable
                    )
                }
            }
        }
    }

    fun deactivate() {
        generation++
        activeContext = null
        activeFulfillmentId = null
        pendingIntent = null
        mutableState.value = DispatchTemperatureUiState()
    }

    private fun send(intent: DispatchTemperatureIntent, context: DispatchAuthorityContext, request: Long) {
        viewModelScope.launch {
            val result = safe { gateway.record(intent.command, context) }
            if (!isCurrent(request, context)) return@launch
            when (result) {
                is DispatchTemperatureGatewayResult.Recorded -> {
                    val evidence = result.evidence
                    val command = intent.command
                    if (!evidence.fulfillmentId.equals(command.fulfillmentId, ignoreCase = true) ||
                        evidence.fulfillmentVersion != command.expectedFulfillmentVersion ||
                        !evidence.lotId.equals(command.lotId, ignoreCase = true) ||
                        evidence.valueCelsius.compareTo(command.valueCelsius) != 0 ||
                        evidence.occurredAt != command.occurredAt
                    ) {
                        markUnknown(intent, context, request)
                    } else {
                        val cleared = metadata.clearIntent(intent.scope, command.fulfillmentId, command.idempotencyKey)
                        if (cleared == DispatchTemperatureMetadataWrite.Saved) {
                            pendingIntent = null
                            if (isCurrent(request, context)) {
                                val currentReadiness = mutableState.value.readiness
                                mutableState.value = mutableState.value.copy(
                                    readiness = currentReadiness?.copy(lots = currentReadiness.lots.map { currentLot ->
                                        if (currentLot.lotId == command.lotId) currentLot.copy(
                                            status = "OPTIONAL_WITHIN_RANGE",
                                            latestEvidence = evidence
                                        ) else currentLot
                                    }),
                                    mutationStatus = DispatchTemperatureMutationStatus.Recorded,
                                    mutationLotId = command.lotId,
                                    hasPendingCommand = false,
                                    pendingCommand = null
                                )
                            }
                        } else {
                            markUnknown(intent.copy(status = DispatchTemperatureIntentStatus.UnknownOutcome),
                                context, request)
                            if (isCurrent(request, context)) {
                                mutableState.value = mutableState.value.copy(
                                    mutationStatus = DispatchTemperatureMutationStatus.Recorded
                                )
                            }
                        }
                    }
                }
                DispatchTemperatureGatewayResult.UnknownOutcome,
                DispatchTemperatureGatewayResult.NetworkUnavailable,
                null -> markUnknown(intent, context, request)
                DispatchTemperatureGatewayResult.ContextInvalidated -> {
                    markUnknown(intent, context, request)
                    if (isCurrent(request, context)) invalidateContext()
                }
                DispatchTemperatureGatewayResult.SessionInvalidated -> {
                    markUnknown(intent, context, request)
                    if (isCurrent(request, context)) invalidateSession()
                }
                DispatchTemperatureGatewayResult.OutsideRangeBackendContractGap ->
                    clearKnownRejection(intent, context, request,
                        DispatchTemperatureMutationStatus.OutsideRangeBackendContractGap)
                DispatchTemperatureGatewayResult.Stale -> {
                    val cleared = clearKnownRejection(intent, context, request, DispatchTemperatureMutationStatus.Stale)
                    if (cleared) {
                        mutableState.value = mutableState.value.copy(
                            valuesCelsius = mutableState.value.valuesCelsius - intent.command.lotId
                        )
                        refresh()
                    }
                }
                DispatchTemperatureGatewayResult.Conflict -> {
                    val cleared = clearKnownRejection(intent, context, request, DispatchTemperatureMutationStatus.Conflict)
                    if (cleared) refresh()
                }
                DispatchTemperatureGatewayResult.PermissionDenied ->
                    clearKnownRejection(intent, context, request, DispatchTemperatureMutationStatus.PermissionDenied)
                DispatchTemperatureGatewayResult.ServiceUnavailable ->
                    clearKnownRejection(intent, context, request, DispatchTemperatureMutationStatus.ServiceUnavailable)
                is DispatchTemperatureGatewayResult.Current ->
                    markUnknown(intent, context, request)
            }
        }
    }

    private suspend fun clearKnownRejection(
        intent: DispatchTemperatureIntent,
        context: DispatchAuthorityContext,
        request: Long,
        status: DispatchTemperatureMutationStatus
    ): Boolean {
        val cleared = metadata.clearIntent(
            intent.scope,
            intent.command.fulfillmentId,
            intent.command.idempotencyKey
        ) == DispatchTemperatureMetadataWrite.Saved
        if (cleared) pendingIntent = null else {
            markUnknown(intent.copy(status = DispatchTemperatureIntentStatus.UnknownOutcome), context, request)
            return false
        }
        if (isCurrent(request, context)) {
            mutableState.value = mutableState.value.copy(
                mutationStatus = status,
                mutationLotId = intent.command.lotId,
                hasPendingCommand = false,
                pendingCommand = null
            )
        }
        return true
    }

    private suspend fun markUnknown(
        intent: DispatchTemperatureIntent,
        context: DispatchAuthorityContext,
        request: Long
    ) {
        val unknown = intent.copy(status = DispatchTemperatureIntentStatus.UnknownOutcome)
        metadata.saveIntent(unknown)
        pendingIntent = unknown
        if (isCurrent(request, context)) {
            mutableState.value = mutableState.value.copy(
                mutationStatus = DispatchTemperatureMutationStatus.UnknownOutcome,
                mutationLotId = intent.command.lotId,
                hasPendingCommand = true,
                pendingCommand = intent.command
            )
        }
    }

    private fun failIfCurrent(request: Long, context: DispatchAuthorityContext, status: DispatchTemperatureStatus) {
        if (isCurrent(request, context)) fail(status)
    }

    private fun fail(status: DispatchTemperatureStatus) {
        mutableState.value = mutableState.value.copy(
            status = status,
            readiness = null,
            observedAt = null
        )
    }

    private fun invalidateContextIfCurrent(request: Long) {
        if (request == generation) invalidateContext()
    }

    private fun invalidateSessionIfCurrent(request: Long) {
        if (request == generation) invalidateSession()
    }

    private fun invalidateContext() {
        generation++
        activeContext = null
        fail(DispatchTemperatureStatus.ContextInvalidated)
    }

    private fun invalidateSession() {
        generation++
        activeContext = null
        fail(DispatchTemperatureStatus.SessionInvalidated)
    }

    private fun isCurrent(request: Long, context: DispatchAuthorityContext): Boolean =
        request == generation && activeContext == context

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun DispatchAuthorityContext.readPermissionHint(): PermissionHint {
        val permissions = identity?.permissions.orEmpty()
        return when {
            permissions.isEmpty() -> PermissionHint.Unknown
            permissions.any { it in FULFILLMENT_READ_PERMISSIONS } -> PermissionHint.Available
            else -> PermissionHint.Unavailable
        }
    }

    private fun DispatchAuthorityContext.hasFulfillmentManagePermission(): Boolean =
        identity?.permissions?.any { it in FULFILLMENT_MANAGE_PERMISSIONS } == true

    private fun DispatchAuthorityContext.scopeIdentity(): DispatchTemperatureScopeIdentity? {
        val current = identity ?: return null
        val fields = listOf(current.userId, current.tenantId, current.workspaceId, current.membershipId)
        if (authorityEpoch <= 0 || fields.any(String::isBlank)) return null
        return DispatchTemperatureScopeIdentity(
            current.userId,
            current.tenantId,
            current.workspaceId,
            current.membershipId
        )
    }

    private enum class PermissionHint { Unknown, Available, Unavailable }

    private companion object {
        val INVALIDATED = setOf(
            DispatchTemperatureStatus.ContextInvalidated,
            DispatchTemperatureStatus.SessionInvalidated
        )
        val FULFILLMENT_READ_PERMISSIONS = setOf("fulfillment.read", "fulfillment:read")
        val FULFILLMENT_MANAGE_PERMISSIONS = setOf("fulfillment.manage", "warehouse:write")
    }
}
