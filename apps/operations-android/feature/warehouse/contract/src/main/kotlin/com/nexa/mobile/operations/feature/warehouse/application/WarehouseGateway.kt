package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.model.CandidateConfirmationResult
import com.nexa.mobile.operations.feature.warehouse.model.ProductCandidate
import com.nexa.mobile.operations.feature.warehouse.model.ProductSearchResult

/** Client port. Search and confirmation outcomes must come from authoritative service responses. */
interface WarehouseGateway {
    suspend fun search(query: String, pageKey: String?, authorityEpoch: Long): ProductSearchResult

    suspend fun confirm(
        candidate: ProductCandidate,
        authorityEpoch: Long,
        context: ActiveOperationsContext
    ): CandidateConfirmationResult
}
