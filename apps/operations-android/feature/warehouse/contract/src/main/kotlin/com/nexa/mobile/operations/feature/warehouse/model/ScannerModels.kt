package com.nexa.mobile.operations.feature.warehouse.model

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
    val context: ActiveOperationsContext,
    val authorityEpoch: Long
) {
    override fun toString(): String =
        "ConfirmedScannedSku(skuId=REDACTED, skuCode=REDACTED, authorityEpoch=$authorityEpoch)"
}

sealed interface ProductScannerResolution {
    data class Resolved(val sku: ConfirmedScannedSku) : ProductScannerResolution
    data object NotFound : ProductScannerResolution
    data class Ambiguous(val candidateCount: Int) : ProductScannerResolution
    data object InvalidIdentifier : ProductScannerResolution
    data object NetworkUnavailable : ProductScannerResolution
    data object ServiceUnavailable : ProductScannerResolution
    data object PermissionDenied : ProductScannerResolution
    data object ContextInvalidated : ProductScannerResolution
    data object SessionInvalidated : ProductScannerResolution
}
