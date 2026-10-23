package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.changefeed.OperationsChangeFeedGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryPodGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryPodSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.SealDispatchPodCommand
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DispatchReadinessViewModel(
    private val gateway: DispatchReadinessGateway,
    private val now: () -> Instant = Instant::now,
    private val newCommandKey: () -> String = { UUID.randomUUID().toString() },
    private val changeFeedGateway: OperationsChangeFeedGateway = OperationsChangeFeedGateway.None
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchReadinessUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: DispatchAuthorityContext? = null
    private var generation = 0L
    private var listReadJob: Job? = null
    private val changeFeedSignals = changeFeedGateway.relevantChangeSignals()

    fun activate(context: DispatchAuthorityContext) {
        if (activeContext == context && mutableState.value.status !in INVALIDATED_STATUS) return
        cancelListRead()
        generation++
        activeContext = context
        mutableState.value = DispatchReadinessUiState(
            authorityEpoch = context.authorityEpoch,
            changeFeedSignals = changeFeedSignals
        )
        refresh()
    }

    fun refresh() {
        if (listReadJob?.isActive == true) return
        val context = activeContext ?: return
        when (context.readPermissionHint()) {
            PermissionHint.Unknown -> {
                generation++
                mutableState.value = DispatchReadinessUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = DispatchReadinessStatus.PermissionUnknown
                )
                return
            }

            PermissionHint.Unavailable -> {
                generation++
                mutableState.value = DispatchReadinessUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = DispatchReadinessStatus.PermissionDenied
                )
                return
            }

            PermissionHint.Available -> Unit
        }

        val current = mutableState.value
        val keepBoardVisible = current.selectedFulfillmentId == null &&
            current.status in BOARD_REFRESHABLE_STATUS
        val request = ++generation
        mutableState.value = if (keepBoardVisible) {
            current.copy(status = DispatchReadinessStatus.Loading)
        } else {
            DispatchReadinessUiState(
                authorityEpoch = context.authorityEpoch,
                status = DispatchReadinessStatus.Loading,
                changeFeedSignals = changeFeedSignals
            )
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = safeCall { gateway.list(context) }
                if (!isCurrent(request, context)) return@launch
                when (result) {
                    is DispatchReadinessGatewayResult.ListResult -> {
                        val ids = result.items.map { it.fulfillmentId.lowercase() }
                        val malformed = ids.size != ids.toSet().size || result.items.any {
                            it.subjectKind != PREPARED_FULFILLMENT
                        }
                        if (malformed) {
                            mutableState.value = DispatchReadinessUiState(
                                authorityEpoch = context.authorityEpoch,
                                status = DispatchReadinessStatus.ServiceUnavailable,
                                changeFeedSignals = changeFeedSignals
                            )
                        } else {
                            mutableState.value = DispatchReadinessUiState(
                                authorityEpoch = context.authorityEpoch,
                                status = if (result.items.isEmpty()) {
                                    DispatchReadinessStatus.Empty
                                } else {
                                    DispatchReadinessStatus.Current
                                },
                                items = result.items,
                                asOf = result.asOf,
                                observedAt = now(),
                                changeFeedSignals = changeFeedSignals
                            )
                        }
                    }

                    DispatchReadinessGatewayResult.NetworkUnavailable -> failList(
                        context,
                        DispatchReadinessStatus.NetworkUnavailable
                    )

                    DispatchReadinessGatewayResult.ServiceUnavailable -> failList(
                        context,
                        DispatchReadinessStatus.ServiceUnavailable
                    )

                    DispatchReadinessGatewayResult.PermissionDenied -> failList(
                        context,
                        DispatchReadinessStatus.PermissionDenied
                    )

                    DispatchReadinessGatewayResult.ContextInvalidated -> invalidateContext()

                    DispatchReadinessGatewayResult.SessionInvalidated -> invalidateSession()

                    is DispatchReadinessGatewayResult.Detail -> failList(
                        context,
                        DispatchReadinessStatus.ServiceUnavailable
                    )
                }
            } finally {
                if (listReadJob === currentCoroutineContext()[Job]) listReadJob = null
            }
        }
        listReadJob = job
        job.start()
    }

    fun selectFulfillment(fulfillmentId: String) {
        val context = activeContext ?: return
        val current = mutableState.value
        if (
            current.status !in setOf(
                DispatchReadinessStatus.Current,
                DispatchReadinessStatus.Loading
            ) ||
            current.items.none { it.fulfillmentId == fulfillmentId }
        ) {
            return
        }
        cancelListRead()
        val request = ++generation
        mutableState.value = current.copy(
            status = if (current.items.isEmpty()) {
                DispatchReadinessStatus.Empty
            } else {
                DispatchReadinessStatus.Current
            },
            selectedFulfillmentId = fulfillmentId,
            detail = null,
            detailStatus = DispatchReadinessDetailStatus.Loading,
            deliveryPod = null,
            deliveryPodStatus = if (current.items.firstOrNull { it.fulfillmentId == fulfillmentId }
                    ?.deliveryId != null
            ) {
                DispatchDeliveryPodStatus.Loading
            } else {
                DispatchDeliveryPodStatus.NotRequested
            },
            podSealStatus = DispatchPodSealStatus.Idle,
            pendingPodSeal = null
        )
        viewModelScope.launch {
            val result = safeCall { gateway.detail(fulfillmentId, context) }
            if (!isCurrent(request, context)) return@launch
            when (result) {
                is DispatchReadinessGatewayResult.Detail -> {
                    val summary = current.items.firstOrNull { it.fulfillmentId == fulfillmentId }
                    if (summary == null || !result.item.matches(summary)) {
                        mutableState.value = mutableState.value.copy(
                            detail = null,
                            detailStatus = DispatchReadinessDetailStatus.ServiceUnavailable
                        )
                    } else {
                        mutableState.value = mutableState.value.copy(
                            detail = result.item,
                            detailStatus = DispatchReadinessDetailStatus.Current,
                            deliveryPodStatus = if (result.item.deliveryId != null) {
                                DispatchDeliveryPodStatus.Loading
                            } else {
                                DispatchDeliveryPodStatus.NotRequested
                            }
                        )
                        result.item.deliveryId?.let { deliveryId ->
                            val podResult = safePodRead { gateway.deliveryPod(deliveryId, context) }
                            if (!isCurrent(request, context)) return@launch
                            applyPodReadResult(result.item, podResult)
                        }
                    }
                }

                DispatchReadinessGatewayResult.NetworkUnavailable -> failDetail(
                    DispatchReadinessDetailStatus.NetworkUnavailable
                )

                DispatchReadinessGatewayResult.ServiceUnavailable -> failDetail(
                    DispatchReadinessDetailStatus.ServiceUnavailable
                )

                DispatchReadinessGatewayResult.PermissionDenied -> {
                    activeContext = null
                    generation++
                    mutableState.value = DispatchReadinessUiState(
                        authorityEpoch = context.authorityEpoch,
                        status = DispatchReadinessStatus.PermissionDenied,
                        detailStatus = DispatchReadinessDetailStatus.PermissionDenied
                    )
                }

                DispatchReadinessGatewayResult.ContextInvalidated -> invalidateContext()

                DispatchReadinessGatewayResult.SessionInvalidated -> invalidateSession()

                is DispatchReadinessGatewayResult.ListResult -> failDetail(
                    DispatchReadinessDetailStatus.ServiceUnavailable
                )
            }
        }
    }

    fun clearSelection() {
        generation++
        mutableState.value = mutableState.value.copy(
            selectedFulfillmentId = null,
            detail = null,
            detailStatus = DispatchReadinessDetailStatus.NotRequested,
            deliveryPod = null,
            deliveryPodStatus = DispatchDeliveryPodStatus.NotRequested,
            podSealStatus = DispatchPodSealStatus.Idle,
            pendingPodSeal = null
        )
    }

    fun refreshDeliveryPod() {
        val context = activeContext ?: return
        val current = mutableState.value
        val detail = current.detail?.takeIf {
            current.detailStatus == DispatchReadinessDetailStatus.Current && it.deliveryId != null
        } ?: return
        val request = ++generation
        mutableState.value = current.copy(
            deliveryPod = null,
            deliveryPodStatus = DispatchDeliveryPodStatus.Loading,
            podSealStatus = DispatchPodSealStatus.Idle,
            pendingPodSeal = null
        )
        viewModelScope.launch {
            val result = safePodRead { gateway.deliveryPod(detail.deliveryId!!, context) }
            if (!isCurrent(request, context)) return@launch
            applyPodReadResult(detail, result)
        }
    }

    fun canSealPod(): Boolean {
        val context = activeContext ?: return false
        val identity = context.identity ?: return false
        val current = mutableState.value
        val detail = current.detail ?: return false
        val snapshot = current.deliveryPod ?: return false
        val pod = snapshot.pod ?: return false
        return current.detailStatus == DispatchReadinessDetailStatus.Current &&
            current.deliveryPodStatus == DispatchDeliveryPodStatus.Current &&
            current.podSealStatus == DispatchPodSealStatus.Idle &&
            detail.deliveryId == snapshot.deliveryId &&
            detail.fulfillmentId == snapshot.fulfillmentId &&
            snapshot.deliveryStatus == "DELIVERED" && pod.status == "CAPTURED" &&
            pod.deliveryId == snapshot.deliveryId &&
            pod.deliveryVersion == snapshot.deliveryVersion &&
            pod.deliveryEtag == snapshot.deliveryEtag &&
            DISPATCH_READ_PERMISSION in identity.permissions &&
            identity.permissions.any { it in POD_SEAL_PERMISSIONS }
    }

    fun sealPod() {
        if (!canSealPod()) return
        val snapshot = mutableState.value.deliveryPod ?: return
        submitPodSeal(
            SealDispatchPodCommand(
                deliveryId = snapshot.deliveryId,
                deliveryEtag = snapshot.deliveryEtag,
                idempotencyKey = newCommandKey()
            )
        )
    }

    fun retryPodSeal() {
        val current = mutableState.value
        val command = current.pendingPodSeal ?: return
        if (current.podSealStatus != DispatchPodSealStatus.UnknownOutcome) return
        submitPodSeal(command)
    }

    fun deactivate() {
        cancelListRead()
        generation++
        activeContext = null
        mutableState.value = DispatchReadinessUiState()
    }

    fun invalidateContext() {
        cancelListRead()
        generation++
        activeContext = null
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = DispatchReadinessUiState(
            authorityEpoch = epoch,
            status = DispatchReadinessStatus.ContextInvalidated
        )
    }

    fun invalidateSession() {
        cancelListRead()
        generation++
        activeContext = null
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = DispatchReadinessUiState(
            authorityEpoch = epoch,
            status = DispatchReadinessStatus.SessionInvalidated
        )
    }

    private fun failList(context: DispatchAuthorityContext, status: DispatchReadinessStatus) {
        mutableState.value = DispatchReadinessUiState(
            authorityEpoch = context.authorityEpoch,
            status = status,
            changeFeedSignals = if (status in BOARD_REFRESHABLE_STATUS) {
                changeFeedSignals
            } else {
                OperationsChangeFeedGateway.None.relevantChangeSignals()
            }
        )
    }

    private fun cancelListRead() {
        listReadJob?.cancel()
        listReadJob = null
    }

    private fun failDetail(status: DispatchReadinessDetailStatus) {
        mutableState.value = mutableState.value.copy(detail = null, detailStatus = status)
    }

    private fun applyPodReadResult(
        readiness: DispatchReadiness,
        result: DispatchDeliveryPodGatewayResult
    ) {
        when (result) {
            is DispatchDeliveryPodGatewayResult.Snapshot -> {
                val snapshot = result.value
                val readinessDeliveryVersion = readiness.deliveryVersion
                val pod = snapshot.pod
                val consistent = readiness.deliveryId == snapshot.deliveryId &&
                    readiness.fulfillmentId == snapshot.fulfillmentId &&
                    readinessDeliveryVersion != null &&
                    snapshot.deliveryVersion >= readinessDeliveryVersion &&
                    (
                        pod == null ||
                            (
                                pod.deliveryId == snapshot.deliveryId &&
                                    pod.deliveryVersion == snapshot.deliveryVersion &&
                                    pod.deliveryEtag == snapshot.deliveryEtag
                                )
                        )
                mutableState.value = mutableState.value.copy(
                    deliveryPod = snapshot.takeIf { consistent },
                    deliveryPodStatus = if (consistent) {
                        DispatchDeliveryPodStatus.Current
                    } else {
                        DispatchDeliveryPodStatus.ServiceUnavailable
                    },
                    podSealStatus = DispatchPodSealStatus.Idle,
                    pendingPodSeal = null
                )
            }

            DispatchDeliveryPodGatewayResult.NetworkUnavailable ->
                failPodRead(DispatchDeliveryPodStatus.NetworkUnavailable)

            DispatchDeliveryPodGatewayResult.ServiceUnavailable ->
                failPodRead(DispatchDeliveryPodStatus.ServiceUnavailable)

            DispatchDeliveryPodGatewayResult.PermissionDenied ->
                failPodRead(DispatchDeliveryPodStatus.PermissionDenied)

            DispatchDeliveryPodGatewayResult.ContextInvalidated -> invalidateContext()

            DispatchDeliveryPodGatewayResult.SessionInvalidated -> invalidateSession()

            is DispatchDeliveryPodGatewayResult.Sealed,
            DispatchDeliveryPodGatewayResult.Stale,
            DispatchDeliveryPodGatewayResult.Conflict,
            DispatchDeliveryPodGatewayResult.UnknownOutcome ->
                failPodRead(DispatchDeliveryPodStatus.ServiceUnavailable)
        }
    }

    private fun failPodRead(status: DispatchDeliveryPodStatus) {
        mutableState.value = mutableState.value.copy(
            deliveryPod = null,
            deliveryPodStatus = status,
            podSealStatus = DispatchPodSealStatus.Idle,
            pendingPodSeal = null
        )
    }

    private fun submitPodSeal(command: SealDispatchPodCommand) {
        val context = activeContext ?: return
        if (command.deliveryId != mutableState.value.deliveryPod?.deliveryId ||
            command.deliveryEtag != mutableState.value.deliveryPod?.deliveryEtag ||
            (command.idempotencyKey.isBlank())
        ) {
            return
        }
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            podSealStatus = DispatchPodSealStatus.Submitting,
            pendingPodSeal = command
        )
        viewModelScope.launch {
            val result = safePodCommand { gateway.sealPod(command, context) }
            if (!isCurrent(request, context)) return@launch
            when (result) {
                is DispatchDeliveryPodGatewayResult.Sealed -> {
                    val snapshot = mutableState.value.deliveryPod
                    if (snapshot == null || result.value.deliveryId != snapshot.deliveryId ||
                        result.value.status != "SEALED" ||
                        result.value.deliveryVersion != snapshot.deliveryVersion + 1 ||
                        result.value.deliveryEtag == snapshot.deliveryEtag
                    ) {
                        mutableState.value = mutableState.value.copy(
                            podSealStatus = DispatchPodSealStatus.UnknownOutcome,
                            pendingPodSeal = command
                        )
                    } else {
                        mutableState.value = mutableState.value.copy(
                            deliveryPod = snapshot.copy(
                                deliveryVersion = result.value.deliveryVersion,
                                deliveryEtag = result.value.deliveryEtag,
                                pod = result.value
                            ),
                            podSealStatus = DispatchPodSealStatus.Sealed,
                            pendingPodSeal = null
                        )
                    }
                }

                DispatchDeliveryPodGatewayResult.Stale -> {
                    mutableState.value = mutableState.value.copy(
                        podSealStatus = DispatchPodSealStatus.Stale,
                        pendingPodSeal = null
                    )
                }

                DispatchDeliveryPodGatewayResult.Conflict -> {
                    mutableState.value = mutableState.value.copy(
                        podSealStatus = DispatchPodSealStatus.Conflict,
                        pendingPodSeal = null
                    )
                }

                DispatchDeliveryPodGatewayResult.UnknownOutcome,
                DispatchDeliveryPodGatewayResult.NetworkUnavailable -> {
                    mutableState.value = mutableState.value.copy(
                        podSealStatus = DispatchPodSealStatus.UnknownOutcome,
                        pendingPodSeal = command
                    )
                }

                DispatchDeliveryPodGatewayResult.ServiceUnavailable -> {
                    mutableState.value = mutableState.value.copy(
                        podSealStatus = DispatchPodSealStatus.ServiceUnavailable,
                        pendingPodSeal = command
                    )
                }

                DispatchDeliveryPodGatewayResult.PermissionDenied -> {
                    mutableState.value = mutableState.value.copy(
                        podSealStatus = DispatchPodSealStatus.PermissionDenied,
                        pendingPodSeal = null
                    )
                }

                DispatchDeliveryPodGatewayResult.ContextInvalidated -> invalidateContext()

                DispatchDeliveryPodGatewayResult.SessionInvalidated -> invalidateSession()

                is DispatchDeliveryPodGatewayResult.Snapshot -> {
                    mutableState.value = mutableState.value.copy(
                        podSealStatus = DispatchPodSealStatus.UnknownOutcome,
                        pendingPodSeal = command
                    )
                }
            }
        }
    }

    private suspend fun safePodRead(
        block: suspend () -> DispatchDeliveryPodGatewayResult
    ): DispatchDeliveryPodGatewayResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchDeliveryPodGatewayResult.ServiceUnavailable
    }

    private suspend fun safePodCommand(
        block: suspend () -> DispatchDeliveryPodGatewayResult
    ): DispatchDeliveryPodGatewayResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchDeliveryPodGatewayResult.UnknownOutcome
    }

    private suspend fun safeCall(
        block: suspend () -> DispatchReadinessGatewayResult
    ): DispatchReadinessGatewayResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchReadinessGatewayResult.ServiceUnavailable
    }

    private fun isCurrent(request: Long, context: DispatchAuthorityContext): Boolean =
        request == generation && activeContext == context &&
            mutableState.value.authorityEpoch == context.authorityEpoch

    private fun DispatchAuthorityContext.readPermissionHint(): PermissionHint {
        val verified = identity ?: return PermissionHint.Unknown
        if (authorityEpoch <= 0 || listOf(
                verified.userId,
                verified.tenantId,
                verified.workspaceId,
                verified.membershipId
            ).any(String::isBlank)
        ) {
            return PermissionHint.Unknown
        }
        return when {
            verified.permissions.isEmpty() -> PermissionHint.Unknown
            verified.permissions.any { it in DISPATCH_READ_PERMISSIONS } -> PermissionHint.Available
            else -> PermissionHint.Unavailable
        }
    }

    private fun DispatchReadiness.matches(summary: DispatchReadiness): Boolean =
        subjectKind == PREPARED_FULFILLMENT && fulfillmentId == summary.fulfillmentId &&
            physicalAllocationId == summary.physicalAllocationId &&
            fulfillmentVersion >= summary.fulfillmentVersion &&
            physicalAllocationVersion >= summary.physicalAllocationVersion &&
            deliveryId == summary.deliveryId

    private enum class PermissionHint { Available, Unavailable, Unknown }

    private companion object {
        const val PREPARED_FULFILLMENT = "PREPARED_FULFILLMENT"
        const val DISPATCH_READ_PERMISSION = "dispatch.read"
        val DISPATCH_READ_PERMISSIONS = setOf("dispatch.read")
        val POD_SEAL_PERMISSIONS = setOf("dispatch.complete", "logistics:write")
        val INVALIDATED_STATUS = setOf(
            DispatchReadinessStatus.ContextInvalidated,
            DispatchReadinessStatus.SessionInvalidated
        )
        val BOARD_REFRESHABLE_STATUS = setOf(
            DispatchReadinessStatus.Current,
            DispatchReadinessStatus.Empty,
            DispatchReadinessStatus.NetworkUnavailable,
            DispatchReadinessStatus.ServiceUnavailable
        )
    }
}
