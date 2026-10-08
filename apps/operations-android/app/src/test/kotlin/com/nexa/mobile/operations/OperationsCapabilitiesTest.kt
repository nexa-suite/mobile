package com.nexa.mobile.operations

import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductScannerUiState
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
import com.nexa.mobile.operations.workentry.TaskVisibilityHint
import com.nexa.mobile.operations.workentry.WarehouseRoute
import com.nexa.mobile.operations.workentry.WarehouseUiState
import com.nexa.mobile.operations.workentry.WorkEntryCapability
import com.nexa.mobile.operations.workentry.WorkEntryStatus
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

    @Test fun receivingPermissionIsIndependentOfCatalogAndRequiresExactScope() {
        val verified = VerifiedContextAuthority(
            "user",
            "tenant",
            "workspace",
            "membership",
            setOf("inventory.receive", "warehouse.read")
        )
        val receivingAccess = access.copy(
            activeContext = access.activeContext!!.copy(
                permissionHint = PermissionHint.Unavailable,
                verifiedAuthority = verified
            )
        )
        val receivingWarehouse = warehouse.copy(
            route = WarehouseRoute.Receiving,
            activeContext = warehouse.activeContext!!.copy(
                verifiedIdentity = VerifiedOperationsIdentity(
                    verified.userId,
                    verified.tenantId,
                    verified.workspaceId,
                    verified.membershipId,
                    verified.permissions
                )
            )
        )
        assertTrue(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.Receiving,
                SessionState.Active,
                receivingAccess,
                receivingWarehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.ProductSearch,
                SessionState.Active,
                receivingAccess,
                receivingWarehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.Receiving,
                SessionState.Active,
                receivingAccess.copy(
                    activeContext = receivingAccess.activeContext!!.copy(
                        verifiedAuthority = verified.copy(membershipId = "other")
                    )
                ),
                receivingWarehouse
            )
        )
        assertEquals(
            listOf(WorkEntryCapability.StockCondition, WorkEntryCapability.Receiving),
            OperationsCapabilities.workEntryCapabilities(
                SessionState.Active,
                receivingAccess,
                receivingWarehouse
            )
        )
    }

    @Test fun stockReadRequiresCurrentIdentityAndOwnPermissionWithoutCatalog() {
        val verified =
            VerifiedContextAuthority(
                "user",
                "tenant",
                "workspace",
                "membership",
                setOf("warehouse.read")
            )
        val stockAccess = access.copy(
            activeContext = access.activeContext!!.copy(
                permissionHint = PermissionHint.Unavailable,
                verifiedAuthority = verified
            )
        )
        val stockWarehouse = warehouse.copy(
            route = WarehouseRoute.StockCondition,
            activeContext = warehouse.activeContext!!.copy(
                verifiedIdentity = VerifiedOperationsIdentity(
                    verified.userId,
                    verified.tenantId,
                    verified.workspaceId,
                    verified.membershipId,
                    verified.permissions
                )
            )
        )
        assertTrue(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.StockCondition,
                SessionState.Active,
                stockAccess,
                stockWarehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.StockCondition,
                SessionState.Active,
                stockAccess.copy(authorityEpoch = 4),
                stockWarehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.StockCondition,
                SessionState.Active,
                stockAccess.copy(
                    activeContext = stockAccess.activeContext!!.copy(
                        verifiedAuthority = verified.copy(membershipId = "other")
                    )
                ),
                stockWarehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.StockCondition,
                SessionState.Active,
                stockAccess.copy(
                    activeContext = stockAccess.activeContext!!.copy(
                        verifiedAuthority = verified.copy(permissions = setOf("catalog.read"))
                    )
                ),
                stockWarehouse
            )
        )
    }

    @Test fun pickingReadRequiresCurrentIdentityAndOwnPermissionWithoutCatalog() {
        val verified =
            VerifiedContextAuthority(
                "user",
                "tenant",
                "workspace",
                "membership",
                setOf("fulfillment.read")
            )
        val stockAccess = access.copy(
            activeContext = access.activeContext!!.copy(
                permissionHint = PermissionHint.Unavailable,
                verifiedAuthority = verified
            )
        )
        val stockWarehouse = warehouse.copy(
            route = WarehouseRoute.Picking,
            activeContext = warehouse.activeContext!!.copy(
                verifiedIdentity = VerifiedOperationsIdentity(
                    verified.userId,
                    verified.tenantId,
                    verified.workspaceId,
                    verified.membershipId,
                    verified.permissions
                )
            )
        )
        assertTrue(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.Picking,
                SessionState.Active,
                stockAccess,
                stockWarehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.Picking,
                SessionState.Active,
                stockAccess.copy(authorityEpoch = 4),
                stockWarehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.Picking,
                SessionState.Active,
                stockAccess.copy(
                    activeContext = stockAccess.activeContext!!.copy(
                        verifiedAuthority = verified.copy(membershipId = "other")
                    )
                ),
                stockWarehouse
            )
        )
        assertFalse(
            OperationsCapabilities.permitsEntry(
                WarehouseRoute.Picking,
                SessionState.Active,
                stockAccess.copy(
                    activeContext = stockAccess.activeContext!!.copy(
                        verifiedAuthority = verified.copy(permissions = setOf("catalog.read"))
                    )
                ),
                stockWarehouse
            )
        )
    }

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
                "operations.warehouse.confirmed-sku",
                "operations.warehouse.picking",
                "operations.warehouse.stock-condition",
                "operations.warehouse.receiving",
                "operations.warehouse.barcode-scanner"
            ),
            OperationsCapabilities.registered.map { it.routeIdentity.stableId }
        )
        assertEquals(
            OperationsCapabilities.registered.size,
            OperationsCapabilities.registered.map { it.destination }.distinct().size
        )
        assertTrue(
            OperationsCapabilities.registered.all {
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
            CapabilityGuardBehavior.ScannerInCurrentAuthorityEpoch,
            OperationsCapabilities.registered.single {
                it.destination == WarehouseRoute.Scanner
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
        assertEquals(
            1,
            OperationsCapabilities.deepRouteInvalidationBackCount(WarehouseRoute.Scanner)
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
        val confirmed = ConfirmedSkuProjection(
            candidateKey = "candidate",
            productDisplayName = "Product",
            variant = null,
            presentation = "Unit",
            sku = "SKU-1",
            brand = null,
            unit = null,
            packaging = null,
            coldChain = null,
            context = CatalogOperationsContext("Company", "Workspace", 3),
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

    @Test fun scannerRouteRequiresFreshExactVerifiedIdentityAndScannerEpoch() {
        val verified = VerifiedContextAuthority(
            userId = "user-1",
            tenantId = "tenant-1",
            workspaceId = "workspace-1",
            membershipId = "member-1",
            permissions = setOf("catalog.read")
        )
        val verifiedAccess = access.copy(
            activeContext = access.activeContext?.copy(verifiedAuthority = verified)
        )
        val verifiedWarehouse = warehouse.copy(
            route = WarehouseRoute.Scanner,
            activeContext = warehouse.activeContext?.copy(
                verifiedIdentity = VerifiedOperationsIdentity(
                    userId = verified.userId,
                    tenantId = verified.tenantId,
                    workspaceId = verified.workspaceId,
                    membershipId = verified.membershipId,
                    permissions = verified.permissions
                )
            )
        )
        assertTrue(
            OperationsCapabilities.permits(
                WarehouseRoute.Scanner,
                SessionState.Active,
                verifiedAccess,
                verifiedWarehouse,
                ProductScannerUiState.PermissionNotRequested(3)
            )
        )
        val verifiedIdentity = requireNotNull(verifiedWarehouse.activeContext?.verifiedIdentity)
        assertFalse(
            OperationsCapabilities.permits(
                WarehouseRoute.Scanner,
                SessionState.Active,
                verifiedAccess,
                verifiedWarehouse.copy(
                    activeContext = verifiedWarehouse.activeContext?.copy(
                        verifiedIdentity = verifiedIdentity.copy(membershipId = "other-member")
                    )
                ),
                ProductScannerUiState.PermissionNotRequested(3)
            )
        )
        assertFalse(
            OperationsCapabilities.permits(
                WarehouseRoute.Scanner,
                SessionState.Active,
                verifiedAccess,
                verifiedWarehouse,
                ProductScannerUiState.PermissionNotRequested(2)
            )
        )
    }
}
