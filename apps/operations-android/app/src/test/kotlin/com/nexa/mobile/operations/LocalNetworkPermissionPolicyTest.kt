package com.nexa.mobile.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionPolicyTest {
    @Test
    fun localDebugOriginRequiresPermissionOnlyOnAndroid17() {
        assertTrue(requiresLocalNetworkPermission(37, true, "http://10.0.2.2:8080/"))
        assertTrue(requiresLocalNetworkPermission(37, true, "http://192.168.1.10/"))
        assertFalse(requiresLocalNetworkPermission(36, true, "http://10.0.2.2:8080/"))
        assertFalse(requiresLocalNetworkPermission(37, false, "http://10.0.2.2:8080/"))
        assertFalse(requiresLocalNetworkPermission(37, true, "https://api.example.com/"))
        assertFalse(requiresLocalNetworkPermission(37, true, "https://fd.example.com/"))
        assertFalse(requiresLocalNetworkPermission(37, true, "https://10.example.com/"))
    }

    @Test
    fun deniedActionIsClearedAndCannotExecuteLater() {
        val continuation = LocalNetworkPermissionContinuation()
        continuation.begin(LocalNetworkPermissionAction.SignIn)
        assertNull(continuation.resolve(false))
        assertNull(continuation.resolve(true))
    }

    @Test
    fun grantedActionRunsOnceWithoutChangingItsPurpose() {
        val continuation = LocalNetworkPermissionContinuation()
        continuation.begin(LocalNetworkPermissionAction.RetrySession)
        assertEquals(LocalNetworkPermissionAction.RetrySession, continuation.resolve(true))
        assertNull(continuation.resolve(true))
    }
}
