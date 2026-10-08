package com.nexa.mobile.operations

import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductScannerUiState
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
import com.nexa.mobile.operations.workentry.TaskVisibilityHint
import com.nexa.mobile.operations.workentry.WarehouseRoute
import com.nexa.mobile.operations.workentry.WarehouseUiState
import com.nexa.mobile.operations.workentry.WorkEntryCapability
import com.nexa.mobile.operations.workentry.WorkEntryStatus

internal enum class OperationsRouteIdentity(val stableId: String) {
    CatalogSearch("operations.warehouse.catalog-search"),
    ConfirmedSku("operations.warehouse.confirmed-sku"),
    Receiving("operations.warehouse.receiving"),
    Picking("operations.warehouse.picking"),
    StockCondition("operations.warehouse.stock-condition"),
    BarcodeScanner("operations.warehouse.barcode-scanner")
}

internal enum class OperationsFeatureOwner { Warehouse }

internal enum class CapabilityGuardBehavior {
    SearchInCurrentAuthorityEpoch,
    ConfirmedSkuFromSearchInCurrentAuthorityEpoch,
    ScannerInCurrentAuthorityEpoch,
    ReceivingInCurrentAuthorityEpoch,
    StockConditionInCurrentAuthorityEpoch,
    PickingInCurrentAuthorityEpoch
}

internal enum class DeepRouteInvalidationBehavior { ReturnToEntry }

/** Root-owned registry for routes backed by an existing permission hint and real feature action. */
internal data class OperationsCapability(
    val routeIdentity: OperationsRouteIdentity,
    val destination: WarehouseRoute,
    val entryDestination: WarehouseRoute,
    val requiredProductCapability: WorkEntryCapability,
    val requiredContextPermissionHint: PermissionHint,
    val requiredTaskVisibilityHint: TaskVisibilityHint,
    val featureOwner: OperationsFeatureOwner,
    val guardBehavior: CapabilityGuardBehavior,
    val deepRouteInvalidationBehavior: DeepRouteInvalidationBehavior
)

