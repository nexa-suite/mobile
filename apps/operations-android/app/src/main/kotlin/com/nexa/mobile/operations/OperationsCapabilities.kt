package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.warehouse.TaskVisibilityHint
import com.nexa.mobile.operations.feature.warehouse.WarehouseRoute
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import com.nexa.mobile.operations.feature.warehouse.WorkEntryCapability
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus

internal enum class OperationsRouteIdentity(val stableId: String) {
    CatalogSearch("operations.warehouse.catalog-search"),
    ConfirmedSku("operations.warehouse.confirmed-sku")
}

internal enum class OperationsFeatureOwner { Warehouse }

internal enum class CapabilityGuardBehavior {
    SearchInCurrentAuthorityEpoch,
    ConfirmedSkuFromSearchInCurrentAuthorityEpoch
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
        warehouse: WarehouseUiState
    ): Boolean {
        val capability = capabilityFor(destination) ?: return false
        if (!hasCurrentAuthority(session, access, warehouse, capability)) return false
        return when (capability.guardBehavior) {
            CapabilityGuardBehavior.SearchInCurrentAuthorityEpoch ->
                warehouse.search?.authorityEpoch == access.authorityEpoch

            CapabilityGuardBehavior.ConfirmedSkuFromSearchInCurrentAuthorityEpoch ->
                warehouse.confirmedSku?.authorityEpoch == access.authorityEpoch &&
                    warehouse.search?.authorityEpoch == access.authorityEpoch
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
            context.permissionHint != capability.requiredContextPermissionHint
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
        return true
    }
}
