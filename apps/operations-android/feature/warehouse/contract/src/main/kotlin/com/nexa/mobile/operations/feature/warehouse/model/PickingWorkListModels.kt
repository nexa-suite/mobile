package com.nexa.mobile.operations.feature.warehouse.model

import java.time.Instant

data class PickingWorkItem(
    val fulfillmentId: String,
    val salesOrderId: String,
    val status: String,
    val version: Long,
    val physicalAllocationId: String,
    val allocationVersion: Long,
    val lineCount: Int
)

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