internal object OperationsCapabilities {
    val registered = listOf(
        OperationsCapability(
            routeIdentity = OperationsRouteIdentity.CatalogSearch,
            destination = WarehouseRoute.ProductSearch,
            entryDestination = WarehouseRoute.WorkEntry,
            requiredProductCapability = WorkEntryCapability.CatalogIdentification,
            requiredContextPermissionHint = PermissionHint.Available,
            requiredTaskVisibilityHint = TaskVisibilityHint.Available,
            featureOwner = OperationsFeatureOwner.Warehouse,
            guardBehavior = CapabilityGuardBehavior.SearchInCurrentAuthorityEpoch,
            deepRouteInvalidationBehavior = DeepRouteInvalidationBehavior.ReturnToEntry
        ),
        OperationsCapability(
            routeIdentity = OperationsRouteIdentity.ConfirmedSku,
            destination = WarehouseRoute.ConfirmedSku,
            entryDestination = WarehouseRoute.ProductSearch,
            requiredProductCapability = WorkEntryCapability.CatalogIdentification,
            requiredContextPermissionHint = PermissionHint.Available,
            requiredTaskVisibilityHint = TaskVisibilityHint.Available,
            featureOwner = OperationsFeatureOwner.Warehouse,
            guardBehavior =
                CapabilityGuardBehavior.ConfirmedSkuFromSearchInCurrentAuthorityEpoch,
            deepRouteInvalidationBehavior = DeepRouteInvalidationBehavior.ReturnToEntry
        ),
        OperationsCapability(
            routeIdentity = OperationsRouteIdentity.Picking,
            destination = WarehouseRoute.Picking,
            entryDestination = WarehouseRoute.WorkEntry,
            requiredProductCapability = WorkEntryCapability.Picking,
            requiredContextPermissionHint = PermissionHint.Available,
            requiredTaskVisibilityHint = TaskVisibilityHint.Available,
            featureOwner = OperationsFeatureOwner.Warehouse,
            guardBehavior = CapabilityGuardBehavior.PickingInCurrentAuthorityEpoch,
            deepRouteInvalidationBehavior = DeepRouteInvalidationBehavior.ReturnToEntry
        ),
        OperationsCapability(
            routeIdentity = OperationsRouteIdentity.StockCondition,
            destination = WarehouseRoute.StockCondition,
            entryDestination = WarehouseRoute.WorkEntry,
            requiredProductCapability = WorkEntryCapability.StockCondition,
            requiredContextPermissionHint = PermissionHint.Available,
            requiredTaskVisibilityHint = TaskVisibilityHint.Available,
            featureOwner = OperationsFeatureOwner.Warehouse,
            guardBehavior = CapabilityGuardBehavior.StockConditionInCurrentAuthorityEpoch,
            deepRouteInvalidationBehavior = DeepRouteInvalidationBehavior.ReturnToEntry
        ),
        OperationsCapability(
            routeIdentity = OperationsRouteIdentity.Receiving,
            destination = WarehouseRoute.Receiving,
            entryDestination = WarehouseRoute.WorkEntry,
            requiredProductCapability = WorkEntryCapability.Receiving,
            requiredContextPermissionHint = PermissionHint.Available,
            requiredTaskVisibilityHint = TaskVisibilityHint.Available,
            featureOwner = OperationsFeatureOwner.Warehouse,
            guardBehavior = CapabilityGuardBehavior.ReceivingInCurrentAuthorityEpoch,
            deepRouteInvalidationBehavior = DeepRouteInvalidationBehavior.ReturnToEntry
        ),
        OperationsCapability(
            routeIdentity = OperationsRouteIdentity.BarcodeScanner,
            destination = WarehouseRoute.Scanner,
            entryDestination = WarehouseRoute.WorkEntry,
            requiredProductCapability = WorkEntryCapability.BarcodeIdentification,
            requiredContextPermissionHint = PermissionHint.Available,
            requiredTaskVisibilityHint = TaskVisibilityHint.Available,
            featureOwner = OperationsFeatureOwner.Warehouse,
            guardBehavior = CapabilityGuardBehavior.ScannerInCurrentAuthorityEpoch,
            deepRouteInvalidationBehavior = DeepRouteInvalidationBehavior.ReturnToEntry
        )
    )

    fun workEntryCapabilities(
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): List<WorkEntryCapability> = registered.asSequence()
        .filter { it.entryDestination == WarehouseRoute.WorkEntry }
        .filter { hasCurrentAuthority(session, access, warehouse, it) }
        .map { it.requiredProductCapability }
        .distinct()
        .toList()

    fun permitsEntry(
        destination: WarehouseRoute,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): Boolean {
        val capability = capabilityFor(destination) ?: return false
        return hasCurrentAuthority(session, access, warehouse, capability)
    }

    fun permits(
        destination: WarehouseRoute,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState,
        scanner: ProductScannerUiState = ProductScannerUiState.PermissionNotRequested(0)
    ): Boolean {
        val capability = capabilityFor(destination) ?: return false
        if (!hasCurrentAuthority(session, access, warehouse, capability)) return false
        return when (capability.guardBehavior) {
            CapabilityGuardBehavior.ReceivingInCurrentAuthorityEpoch,
            CapabilityGuardBehavior.StockConditionInCurrentAuthorityEpoch,
            CapabilityGuardBehavior.PickingInCurrentAuthorityEpoch -> true

            CapabilityGuardBehavior.SearchInCurrentAuthorityEpoch ->
                warehouse.search?.authorityEpoch == access.authorityEpoch

            CapabilityGuardBehavior.ConfirmedSkuFromSearchInCurrentAuthorityEpoch ->
                warehouse.confirmedSku?.authorityEpoch == access.authorityEpoch &&
                    warehouse.search?.authorityEpoch == access.authorityEpoch

            CapabilityGuardBehavior.ScannerInCurrentAuthorityEpoch ->
                scanner.authorityEpoch == access.authorityEpoch
        }
    }

