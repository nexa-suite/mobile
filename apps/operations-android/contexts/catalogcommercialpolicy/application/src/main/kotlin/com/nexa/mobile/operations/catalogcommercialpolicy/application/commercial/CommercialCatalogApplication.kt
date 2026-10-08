package com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial

import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductFacts
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority

sealed interface CommercialCatalogResult {
    data class Choices(val items: List<CommercialProductChoice>, val nextPage: String?) :
        CommercialCatalogResult
    data class Product(val value: CommercialProductFacts) : CommercialCatalogResult
    data object Unavailable : CommercialCatalogResult
    data object PermissionDenied : CommercialCatalogResult
}

interface CommercialCatalogGateway {
    suspend fun search(
        authority: CommercialAuthority,
        customerId: String,
        query: String,
        page: String?
    ): CommercialCatalogResult
    suspend fun detail(
        authority: CommercialAuthority,
        customerId: String,
        id: String
    ): CommercialCatalogResult
}
