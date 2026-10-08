package com.nexa.mobile.operations.inventoryavailability.application.publicapi

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Immutable physical-allocation facts published for cross-context client reads. */
data class PhysicalAllocationProjection(
    val allocationId: String,
    val status: String,
    val version: Long,
    val asOf: Instant,
    val lines: List<PhysicalAllocationLineProjection>
) {
    override fun toString(): String =
        "PhysicalAllocationProjection(status=$status, version=$version, lines=${lines.size})"
}

data class PhysicalAllocationLineProjection(
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
        "PhysicalAllocationLineProjection(lotId=REDACTED, remaining=$remainingQuantity, unit=$unit)"
}
