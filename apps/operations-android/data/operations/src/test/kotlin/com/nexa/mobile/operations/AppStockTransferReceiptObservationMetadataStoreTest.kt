package com.nexa.mobile.operations

import com.nexa.mobile.operations.data.AppStockTransferReceiptObservationMetadataStore
import com.nexa.mobile.operations.data.TransferReceiptObservationScopedBackend
import com.nexa.mobile.operations.data.TransferReceiptObservationScopedRead
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationIntentStatus
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptTransfer
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppStockTransferReceiptObservationMetadataStoreTest {
    @Test
    fun reconstructsSeparateExactObservationAndPreventsReplacementOrStaleClear() = runTest {
        val backend = MemoryBackend()
        val store = AppStockTransferReceiptObservationMetadataStore(backend)
        val pending = intent(scopeA, "observation-key-a", "0.000")

        assertEquals(StockTransferReceiptObservationMetadataWrite.Saved, store.saveIntent(pending))
        assertEquals(
            StockTransferReceiptObservationMetadataRead.Available(pending),
            AppStockTransferReceiptObservationMetadataStore(backend).loadIntent(scopeA)
        )
        assertEquals(
            StockTransferReceiptObservationMetadataRead.Available(null),
            store.loadIntent(scopeB)
        )
        assertEquals(
            StockTransferReceiptObservationMetadataWrite.Unavailable,
            store.saveIntent(intent(scopeA, "observation-key-b", "0"))
        )
        assertEquals(
            StockTransferReceiptObservationMetadataWrite.Unavailable,
            store.clearIntent(scopeA, "stale-key")
        )
        assertEquals(
            StockTransferReceiptObservationMetadataRead.Available(pending),
            store.loadIntent(scopeA)
        )

        assertEquals(
            StockTransferReceiptObservationMetadataWrite.Saved,
            store.markUnknownOutcome(scopeA, pending.idempotencyKey)
        )
        assertEquals(
            StockTransferReceiptObservationMetadataRead.Available(
                pending.copy(status = StockTransferReceiptObservationIntentStatus.UnknownOutcome)
            ),
            store.loadIntent(scopeA)
        )
    }

    @Test
    fun corruptMetadataFailsClosedInsteadOfReplacingFrozenObservation() = runTest {
        val backend = MemoryBackend().apply { values[scopeA] = "{broken" }
        val store = AppStockTransferReceiptObservationMetadataStore(backend)

        assertEquals(
            StockTransferReceiptObservationMetadataRead.Unavailable,
            store.loadIntent(scopeA)
        )
        assertEquals(
            StockTransferReceiptObservationMetadataWrite.Unavailable,
            store.saveIntent(intent(scopeA, "replacement-key", "4.2"))
        )
        assertEquals("{broken", backend.values[scopeA])
    }

    private fun intent(scope: StockTransferScope, key: String, quantity: String) =
        StockTransferReceiptObservationIntent(
            scope = scope,
            idempotencyKey = key,
            transfer = transfer,
            observedBatchNumber = "B-OBSERVED",
            observedExpirationDate = "2027-02-16",
            observedQuantityText = quantity,
            observedUnit = "EA",
            status = StockTransferReceiptObservationIntentStatus.Pending
        )

    private class MemoryBackend : TransferReceiptObservationScopedBackend {
        val values = mutableMapOf<StockTransferScope, String>()

        override suspend fun load(scope: StockTransferScope): TransferReceiptObservationScopedRead =
            TransferReceiptObservationScopedRead.Value(values[scope])

        override suspend fun save(scope: StockTransferScope, payload: String): Boolean {
            values[scope] = payload
            return true
        }

        override suspend fun clear(scope: StockTransferScope): Boolean {
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
        val scopeB = scopeA.copy(membershipId = "a8c24a46-57d9-4f64-8fa7-6a641b413004")
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
