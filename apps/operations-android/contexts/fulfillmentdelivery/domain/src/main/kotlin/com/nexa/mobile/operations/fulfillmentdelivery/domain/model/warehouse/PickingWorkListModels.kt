package com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse

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
