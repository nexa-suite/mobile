package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryPodSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.SealDispatchPodCommand
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class DispatchReadinessStatus {
    Initial,
    Loading,
    Current,
    Empty,
    PermissionUnknown,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable,
    ContextInvalidated,
    SessionInvalidated
}

enum class DispatchReadinessDetailStatus {
    NotRequested,
    Loading,
    Current,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class DispatchDeliveryPodStatus {
    NotRequested,
    Loading,
    Current,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class DispatchPodSealStatus {
    Idle,
    Submitting,
    Sealed,
    UnknownOutcome,
    Stale,
    Conflict,
    ServiceUnavailable,
    PermissionDenied
}

@Immutable
data class DispatchReadinessUiState(
    val authorityEpoch: Long = 0,
    val status: DispatchReadinessStatus = DispatchReadinessStatus.Initial,
    val items: List<DispatchReadiness> = emptyList(),
    val asOf: Instant? = null,
    val observedAt: Instant? = null,
    val selectedFulfillmentId: String? = null,
    val detail: DispatchReadiness? = null,
    val detailStatus: DispatchReadinessDetailStatus = DispatchReadinessDetailStatus.NotRequested,
    val deliveryPod: DispatchDeliveryPodSnapshot? = null,
    val deliveryPodStatus: DispatchDeliveryPodStatus = DispatchDeliveryPodStatus.NotRequested,
    val podSealStatus: DispatchPodSealStatus = DispatchPodSealStatus.Idle,
    val pendingPodSeal: SealDispatchPodCommand? = null,
    val changeFeedSignals: Flow<Unit> = emptyFlow()
) {
    override fun toString(): String = "DispatchReadinessUiState(status=$status, " +
        "items=${items.size}, detailStatus=$detailStatus, deliveryPodStatus=$deliveryPodStatus, " +
        "podSealStatus=$podSealStatus, authorityEpoch=$authorityEpoch)"
}
