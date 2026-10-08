package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class PickingAllocationProjection(
    val allocationId: String,
    val status: String,
    val version: Long,
    val asOf: Instant,
    val lines: List<PickingAllocationLine>
) {
    override fun toString(): String =
        "PickingAllocationProjection(status=$status, version=$version, lines=${lines.size})"
}

data class PickingAllocationLine(
    val physicalAllocationLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val warehouseId: String,
    val zoneId: String?,
    val lotId: String,
    val quantity: BigDecimal,
    val releasedQuantity: BigDecimal,
    val consumedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String,
    val expirationDate: LocalDate?
) {
    override fun toString(): String =
        "PickingAllocationLine(lotId=REDACTED, remaining=$remainingQuantity, unit=$unit)"
}

/** Typed proposal joined by server identifiers; a match is usable only when unique. */
