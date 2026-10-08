package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import java.time.Instant

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

@Immutable
data class DispatchReadinessUiState(
    val authorityEpoch: Long = 0,
    val status: DispatchReadinessStatus = DispatchReadinessStatus.Initial,
    val items: List<DispatchReadiness> = emptyList(),
    val asOf: Instant? = null,
    val observedAt: Instant? = null,
    val selectedFulfillmentId: String? = null,
    val detail: DispatchReadiness? = null,
    val detailStatus: DispatchReadinessDetailStatus = DispatchReadinessDetailStatus.NotRequested
) {
    override fun toString(): String = "DispatchReadinessUiState(status=$status, " +
        "items=${items.size}, detailStatus=$detailStatus, authorityEpoch=$authorityEpoch)"
}
