package com.nexa.mobile.operations

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugNetworkSecurityTest {
    @Test
    fun cleartextIsLimitedToDevelopmentHosts() {
        val policy = NetworkSecurityPolicy.getInstance()

        assertFalse(policy.isCleartextTrafficPermitted())
        assertTrue(policy.isCleartextTrafficPermitted("10.0.2.2"))
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertFalse(policy.isCleartextTrafficPermitted("10.0.2.3"))
        assertFalse(policy.isCleartextTrafficPermitted("api.localhost"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
    }
}
