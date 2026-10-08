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
