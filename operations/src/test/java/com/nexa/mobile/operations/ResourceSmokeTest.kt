package com.nexa.mobile.operations

import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResourceSmokeTest {
    @Test
    fun applicationResourcesExposeLocalizedAppLabel() {
        assertNotEquals(0, R.string.app_name)
    }
}
