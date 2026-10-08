package com.nexa.mobile.operations

import com.nexa.mobile.operations.data.AppStockTransferReceiptMetadataStore
import com.nexa.mobile.operations.data.TransferReceiptScopedMetadataBackend
import com.nexa.mobile.operations.data.TransferReceiptScopedRead
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptIntentStatus
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptTransfer
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStockTransferReceiptMetadataStoreTest {
    @Test
    fun reconstructsExactIntentAndKeepsScopesIsolated() = runTest {
        val backend = MemoryBackend()
        val store = AppStockTransferReceiptMetadataStore(backend)
        val pending = intent(scopeA, "receipt-key-a", "1.2300")

        assertEquals(StockTransferReceiptMetadataWrite.Saved, store.saveIntent(pending))
        val reconstructed = AppStockTransferReceiptMetadataStore(backend).loadIntent(scopeA)
        assertEquals(StockTransferReceiptMetadataRead.Available(pending), reconstructed)
        assertEquals(StockTransferReceiptMetadataRead.Available(null), store.loadIntent(scopeB))
        assertTrue(backend.values.keys.single() == scopeA)
    }

    @Test
    fun frozenKeyAndPayloadCannotBeReplacedAndStaleClearCannotClearCurrentIntent() = runTest {
        val backend = MemoryBackend()
        val firstStore = AppStockTransferReceiptMetadataStore(backend)
        val secondStore = AppStockTransferReceiptMetadataStore(backend)
        val original = intent(scopeA, "receipt-key-a", "1.2300")
        val changed = intent(scopeA, "receipt-key-b", "1.23")

        val writes = listOf(
            async { firstStore.saveIntent(original) },
            async { secondStore.saveIntent(changed) }
        ).map { it.await() }
        assertEquals(1, writes.count { it == StockTransferReceiptMetadataWrite.Saved })
        assertEquals(1, writes.count { it == StockTransferReceiptMetadataWrite.Unavailable })

        val current = (
            firstStore.loadIntent(
                scopeA
            ) as StockTransferReceiptMetadataRead.Available
            ).value!!
        val staleKey = if (current.idempotencyKey ==
            "receipt-key-a"
        ) {
            "receipt-key-b"
        } else {
            "receipt-key-a"
        }
        assertEquals(
            StockTransferReceiptMetadataWrite.Unavailable,
            firstStore.clearIntent(scopeA, staleKey)
        )
        assertEquals(
            StockTransferReceiptMetadataRead.Available(current),
            firstStore.loadIntent(scopeA)
        )
    }

    @Test
    fun pendingBecomesUnknownWithoutChangingFrozenCommandAndOnlyMatchingKeyCanClear() = runTest {
        val backend = MemoryBackend()
        val store = AppStockTransferReceiptMetadataStore(backend)
        val pending = intent(scopeA, "receipt-key-a", "3.500")
        assertEquals(StockTransferReceiptMetadataWrite.Saved, store.saveIntent(pending))
        assertEquals(
            StockTransferReceiptMetadataWrite.Saved,
            store.markUnknownOutcome(scopeA, pending.idempotencyKey)
        )

        val restored = (
            store.loadIntent(
                scopeA
            ) as StockTransferReceiptMetadataRead.Available
            ).value!!
        assertEquals(
            pending.copy(status = StockTransferReceiptIntentStatus.UnknownOutcome),
            restored
        )
        assertEquals("3.500", restored.transfer.transferredQuantityText)
        assertEquals(
            StockTransferReceiptMetadataWrite.Unavailable,
            store.clearIntent(scopeA, "stale-key")
        )
        assertEquals(
            StockTransferReceiptMetadataWrite.Saved,
            store.clearIntent(scopeA, pending.idempotencyKey)
        )
    }

    @Test
    fun corruptOrUnreadableMetadataFailsClosedAndCannotBeOverwritten() = runTest {
        val backend = MemoryBackend()
        backend.values[scopeA] = "{broken"
        val store = AppStockTransferReceiptMetadataStore(backend)

        assertEquals(StockTransferReceiptMetadataRead.Unavailable, store.loadIntent(scopeA))
        assertEquals(
            StockTransferReceiptMetadataWrite.Unavailable,
            store.saveIntent(intent(scopeA, "replacement", "8"))
        )
        assertEquals("{broken", backend.values[scopeA])

        backend.failReads = true
        assertEquals(StockTransferReceiptMetadataRead.Unavailable, store.loadIntent(scopeA))
        assertEquals(
            StockTransferReceiptMetadataWrite.Unavailable,
            store.saveIntent(intent(scopeA, "replacement", "8"))
        )
    }

    private fun intent(scope: StockTransferScope, key: String, quantity: String) =
        StockTransferReceiptIntent(
            scope = scope,
            idempotencyKey = key,
            transfer = transfer.copy(transferredQuantityText = quantity),
            status = StockTransferReceiptIntentStatus.Pending
        )

    private class MemoryBackend : TransferReceiptScopedMetadataBackend {
        val values = mutableMapOf<StockTransferScope, String>()
        var failReads = false

        override suspend fun load(scope: StockTransferScope): TransferReceiptScopedRead =
            if (failReads) {
                TransferReceiptScopedRead.Unavailable
            } else {
                TransferReceiptScopedRead.Value(values[scope])
            }

        override suspend fun save(scope: StockTransferScope, payload: String): Boolean {
            if (failReads) return false
            values[scope] = payload
            return true
        }

        override suspend fun clear(scope: StockTransferScope): Boolean {
            if (failReads) return false
            values.remove(scope)
            return true
        }
    }

    private companion object {
        val scopeA = StockTransferScope(
            "b8c24a46-57d9-4f64-8fa7-6a641b413001",
            "b8c24a46-57d9-4f64-8fa7-6a641b413002",
            "b8c24a46-57d9-4f64-8fa7-6a641b413003",
            "b8c24a46-57d9-4f64-8fa7-6a641b413004"
        )
        val scopeB = scopeA.copy(userId = "a8c24a46-57d9-4f64-8fa7-6a641b413001")
        val transfer = StockTransferReceiptTransfer(
            id = "c8c24a46-57d9-4f64-8fa7-6a641b413001",
            sourceWarehouseId = "b8c24a46-57d9-4f64-8fa7-6a641b413101",
            sourceZoneId = "b8c24a46-57d9-4f64-8fa7-6a641b413201",
            sourceLotId = "b8c24a46-57d9-4f64-8fa7-6a641b413401",
            destinationWarehouseId = "a8c24a46-57d9-4f64-8fa7-6a641b413101",
            destinationZoneId = "a8c24a46-57d9-4f64-8fa7-6a641b413202",
            destinationLotId = null,
            skuId = "b8c24a46-57d9-4f64-8fa7-6a641b413301",
            catalogItemId = null,
            batchNumber = "BATCH-17",
            expirationDate = "2027-02-15",
            requestedQuantityText = "1.2300",
            transferredQuantityText = "1.2300",
            mode = "FULL",
            unit = "EA",
            status = "IN_TRANSIT",
            reason = "relocate stock",
            sourceVersionBefore = 17,
            sourceVersionAfter = 18,
            destinationVersionAfter = null,
            version = 2,
            dispatchedAt = "2026-09-30T10:00:00Z",
            receivedAt = null
        )
    }
}
