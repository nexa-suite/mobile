package com.nexa.mobile.operations

import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyDraft
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyDraftRead
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyDraftWrite
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyKind
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInboundDiscrepancyDraftStoreTest {
    @Test
    fun scopeIsolatedDraftReconstructsExactFactsAndCannotBeSilentlyReplaced() = runTest {
        val backend = MemoryBackend()
        val store = AppInboundDiscrepancyDraftStore(backend)
        val draft = draft("draft-a")

        assertEquals(InboundDiscrepancyDraftWrite.Saved, store.save(scopeA, draft))
        assertEquals(draft, (store.load(scopeA) as InboundDiscrepancyDraftRead.Available).draft)
        assertEquals(
            "10.2500",
            (
                store.load(
                    scopeA
                ) as InboundDiscrepancyDraftRead.Available
                ).draft?.expectedQuantityText
        )
        assertEquals(
            InboundDiscrepancyDraftWrite.Conflict,
            store.save(scopeA, draft("draft-b"))
        )
        assertEquals(InboundDiscrepancyDraftRead.Available(null), store.load(scopeB))
        assertEquals(draft, (store.load(scopeA) as InboundDiscrepancyDraftRead.Available).draft)
    }

    @Test
    fun staleDiscardCannotClearCurrentDraftAndOnlyExpectedIdCanClear() = runTest {
        val backend = MemoryBackend()
        val store = AppInboundDiscrepancyDraftStore(backend)
        val current = draft("current-id")
        assertEquals(InboundDiscrepancyDraftWrite.Saved, store.save(scopeA, current))

        assertEquals(InboundDiscrepancyDraftWrite.Conflict, store.discard(scopeA, "old-id"))
        assertEquals(current, (store.load(scopeA) as InboundDiscrepancyDraftRead.Available).draft)
        assertEquals(InboundDiscrepancyDraftWrite.Discarded, store.discard(scopeA, "current-id"))
        assertEquals(InboundDiscrepancyDraftRead.Available(null), store.load(scopeA))
    }

    @Test
    fun malformedStoredDraftFailsClosedAndDoesNotGetOverwritten() = runTest {
        val backend = MemoryBackend().apply { values[scopeA] = "{broken" }
        val store = AppInboundDiscrepancyDraftStore(backend)

        assertEquals(InboundDiscrepancyDraftRead.Unavailable, store.load(scopeA))
        assertEquals(InboundDiscrepancyDraftWrite.Unavailable, store.save(scopeA, draft("new")))
        assertEquals("{broken", backend.values[scopeA])
        assertFalse(store.discard(scopeA, "new") == InboundDiscrepancyDraftWrite.Discarded)
        assertTrue(backend.values.containsKey(scopeA))
    }

    private fun draft(id: String) = InboundDiscrepancyDraft(
        id = id,
        warehouseId = "00000000-0000-0000-0000-000000000050",
        expectedSkuId = "00000000-0000-0000-0000-000000000051",
        observedSkuId = "00000000-0000-0000-0000-000000000051",
        expectedBatchReference = "batch-2026-08",
        observedBatchReference = "batch-2026-09",
        kind = InboundDiscrepancyKind.QuantityDifference,
        reasonDetails = "Short count at receiving dock",
        expectedQuantityText = "10.2500",
        observedQuantityText = "9.7500",
        unit = "UNIT",
        observationNotes = "Local only",
        capturedAtDeviceMillis = 1_727_700_000_000
    )

    private class MemoryBackend : InboundDiscrepancyScopedMetadataBackend {
        val values = mutableMapOf<InboundDiscrepancyScope, String>()
        var unavailable = false

        override suspend fun load(scope: InboundDiscrepancyScope): InboundDiscrepancyScopedRead =
            if (unavailable) {
                InboundDiscrepancyScopedRead.Unavailable
            } else {
                InboundDiscrepancyScopedRead.Value(values[scope])
            }

        override suspend fun save(scope: InboundDiscrepancyScope, payload: String): Boolean {
            if (unavailable) return false
            values[scope] = payload
            return true
        }

        override suspend fun clear(scope: InboundDiscrepancyScope): Boolean {
            if (unavailable) return false
            values.remove(scope)
            return true
        }
    }

    private companion object {
        val scopeA = InboundDiscrepancyScope("user-a", "tenant-a", "workspace-a", "membership-a")
        val scopeB = InboundDiscrepancyScope("user-b", "tenant-b", "workspace-b", "membership-b")
    }
}
