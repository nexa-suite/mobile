@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexa.mobile.operations.feature.warehouse

import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StockTransferViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun preservesExactRequestAndOnlyShowsServerConfirmedRequestedTransfer() = runTest {
        val gateway = FakeTransferGateway()
        val store = MemoryTransferStore()
        gateway.lifecycleEvents = store.events
        val viewModel = viewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()
        configure(viewModel)
        advanceUntilIdle()
        viewModel.quantityChanged("1.2300")
        viewModel.reasonChanged("relocate fragile goods")
        viewModel.startTransfer()
        advanceUntilIdle()

        val submitted = gateway.commands.single()
        assertEquals("transfer-key-1", submitted.idempotencyKey)
        assertEquals(17L, submitted.expectedSourceVersion)
        assertTrue(submitted.payload.contains("\"quantity\":1.2300"))
        assertEquals(listOf("persist", "post", "clear"), store.events)
        assertEquals(TransferCommandStatus.Confirmed, viewModel.state.value.command)
        assertEquals("REQUESTED", viewModel.state.value.confirmed?.status)
        assertEquals(BigDecimal("0"), viewModel.state.value.confirmed?.transferredQuantity)
        assertNull(store.intent)
        assertFalse(viewModel.state.value.toString().contains("relocate fragile goods"))
    }

    @Test
    fun unknownOutcomeLocksRequestAndExplicitRetryUsesSameKeyBodyVersion() = runTest {
        val gateway = FakeTransferGateway().apply {
            results += TransferSubmitResult.UnknownOutcome
            results += TransferSubmitResult.Confirmed(confirmed())
        }
        val store = MemoryTransferStore()
        gateway.lifecycleEvents = store.events
        val viewModel = viewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()
        configure(viewModel)
        advanceUntilIdle()
        viewModel.quantityChanged("0.0100")
        viewModel.reasonChanged("cycle movement")
        viewModel.startTransfer()
        advanceUntilIdle()

        val frozen = requireNotNull(store.intent)
        assertEquals(TransferIntentStatus.UnknownOutcome, frozen.status)
        assertEquals(TransferCommandStatus.UnknownOutcome, viewModel.state.value.command)
        viewModel.quantityChanged("12")
        viewModel.reasonChanged("changed while frozen")
        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(2, gateway.commands.size)
        assertEquals(gateway.commands[0], gateway.commands[1])
        assertTrue(gateway.commands[1].payload.contains("\"quantity\":0.0100"))
        assertEquals("transfer-key-1", gateway.commands[1].idempotencyKey)
        assertEquals(TransferCommandStatus.Confirmed, viewModel.state.value.command)
        assertNull(store.intent)
    }

    @Test
    fun restoredPendingIsUnknownAndNeverAutomaticallyReplayed() = runTest {
        val scope = authority().scope
        val intent = StockTransferIntent(
            scope,
            "restored-key",
            request().canonicalPayload(),
            17,
            TransferIntentStatus.Pending
        )
        val store = MemoryTransferStore().apply { this.intent = intent }
        val gateway = FakeTransferGateway()
        val viewModel = viewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()

        assertEquals(TransferCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertEquals(TransferIntentStatus.UnknownOutcome, store.intent?.status)
        assertTrue(gateway.commands.isEmpty())
        assertEquals("restored-key", store.intent?.idempotencyKey)
        assertEquals(17L, store.intent?.expectedSourceVersion)
    }

    @Test
    fun permissionMissingInvalidInputAndStaleScopeNeverSendARequest() = runTest {
        val gateway = FakeTransferGateway()
        val unauthorized = viewModel(gateway, MemoryTransferStore())
        unauthorized.activate(authority(permissions = setOf("warehouse:read")))
        advanceUntilIdle()
        configure(unauthorized)
        advanceUntilIdle()
        unauthorized.quantityChanged("1")
        unauthorized.reasonChanged("requested")
        unauthorized.startTransfer()
        advanceUntilIdle()
        assertTrue(gateway.commands.isEmpty())

        val scopeStore = MemoryTransferStore()
        val delayed = CompletableDeferred<TransferSubmitResult>()
        val delayedGateway = FakeTransferGateway().apply { pendingResult = delayed }
        val viewModel = viewModel(delayedGateway, scopeStore)
        viewModel.activate(authority())
        advanceUntilIdle()
        configure(viewModel)
        advanceUntilIdle()
        viewModel.quantityChanged("1")
        viewModel.reasonChanged("requested")
        viewModel.startTransfer()
        advanceUntilIdle()
        viewModel.deactivate()
        delayed.complete(TransferSubmitResult.Confirmed(confirmed()))
        advanceUntilIdle()

        assertNull(viewModel.state.value.confirmed)
        assertEquals(0, viewModel.state.value.authorityEpoch)
    }

    private fun viewModel(gateway: FakeTransferGateway, store: MemoryTransferStore) =
        StockTransferViewModel(gateway, store, newIdempotencyKey = { "transfer-key-1" })

    private suspend fun TestScope.configure(viewModel: StockTransferViewModel) {
        viewModel.selectSourceLot(SOURCE_LOT_ID)
        viewModel.selectDestinationWarehouse(DESTINATION_WAREHOUSE_ID)
        advanceUntilIdle()
        viewModel.selectDestinationZone(DESTINATION_ZONE_ID)
    }

    private fun authority(permissions: Set<String> = setOf("warehouse:read", "warehouse:write")) =
        StockTransferAuthority(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBERSHIP_ID,
            permissions,
            authorityEpoch = 9
        )

    private fun request() = StockTransferRequest(
        SOURCE_LOT_ID,
        SOURCE_WAREHOUSE_ID,
        SOURCE_ZONE_ID,
        DESTINATION_WAREHOUSE_ID,
        DESTINATION_ZONE_ID,
        SKU_ID,
        "CAT-42",
        "0.0100",
        "EA",
        "cycle movement"
    )

    private fun confirmed() = ConfirmedStockTransfer(
        "transfer-1",
        "REQUESTED",
        SOURCE_LOT_ID,
        SOURCE_WAREHOUSE_ID,
        SOURCE_ZONE_ID,
        DESTINATION_WAREHOUSE_ID,
        DESTINATION_ZONE_ID,
        BigDecimal("0.0100"),
        BigDecimal.ZERO,
        "EA",
        17,
        0
    )

    private inner class FakeTransferGateway : StockTransferGateway {
        val commands = mutableListOf<Submitted>()
        val results = mutableListOf<TransferSubmitResult>()
        var lifecycleEvents: MutableList<String> = mutableListOf()
        var pendingResult: CompletableDeferred<TransferSubmitResult>? = null

        override suspend fun warehouses(authority: StockTransferAuthority) =
            TransferLookupResult.Warehouses(
                listOf(
                    TransferWarehouseChoice(SOURCE_WAREHOUSE_ID, "SRC", "Source", "ACTIVE"),
                    TransferWarehouseChoice(DESTINATION_WAREHOUSE_ID, "DST", "Destination", "ACTIVE")
                )
            )

        override suspend fun zones(
            warehouseId: String,
            authority: StockTransferAuthority
        ) = TransferLookupResult.Zones(
            listOf(TransferZoneChoice(DESTINATION_ZONE_ID, warehouseId, "DST-Z", "Target", "ACTIVE"))
        )

        override suspend fun sourceLots(authority: StockTransferAuthority) =
            TransferLookupResult.Lots(
                listOf(
                    TransferSourceLotChoice(
                        SOURCE_LOT_ID,
                        SOURCE_WAREHOUSE_ID,
                        SOURCE_ZONE_ID,
                        "CAT-42",
                        SKU_ID,
                        "LOT-17",
                        BigDecimal("10.2500"),
                        "EA",
                        "AVAILABLE",
                        17
                    )
                )
            )

        override suspend fun create(
            frozenPayload: String,
            expectedSourceVersion: Long,
            idempotencyKey: String,
            authority: StockTransferAuthority
        ): TransferSubmitResult {
            commands += Submitted(frozenPayload, expectedSourceVersion, idempotencyKey)
            lifecycleEvents += "post"
            return pendingResult?.await() ?: results.removeFirstOrNull()
                ?: TransferSubmitResult.Confirmed(confirmed())
        }
    }

    private data class Submitted(val payload: String, val expectedSourceVersion: Long, val idempotencyKey: String)

    private class MemoryTransferStore : StockTransferMetadataStore {
        var intent: StockTransferIntent? = null
        val events = mutableListOf<String>()

        override suspend fun loadIntent(scope: StockTransferScope): TransferMetadataRead<StockTransferIntent> =
            TransferMetadataRead.Available(intent?.takeIf { it.scope == scope })

        override suspend fun saveIntent(intent: StockTransferIntent): TransferMetadataWrite {
            if (this.intent != null && this.intent?.idempotencyKey != intent.idempotencyKey) {
                return TransferMetadataWrite.Unavailable
            }
            this.intent = intent
            if (events.lastOrNull() != "persist") events += "persist"
            return TransferMetadataWrite.Saved
        }

        override suspend fun markUnknownOutcome(
            scope: StockTransferScope,
            idempotencyKey: String
        ): TransferMetadataWrite {
            val current = intent?.takeIf { it.scope == scope && it.idempotencyKey == idempotencyKey }
                ?: return TransferMetadataWrite.Unavailable
            intent = current.copy(status = TransferIntentStatus.UnknownOutcome)
            return TransferMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: StockTransferScope,
            idempotencyKey: String
        ): TransferMetadataWrite {
            if (intent?.scope != scope || intent?.idempotencyKey != idempotencyKey) {
                return TransferMetadataWrite.Unavailable
            }
            intent = null
            events += "clear"
            return TransferMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "e6c14000-0479-453f-93b9-c71cde8fbd01"
        const val TENANT_ID = "e6c14000-0479-453f-93b9-c71cde8fbd02"
        const val WORKSPACE_ID = "e6c14000-0479-453f-93b9-c71cde8fbd03"
        const val MEMBERSHIP_ID = "e6c14000-0479-453f-93b9-c71cde8fbd04"
        const val SOURCE_LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val SOURCE_WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val SOURCE_ZONE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val DESTINATION_WAREHOUSE_ID = "a8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val DESTINATION_ZONE_ID = "a8c24a46-57d9-4f64-8fa7-6a641b413202"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413301"
    }
}
