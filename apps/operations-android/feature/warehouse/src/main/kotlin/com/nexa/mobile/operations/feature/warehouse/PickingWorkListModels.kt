package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.feature.warehouse.model.PickingWorkItem
import java.time.Instant

enum class PickingWorkListStatus {
    NotRequested,
    Loading,
    Ready,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

@Immutable
data class PickingWorkListUiState(
    val status: PickingWorkListStatus = PickingWorkListStatus.NotRequested,
    val items: List<PickingWorkItem> = emptyList(),
    val page: Int = 0,
    val size: Int = 25,
    val totalItems: Long = 0,
    val asOf: Instant? = null
)
