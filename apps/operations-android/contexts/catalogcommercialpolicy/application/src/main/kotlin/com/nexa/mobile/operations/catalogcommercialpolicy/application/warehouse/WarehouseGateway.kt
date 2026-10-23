package com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse

import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductFilterOptionsResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductSearchResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate

/** Client port. Search and confirmation outcomes must come from authoritative service responses. */
interface WarehouseGateway {
    suspend fun search(
        query: String,
        brandName: String?,
        categoryName: String?,
        pageKey: String?,
        authorityEpoch: Long
    ): ProductSearchResult

    suspend fun filterOptions(
        kind: WarehouseCatalogFilterKind,
        search: String,
        page: Int,
        authorityEpoch: Long
    ): ProductFilterOptionsResult

    suspend fun confirm(
        candidate: ProductCandidate,
        authorityEpoch: Long,
        context: CatalogOperationsContext
    ): CandidateConfirmationResult
}
