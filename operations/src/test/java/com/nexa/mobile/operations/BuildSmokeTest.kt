package com.nexa.mobile.operations

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildSmokeTest {
    @Test
    fun applicationIdUsesOperationsNamespace() {
        assertEquals("com.nexa.mobile.operations", BuildConfig.APPLICATION_ID)
    }
}
