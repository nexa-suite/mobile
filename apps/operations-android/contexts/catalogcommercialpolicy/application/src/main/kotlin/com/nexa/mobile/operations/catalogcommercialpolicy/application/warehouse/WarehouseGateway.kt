package com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse

import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductSearchResult
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.operations.ActiveOperationsContext

/** Client port. Search and confirmation outcomes must come from authoritative service responses. */
interface WarehouseGateway {
    suspend fun search(query: String, pageKey: String?, authorityEpoch: Long): ProductSearchResult

    suspend fun confirm(
        candidate: ProductCandidate,
        authorityEpoch: Long,
        context: ActiveOperationsContext
    ): CandidateConfirmationResult
}
