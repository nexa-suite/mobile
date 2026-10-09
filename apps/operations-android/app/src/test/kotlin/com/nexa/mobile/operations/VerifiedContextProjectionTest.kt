package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.auth.session.contextIsCurrent
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.adapters.toWorkforceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedContextProjectionTest {
    @Test
    fun missingServerScopeOrUnauthorizedContextCannotProjectWorkAuthority() {
        val verified = session()
        assertNull(verified.copy(hasAuthorizedContext = false).toWorkforceContext())
        assertNull(verified.copy(userId = null).toWorkforceContext())
        assertNull(verified.copy(tenantId = "").toWorkforceContext())
        assertNull(verified.copy(workspaceId = null).toWorkforceContext())
        assertNull(verified.copy(membershipId = "").toWorkforceContext())
    }

    @Test
    fun sameNamesAndCatalogHintDoNotHideServerScopeOrPermissionChange() {
        val original = session()
        val permissionChanged = original.copy(permissions = setOf("catalog.read", "work.b"))
        val scopeChanged = original.copy(workspaceId = "workspace-b")

        assertEquals(PermissionHint.Available, original.toWorkforceContext()?.permissionHint)
        assertEquals(
            original.toWorkforceContext()?.permissionHint,
            permissionChanged.toWorkforceContext()?.permissionHint
        )
        assertFalse(contextIsCurrent(original, permissionChanged))
        assertFalse(contextIsCurrent(original, scopeChanged))
        assertTrue(contextIsCurrent(original, original.copy()))
        assertEquals(
            permissionChanged.permissions,
            permissionChanged.toWorkforceContext()?.verifiedAuthority?.permissions
        )
        assertFalse(permissionChanged.toWorkforceContext().toString().contains("user-a"))
    }

    private fun session() = VerifiedSession(
        hasAuthorizedContext = true,
        userId = "user-a",
        tenantId = "tenant-a",
        tenantName = "Company",
        workspaceId = "workspace-a",
        workspaceName = "Workspace",
        membershipId = "membership-a",
        permissions = setOf("catalog.read", "work.a")
    )
}
