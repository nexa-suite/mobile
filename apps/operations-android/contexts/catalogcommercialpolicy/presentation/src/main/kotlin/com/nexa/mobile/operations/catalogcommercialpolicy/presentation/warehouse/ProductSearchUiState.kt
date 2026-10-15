package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate

enum class ProductSearchStatus {
    Initial,
    Typing,
    InvalidQuery,
    Loading,
    OneCandidate,
    MultipleCandidates,
    Empty,
    LoadingMore,
    LoadMoreFailed,
    ConfirmationPending,
    CandidateUnavailable,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    IntegrationUnavailable
}

enum class ProductFilterStatus {
    Idle,
    Loading,
    Ready,
    Empty,
    LoadingMore,
    LoadMoreFailed,
    InvalidQuery,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

@Immutable
data class ProductSearchUiState(
    val query: String = "",
    val status: ProductSearchStatus = ProductSearchStatus.Initial,
    val candidates: List<ProductCandidate> = emptyList(),
    val nextPageKey: String? = null,
    val pendingCandidateKey: String? = null,
    val errorMessage: ProductSearchStatus? = null,
    val brand: WarehouseCatalogFilterChoice? = null,
    val category: WarehouseCatalogFilterChoice? = null,
    val filterKind: WarehouseCatalogFilterKind? = null,
    val filterSearch: String = "",
    val filterOptions: List<WarehouseCatalogFilterChoice> = emptyList(),
    val filterPage: Int = 0,
    val nextFilterPage: Int? = null,
    val filterTotal: Long = 0,
    val filterStatus: ProductFilterStatus = ProductFilterStatus.Idle,
    val authorityEpoch: Long = 0
) {
    override fun toString(): String =
        "ProductSearchUiState(queryPresent=${query.isNotBlank()}, status=$status, " +
            "candidates=${candidates.size}, filterOptions=${filterOptions.size}, " +
            "authorityEpoch=$authorityEpoch)"
}
