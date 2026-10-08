package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.model.ProductScannerResolution

/** Read-only Catalog resolution port. Result fields are server projections, never stock facts. */
interface ProductScannerGateway {
    suspend fun resolve(
        candidate: String,
        authorityEpoch: Long,
        context: ActiveOperationsContext
    ): ProductScannerResolution
}
