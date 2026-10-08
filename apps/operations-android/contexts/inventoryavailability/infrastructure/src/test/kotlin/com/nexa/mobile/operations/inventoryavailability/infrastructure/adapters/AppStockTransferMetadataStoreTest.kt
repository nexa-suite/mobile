package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.AppStockTransferMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.StockTransferScopedMetadataBackend
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.StockTransferScopedRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferScope
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TransferIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TransferMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TransferMetadataWrite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Test

class AppStockTransferMetadataStoreTest {
    @Test
    fun durableAdapterReconstructsExactIntentAndDoesNotOverwriteDifferentCommand() = runTest {
        val backend = MemoryScopedMetadataBackend()
        val store = AppStockTransferMetadataStore(backend)
        val original = intent(scopeA, key = "transfer-a", payload = commandBody("1.2300"))

        assertEquals(TransferMetadataWrite.Saved, store.saveIntent(original))
        assertEquals(
            TransferMetadataWrite.Unavailable,
            store.saveIntent(
                original.copy(
                    idempotencyKey = "transfer-b",
                    frozenPayload = commandBody("1.23"),
                    expectedSourceVersion = 18
                )
            )
        )

        val restored = (store.loadIntent(scopeA) as TransferMetadataRead.Available).value
        assertEquals(original, restored)
        assertEquals(
            "1.2300",
            restored?.frozenPayload?.substringAfter("\"quantity\":")?.substringBefore(',')
        )
        assertEquals(TransferMetadataRead.Available(null), store.loadIntent(scopeB))
    }

    @Test
    fun unknownTransitionIsSameIntentAndStaleClearCannotDeleteReplacement() = runTest {
        val backend = MemoryScopedMetadataBackend()
        val store = AppStockTransferMetadataStore(backend)
        val original = intent(scopeA, key = "transfer-a", payload = commandBody("2"))
        assertEquals(TransferMetadataWrite.Saved, store.saveIntent(original))
        assertEquals(
            TransferMetadataWrite.Saved,
            store.markUnknownOutcome(scopeA, "transfer-a")
        )
        assertEquals(
            TransferIntentStatus.UnknownOutcome,
            (store.loadIntent(scopeA) as TransferMetadataRead.Available).value?.status
        )

        backend.force(
            scopeA,
            intent(scopeA, key = "newer-key", payload = commandBody("3")).encodeForTest()
        )
        assertEquals(
            TransferMetadataWrite.Unavailable,
            store.clearIntent(scopeA, "transfer-a")
        )
        assertEquals(
            "newer-key",
            (store.loadIntent(scopeA) as TransferMetadataRead.Available).value?.idempotencyKey
        )
    }

    @Test
    fun malformedPayloadFailsClosedInsteadOfReturningEmptyScope() = runTest {
        val backend = MemoryScopedMetadataBackend()
        backend.force(scopeA, "{broken")
        val store = AppStockTransferMetadataStore(backend)

        assertEquals(TransferMetadataRead.Unavailable, store.loadIntent(scopeA))
        assertFalse(store.clearIntent(scopeA, "any-key") == TransferMetadataWrite.Saved)
    }

    private fun intent(scope: StockTransferScope, key: String, payload: String) =
        StockTransferIntent(
            scope,
            key,
            payload,
            expectedSourceVersion = 17,
            TransferIntentStatus.Pending
        )

    private fun commandBody(quantity: String) =
        """{"sourceLotId":"lot","quantity":$quantity,"unit":"EA","reason":"move"}"""

    private fun StockTransferIntent.encodeForTest(): String =
        """{"schemaVersion":1,"idempotencyKey":"$idempotencyKey","frozenPayload":${frozenPayload.quote()},"expectedSourceVersion":$expectedSourceVersion,"status":"${status.name}"}"""

    private fun String.quote(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private class MemoryScopedMetadataBackend : StockTransferScopedMetadataBackend {
        private val values = mutableMapOf<StockTransferScope, String>()
        var unavailable = false

        override suspend fun load(scope: StockTransferScope): StockTransferScopedRead = when {
            unavailable -> StockTransferScopedRead.Unavailable
            else -> StockTransferScopedRead.Value(values[scope])
        }

        override suspend fun save(scope: StockTransferScope, payload: String): Boolean {
            if (unavailable) return false
            values[scope] = payload
            return true
        }

        override suspend fun clear(scope: StockTransferScope): Boolean {
            if (unavailable) return false
            values.remove(scope)
            return true
        }

        fun force(scope: StockTransferScope, payload: String) {
            values[scope] = payload
        }
    }

    private companion object {
        val scopeA = StockTransferScope("user-a", "tenant-a", "workspace-a", "membership-a")
        val scopeB = StockTransferScope("user-b", "tenant-b", "workspace-b", "membership-b")
    }
}
