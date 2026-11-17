package com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse

import java.time.Instant

/** Non-authoritative, summary-only projection of one server-confirmed picking work page. */
data class PickingWorkListCacheProjection(
    val items: List<PickingWorkListCacheItem>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val asOf: Instant
)

/** Fields allowed in a local picking work list cache. It intentionally excludes allocation data. */
data class PickingWorkListCacheItem(
    val fulfillmentId: String,
    val salesOrderId: String,
    val orderNumber: String?,
    val status: String,
    val version: Long,
    val lineCount: Int
)
