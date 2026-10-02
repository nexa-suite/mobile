package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import java.time.Instant

@Immutable
data class PickingWorkItem(
    val fulfillmentId: String,
    val salesOrderId: String,
    val status: String,
    val version: Long,
    val physicalAllocationId: String,
    val allocationVersion: Long,
    val lineCount: Int
)

@Immutable
data class PickingWorkPage(
    val items: List<PickingWorkItem>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val asOf: Instant
)

sealed interface PickingWorkListResult {
    data class Loaded(val value: PickingWorkPage) : PickingWorkListResult
    data object NetworkUnavailable : PickingWorkListResult
    data object ServiceUnavailable : PickingWorkListResult
    data object PermissionDenied : PickingWorkListResult
    data object ContextInvalidated : PickingWorkListResult
    data object SessionInvalidated : PickingWorkListResult
}

/** Client port for server-authorized prepared fulfillment work. */
interface PickingWorkListGateway {
    suspend fun list(authority: PickingAuthority, page: Int, size: Int): PickingWorkListResult
}

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
