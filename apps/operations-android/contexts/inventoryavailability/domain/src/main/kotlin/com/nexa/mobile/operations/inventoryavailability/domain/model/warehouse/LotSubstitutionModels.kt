package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

data class LotSubstitutionWork(
    val fulfillmentId: String,
    val allocationId: String,
    val allocationLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val expectedLotId: String,
    val warehouseId: String,
    val zoneId: String?,
    val preparedQuantityText: String,
    val unit: String,
    val allocationVersion: Long
) {
    override fun toString(): String =
        "LotSubstitutionWork(allocationVersion=$allocationVersion, quantity=REDACTED)"
}

/** Current stock facts for a possible alternative. The server revalidates every fact on submit. */

data class LotSubstitutionAlternative(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val skuId: String?,
    val catalogItemId: String?,
    val batchNumber: String,
    val expirationDate: String,
    val availableText: String,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String =
        "LotSubstitutionAlternative(status=$status, quantity=REDACTED, version=$version)"
}

/** Server-created request fact. It never claims that the allocation was changed. */

data class LotSubstitutionRequest(
    val id: String,
    val expectedLotId: String,
    val alternativeLotId: String,
    val quantityText: String,
    val reason: String,
    val status: String,
    val currentAllocationVersion: Long
) {
    override fun toString(): String =
        "LotSubstitutionRequest(status=$status, version=$currentAllocationVersion, quantity=REDACTED)"
}

data class LotSubstitutionCurrentFacts(
    val allocationId: String,
    val version: Long,
    val expectedLotId: String?,
    val quantityText: String?,
    val unit: String?
)

fun LotSubstitutionWork?.isUsable(): Boolean {
    if (this == null) return false
    val quantity = preparedQuantityText.toBigDecimalOrNull() ?: return false
    return UUID_TEXT.matches(fulfillmentId) && UUID_TEXT.matches(allocationId) &&
        UUID_TEXT.matches(allocationLineId) && UUID_TEXT.matches(skuId) &&
        UUID_TEXT.matches(expectedLotId) && UUID_TEXT.matches(warehouseId) &&
        (zoneId == null || UUID_TEXT.matches(zoneId)) &&
        catalogItemId.isNotBlank() && quantity.signum() > 0 && allocationVersion >= 0 &&
        unit.isNotBlank()
}

fun LotSubstitutionAlternative?.isEligibleFor(work: LotSubstitutionWork?): Boolean {
    if (this == null || work == null) return false
    val available = availableText.toBigDecimalOrNull() ?: return false
    return UUID_TEXT.matches(id) && id != work.expectedLotId &&
        warehouseId == work.warehouseId && skuId == work.skuId &&
        catalogItemId == work.catalogItemId && unit.equals(work.unit, ignoreCase = true) &&
        status.equals("AVAILABLE", ignoreCase = true) &&
        available >= (work.preparedQuantityText.toBigDecimalOrNull() ?: return false) &&
        version >= 0 && batchNumber.isNotBlank() && expirationDate.isNotBlank()
}

fun String.isValidSubstitutionReason(): Boolean =
    isNotBlank() && this == trim() && length <= 2_000 && none(Char::isISOControl)

private val UUID_TEXT = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
