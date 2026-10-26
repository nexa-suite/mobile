package com.nexa.mobile.operations

import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseUiState
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverPermissions
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedOperationsNavigationTest {
    private val entry = ConnectedOperationEntry(
        "dispatch.readiness",
        "Preparación de despacho",
        setOf("dispatch.read")
    )
    private val authority =
        VerifiedContextAuthority(
            "user",
            "tenant",
            "workspace",
            "member",
            setOf("dispatch.read"),
            setOf("LOGISTICS")
        )
    private val access = AccessUiState(
        stage = AccessStage.WorkAuthorized,
        activeContext = WorkforceContextSummary(
            "key",
            "Company",
            "Workspace",
            PermissionHint.Unavailable,
            true,
            authority
        ),
        authorityEpoch = 3
    )
    private val warehouse = WarehouseUiState(
        authorityEpoch = 3,
        activeContext = ActiveOperationsContext(
            "Company",
            "Workspace",
            3,
            VerifiedOperationsIdentity(
                "user",
                "tenant",
                "workspace",
                "member",
                authority.permissions
            )
        )
    )

    @Test fun independentPermissionOpensOnlyConnectedEntry() {
        val route = ConnectedOperationsNavigation.open(
            entry,
            SessionState.Active,
            access,
            warehouse
        )
        assertNotNull(route)
        assertEquals(
            listOf(entry),
            ConnectedOperationsNavigation.visibleEntries(
                listOf(entry),
                SessionState.Active,
                access,
                warehouse
            )
        )
        assertTrue(
            ConnectedOperationsNavigation.permits(
                route!!,
                listOf(entry),
                SessionState.Active,
                access,
                warehouse
            )
        )
        assertFalse(
            ConnectedOperationsNavigation.permits(
                route,
                emptyList(),
                SessionState.Active,
                access,
                warehouse
            )
        )
        val logisticsNavigation = operationsNavigationSections(
            listOf(entry),
            setOf("LOGISTICS")
        )
        assertEquals(1, logisticsNavigation.size)
        assertEquals(setOf(entry.key), logisticsNavigation.single().destinationKeys)
        val warehouseAuthority = authority.copy(roles = setOf("WAREHOUSE"))
        val warehouseAccess = access.copy(
            activeContext = access.activeContext!!.copy(verifiedAuthority = warehouseAuthority)
        )
        assertTrue(
            ConnectedOperationsNavigation.visibleEntries(
                listOf(entry),
                SessionState.Active,
                warehouseAccess,
                warehouse
            ).isEmpty()
        )
    }

    @Test fun identicalEntriesAreShownOnceAndConflictingKeysFailClosed() {
        val duplicate = listOf(entry, entry)
        assertEquals(
            listOf(entry),
            ConnectedOperationsNavigation.visibleEntries(
                duplicate,
                SessionState.Active,
                access,
                warehouse
            )
        )
        val route = ConnectedOperationsNavigation.open(
            entry,
            SessionState.Active,
            access,
            warehouse
        )!!
        assertTrue(
            ConnectedOperationsNavigation.permits(
                route,
                duplicate,
                SessionState.Active,
                access,
                warehouse
            )
        )

        val conflicting = entry.copy(readPermissions = setOf("other.read"))
        val conflictingEntries = listOf(entry, conflicting)
        assertTrue(
            ConnectedOperationsNavigation.visibleEntries(
                conflictingEntries,
                SessionState.Active,
                access,
                warehouse
            ).isEmpty()
        )
        assertFalse(
            ConnectedOperationsNavigation.permits(
                route,
                conflictingEntries,
                SessionState.Active,
                access,
                warehouse
            )
        )
    }

    @Test fun routeCannotSurviveRevalidationOrScopeReplacement() {
        val route = ConnectedOperationsNavigation.open(
            entry,
            SessionState.Active,
            access,
            warehouse
        )!!
        assertFalse(
            ConnectedOperationsNavigation.permits(
                route,
                listOf(entry),
                SessionState.Active,
                access.copy(authorityEpoch = 4),
                warehouse
            )
        )
        assertFalse(
            ConnectedOperationsNavigation.permits(
                route,
                listOf(entry),
                SessionState.SignedOut,
                access,
                warehouse
            )
        )
        for (changed in listOf(
            authority.copy(userId = "other"),
            authority.copy(tenantId = "other"),
            authority.copy(workspaceId = "other"),
            authority.copy(membershipId = "other"),
            authority.copy(permissions = emptySet()),
            authority.copy(roles = setOf("COMPANY_OWNER"))
        )) {
            val replaced = access.copy(
                activeContext = access.activeContext!!.copy(verifiedAuthority = changed)
            )
            assertFalse(
                ConnectedOperationsNavigation.permits(
                    route,
                    listOf(entry),
                    SessionState.Active,
                    replaced,
                    warehouse
                )
            )
        }
    }

    @Test fun commercialRequestRequiresCustomerSalesAndCatalogReadPermissions() {
        val requestEntry = ConnectedOperationEntry(
            key = "commercial.request",
            label = "Preparar solicitud",
            readPermissions = setOf("catalog.read", "catalog:read"),
            requiredReadPermissionGroups = listOf(setOf("client.read"), setOf("sales:read"))
        )
        for (catalogPermission in listOf("catalog.read", "catalog:read")) {
            val requestAuthority = authority.copy(
                permissions = setOf("client.read", "sales:read", catalogPermission),
                roles = setOf("SALES")
            )
            val requestAccess = access.copy(
                activeContext = access.activeContext!!.copy(verifiedAuthority = requestAuthority)
            )
            val requestWarehouse = warehouse.copy(
                activeContext = warehouse.activeContext!!.copy(
                    verifiedIdentity = warehouse.activeContext!!.verifiedIdentity!!.copy(
                        permissions = requestAuthority.permissions
                    )
                )
            )

            val route = ConnectedOperationsNavigation.open(
                requestEntry,
                SessionState.Active,
                requestAccess,
                requestWarehouse
            )
            assertNotNull(route)
            assertEquals(
                listOf(requestEntry),
                ConnectedOperationsNavigation.visibleEntries(
                    listOf(requestEntry),
                    SessionState.Active,
                    requestAccess,
                    requestWarehouse
                )
            )

            for (permissions in listOf(
                requestAuthority.permissions - "client.read",
                requestAuthority.permissions - "sales:read",
                requestAuthority.permissions - setOf("catalog.read", "catalog:read")
            )) {
                val deniedAccess = requestAccess.copy(
                    activeContext = requestAccess.activeContext!!.copy(
                        verifiedAuthority = requestAuthority.copy(permissions = permissions)
                    )
                )
                val deniedWarehouse = requestWarehouse.copy(
                    activeContext = requestWarehouse.activeContext!!.copy(
                        verifiedIdentity = requestWarehouse.activeContext!!.verifiedIdentity!!.copy(
                            permissions = permissions
                        )
                    )
                )
                assertTrue(
                    ConnectedOperationsNavigation.visibleEntries(
                        listOf(requestEntry),
                        SessionState.Active,
                        deniedAccess,
                        deniedWarehouse
                    ).isEmpty()
                )
                assertEquals(
                    null,
                    ConnectedOperationsNavigation.open(
                        requestEntry,
                        SessionState.Active,
                        deniedAccess,
                        deniedWarehouse
                    )
                )
            }
        }
    }

    @Test fun dispatchHandoverRequiresDispatchReadAndCanonicalOrAliasCompletion() {
        val handover = ConnectedOperationEntry(
            key = "dispatch.handover",
            label = "Confirmar salida y evidencia de handoff",
            readPermissions = DispatchHandoverPermissions.complete,
            visibleInHub = false,
            requiredReadPermissionGroups = listOf(
                setOf(DispatchHandoverPermissions.READ)
            )
        )

        for (completionPermission in DispatchHandoverPermissions.complete) {
            val permissions = setOf(DispatchHandoverPermissions.READ, completionPermission)
            val currentAuthority = authority.copy(permissions = permissions)
            val currentAccess = access.copy(
                activeContext = access.activeContext!!.copy(verifiedAuthority = currentAuthority)
            )
            val currentWarehouse = warehouse.copy(
                activeContext = warehouse.activeContext!!.copy(
                    verifiedIdentity = warehouse.activeContext!!.verifiedIdentity!!.copy(
                        permissions = permissions
                    )
                )
            )
            val route = ConnectedOperationsNavigation.open(
                handover,
                SessionState.Active,
                currentAccess,
                currentWarehouse
            )

            assertNotNull("$completionPermission should open handover", route)
            assertTrue(
                ConnectedOperationsNavigation.permits(
                    route!!,
                    listOf(handover),
                    SessionState.Active,
                    currentAccess,
                    currentWarehouse
                )
            )
        }

        val manageOnlyPermissions = setOf(DispatchHandoverPermissions.READ, "fulfillment.manage")
        val manageOnlyAuthority = authority.copy(permissions = manageOnlyPermissions)
        val manageOnlyAccess = access.copy(
            activeContext = access.activeContext!!.copy(verifiedAuthority = manageOnlyAuthority)
        )
        val manageOnlyWarehouse = warehouse.copy(
            activeContext = warehouse.activeContext!!.copy(
                verifiedIdentity = warehouse.activeContext!!.verifiedIdentity!!.copy(
                    permissions = manageOnlyPermissions
                )
            )
        )

        assertNull(
            ConnectedOperationsNavigation.open(
                handover,
                SessionState.Active,
                manageOnlyAccess,
                manageOnlyWarehouse
            )
        )
    }

    @Test fun walletRefundRequiresAllowedVerifiedMembershipRoleAndPermission() {
        val refundEntry = ConnectedOperationEntry(
            "payments.wallet-refund",
            "Devolución a billetera",
            setOf("payment.reconcile"),
            readRoles = setOf("BUSINESS_OPERATIONS_MANAGER", "COMPANY_OWNER")
        )
        val owner = authority.copy(
            permissions = setOf("payment.reconcile"),
            roles = setOf("COMPANY_OWNER")
        )
        val ownerAccess = access.copy(
            activeContext = access.activeContext!!.copy(verifiedAuthority = owner)
        )
        val ownerWarehouse = warehouse.copy(
            activeContext = warehouse.activeContext!!.copy(
                verifiedIdentity = warehouse.activeContext!!.verifiedIdentity!!.copy(
                    permissions = owner.permissions,
                    roles = owner.roles
                )
            )
        )
        val route = ConnectedOperationsNavigation.open(
            refundEntry,
            SessionState.Active,
            ownerAccess,
            ownerWarehouse
        )
        assertNotNull(route)

        for (unauthorized in listOf(
            owner.copy(roles = emptySet()),
            owner.copy(roles = setOf("SALES")),
            owner.copy(permissions = emptySet())
        )) {
            val deniedAccess = ownerAccess.copy(
                activeContext = ownerAccess.activeContext!!.copy(
                    verifiedAuthority = unauthorized
                )
            )
            val deniedWarehouse = ownerWarehouse.copy(
                activeContext = ownerWarehouse.activeContext!!.copy(
                    verifiedIdentity = ownerWarehouse.activeContext!!.verifiedIdentity!!.copy(
                        permissions = unauthorized.permissions,
                        roles = unauthorized.roles
                    )
                )
            )
            assertEquals(
                emptyList<ConnectedOperationEntry>(),
                ConnectedOperationsNavigation.visibleEntries(
                    listOf(refundEntry),
                    SessionState.Active,
                    deniedAccess,
                    deniedWarehouse
                )
            )
            assertEquals(
                null,
                ConnectedOperationsNavigation.open(
                    refundEntry,
                    SessionState.Active,
                    deniedAccess,
                    deniedWarehouse
                )
            )
        }
    }
}
