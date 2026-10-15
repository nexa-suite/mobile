package com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse

import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate

sealed interface ProductSearchResult {
    data class Page(val items: List<ProductCandidate>, val nextPageKey: String?) :
        ProductSearchResult
    data object InvalidQuery : ProductSearchResult
    data object NetworkUnavailable : ProductSearchResult
    data object ServiceUnavailable : ProductSearchResult
    data object PermissionDenied : ProductSearchResult
    data object ContextInvalidated : ProductSearchResult
    data object SessionInvalidated : ProductSearchResult
    data object IntegrationUnavailable : ProductSearchResult
}

enum class WarehouseCatalogFilterKind(val wireValue: String) {
    Brand("brand"),
    Category("category")
}

data class WarehouseCatalogFilterChoice(val id: String, val name: String)

data class WarehouseCatalogFilterPage(
    val items: List<WarehouseCatalogFilterChoice>,
    val page: Int,
    val size: Int,
    val total: Long
)

sealed interface ProductFilterOptionsResult {
    data class Page(val value: WarehouseCatalogFilterPage) : ProductFilterOptionsResult
    data object InvalidQuery : ProductFilterOptionsResult
    data object NetworkUnavailable : ProductFilterOptionsResult
    data object ServiceUnavailable : ProductFilterOptionsResult
    data object PermissionDenied : ProductFilterOptionsResult
    data object ContextInvalidated : ProductFilterOptionsResult
    data object SessionInvalidated : ProductFilterOptionsResult
}

sealed interface CandidateConfirmationResult {
    data class Confirmed(val sku: ConfirmedSkuProjection) : CandidateConfirmationResult
    data object CandidateUnavailable : CandidateConfirmationResult
    data object NetworkUnavailable : CandidateConfirmationResult
    data object ServiceUnavailable : CandidateConfirmationResult
    data object PermissionDenied : CandidateConfirmationResult
    data object ContextInvalidated : CandidateConfirmationResult
    data object SessionInvalidated : CandidateConfirmationResult
    data object IntegrationUnavailable : CandidateConfirmationResult
    data object UnknownOutcome : CandidateConfirmationResult
}
