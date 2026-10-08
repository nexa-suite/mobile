package com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse

import java.util.UUID

enum class ScannerIdentifierType { SkuCode, Gtin, SkuCodeAndGtin }

data class ConfirmedScannedSku(
    val skuId: UUID,
    val skuCode: String,
    val gtin: String?,
    val presentation: String,
    val unitOfMeasure: String?,
    val status: String,
    val identifierType: ScannerIdentifierType,
    val context: CatalogOperationsContext,
    val authorityEpoch: Long
) {
    override fun toString(): String =
        "ConfirmedScannedSku(skuId=REDACTED, skuCode=REDACTED, authorityEpoch=$authorityEpoch)"
}
