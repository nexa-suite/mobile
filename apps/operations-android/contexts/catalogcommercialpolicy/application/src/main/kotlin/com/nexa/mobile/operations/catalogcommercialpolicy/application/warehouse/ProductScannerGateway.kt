package com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse

import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductScannerResolution
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.operations.ActiveOperationsContext

/** Read-only Catalog resolution port. Result fields are server projections, never stock facts. */
interface ProductScannerGateway {
    suspend fun resolve(
        candidate: String,
        authorityEpoch: Long,
        context: ActiveOperationsContext
    ): ProductScannerResolution
}
