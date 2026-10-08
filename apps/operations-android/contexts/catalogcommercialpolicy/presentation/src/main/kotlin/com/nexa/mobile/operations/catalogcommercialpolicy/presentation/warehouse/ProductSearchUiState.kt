package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse

import androidx.compose.runtime.Immutable
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

@Immutable
data class ProductSearchUiState(
    val query: String = "",
    val status: ProductSearchStatus = ProductSearchStatus.Initial,
    val candidates: List<ProductCandidate> = emptyList(),
    val nextPageKey: String? = null,
    val pendingCandidateKey: String? = null,
    val errorMessage: ProductSearchStatus? = null,
    val authorityEpoch: Long = 0
) {
    override fun toString(): String =
        "ProductSearchUiState(queryPresent=${query.isNotBlank()}, status=$status, " +
            "candidates=${candidates.size}, authorityEpoch=$authorityEpoch)"
}
