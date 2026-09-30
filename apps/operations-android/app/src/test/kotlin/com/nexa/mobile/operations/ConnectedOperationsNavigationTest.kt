package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.VerifiedContextAuthority
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import com.nexa.mobile.operations.feature.warehouse.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.VerifiedOperationsIdentity
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedOperationsNavigationTest {
    private val entry = ConnectedOperationEntry("dispatch", "Despacho", setOf("dispatch.read"))
    private val authority = VerifiedContextAuthority("user", "tenant", "workspace", "member", setOf("dispatch.read"))
    private val access = AccessUiState(
        stage = AccessStage.WorkAuthorized,
        activeContext = WorkforceContextSummary("key", "Company", "Workspace", PermissionHint.Unavailable, true, authority),
        authorityEpoch = 3
    )
    private val warehouse = WarehouseUiState(
        authorityEpoch = 3,
        activeContext = ActiveOperationsContext("Company", "Workspace", 3,
            VerifiedOperationsIdentity("user", "tenant", "workspace", "member", authority.permissions))
    )

    @Test fun independentPermissionOpensOnlyConnectedEntry() {
        val route = ConnectedOperationsNavigation.open(entry, SessionState.Active, access, warehouse)
        assertNotNull(route)
        assertEquals(listOf(entry), ConnectedOperationsNavigation.visibleEntries(listOf(entry), SessionState.Active, access, warehouse))
        assertTrue(ConnectedOperationsNavigation.permits(route!!, listOf(entry), SessionState.Active, access, warehouse))
        assertFalse(ConnectedOperationsNavigation.permits(route, emptyList(), SessionState.Active, access, warehouse))
    }

    @Test fun routeCannotSurviveRevalidationOrScopeReplacement() {
        val route = ConnectedOperationsNavigation.open(entry, SessionState.Active, access, warehouse)!!
        assertFalse(ConnectedOperationsNavigation.permits(route, listOf(entry), SessionState.Active, access.copy(authorityEpoch = 4), warehouse))
        assertFalse(ConnectedOperationsNavigation.permits(route, listOf(entry), SessionState.SignedOut, access, warehouse))
        for (changed in listOf(authority.copy(userId = "other"), authority.copy(tenantId = "other"),
            authority.copy(workspaceId = "other"), authority.copy(membershipId = "other"), authority.copy(permissions = emptySet()))) {
            val replaced = access.copy(activeContext = access.activeContext!!.copy(verifiedAuthority = changed))
            assertFalse(ConnectedOperationsNavigation.permits(route, listOf(entry), SessionState.Active, replaced, warehouse))
        }
    }
}
