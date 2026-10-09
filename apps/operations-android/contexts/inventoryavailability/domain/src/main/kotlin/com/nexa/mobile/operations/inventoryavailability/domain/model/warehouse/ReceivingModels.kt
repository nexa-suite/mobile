package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

import java.math.BigDecimal
import java.time.LocalDate

data class ReceivingProductReference(
    val catalogItemId: String?,
    val skuId: String?,
    val displayName: String,
    val skuCode: String,
    val unit: String
) {
    init {
        require(!catalogItemId.isNullOrBlank() || !skuId.isNullOrBlank())
        require(displayName.isNotBlank())
        require(skuCode.isNotBlank())
        require(catalogItemId == null || CATALOG_ITEM_ID.matches(catalogItemId))
    }

    override fun toString(): String =
        "ReceivingProductReference(displayName=$displayName, identifiers=REDACTED)"

    private companion object {
        val CATALOG_ITEM_ID = Regex("(?i)CAT-[A-Z0-9-]{1,63}")
    }
}

/** A server-confirmed catalog selection from the same active authority epoch. */

data class ConfirmedReceivingProduct(
    val reference: ReceivingProductReference,
    val verifiedAuthorityEpoch: Long
)

data class ReceivingWarehouseChoice(
    val id: String,
    val code: String,
    val name: String,
    val status: String
) {
    val isSelectable: Boolean get() = id.isNotBlank() && status.equals("ACTIVE", ignoreCase = true)
}

data class ReceivingZoneChoice(
    val id: String,
    val warehouseId: String,
    val code: String,
    val name: String,
    val status: String
) {
    val isSelectable: Boolean get() = id.isNotBlank() && status.equals("ACTIVE", ignoreCase = true)
}

/** Immutable command body; quantity remains decimal text/value without client rounding. */

data class ReceivingEvidenceObject(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String,
    val declaredContentType: String,
    val byteSize: Long
) {
    override fun toString(): String =
        "ReceivingEvidenceObject(status=$lifecycleStatus, bytes=$byteSize)"
}

data class ReceivedLotFacts(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val expirationDate: LocalDate,
    val receivedAt: String,
    val onHand: BigDecimal,
    val reserved: BigDecimal,
    val available: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
)
