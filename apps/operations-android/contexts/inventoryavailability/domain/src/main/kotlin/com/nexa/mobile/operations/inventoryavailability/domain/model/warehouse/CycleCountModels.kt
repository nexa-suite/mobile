package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

data class CycleCountLot(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val batchNumber: String,
    val expirationDate: String,
    val onHandText: String,
    val reservedText: String,
    val availableText: String,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String =
        "CycleCountLot(status=$status, version=$version, stock=REDACTED)"
}

data class CycleCountRecord(
    val id: String,
    val lotId: String,
    val warehouseId: String,
    val zoneId: String,
    val lotVersion: Long,
    val expectedQuantityText: String,
    val observedQuantityText: String,
    val unit: String,
    val status: String,
    val actorMembershipId: String,
    val recordedAt: String
) {
    override fun toString(): String =
        "CycleCountRecord(status=$status, version=$lotVersion, quantities=REDACTED)"
}

data class CycleCountCorrection(
    val id: String,
    val cycleCountId: String,
    val lotId: String,
    val warehouseId: String,
    val zoneId: String,
    val lotVersionBefore: Long,
    val lotVersionAfter: Long,
    val quantityBeforeText: String,
    val quantityAfterText: String,
    val quantityDeltaText: String,
    val unit: String,
    val actorMembershipId: String,
    val recordedAt: String
) {
    override fun toString(): String =
        "CycleCountCorrection(version=$lotVersionAfter, quantities=REDACTED)"
}
