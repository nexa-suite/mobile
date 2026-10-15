package com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial

import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductFacts
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority

enum class CommercialCatalogFilterKind(val wireValue: String) {
    BRAND("brand"),
    CATEGORY("category")
}

data class CommercialCatalogFilterChoice(val id: String, val name: String)

sealed interface CommercialCatalogFilterOptionsResult {
    data class Choices(
        val items: List<CommercialCatalogFilterChoice>,
        val page: Int,
        val size: Int,
        val total: Long
    ) : CommercialCatalogFilterOptionsResult

    data object Unavailable : CommercialCatalogFilterOptionsResult
    data object PermissionDenied : CommercialCatalogFilterOptionsResult
}

sealed interface CommercialCatalogResult {
    data class Choices(val items: List<CommercialProductChoice>, val nextPage: String?) :
        CommercialCatalogResult
    data class Product(val value: CommercialProductFacts) : CommercialCatalogResult
    data object Unavailable : CommercialCatalogResult
    data object PermissionDenied : CommercialCatalogResult
}

interface CommercialCatalogGateway {
    suspend fun filterOptions(
        authority: CommercialAuthority,
        kind: CommercialCatalogFilterKind,
        search: String,
        page: Int
    ): CommercialCatalogFilterOptionsResult = CommercialCatalogFilterOptionsResult.Unavailable

    suspend fun search(
        authority: CommercialAuthority,
        customerId: String,
        query: String,
        page: String?
    ): CommercialCatalogResult
    suspend fun search(
        authority: CommercialAuthority,
        customerId: String,
        query: String,
        brandName: String,
        categoryName: String,
        page: String?
    ): CommercialCatalogResult = search(authority, customerId, query, page)
    suspend fun detail(
        authority: CommercialAuthority,
        customerId: String,
        id: String
    ): CommercialCatalogResult
}
