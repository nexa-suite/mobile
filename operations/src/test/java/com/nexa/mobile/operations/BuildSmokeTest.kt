package com.nexa.mobile.operations

import com.nexa.mobile.core.network.ApiClientSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildSmokeTest {
    @Test
    fun applicationIdUsesOperationsNamespace() {
        assertEquals("com.nexa.mobile.operations", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun operationsUsesAcceptedPlatformSurfaceSemantics() {
        assertTrue(
            BuildConfig.NEXA_API_BASE_URL.isBlank() ||
                BuildConfig.NEXA_API_BASE_URL.startsWith("https://") ||
                BuildConfig.NEXA_API_BASE_URL.startsWith("http://"),
        )
        assertEquals("PLATFORM", BuildConfig.NEXA_API_SURFACE)
        assertEquals(ApiClientSurface.PLATFORM, ApiClientSurface.parse(BuildConfig.NEXA_API_SURFACE))
    }
}
