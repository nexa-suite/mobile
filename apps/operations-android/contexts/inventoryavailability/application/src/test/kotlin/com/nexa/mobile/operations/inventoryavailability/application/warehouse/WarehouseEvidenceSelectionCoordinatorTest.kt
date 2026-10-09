package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyScope
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancySelectionContext
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingEvidenceSelectionContext
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoSelection
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceScope
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubjectType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WarehouseEvidenceSelectionCoordinatorTest {
    @Test
    fun eachWorkflowBuildsItsOwnLengthPrefixedScopeKey() = runTest {
        val files = RecordingSelectionPort()
        val coordinator = WarehouseEvidenceSelectionCoordinator(files)

        coordinator.prepareInboundDiscrepancy(
            InboundDiscrepancySelectionContext(
                authorityEpoch = 7,
                scope = InboundDiscrepancyScope("u:1", "t", "w", "m"),
                warehouseId = "warehouse",
                caseId = "case"
            ),
            "content://inbound"
        )
        coordinator.prepareReceivingTemperature(
            ReceivingEvidenceSelectionContext(
                ReceivingScopeIdentity("u:1", "t", "w", "m"),
                warehouseId = "warehouse",
                authorityEpoch = 8
            ),
            "content://receiving"
        )
        coordinator.prepareTemperatureEvidence(
            TemperatureEvidencePhotoSelection(
                scope = TemperatureEvidenceScope("u:1", "t", "w", "m"),
                authorityEpoch = 9,
                subjectType = TemperatureEvidenceSubjectType.LOT,
                subjectId = "lot",
                warehouseId = "warehouse",
                expectedLotVersion = 12
            ),
            "content://temperature"
        )

        assertEquals(
            listOf(
                "3:u:11:t1:w1:m9:warehouse4:case",
                "3:u:11:t1:w1:m9:warehouse",
                "3:u:11:t1:w1:m1:93:LOT3:lot9:warehouse2:12"
            ),
            files.scopeKeys
        )
    }

    private class RecordingSelectionPort : WarehouseEvidenceFileSelectionPort {
        val scopeKeys = mutableListOf<String>()

        override suspend fun prepare(
            sourceUri: String,
            scopeKey: String
        ): WarehouseEvidenceFileCandidate? {
            scopeKeys += scopeKey
            return null
        }

        override fun discard(candidate: WarehouseEvidenceFileCandidate) = Unit
    }
}
