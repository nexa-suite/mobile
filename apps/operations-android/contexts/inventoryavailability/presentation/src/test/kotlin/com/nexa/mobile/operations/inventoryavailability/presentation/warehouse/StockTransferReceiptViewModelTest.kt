@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptObservationMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferReceiptObservation
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptResult
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferReceiptTransfer
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferScope
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TransferWarehouseChoice
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StockTransferReceiptViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun receiptIntentIsPersistedBeforePostAndExplicitRetryReusesSameKeyAndFacts() = runTest {
        val events = mutableListOf<String>()
        val metadata = MemoryMetadataStore(events)
        val gateway = FakeReceiptGateway(events).apply {
            results += StockTransferReceiptResult.NetworkUnavailable
            results += StockTransferReceiptResult.Confirmed(
                transfer.copy(
                    destinationLotId = DESTINATION_LOT_ID,
                    status = "RECEIVED",
                    destinationVersionAfter = 23,
                    version = 3,
                    receivedAt = "2026-09-30T10:30:00Z"
                )
            )
        }
        val viewModel = StockTransferReceiptViewModel(gateway, metadata) { "receipt-key-1" }
        viewModel.activate(authority())
        advanceUntilIdle()
        viewModel.selectDestinationWarehouse(DESTINATION_WAREHOUSE_ID)
        advanceUntilIdle()
        viewModel.selectTransfer(TRANSFER_ID)
        viewModel.receiveExpectedQuantity()
        advanceUntilIdle()

        assertEquals(listOf("persist", "post", "mark-unknown"), events)
        assertEquals(
            StockTransferReceiptCommandStatus.UnknownOutcome,
            viewModel.state.value.command
        )
        val frozen = requireNotNull(metadata.intent)
        assertEquals("receipt-key-1", frozen.idempotencyKey)
        assertEquals("1.2300", frozen.transfer.transferredQuantityText)

        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(listOf("persist", "post", "mark-unknown", "post", "clear"), events)
        assertTrue(gateway.receiptCommands[0].sameFrozenCommand(gateway.receiptCommands[1]))
        assertEquals(StockTransferReceiptCommandStatus.Confirmed, viewModel.state.value.command)
        assertEquals("RECEIVED", viewModel.state.value.confirmed?.status)
        assertEquals(null, metadata.intent)
    }

    @Test
    fun restoredPendingReceiptBecomesUnknownAndIsNotAutomaticallyPosted() = runTest {
        val events = mutableListOf<String>()
        val original = StockTransferReceiptIntent(
            authority().scope,
            "restored-receipt-key",
            transfer,
            StockTransferReceiptIntentStatus.Pending
        )
        val metadata = MemoryMetadataStore(events).apply { intent = original }
        val gateway = FakeReceiptGateway(events)
        val viewModel = StockTransferReceiptViewModel(gateway, metadata)

        viewModel.activate(authority())
        advanceUntilIdle()

        assertTrue(gateway.receiptCommands.isEmpty())
        assertEquals(
            StockTransferReceiptCommandStatus.UnknownOutcome,
            viewModel.state.value.command
        )
        assertEquals(StockTransferReceiptIntentStatus.UnknownOutcome, metadata.intent?.status)
        assertEquals(listOf("mark-unknown", "get-current"), events)
    }

    @Test
    fun arrivalObservationPersistsBeforePostAndNeverConfirmsOrReceivesStock() = runTest {
        val events = mutableListOf<String>()
        val receiptMetadata = MemoryMetadataStore(events)
        val observationMetadata = MemoryObservationMetadataStore(events)
        val gateway = FakeReceiptGateway(events).apply {
            observationResults += StockTransferReceiptObservationResult.NetworkUnavailable
            observationResults += StockTransferReceiptObservationResult.Recorded(
                StockTransferReceiptObservation(
                    observationId = "d8c24a46-57d9-4f64-8fa7-6a641b413401",
                    transferId = TRANSFER_ID,
                    transferVersion = 2,
                    observedBatchNumber = "LOT-18",
                    observedExpirationDate = null,
                    observedQuantityText = "1.2300",
                    observedUnit = "EA",
                    hasDifference = true,
                    actorMembershipId = MEMBERSHIP_ID,
                    recordedAt = "2026-09-30T10:40:00Z"
                )
            )
        }
        val viewModel = StockTransferReceiptViewModel(
            gateway,
            receiptMetadata,
            observationMetadata
        ) { "observation-key-1" }
        viewModel.activate(authority())
        advanceUntilIdle()
        viewModel.selectDestinationWarehouse(DESTINATION_WAREHOUSE_ID)
        advanceUntilIdle()
        viewModel.selectTransfer(TRANSFER_ID)

        viewModel.observeArrival("LOT-18", null, "1.2300", "EA")
        advanceUntilIdle()

        assertEquals(listOf("observation-persist", "observe", "observation-mark-unknown"), events)
        assertEquals(
            StockTransferReceiptObservationCommandStatus.UnknownOutcome,
            viewModel.state.value.observationCommand
        )
        assertEquals("1.2300", observationMetadata.intent?.observedQuantityText)
        assertTrue(gateway.receiptCommands.isEmpty())

        viewModel.retryObservationUnknownOutcome()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "observation-persist",
                "observe",
                "observation-mark-unknown",
                "observe",
                "observation-clear"
            ),
            events
        )
        assertTrue(gateway.observationCommands[0].sameFrozenCommand(gateway.observationCommands[1]))
        assertEquals(
            StockTransferReceiptObservationCommandStatus.Recorded,
            viewModel.state.value.observationCommand
        )
        assertEquals("LOT-18", viewModel.state.value.recordedObservation?.observedBatchNumber)
        assertEquals(null, viewModel.state.value.confirmed)
        assertEquals("IN_TRANSIT", viewModel.state.value.selectedTransfer?.status)
        assertEquals(null, observationMetadata.intent)

        viewModel.receiveExpectedQuantity()
        advanceUntilIdle()
        assertEquals(5, events.size)
        assertEquals(
            StockTransferReceiptNotice.ObservedDifferenceRequiresResolution,
            viewModel.state.value.notice
        )
    }

    @Test
    fun restoredPendingObservationBecomesUnknownWithoutAutomaticPost() = runTest {
        val events = mutableListOf<String>()
        val pending = StockTransferReceiptObservationIntent(
            scope = authority().scope,
            idempotencyKey = "restored-observation-key",
            transfer = transfer,
            observedBatchNumber = "LOT-18",
            observedExpirationDate = null,
            observedQuantityText = "1.2300",
            observedUnit = "EA",
            status = StockTransferReceiptObservationIntentStatus.Pending
        )
        val observationMetadata = MemoryObservationMetadataStore(events).apply { intent = pending }
        val gateway = FakeReceiptGateway(events)
        val viewModel =
            StockTransferReceiptViewModel(gateway, MemoryMetadataStore(events), observationMetadata)

        viewModel.activate(authority())
        advanceUntilIdle()

        assertTrue(gateway.observationCommands.isEmpty())
        assertTrue(gateway.receiptCommands.isEmpty())
        assertEquals(
            StockTransferReceiptObservationCommandStatus.UnknownOutcome,
            viewModel.state.value.observationCommand
        )
        assertEquals(
            StockTransferReceiptObservationIntentStatus.UnknownOutcome,
            observationMetadata.intent?.status
        )
        assertTrue(events.contains("observation-mark-unknown"))
        assertTrue(events.contains("get-current"))
    }

    private fun authority() = StockTransferAuthority(
        USER_ID,
        TENANT_ID,
        WORKSPACE_ID,
        MEMBERSHIP_ID,
        setOf("warehouse:read", "warehouse:write"),
        authorityEpoch = 9
    )

    private class FakeReceiptGateway(private val events: MutableList<String>) :
        StockTransferReceiptGateway {
        val receiptCommands = mutableListOf<StockTransferReceiptIntent>()
        val observationCommands = mutableListOf<StockTransferReceiptObservationIntent>()
        val results = mutableListOf<StockTransferReceiptResult>()
        val observationResults = mutableListOf<StockTransferReceiptObservationResult>()

        override suspend fun warehouses(authority: StockTransferAuthority) =
            StockTransferReceiptLookupResult.Warehouses(
                listOf(
                    TransferWarehouseChoice(
                        DESTINATION_WAREHOUSE_ID,
                        "DST",
                        "Destination",
                        "ACTIVE"
                    )
                )
            )

        override suspend fun transfers(
            destinationWarehouseId: String,
            page: Int,
            authority: StockTransferAuthority
        ) = StockTransferReceiptLookupResult.TransferPage(listOf(transfer), page, 1)

        override suspend fun transfer(
            transferId: String,
            authority: StockTransferAuthority
        ): StockTransferReceiptLookupResult {
            events += "get-current"
            return StockTransferReceiptLookupResult.Transfer(transfer)
        }

        override suspend fun receive(
            intent: StockTransferReceiptIntent,
            authority: StockTransferAuthority
        ): StockTransferReceiptResult {
            events += "post"
            receiptCommands += intent
            return results.removeFirstOrNull() ?: StockTransferReceiptResult.UnknownOutcome
        }

        override suspend fun observeArrival(
            intent: StockTransferReceiptObservationIntent,
            authority: StockTransferAuthority
        ): StockTransferReceiptObservationResult {
            events += "observe"
            observationCommands += intent
            return observationResults.removeFirstOrNull()
                ?: StockTransferReceiptObservationResult.UnknownOutcome
        }
    }

    private class MemoryMetadataStore(private val events: MutableList<String>) :
        StockTransferReceiptMetadataStore {
        var intent: StockTransferReceiptIntent? = null

        override suspend fun loadIntent(scope: StockTransferScope) =
            StockTransferReceiptMetadataRead.Available(intent?.takeIf { it.scope == scope })

        override suspend fun saveIntent(
            intent: StockTransferReceiptIntent
        ): StockTransferReceiptMetadataWrite {
            if (this.intent != null && this.intent?.idempotencyKey != intent.idempotencyKey) {
                return StockTransferReceiptMetadataWrite.Unavailable
            }
            this.intent = intent
            events += "persist"
            return StockTransferReceiptMetadataWrite.Saved
        }

        override suspend fun markUnknownOutcome(
            scope: StockTransferScope,
            idempotencyKey: String
        ): StockTransferReceiptMetadataWrite {
            val current =
                intent?.takeIf { it.scope == scope && it.idempotencyKey == idempotencyKey }
                    ?: return StockTransferReceiptMetadataWrite.Unavailable
            intent = current.copy(status = StockTransferReceiptIntentStatus.UnknownOutcome)
            events += "mark-unknown"
            return StockTransferReceiptMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: StockTransferScope,
            idempotencyKey: String
        ): StockTransferReceiptMetadataWrite {
            if (intent?.scope != scope || intent?.idempotencyKey != idempotencyKey) {
                return StockTransferReceiptMetadataWrite.Unavailable
            }
            intent = null
            events += "clear"
            return StockTransferReceiptMetadataWrite.Saved
        }
    }

    private class MemoryObservationMetadataStore(private val events: MutableList<String>) :
        StockTransferReceiptObservationMetadataStore {
        var intent: StockTransferReceiptObservationIntent? = null

        override suspend fun loadIntent(scope: StockTransferScope) =
            StockTransferReceiptObservationMetadataRead.Available(
                intent?.takeIf {
                    it.scope == scope
                }
            )

        override suspend fun saveIntent(
            intent: StockTransferReceiptObservationIntent
        ): StockTransferReceiptObservationMetadataWrite {
            val current = this.intent
            if (current != null && !current.sameFrozenCommand(intent)) {
                return StockTransferReceiptObservationMetadataWrite.Unavailable
            }
            this.intent = intent
            events += "observation-persist"
            return StockTransferReceiptObservationMetadataWrite.Saved
        }

        override suspend fun markUnknownOutcome(
            scope: StockTransferScope,
            idempotencyKey: String
        ): StockTransferReceiptObservationMetadataWrite {
            val current =
                intent?.takeIf { it.scope == scope && it.idempotencyKey == idempotencyKey }
                    ?: return StockTransferReceiptObservationMetadataWrite.Unavailable
            intent =
                current.copy(status = StockTransferReceiptObservationIntentStatus.UnknownOutcome)
            events += "observation-mark-unknown"
            return StockTransferReceiptObservationMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: StockTransferScope,
            idempotencyKey: String
        ): StockTransferReceiptObservationMetadataWrite {
            if (intent?.scope != scope || intent?.idempotencyKey != idempotencyKey) {
                return StockTransferReceiptObservationMetadataWrite.Unavailable
            }
            intent = null
            events += "observation-clear"
            return StockTransferReceiptObservationMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "e6c14000-0479-453f-93b9-c71cde8fbd01"
        const val TENANT_ID = "e6c14000-0479-453f-93b9-c71cde8fbd02"
        const val WORKSPACE_ID = "e6c14000-0479-453f-93b9-c71cde8fbd03"
        const val MEMBERSHIP_ID = "e6c14000-0479-453f-93b9-c71cde8fbd04"
        const val SOURCE_WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val SOURCE_ZONE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val SOURCE_LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val DESTINATION_WAREHOUSE_ID = "a8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val DESTINATION_ZONE_ID = "a8c24a46-57d9-4f64-8fa7-6a641b413202"
        const val DESTINATION_LOT_ID = "d8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val TRANSFER_ID = "c8c24a46-57d9-4f64-8fa7-6a641b413401"

        val transfer = StockTransferReceiptTransfer(
            id = TRANSFER_ID,
            sourceWarehouseId = SOURCE_WAREHOUSE_ID,
            sourceZoneId = SOURCE_ZONE_ID,
            sourceLotId = SOURCE_LOT_ID,
            destinationWarehouseId = DESTINATION_WAREHOUSE_ID,
            destinationZoneId = DESTINATION_ZONE_ID,
            destinationLotId = null,
            skuId = "b8c24a46-57d9-4f64-8fa7-6a641b413301",
            catalogItemId = "CAT-42",
            batchNumber = "LOT-17",
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
