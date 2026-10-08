package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.feature.warehouse.model.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.model.ConfirmedSkuProjection
import com.nexa.mobile.operations.feature.warehouse.model.ProductCandidate

enum class WarehouseRoute {
    WorkEntry,
    ProductSearch,
    ConfirmedSku,
    Scanner,
    Receiving,
    StockCondition,
    Picking
}

/** A work-entry item must have a registered destination and a real action. */
enum class WorkEntryCapability {
    CatalogIdentification,
    BarcodeIdentification,
    Receiving,
    StockCondition,
    Picking
}

enum class WorkEntryStatus {
    TaskAvailable,
    NoPermittedTask,
    PermissionUnavailable,
    PermissionUnknown,
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
    val confirmedSku: ConfirmedSkuProjection? = null,
    val authorityEpoch: Long = 0,
    val invalidatedFromAuthorityEpoch: Long? = null
) {
    override fun toString(): String =
        "WarehouseUiState(route=$route, workEntryStatus=$workEntryStatus, authorityEpoch=$authorityEpoch)"
}
