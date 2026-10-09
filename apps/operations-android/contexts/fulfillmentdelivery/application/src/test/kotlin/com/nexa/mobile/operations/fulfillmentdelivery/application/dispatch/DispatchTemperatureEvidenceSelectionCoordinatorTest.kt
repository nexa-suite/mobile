package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DispatchTemperatureEvidenceSelectionCoordinatorTest {
    @Test
    fun scopeKeyPreservesEveryDispatchIdentityWithLengthPrefixes() = runTest {
        val files = RecordingPhotoSelectionPort()
        val coordinator = DispatchTemperatureEvidenceSelectionCoordinator(files)
        coordinator.prepare(
            DispatchTemperatureEvidenceSelectionContext(
                scope = DispatchTemperatureScopeIdentity("u:1", "t", "w", "m"),
                authorityEpoch = 17,
                fulfillmentId = "fulfillment",
                lotId = "lot",
                warehouseId = "warehouse"
            ),
            "content://dispatch"
        )

        assertEquals(
            "3:u:11:t1:w1:m11:fulfillment3:lot9:warehouse",
            files.scopeKey
        )
    }

    private class RecordingPhotoSelectionPort : DispatchTemperaturePhotoSelectionPort {
        var scopeKey: String? = null

        override suspend fun prepare(
            sourceUri: String,
            scopeKey: String
        ): DispatchTemperatureSelectedPhoto? {
            this.scopeKey = scopeKey
            return null
        }

        override fun discard(candidate: DispatchTemperatureSelectedPhoto) = Unit
    }
}
