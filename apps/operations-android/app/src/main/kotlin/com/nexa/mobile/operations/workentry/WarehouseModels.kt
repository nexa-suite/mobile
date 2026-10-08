package com.nexa.mobile.operations.workentry

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.R
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchStatus
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.operations.ActiveOperationsContext

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
