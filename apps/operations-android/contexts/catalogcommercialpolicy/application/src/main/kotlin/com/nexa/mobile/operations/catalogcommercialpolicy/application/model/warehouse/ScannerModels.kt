package com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse

import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedScannedSku

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
