package com.nexa.mobile.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleWireflowCaptureFlagTest {
    @Test
    fun captureFlagDefaultsToEnabledAndOnlyExplicitFalseDisables() {
        assertTrue(roleWireflowCaptureEnabled(null))
        assertTrue(roleWireflowCaptureEnabled(""))
        assertTrue(roleWireflowCaptureEnabled("true"))
        assertTrue(roleWireflowCaptureEnabled("TRUE"))
        assertEquals(false, roleWireflowCaptureEnabled("false"))
    }
}

internal fun roleWireflowCaptureEnabled(argument: String?): Boolean = argument != "false"
