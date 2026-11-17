package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.changefeed.OperationsChangeFeedGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryPodGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryPodSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartConfirmation
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartScopeIdentity
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
    private val transitMetadataStore: DispatchTransitStartMetadataStore,
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
        val selectedItem = current.items.firstOrNull { it.fulfillmentId == fulfillmentId }
        val hasDelivery = selectedItem?.deliveryId != null
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
            deliveryPodStatus = if (hasDelivery) {
                DispatchDeliveryPodStatus.Loading
            } else {
                DispatchDeliveryPodStatus.NotRequested
            },
            podSealStatus = DispatchPodSealStatus.Idle,
            pendingPodSeal = null,
            transitStartStatus = if (hasDelivery) {
                DispatchTransitStartStatus.CheckingIntent
            } else {
                DispatchTransitStartStatus.Idle
            },
            pendingTransitStart = null
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
                            restoreTransitIntent(result.item, context, request)
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
            pendingPodSeal = null,
            transitStartStatus = DispatchTransitStartStatus.Idle,
            pendingTransitStart = null
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

    fun canStartTransit(): Boolean {
        val context = activeContext ?: return false
        val identity = context.identity ?: return false
        val current = mutableState.value
        val detail = current.detail ?: return false
        val delivery = current.deliveryPod ?: return false
        return current.detailStatus == DispatchReadinessDetailStatus.Current &&
            current.deliveryPodStatus == DispatchDeliveryPodStatus.Current &&
            current.transitStartStatus == DispatchTransitStartStatus.Idle &&
            current.pendingTransitStart == null && detail.deliveryId == delivery.deliveryId &&
            detail.fulfillmentId == delivery.fulfillmentId &&
            delivery.deliveryStatus == "DISPATCHED" &&
            DISPATCH_READ_PERMISSION in identity.permissions &&
            LOGISTICS_WRITE_PERMISSION in identity.permissions
    }

    fun canRetryTransitStart(): Boolean {
        val context = activeContext ?: return false
        val identity = context.identity ?: return false
        val intent = mutableState.value.pendingTransitStart ?: return false
        return mutableState.value.transitStartStatus == DispatchTransitStartStatus.UnknownOutcome &&
            intent.status == DispatchTransitStartIntentStatus.UnknownOutcome &&
            intent.scope == identity.transitScope() &&
            DISPATCH_READ_PERMISSION in identity.permissions &&
            LOGISTICS_WRITE_PERMISSION in identity.permissions
    }

    /** Persists a fixed Delivery version and key before asking the server to start transit. */
    fun startTransit() {
        if (!canStartTransit()) return
        val context = activeContext ?: return
        val identity = context.identity ?: return
        val delivery = mutableState.value.deliveryPod ?: return
        val command = DispatchTransitStartCommand(
            deliveryId = delivery.deliveryId,
            fulfillmentId = delivery.fulfillmentId,
            deliveryEtag = delivery.deliveryEtag,
            idempotencyKey = newCommandKey()
        )
        if (!command.isValid()) return
        val intent = DispatchTransitStartIntent(identity.transitScope(), command)
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            transitStartStatus = DispatchTransitStartStatus.Persisting,
            pendingTransitStart = intent
        )
        viewModelScope.launch {
            val saved = safeTransitMetadataWrite { transitMetadataStore.saveIntent(intent) }
            if (!isCurrent(request, context)) return@launch
            when (saved) {
                DispatchTransitStartMetadataWrite.Saved -> {
                    mutableState.value = mutableState.value.copy(
                        transitStartStatus = DispatchTransitStartStatus.Pending
                    )
                    runTransitStart(intent, context, request, replayUnknown = false)
                }

                DispatchTransitStartMetadataWrite.Conflict,
                DispatchTransitStartMetadataWrite.Stale -> {
                    mutableState.value = mutableState.value.copy(
                        transitStartStatus = DispatchTransitStartStatus.Conflict,
                        pendingTransitStart = null
                    )
                }

                DispatchTransitStartMetadataWrite.Unavailable -> {
                    mutableState.value = mutableState.value.copy(
                        transitStartStatus = DispatchTransitStartStatus.PersistenceUnavailable,
                        pendingTransitStart = null
                    )
                }
            }
        }
    }

    /** Replays only the persisted command after an ambiguous result, with its original ETag and key. */
    fun retryTransitStart() {
        val context = activeContext ?: return
        val intent = mutableState.value.pendingTransitStart ?: return
        if (!canRetryTransitStart() || context.identity?.transitScope() != intent.scope) return
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            transitStartStatus = DispatchTransitStartStatus.Pending
        )
        viewModelScope.launch {
            runTransitStart(intent, context, request, replayUnknown = true)
        }
    }

    private suspend fun restoreTransitIntent(
        readiness: DispatchReadiness,
        context: DispatchAuthorityContext,
        request: Long
    ) {
        val deliveryId = readiness.deliveryId ?: return
        val scope = context.identity?.transitScope()
        if (scope == null) {
            mutableState.value = mutableState.value.copy(
                transitStartStatus = DispatchTransitStartStatus.PersistenceUnavailable
            )
            return
        }
        val read = safeTransitMetadataRead {
            transitMetadataStore.loadIntent(
                scope,
                deliveryId
            )
        }
        when (read) {
            is DispatchTransitStartMetadataRead.Available -> {
                if (!isCurrent(request, context)) return
                val intent = read.intent
                if (intent == null) {
                    mutableState.value = mutableState.value.copy(
                        transitStartStatus = DispatchTransitStartStatus.Idle,
                        pendingTransitStart = null
                    )
                } else if (
                    intent.scope == scope && intent.command.deliveryId.equals(deliveryId, true) &&
                    intent.command.fulfillmentId.equals(readiness.fulfillmentId, true) &&
                    intent.command.isValid()
                ) {
                    val recovered = intent.copy(
                        status = DispatchTransitStartIntentStatus.UnknownOutcome
                    )
                    mutableState.value = mutableState.value.copy(
                        transitStartStatus = DispatchTransitStartStatus.UnknownOutcome,
                        pendingTransitStart = recovered
                    )
                } else {
                    mutableState.value = mutableState.value.copy(
                        transitStartStatus = DispatchTransitStartStatus.PersistenceUnavailable,
                        pendingTransitStart = null
                    )
                }
            }

            DispatchTransitStartMetadataRead.Unavailable -> {
                if (!isCurrent(request, context)) return
                mutableState.value = mutableState.value.copy(
                    transitStartStatus = DispatchTransitStartStatus.PersistenceUnavailable,
                    pendingTransitStart = null
                )
            }
        }
    }

    private suspend fun runTransitStart(
        intent: DispatchTransitStartIntent,
        context: DispatchAuthorityContext,
        request: Long,
        replayUnknown: Boolean
    ) {
        val result = safeTransitStart {
            gateway.startTransit(intent, context, replayUnknown)
        }
        if (!isCurrent(request, context)) return
        when (result) {
            is DispatchTransitStartGatewayResult.Started -> {
                if (!reconcileTransitConfirmation(intent, result.value, replayUnknown)) {
                    persistUnknownTransitIntent(intent, request, context)
                    return
                }
                if (hasEqualVersionTransitConflict(result.value)) {
                    persistUnknownTransitIntent(intent, request, context)
                    if (!isCurrent(request, context)) return
                    refreshDeliveryPod()
                    return
                }
                if (!clearTransitIntentIfCurrent(intent, request, context)) return
                if (!hasNewerLocalTransitVersion(result.value)) {
                    updateDeliveryAfterTransit(result.value)
                }
                mutableState.value = mutableState.value.copy(
                    transitStartStatus = DispatchTransitStartStatus.Started,
                    pendingTransitStart = null
                )
            }

            DispatchTransitStartGatewayResult.UnknownOutcome,
            DispatchTransitStartGatewayResult.NetworkUnavailable,
            DispatchTransitStartGatewayResult.ServiceUnavailable ->
                persistUnknownTransitIntent(intent, request, context)

            DispatchTransitStartGatewayResult.Stale -> {
                if (!clearTransitIntentIfCurrent(intent, request, context)) return
                mutableState.value = mutableState.value.copy(
                    transitStartStatus = DispatchTransitStartStatus.Stale,
                    pendingTransitStart = null
                )
            }

            DispatchTransitStartGatewayResult.Conflict -> {
                if (!clearTransitIntentIfCurrent(intent, request, context)) return
                mutableState.value = mutableState.value.copy(
                    transitStartStatus = DispatchTransitStartStatus.Conflict,
                    pendingTransitStart = null
                )
            }

            DispatchTransitStartGatewayResult.PermissionDenied -> {
                if (!clearTransitIntentIfCurrent(intent, request, context)) return
                mutableState.value = mutableState.value.copy(
                    transitStartStatus = DispatchTransitStartStatus.PermissionDenied,
                    pendingTransitStart = null
                )
            }

            DispatchTransitStartGatewayResult.ContextInvalidated -> invalidateContext()

            DispatchTransitStartGatewayResult.SessionInvalidated -> invalidateSession()
        }
    }

    private suspend fun clearTransitIntentIfCurrent(
        intent: DispatchTransitStartIntent,
        request: Long,
        context: DispatchAuthorityContext
    ): Boolean {
        safeClearTransitIntent(intent)
        return isCurrent(request, context)
    }

    private suspend fun persistUnknownTransitIntent(
        intent: DispatchTransitStartIntent,
        request: Long,
        context: DispatchAuthorityContext
    ) {
        val unknown = intent.copy(status = DispatchTransitStartIntentStatus.UnknownOutcome)
        safeTransitMetadataWrite { transitMetadataStore.saveIntent(unknown) }
        if (!isCurrent(request, context)) return
        mutableState.value = mutableState.value.copy(
            transitStartStatus = DispatchTransitStartStatus.UnknownOutcome,
            pendingTransitStart = unknown
        )
    }

    private fun reconcileTransitConfirmation(
        intent: DispatchTransitStartIntent,
        confirmation: DispatchTransitStartConfirmation,
        replayUnknown: Boolean
    ): Boolean {
        val expectedVersion = intent.command.expectedVersion ?: return false
        val confirmedVersion = confirmation.deliveryEtag.versionFromEtag() ?: return false
        val statusMatches = if (replayUnknown) {
            confirmation.deliveryStatus in TRANSIT_REPLAY_STATUSES
        } else {
            confirmation.deliveryStatus == "IN_TRANSIT"
        }
        return confirmation.deliveryId.equals(intent.command.deliveryId, true) &&
            confirmation.fulfillmentId.equals(intent.command.fulfillmentId, true) &&
            statusMatches &&
            confirmation.deliveryVersion > expectedVersion &&
            (replayUnknown || confirmation.deliveryVersion == expectedVersion + 1) &&
            confirmedVersion == confirmation.deliveryVersion
    }

    private fun hasEqualVersionTransitConflict(
        confirmation: DispatchTransitStartConfirmation
    ): Boolean {
        val current = mutableState.value
        val delivery = current.deliveryPod?.takeIf {
            it.deliveryId == confirmation.deliveryId &&
                it.fulfillmentId == confirmation.fulfillmentId
        }
        if (delivery != null && delivery.deliveryVersion == confirmation.deliveryVersion) {
            if (delivery.deliveryStatus != confirmation.deliveryStatus ||
                delivery.deliveryEtag != confirmation.deliveryEtag
            ) {
                return true
            }
        }

        val projections = buildList {
            current.detail?.let(::add)
            addAll(current.items)
        }
        return projections.any { projection ->
            projection.deliveryId == confirmation.deliveryId &&
                projection.fulfillmentId == confirmation.fulfillmentId &&
                projection.deliveryVersion == confirmation.deliveryVersion &&
                projection.deliveryStatus != confirmation.deliveryStatus
        }
    }

    private fun hasNewerLocalTransitVersion(
        confirmation: DispatchTransitStartConfirmation
    ): Boolean {
        val current = mutableState.value
        if (current.deliveryPod?.let {
                it.deliveryId == confirmation.deliveryId &&
                    it.fulfillmentId == confirmation.fulfillmentId &&
                    it.deliveryVersion > confirmation.deliveryVersion
            } == true
        ) {
            return true
        }
        val projections = buildList {
            current.detail?.let(::add)
            addAll(current.items)
        }
        return projections.any { projection ->
            projection.deliveryId == confirmation.deliveryId &&
                projection.fulfillmentId == confirmation.fulfillmentId &&
                projection.deliveryVersion?.let { it > confirmation.deliveryVersion } == true
        }
    }

    private fun updateDeliveryAfterTransit(confirmation: DispatchTransitStartConfirmation) {
        val current = mutableState.value
        val delivery = current.deliveryPod ?: return
        if (delivery.deliveryId != confirmation.deliveryId ||
            delivery.fulfillmentId != confirmation.fulfillmentId
        ) {
            return
        }
        val updated = delivery.copy(
            deliveryStatus = confirmation.deliveryStatus,
            deliveryVersion = confirmation.deliveryVersion,
            deliveryEtag = confirmation.deliveryEtag,
            pod = delivery.pod?.takeIf {
                it.deliveryVersion == confirmation.deliveryVersion &&
                    it.deliveryEtag == confirmation.deliveryEtag
            }
        )
        val detail = current.detail?.takeIf {
            it.deliveryId == confirmation.deliveryId &&
                it.fulfillmentId == confirmation.fulfillmentId
        }?.copy(
            deliveryStatus = confirmation.deliveryStatus,
            deliveryVersion = confirmation.deliveryVersion
        )
        val items = current.items.map { item ->
            if (item.fulfillmentId == confirmation.fulfillmentId &&
                item.deliveryId == confirmation.deliveryId
            ) {
                item.copy(
                    deliveryStatus = confirmation.deliveryStatus,
                    deliveryVersion = confirmation.deliveryVersion
                )
            } else {
                item
            }
        }
        mutableState.value = current.copy(
            items = items,
            detail = detail,
            deliveryPod = updated,
            deliveryPodStatus = DispatchDeliveryPodStatus.Current
        )
    }

    private suspend fun safeClearTransitIntent(intent: DispatchTransitStartIntent) {
        try {
            transitMetadataStore.clearIntent(
                intent.scope,
                intent.command.deliveryId,
                intent.command.idempotencyKey
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A confirmed/rejected server result remains authoritative; persisted retries stay safe.
        }
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
                val podConsistent = pod?.let { evidence ->
                    evidence.deliveryId == snapshot.deliveryId &&
                        evidence.deliveryVersion == snapshot.deliveryVersion &&
                        evidence.deliveryEtag == snapshot.deliveryEtag
                } ?: true
                val consistent = readiness.deliveryId == snapshot.deliveryId &&
                    readiness.fulfillmentId == snapshot.fulfillmentId &&
                    readinessDeliveryVersion != null &&
                    snapshot.deliveryVersion >= readinessDeliveryVersion &&
                    podConsistent
                val current = mutableState.value
                val updatedDetail = current.detail?.takeIf {
                    it.deliveryId == snapshot.deliveryId &&
                        it.fulfillmentId == snapshot.fulfillmentId
                }?.copy(
                    deliveryStatus = snapshot.deliveryStatus,
                    deliveryVersion = snapshot.deliveryVersion
                )
                val items = current.items.map { item ->
                    if (item.deliveryId == snapshot.deliveryId &&
                        item.fulfillmentId == snapshot.fulfillmentId
                    ) {
                        item.copy(
                            deliveryStatus = snapshot.deliveryStatus,
                            deliveryVersion = snapshot.deliveryVersion
                        )
                    } else {
                        item
                    }
                }
                val transitStatus = when (current.transitStartStatus) {
                    DispatchTransitStartStatus.Stale,
                    DispatchTransitStartStatus.Conflict,
                    DispatchTransitStartStatus.PermissionDenied,
                    DispatchTransitStartStatus.PersistenceUnavailable ->
                        DispatchTransitStartStatus.Idle

                    else -> current.transitStartStatus
                }
                mutableState.value = current.copy(
                    items = items,
                    detail = updatedDetail,
                    deliveryPod = snapshot.takeIf { consistent },
                    deliveryPodStatus = if (consistent) {
                        DispatchDeliveryPodStatus.Current
                    } else {
                        DispatchDeliveryPodStatus.ServiceUnavailable
                    },
                    podSealStatus = DispatchPodSealStatus.Idle,
                    pendingPodSeal = null,
                    transitStartStatus = if (consistent) {
                        transitStatus
                    } else {
                        current.transitStartStatus
                    }
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

    private suspend fun safeTransitMetadataRead(
        block: suspend () -> DispatchTransitStartMetadataRead
    ): DispatchTransitStartMetadataRead = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchTransitStartMetadataRead.Unavailable
    }

    private suspend fun safeTransitMetadataWrite(
        block: suspend () -> DispatchTransitStartMetadataWrite
    ): DispatchTransitStartMetadataWrite = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchTransitStartMetadataWrite.Unavailable
    }

    private suspend fun safeTransitStart(
        block: suspend () -> DispatchTransitStartGatewayResult
    ): DispatchTransitStartGatewayResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchTransitStartGatewayResult.UnknownOutcome
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

    private fun DispatchAuthorityIdentity.transitScope() = DispatchTransitStartScopeIdentity(
        userId = userId,
        tenantId = tenantId,
        workspaceId = workspaceId,
        membershipId = membershipId
    )

    private fun String.versionFromEtag(): Long? {
        val value = trim().removeSurrounding("\"")
        if (value.isEmpty() || value.any { !it.isDigit() }) return null
        return value.toLongOrNull()?.takeIf { it >= 0 }
    }

    private enum class PermissionHint { Available, Unavailable, Unknown }

    private companion object {
        const val PREPARED_FULFILLMENT = "PREPARED_FULFILLMENT"
        const val DISPATCH_READ_PERMISSION = "dispatch.read"
        const val LOGISTICS_WRITE_PERMISSION = "logistics:write"
        val DISPATCH_READ_PERMISSIONS = setOf("dispatch.read")
        val POD_SEAL_PERMISSIONS = setOf("dispatch.complete", "logistics:write")
        val TRANSIT_REPLAY_STATUSES = setOf(
            "IN_TRANSIT",
            "PARTIAL",
            "DELIVERED",
            "FAILED",
            "CANCELLED"
        )
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
