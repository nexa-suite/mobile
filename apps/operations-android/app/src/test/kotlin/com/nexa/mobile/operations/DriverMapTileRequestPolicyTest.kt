package com.nexa.mobile.operations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverMapTileRequestPolicyTest {
    @Test
    fun emptyDefaultBlocksAllExternalRequests() {
        val policy = DriverMapTileRequestPolicy("")

        assertFalse(policy.allows("https://tile.openstreetmap.org/15/5236/12664.png"))
    }

    @Test
    fun allowsOnlyConfiguredOsmTilePath() {
        val policy = DriverMapTileRequestPolicy(
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        )

        assertTrue(policy.allows("https://tile.openstreetmap.org/15/5236/12664.png"))
        assertFalse(policy.allows("https://sub.tile.openstreetmap.org/15/5236/12664.png"))
        assertFalse(policy.allows("https://tile.openstreetmap.org/15/5236/12664.jpg"))
        assertFalse(policy.allows("https://tile.openstreetmap.org:444/15/5236/12664.png"))
        assertFalse(policy.allows("https://tile.openstreetmap.org/15/5236/12664.png?key=value"))
        assertFalse(policy.allows("http://tile.openstreetmap.org/15/5236/12664.png"))
    }

    @Test
    fun malformedOrNonOsmTemplatesFailClosed() {
        assertFalse(
            DriverMapTileRequestPolicy("https://tiles.example.test/{z}/{x}/{y}.png")
                .allows("https://tiles.example.test/15/5236/12664.png")
        )
        assertFalse(
            DriverMapTileRequestPolicy("https://tile.openstreetmap.org/{z}/{x}/missing.png")
                .allows("https://tile.openstreetmap.org/15/5236/12664.png")
        )
    }
}
