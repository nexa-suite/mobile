package com.nexa.mobile.operations

import com.nexa.mobile.core.network.ApiClientSurface
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.network.CurrentSession
import com.nexa.mobile.core.network.SessionMembership
import com.nexa.mobile.core.network.SessionTenant
import com.nexa.mobile.core.network.SessionUser
import com.nexa.mobile.core.network.SessionWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContextMappingTest {
    @Test
    fun mapsOnlyServerConfirmedOperationsContext() {
        val result = currentSession().toConfirmedSessionContext(ApiClientSurface.PLATFORM)
        val confirmed = (result as ApiResult.Success).value

        assertEquals("Operator", confirmed.user.displayName)
        assertEquals("icisa", confirmed.tenant.tenantSlug)
        assertEquals("operations", confirmed.workspace.workspaceSlug)
        assertEquals(setOf("OPERATIONS"), confirmed.roles)
        assertEquals(setOf("catalog:read"), confirmed.capabilities)
    }

    @Test
    fun rejectsUnexpectedServerSurface() {
        val result = currentSession(surface = "PORTAL")
            .toConfirmedSessionContext(ApiClientSurface.PLATFORM)

        assertEquals(ApiErrorCategory.FORBIDDEN, (result as ApiResult.Failure).error.category)
        assertEquals("AUTH_SURFACE_MISMATCH", result.error.code)
    }

    @Test
    fun rejectsIncompleteServerContext() {
        val result = currentSession(workspaceSlug = "")
            .toConfirmedSessionContext(ApiClientSurface.PLATFORM)

        assertTrue(result is ApiResult.Failure)
        assertEquals("SESSION_CONTEXT_MISSING", (result as ApiResult.Failure).error.code)
    }

    @Test
    fun rejectsSessionWithoutServerConfirmedMembership() {
        val result = currentSession(membershipId = "")
            .toConfirmedSessionContext(ApiClientSurface.PLATFORM)

        assertTrue(result is ApiResult.Failure)
        assertEquals("SESSION_CONTEXT_MISSING", (result as ApiResult.Failure).error.code)
    }

    private fun currentSession(
        surface: String = "PLATFORM",
        workspaceSlug: String = "operations",
        membershipId: String = "membership-1",
    ): CurrentSession = CurrentSession(
        user = SessionUser("user-1", "Operator", "operator@example.test", "en-US"),
        tenant = SessionTenant("tenant-1", "icisa"),
        workspace = SessionWorkspace("workspace-1", workspaceSlug),
        membership = SessionMembership(
            membershipId = membershipId,
            roles = setOf("OPERATIONS"),
            permissions = setOf("catalog:read"),
        ),
        surface = surface,
    )
}
