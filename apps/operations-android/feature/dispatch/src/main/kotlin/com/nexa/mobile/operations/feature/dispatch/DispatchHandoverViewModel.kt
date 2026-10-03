package com.nexa.mobile.operations.feature.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DispatchHandoverViewModel(
    private val gateway: DispatchHandoverGateway,
    private val metadata: DispatchHandoverMetadataStore,
    private val now: () -> Instant = Instant::now,
    private val newCommandKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchHandoverUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: DispatchAuthorityContext? = null
    private var originalFulfillment: DispatchReadiness? = null
    private var pendingIntent: DispatchHandoverIntent? = null
    private var generation = 0L

    fun activate(fulfillment: DispatchReadiness, context: DispatchAuthorityContext) {
        if (activeContext == context &&
            originalFulfillment?.fulfillmentId == fulfillment.fulfillmentId &&
            mutableState.value.status !in INVALIDATED
        ) {
            return
        }
        generation++
        activeContext = context
        originalFulfillment = fulfillment
        pendingIntent = null
        mutableState.value = DispatchHandoverUiState(
            authorityEpoch = context.authorityEpoch,
            fulfillmentId = fulfillment.fulfillmentId
        )
        refresh()
    }

    fun refresh() {
        val context = activeContext ?: return
        val fulfillment = originalFulfillment ?: return
        if (!context.hasDispatchAuthority()) {
            fail(DispatchHandoverStatus.PermissionDenied)
            return
        }
        val scope = context.scopeIdentity() ?: run {
            fail(DispatchHandoverStatus.ContextInvalidated)
            return
        }
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            status = DispatchHandoverStatus.Loading,
            authorityEpoch = context.authorityEpoch,
            fulfillmentId = fulfillment.fulfillmentId
        )
        viewModelScope.launch {
            val stored = safe { metadata.loadIntent(scope, fulfillment.fulfillmentId) }
            if (!isCurrent(request, context, fulfillment.fulfillmentId)) return@launch
            val intent = when (stored) {
                is DispatchHandoverMetadataRead.Available -> stored.intent

                DispatchHandoverMetadataRead.Unavailable, null -> {
                    fail(DispatchHandoverStatus.ServiceUnavailable)
                    return@launch
                }
            }
            if (intent != null && intent.scope != scope) {
                pendingIntent = intent
                fail(
                    DispatchHandoverStatus.Stale,
                    hasPending = true,
                    pendingCommand = intent.command
                )
                return@launch
            }
            pendingIntent = intent
            when (val result = safe { gateway.load(fulfillment, context) }) {
                is DispatchHandoverGatewayResult.Snapshot -> {
                    if (!result.value.readiness.fulfillmentId.equals(
                            fulfillment.fulfillmentId,
                            true
                        )
                    ) {
                        fail(DispatchHandoverStatus.Stale, intent != null, intent?.command)
                    } else if (intent != null) {
                        mutableState.value = DispatchHandoverUiState(
                            authorityEpoch = context.authorityEpoch,
                            fulfillmentId = fulfillment.fulfillmentId,
                            status = DispatchHandoverStatus.UnknownOutcome,
                            snapshot = result.value,
                            pendingCommand = intent.command,
                            hasPendingCommand = true
                        )
                    } else {
                        mutableState.value = DispatchHandoverUiState(
                            authorityEpoch = context.authorityEpoch,
                            fulfillmentId = fulfillment.fulfillmentId,
                            status = DispatchHandoverStatus.Current,
                            snapshot = result.value
                        )
                    }
                }

                is DispatchHandoverGatewayResult.AlreadyCompleted -> {
                    clearIntentIfPresent(scope, intent)
                    mutableState.value = DispatchHandoverUiState(
                        authorityEpoch = context.authorityEpoch,
                        fulfillmentId = fulfillment.fulfillmentId,
                        status = DispatchHandoverStatus.Completed,
                        receipt = result.receipt
                    )
                }

                DispatchHandoverGatewayResult.UnknownOutcome ->
                    fail(DispatchHandoverStatus.UnknownOutcome, intent != null, intent?.command)

                DispatchHandoverGatewayResult.NetworkUnavailable ->
                    fail(
                        if (intent !=
                            null
                        ) {
                            DispatchHandoverStatus.UnknownOutcome
                        } else {
                            DispatchHandoverStatus.NetworkUnavailable
                        },
                        intent != null,
                        intent?.command
                    )

                DispatchHandoverGatewayResult.ServiceUnavailable ->
                    fail(
                        if (intent !=
                            null
                        ) {
                            DispatchHandoverStatus.UnknownOutcome
                        } else {
                            DispatchHandoverStatus.ServiceUnavailable
                        },
                        intent != null,
                        intent?.command
                    )

                DispatchHandoverGatewayResult.PermissionDenied ->
                    fail(DispatchHandoverStatus.PermissionDenied, intent != null, intent?.command)

                DispatchHandoverGatewayResult.Stale ->
                    fail(
                        if (intent !=
                            null
                        ) {
                            DispatchHandoverStatus.UnknownOutcome
                        } else {
                            DispatchHandoverStatus.Stale
                        },
                        intent != null,
                        intent?.command
                    )

                DispatchHandoverGatewayResult.Conflict ->
                    fail(
                        if (intent !=
                            null
                        ) {
                            DispatchHandoverStatus.UnknownOutcome
                        } else {
                            DispatchHandoverStatus.Conflict
                        },
                        intent != null,
                        intent?.command
                    )

                DispatchHandoverGatewayResult.ContextInvalidated -> invalidateContext()

                DispatchHandoverGatewayResult.SessionInvalidated -> invalidateSession()

                is DispatchHandoverGatewayResult.Dispatched -> fail(
                    DispatchHandoverStatus.ServiceUnavailable
                )

                null -> fail(
                    if (intent !=
                        null
                    ) {
                        DispatchHandoverStatus.UnknownOutcome
                    } else {
                        DispatchHandoverStatus.ServiceUnavailable
                    },
                    intent != null,
                    intent?.command
                )
            }
        }
    }

    fun confirm() {
        val context = activeContext ?: return
        val fulfillment = originalFulfillment ?: return
        val snapshot = mutableState.value.snapshot ?: return
        if (!mutableState.value.canConfirm || !context.hasDispatchAuthority()) return
        val scope = context.scopeIdentity() ?: return
        val allocation = snapshot.allocation
        val assignment = snapshot.driverAssignment ?: return
        val check = snapshot.outgoingCheck ?: return
        val partial = DispatchHandoverCommand(
            fulfillmentId = fulfillment.fulfillmentId,
            expectedFulfillmentVersion = snapshot.readiness.fulfillmentVersion,
            physicalAllocationId = allocation.id,
            physicalAllocationVersion = allocation.version,
            driverAssignmentId = assignment.id,
            driverAssignmentVersion = assignment.fulfillmentVersion,
            outgoingGoodsCheckId = check.id,
            idempotencyKey = newCommandKey(),
            exactRequestBody = ""
        )
        val command = partial.copy(exactRequestBody = partial.toRequestBody())
        val intent = DispatchHandoverIntent(scope, command)
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            status = DispatchHandoverStatus.Submitting,
            pendingCommand = command,
            hasPendingCommand = true
        )
        viewModelScope.launch {
            when (safe { metadata.saveIntent(intent) }) {
                DispatchHandoverMetadataWrite.Saved -> {
                    if (!isCurrent(request, context, fulfillment.fulfillmentId)) return@launch
                    pendingIntent = intent
                    send(intent, context, snapshot, replay = false, fulfillment = fulfillment)
                }

                DispatchHandoverMetadataWrite.Conflict,
                DispatchHandoverMetadataWrite.Stale -> if (isCurrent(
                        request,
                        context,
                        fulfillment.fulfillmentId
                    )
                ) {
                    fail(DispatchHandoverStatus.Conflict)
                }

                DispatchHandoverMetadataWrite.Unavailable,
                null -> if (isCurrent(request, context, fulfillment.fulfillmentId)) {
                    fail(DispatchHandoverStatus.ServiceUnavailable)
                }
            }
        }
    }

    /** Sends only the stored key and exact body; the server checks current authority before replay lookup. */
    fun replayUnknownOutcome() {
        val context = activeContext ?: return
        val intent = pendingIntent ?: return
        val fulfillment = originalFulfillment ?: return
        if (!mutableState.value.canReplay || intent.scope != context.scopeIdentity() ||
            !context.hasDispatchAuthority() || !intent.command.isValid()
        ) {
            return
        }
        send(intent, context, null, replay = true, fulfillment = fulfillment)
    }

    fun invalidateContext() {
        generation++
        activeContext = null
        mutableState.value = DispatchHandoverUiState(
            authorityEpoch = mutableState.value.authorityEpoch,
            status = DispatchHandoverStatus.ContextInvalidated
        )
    }

    fun invalidateSession() {
        generation++
        activeContext = null
        mutableState.value = DispatchHandoverUiState(
            authorityEpoch = mutableState.value.authorityEpoch,
            status = DispatchHandoverStatus.SessionInvalidated
        )
    }

    fun deactivate() {
        generation++
        activeContext = null
        originalFulfillment = null
        pendingIntent = null
        mutableState.value = DispatchHandoverUiState()
    }

    private fun send(
        intent: DispatchHandoverIntent,
        context: DispatchAuthorityContext,
        snapshot: DispatchHandoverSnapshot?,
        replay: Boolean,
        fulfillment: DispatchReadiness
    ) {
        if (!context.hasDispatchAuthority() || intent.scope != context.scopeIdentity() ||
            !intent.command.isValid() || (replay && snapshot != null) ||
            (!replay && snapshot == null)
        ) {
            fail(DispatchHandoverStatus.Stale, hasPending = true, pendingCommand = intent.command)
            return
        }
        val request = ++generation
        viewModelScope.launch {
            val result = safe { gateway.dispatch(fulfillment, snapshot, intent.command, context) }
            if (!isCurrent(request, context, fulfillment.fulfillmentId)) return@launch
            when (result) {
                is DispatchHandoverGatewayResult.Dispatched,
                is DispatchHandoverGatewayResult.AlreadyCompleted -> {
                    val receipt = when (result) {
                        is DispatchHandoverGatewayResult.Dispatched -> result.receipt
                        is DispatchHandoverGatewayResult.AlreadyCompleted -> result.receipt
                        else -> error("Unreachable")
                    }
                    val cleared = metadata.clearIntent(
                        intent.scope,
                        fulfillment.fulfillmentId,
                        intent.command.idempotencyKey
                    ) == DispatchHandoverMetadataWrite.Saved
                    if (cleared) pendingIntent = null
                    mutableState.value = DispatchHandoverUiState(
                        authorityEpoch = context.authorityEpoch,
                        fulfillmentId = fulfillment.fulfillmentId,
                        status = DispatchHandoverStatus.Completed,
                        receipt = receipt,
                        pendingCommand = if (cleared) null else intent.command,
                        hasPendingCommand = !cleared
                    )
                }

                DispatchHandoverGatewayResult.UnknownOutcome,
                DispatchHandoverGatewayResult.NetworkUnavailable,
                DispatchHandoverGatewayResult.ServiceUnavailable -> {
                    metadata.saveIntent(
                        intent.copy(status = DispatchOutgoingGoodsIntentStatus.UnknownOutcome)
                    )
                    pendingIntent =
                        intent.copy(status = DispatchOutgoingGoodsIntentStatus.UnknownOutcome)
                    fail(DispatchHandoverStatus.UnknownOutcome, true, intent.command)
                }

                DispatchHandoverGatewayResult.Stale,
                DispatchHandoverGatewayResult.Conflict -> {
                    metadata.clearIntent(
                        intent.scope,
                        fulfillment.fulfillmentId,
                        intent.command.idempotencyKey
                    )
                    pendingIntent = null
                    fail(DispatchHandoverStatus.Stale)
                }

                DispatchHandoverGatewayResult.PermissionDenied ->
                    fail(DispatchHandoverStatus.PermissionDenied, true, intent.command)

                DispatchHandoverGatewayResult.ContextInvalidated -> invalidateContext()

                DispatchHandoverGatewayResult.SessionInvalidated -> invalidateSession()

                is DispatchHandoverGatewayResult.Snapshot -> fail(
                    DispatchHandoverStatus.ServiceUnavailable
                )

                null -> {
                    metadata.saveIntent(
                        intent.copy(status = DispatchOutgoingGoodsIntentStatus.UnknownOutcome)
                    )
                    pendingIntent =
                        intent.copy(status = DispatchOutgoingGoodsIntentStatus.UnknownOutcome)
                    fail(DispatchHandoverStatus.UnknownOutcome, true, intent.command)
                }
            }
        }
    }

    private suspend fun clearIntentIfPresent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        intent: DispatchHandoverIntent?
    ) {
        if (intent != null &&
            metadata.clearIntent(
                scope,
                intent.command.fulfillmentId,
                intent.command.idempotencyKey
            ) == DispatchHandoverMetadataWrite.Saved
        ) {
            pendingIntent =
                null
        }
    }

    private fun fail(
        status: DispatchHandoverStatus,
        hasPending: Boolean = false,
        pendingCommand: DispatchHandoverCommand? = null
    ) {
        mutableState.value = DispatchHandoverUiState(
            authorityEpoch = mutableState.value.authorityEpoch,
            fulfillmentId = mutableState.value.fulfillmentId,
            status = status,
            pendingCommand = pendingCommand,
            hasPendingCommand = hasPending
        )
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun isCurrent(
        request: Long,
        context: DispatchAuthorityContext,
        fulfillmentId: String
    ): Boolean = generation == request && activeContext == context &&
        originalFulfillment?.fulfillmentId == fulfillmentId

    private fun DispatchAuthorityContext.hasDispatchAuthority(): Boolean {
        val identity = identity ?: return false
        return authorityEpoch > 0 &&
            listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).none(String::isBlank) &&
            "dispatch.read" in identity.permissions && "fulfillment.manage" in identity.permissions
    }

    private fun DispatchAuthorityContext.scopeIdentity(): DispatchOutgoingGoodsScopeIdentity? {
        val value = identity ?: return null
        if (authorityEpoch <= 0 || listOf(
                value.userId,
                value.tenantId,
                value.workspaceId,
                value.membershipId
            ).any(String::isBlank)
        ) {
            return null
        }
        return DispatchOutgoingGoodsScopeIdentity(
            value.userId,
            value.tenantId,
            value.workspaceId,
            value.membershipId
        )
    }

    private companion object {
        val INVALIDATED =
            setOf(
                DispatchHandoverStatus.ContextInvalidated,
                DispatchHandoverStatus.SessionInvalidated
            )
    }
}