    fun deepRouteInvalidationBackCount(destination: WarehouseRoute): Int {
        var current = destination
        val visited = mutableSetOf<WarehouseRoute>()
        var backCount = 0
        while (current != WarehouseRoute.WorkEntry) {
            if (!visited.add(current)) return 0
            val capability = capabilityFor(current) ?: return 0
            when (capability.deepRouteInvalidationBehavior) {
                DeepRouteInvalidationBehavior.ReturnToEntry -> {
                    backCount++
                    current = capability.entryDestination
                }
            }
        }
        return backCount
    }

    private fun capabilityFor(destination: WarehouseRoute): OperationsCapability? =
        registered.singleOrNull { it.destination == destination }

    private fun hasCurrentAuthority(
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState,
        capability: OperationsCapability
    ): Boolean {
        if (session != SessionState.Active || access.stage != AccessStage.WorkAuthorized) {
            return false
        }
        val context = access.activeContext ?: return false
        val warehouseContext = warehouse.activeContext ?: return false
        if (!context.isCurrent ||
            (
                capability.guardBehavior !=
                    CapabilityGuardBehavior.ReceivingInCurrentAuthorityEpoch &&
                    capability.guardBehavior !=
                    CapabilityGuardBehavior.StockConditionInCurrentAuthorityEpoch &&
                    capability.guardBehavior !=
                    CapabilityGuardBehavior.PickingInCurrentAuthorityEpoch &&
                    context.permissionHint != capability.requiredContextPermissionHint
                )
        ) {
            return false
        }
        if (warehouse.permissionHint != capability.requiredTaskVisibilityHint ||
            warehouse.workEntryStatus != WorkEntryStatus.TaskAvailable
        ) {
            return false
        }
        if (warehouse.authorityEpoch != access.authorityEpoch ||
            warehouseContext.authorityEpoch != access.authorityEpoch ||
            warehouseContext.companyName != context.companyName ||
            warehouseContext.workspaceName != context.workspaceName
        ) {
            return false
        }
        if (capability.guardBehavior == CapabilityGuardBehavior.ScannerInCurrentAuthorityEpoch ||
            capability.guardBehavior == CapabilityGuardBehavior.ReceivingInCurrentAuthorityEpoch ||
            capability.guardBehavior ==
            CapabilityGuardBehavior.StockConditionInCurrentAuthorityEpoch ||
            capability.guardBehavior == CapabilityGuardBehavior.PickingInCurrentAuthorityEpoch
        ) {
            val authority = context.verifiedAuthority ?: return false
            val scannerIdentity = warehouseContext.verifiedIdentity ?: return false
            if (
                capability.guardBehavior ==
                CapabilityGuardBehavior.PickingInCurrentAuthorityEpoch &&
                authority.permissions.none { it == "fulfillment.read" || it == "fulfillment:read" }
            ) {
                return false
            }
            if (capability.guardBehavior ==
                CapabilityGuardBehavior.ReceivingInCurrentAuthorityEpoch &&
                authority.permissions.none { it == "inventory.receive" || it == "warehouse:write" }
            ) {
                return false
            }
            if (capability.guardBehavior ==
                CapabilityGuardBehavior.StockConditionInCurrentAuthorityEpoch &&
                authority.permissions.none {
                    it in
                        setOf("warehouse.read", "inventory.read", "warehouse:read")
                }
            ) {
                return false
            }
            if (scannerIdentity.userId != authority.userId ||
                scannerIdentity.tenantId != authority.tenantId ||
                scannerIdentity.workspaceId != authority.workspaceId ||
                scannerIdentity.membershipId != authority.membershipId ||
                scannerIdentity.permissions != authority.permissions
            ) {
                return false
            }
        }
        return true
    }
}
