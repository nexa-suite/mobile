package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import com.nexa.mobile.operations.feature.warehouse.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.ConfirmedSkuUiState
import com.nexa.mobile.operations.feature.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.feature.warehouse.TaskVisibilityHint
import com.nexa.mobile.operations.feature.warehouse.WarehouseRoute
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import com.nexa.mobile.operations.feature.warehouse.WorkEntryCapability
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsCapabilitiesTest {
    private val access = AccessUiState(
        stage = AccessStage.WorkAuthorized,
        activeContext = WorkforceContextSummary(
            "context",
            "Company",
            "Workspace",
            PermissionHint.Available,
            true
        ),
        authorityEpoch = 3
    )
    private val warehouse = WarehouseUiState(
        route = WarehouseRoute.ProductSearch,
        workEntryStatus = WorkEntryStatus.TaskAvailable,
        permissionHint = TaskVisibilityHint.Available,
        activeContext = ActiveOperationsContext("Company", "Workspace", 3),
        search = ProductSearchUiState(authorityEpoch = 3),
        authorityEpoch = 3
    )

    @Test fun confirmedTaskOpensInCurrentContext() {
        assertTrue(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.ProductSearch,
                SessionState.Active,
                access,
                warehouse
            )
        )
        assertTrue(
            OperationsCapabilities.permits(
                WarehouseRoute.ProductSearch,
                SessionState.Active,
                access,
                warehouse
            )
        )
    }

    @Test fun registryHasStableCompleteMetadataForRegisteredWarehouseRoutes() {
        assertEquals(
            listOf(
                "operations.warehouse.catalog-search",
                "operations.warehouse.confirmed-sku"
            ),
            OperationsCapabilities.registered.map { it.routeIdentity.stableId }
        )
        assertEquals(
            OperationsCapabilities.registered.size,
            OperationsCapabilities.registered.map { it.destination }.distinct().size
        )
        assertTrue(
            OperationsCapabilities.registered.all {
                it.requiredProductCapability == WorkEntryCapability.CatalogIdentification &&
                    it.requiredContextPermissionHint == PermissionHint.Available &&
                    it.requiredTaskVisibilityHint == TaskVisibilityHint.Available &&
                    it.featureOwner == OperationsFeatureOwner.Warehouse &&
                    it.deepRouteInvalidationBehavior ==
                    DeepRouteInvalidationBehavior.ReturnToEntry
            }
        )
        assertEquals(
            CapabilityGuardBehavior.SearchInCurrentAuthorityEpoch,
            OperationsCapabilities.registered.single {
                it.destination == WarehouseRoute.ProductSearch
            }.guardBehavior
        )
        assertEquals(
            CapabilityGuardBehavior.ConfirmedSkuFromSearchInCurrentAuthorityEpoch,
            OperationsCapabilities.registered.single {
                it.destination == WarehouseRoute.ConfirmedSku
            }.guardBehavior
        )
        assertEquals(
            1,
            OperationsCapabilities.deepRouteInvalidationBackCount(
                WarehouseRoute.ProductSearch
            )
        )
        assertEquals(
            2,
            OperationsCapabilities.deepRouteInvalidationBackCount(WarehouseRoute.ConfirmedSku)
        )
        assertEquals(
            0,
            OperationsCapabilities.deepRouteInvalidationBackCount(WarehouseRoute.WorkEntry)
        )
    }

    @Test fun workEntryDestinationsComeFromCurrentRegisteredCapabilities() {
        assertEquals(
            listOf(WorkEntryCapability.CatalogIdentification),
            OperationsCapabilities.workEntryCapabilities(
                SessionState.Active,
                access,
                warehouse
            )
        )
        assertTrue(
            OperationsCapabilities.workEntryCapabilities(
                SessionState.Active,
                access,
                warehouse.copy(permissionHint = TaskVisibilityHint.Unknown)
            ).isEmpty()
        )
        for (hint in listOf(PermissionHint.Unavailable, PermissionHint.Unknown)) {
            assertTrue(
                OperationsCapabilities.workEntryCapabilities(
                    SessionState.Active,
                    access.copy(
                        activeContext = access.activeContext?.copy(permissionHint = hint)
                    ),
                    warehouse
                ).isEmpty()
            )
        }
        for (hint in listOf(TaskVisibilityHint.Unavailable, TaskVisibilityHint.Unknown)) {
            assertTrue(
                OperationsCapabilities.workEntryCapabilities(
                    SessionState.Active,
                    access,
                    warehouse.copy(permissionHint = hint)
                ).isEmpty()
            )
        }
    }

    @Test fun missingChangedOrUnknownHintBlocksTask() {
        for (hint in listOf(PermissionHint.Unavailable, PermissionHint.Unknown)) {
            assertFalse(
                OperationsCapabilities.permitsEntry(
                    WarehouseRoute.ProductSearch,
                    SessionState.Active,
                    access.copy(activeContext = access.activeContext?.copy(permissionHint = hint)),
                    warehouse
                )
            )
        }
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.ProductSearch,
                SessionState.Active,
                access,
                warehouse.copy(permissionHint = TaskVisibilityHint.Unavailable)
            )
        )
    }

    @Test fun staleSessionContextOrDestinationBlocksProtectedRoute() {
        assertFalse(
            OperationsCapabilities.permits(
                WarehouseRoute.ProductSearch,
                SessionState.SignedOut,
                access,
                warehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permits(
                WarehouseRoute.ProductSearch,
                SessionState.Active,
                access.copy(authorityEpoch = 4),
                warehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permits(
                WarehouseRoute.ProductSearch,
                SessionState.Active,
                access,
                warehouse.copy(
                    activeContext = warehouse.activeContext?.copy(workspaceName = "Other")
                )
            )
        )
        assertFalse(
            OperationsCapabilities.permits(
                WarehouseRoute.ConfirmedSku,
                SessionState.Active,
                access,
                warehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.WorkEntry,
                SessionState.Active,
                access,
                warehouse
            )
        )
    }

    @Test fun confirmedSkuRequiresCurrentConfirmedDetailAndSearch() {
        val confirmed = ConfirmedSkuUiState(
            candidateKey = "candidate",
            productDisplayName = "Product",
            variant = null,
            presentation = "Unit",
            sku = "SKU-1",
            brand = null,
            unit = null,
            packaging = null,
            coldChain = null,
            context = ActiveOperationsContext("Company", "Workspace", 3),
            authorityEpoch = 3
        )
        assertTrue(
            OperationsCapabilities.permits(
                WarehouseRoute.ConfirmedSku,
                SessionState.Active,
                access,
                warehouse.copy(route = WarehouseRoute.ConfirmedSku, confirmedSku = confirmed)
            )
        )
        assertFalse(
            OperationsCapabilities.permits(
                WarehouseRoute.ConfirmedSku,
                SessionState.Active,
                access,
                warehouse.copy(
                    route = WarehouseRoute.ConfirmedSku,
                    confirmedSku = confirmed.copy(authorityEpoch = 2)
                )
            )
        )
    }
}
