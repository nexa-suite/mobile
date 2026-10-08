package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.data.AppCycleCountMetadataStore
import com.nexa.mobile.operations.data.CycleCountMetadataBackend
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountIntent
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountIntentStatus
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountLot
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountScope
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountStoredWork
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCycleCountMetadataStoreTest {
    @Test
    fun reconstructionRetainsExactPendingBodyAndExpectedKeyGuardPreventsReplacement() = runTest {
        val backend = MemoryBackend()
        val firstProcess = AppCycleCountMetadataStore(backend)
        val original = countIntent()

        assertEquals(CycleCountMetadataWrite.Saved, firstProcess.freezeCount(original))
        assertEquals(
            CycleCountMetadataWrite.Saved,
            firstProcess.markCountUnknown(scope, original.idempotencyKey)
        )

        val reconstructedStore = AppCycleCountMetadataStore(backend)
        val restored = (reconstructedStore.load(scope) as CycleCountMetadataRead.Available).value
        assertEquals(original.frozenBody, restored?.countIntent?.frozenBody)
        assertEquals(original.idempotencyKey, restored?.countIntent?.idempotencyKey)
        assertEquals(CycleCountIntentStatus.UnknownOutcome, restored?.countIntent?.status)

        assertEquals(
            CycleCountMetadataWrite.Unavailable,
            reconstructedStore.clearCountIntent(scope, "stale-key")
        )
        assertEquals(
            CycleCountMetadataWrite.Unavailable,
            reconstructedStore.saveDraft(CycleCountStoredWork(scope, OTHER_LOT_ID, "2"))
        )
        val unchanged = (reconstructedStore.load(scope) as CycleCountMetadataRead.Available).value
        assertEquals(
            original,
            unchanged?.countIntent?.copy(status = CycleCountIntentStatus.Pending)
        )
    }

    private class MemoryBackend : CycleCountMetadataBackend {
        private val records = mutableMapOf<ScopedMetadataScope, String>()

        override suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead =
            ScopedMetadataRead.Value(records[scope])

        override suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean {
            records[scope] = payload
            return true
        }
    }

    private fun countIntent() = CycleCountIntent(
        scope = scope,
        idempotencyKey = "cycle-count-key-1",
        lot = CycleCountLot(
            LOT_ID, WAREHOUSE_ID, ZONE_ID, "CAT-0042", "BATCH-9", "2027-03-31",
            "5.000", "0", "5.000", "EA", "AVAILABLE", 7
        ),
        observedQuantityText = "4.250",
        frozenBody = "{\"observedQuantity\":4.250,\"unit\":\"EA\"}",
        status = CycleCountIntentStatus.Pending
    )

    private companion object {
        val scope = CycleCountScope(
            "00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000002",
            "00000000-0000-4000-8000-000000000003",
            "00000000-0000-4000-8000-000000000004"
        )
        const val LOT_ID = "00000000-0000-4000-8000-000000000005"
        const val WAREHOUSE_ID = "00000000-0000-4000-8000-000000000006"
        const val ZONE_ID = "00000000-0000-4000-8000-000000000007"
        const val OTHER_LOT_ID = "00000000-0000-4000-8000-000000000008"
    }
}
