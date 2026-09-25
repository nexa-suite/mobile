package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable

enum class WarehouseRoute { WorkEntry, ProductSearch, ConfirmedSku }

enum class WorkEntryStatus {
    TaskAvailable,
    NoPermittedTask,
    PermissionUnavailable,
    ContextInvalidated,
    SessionInvalidated
}

enum class TaskVisibilityHint { Available, Unavailable, Unknown }

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
data class ActiveOperationsContext(
    val companyName: String,
    val workspaceName: String,
    val authorityEpoch: Long
)

@Immutable
data class ProductCandidate(
    val key: String,
    val productDisplayName: String,
    val brandOrVariant: String?,
    val presentation: String,
    val sku: String
) {
    override fun toString(): String =
        "ProductCandidate(productDisplayName=$productDisplayName, sku=REDACTED, key=REDACTED)"
}

@Immutable
data class ConfirmedSkuUiState(
    val candidateKey: String,
    val productDisplayName: String,
    val variant: String?,
    val presentation: String,
    val sku: String,
    val brand: String?,
    val unit: String?,
    val packaging: String?,
    val coldChain: String?,
    val context: ActiveOperationsContext,
    val authorityEpoch: Long
) {
    override fun toString(): String =
        "ConfirmedSkuUiState(productDisplayName=$productDisplayName, " +
            "sku=REDACTED, authorityEpoch=$authorityEpoch)"
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

@Immutable
data class WarehouseUiState(
    val route: WarehouseRoute = WarehouseRoute.WorkEntry,
    val workEntryStatus: WorkEntryStatus = WorkEntryStatus.ContextInvalidated,
    val permissionHint: TaskVisibilityHint = TaskVisibilityHint.Unknown,
    val activeContext: ActiveOperationsContext? = null,
    val search: ProductSearchUiState? = null,
    val confirmedSku: ConfirmedSkuUiState? = null,
    val authorityEpoch: Long = 0
) {
    override fun toString(): String =
        "WarehouseUiState(route=$route, workEntryStatus=$workEntryStatus, authorityEpoch=$authorityEpoch)"
}

sealed interface ProductSearchResult {
    data class Page(val items: List<ProductCandidate>, val nextPageKey: String?) :
        ProductSearchResult
    data object NetworkUnavailable : ProductSearchResult
    data object ServiceUnavailable : ProductSearchResult
    data object PermissionDenied : ProductSearchResult
    data object ContextInvalidated : ProductSearchResult
    data object SessionInvalidated : ProductSearchResult
    data object IntegrationUnavailable : ProductSearchResult
}

sealed interface CandidateConfirmationResult {
    data class Confirmed(val sku: ConfirmedSkuUiState) : CandidateConfirmationResult
    data object CandidateUnavailable : CandidateConfirmationResult
    data object NetworkUnavailable : CandidateConfirmationResult
    data object ServiceUnavailable : CandidateConfirmationResult
    data object PermissionDenied : CandidateConfirmationResult
    data object ContextInvalidated : CandidateConfirmationResult
    data object SessionInvalidated : CandidateConfirmationResult
    data object IntegrationUnavailable : CandidateConfirmationResult
    data object UnknownOutcome : CandidateConfirmationResult
}

/** Client port. Search and confirmation outcomes must come from authoritative service responses. */
interface WarehouseGateway {
    suspend fun search(query: String, pageKey: String?, authorityEpoch: Long): ProductSearchResult

    suspend fun confirm(
        candidate: ProductCandidate,
        authorityEpoch: Long,
        context: ActiveOperationsContext
    ): CandidateConfirmationResult
}
