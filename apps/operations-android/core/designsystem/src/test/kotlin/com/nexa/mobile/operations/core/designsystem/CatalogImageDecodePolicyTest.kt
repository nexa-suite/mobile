package com.nexa.mobile.operations.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogImageDecodePolicyTest {
    @Test
    fun samplesTheLargestCanonicalSourceToTheUiBounds() {
        assertEquals(
            4,
            CatalogImageDecodePolicy.calculateInSampleSize(
                sourceWidthPx = 1600,
                sourceHeightPx = 1600,
                targetWidthPx = 288,
                targetHeightPx = 288
            )
        )
    }

    @Test
    fun keepsSmallSourcesAtNativeResolution() {
        assertEquals(
            1,
            CatalogImageDecodePolicy.calculateInSampleSize(
                sourceWidthPx = 465,
                sourceHeightPx = 465,
                targetWidthPx = 288,
                targetHeightPx = 288
            )
        )
    }

    @Test
    fun cacheBudgetIsBoundedAndDecodedBitmapsUseExplicitArgbEstimate() {
        assertEquals(8 * 1024 * 1024, CatalogImageDecodePolicy.MAX_CACHE_BYTES)
        assertTrue(
            CatalogImageDecodePolicy.estimatedArgb8888Bytes(400, 400) <
                CatalogImageDecodePolicy.MAX_CACHE_BYTES
        )
    }
}
