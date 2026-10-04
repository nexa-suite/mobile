package com.nexa.mobile.operations.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogImageRegistryTest {
    @Test
    fun registryContainsAllCanonicalCatalogAssets() {
        assertEquals(102, CatalogImageRegistry.canonicalFileNameCount)
    }

    @Test
    fun knownPngAndJpegFileNamesResolveToBundledResources() {
        assertNotNull(
            CatalogImageRegistry.resolve("agriform-queso-grana-padano-dop-150g.png")
        )
        assertNotNull(CatalogImageRegistry.resolve("cavour-salame-milano-100g.jpeg"))
    }

    @Test
    fun unknownOrPathBearingValuesDoNotResolve() {
        assertNull(CatalogImageRegistry.resolve(null))
        assertNull(CatalogImageRegistry.resolve(""))
        assertNull(CatalogImageRegistry.resolve("PROD-0001"))
        assertNull(CatalogImageRegistry.resolve("../agriform-queso-grana-padano-dop-150g.png"))
        assertNull(
            CatalogImageRegistry.resolve(
                "/catalog-items/agriform-queso-grana-padano-dop-150g.png"
            )
        )
        assertNull(CatalogImageRegistry.resolve("unknown-product.png"))
    }
}
