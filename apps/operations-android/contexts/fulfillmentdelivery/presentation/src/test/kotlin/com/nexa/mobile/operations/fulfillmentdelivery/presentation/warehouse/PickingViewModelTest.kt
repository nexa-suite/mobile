package com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse

import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingConfirmationCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingFulfillmentSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingIntentCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingIntentMetadataStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingMutationResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.FulfillmentPickingLine
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.FulfillmentPickingSnapshot
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.PhysicalAllocationLineProjection
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.PhysicalAllocationProjection
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PickingViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun unknownLotAndOverRemainingQuantityAreBlockedBeforeMutation() = runTest {
        val gateway = FakePickingGateway()
        val viewModel = readyViewModel(gateway)
        viewModel.selectOffer(PHYSICAL_LINE_ID)
        viewModel.lotIdentifierChanged(OTHER_LOT_ID)
        viewModel.quantityChanged("0.0100")
        viewModel.confirmPick()
        advanceUntilIdle()
        assertEquals(
            PickingValidationError.LotIdentifierMismatch,
            viewModel.state.value.validationError
        )
        assertEquals(0, gateway.confirmCalls)

        viewModel.lotIdentifierChanged(LOT_ID)
        viewModel.quantityChanged("0.5001")
        viewModel.confirmPick()
        advanceUntilIdle()
        assertEquals(
            PickingValidationError.QuantityExceedsRemaining,
            viewModel.state.value.validationError
        )
        assertEquals(0, gateway.confirmCalls)

        gateway.currentSnapshot = snapshot(allocationStatus = "QUARANTINED")
        viewModel.reload()
        advanceUntilIdle()
        viewModel.selectOffer(PHYSICAL_LINE_ID)
        viewModel.lotIdentifierChanged(LOT_ID)
        viewModel.quantityChanged("0.0100")
        viewModel.confirmPick()
        advanceUntilIdle()
        assertEquals(
            PickingValidationError.AllocationNotReady,
            viewModel.state.value.validationError
        )
        assertEquals(0, gateway.confirmCalls)
    }

    @Test
    fun unknownOutcomeNeedsExplicitReplayWithSameKeyAndFrozenExactDecimalCommand() = runTest {
        val gateway = FakePickingGateway().apply {
            confirmResults += PickingMutationResult.UnknownOutcome
            confirmResults += PickingMutationResult.Confirmed(updatedFulfillment())
        }
        val store = FakePickingMetadataStore()
        val viewModel = readyViewModel(gateway, store)
        viewModel.selectOffer(PHYSICAL_LINE_ID)
        viewModel.lotIdentifierChanged(LOT_ID)
        viewModel.quantityChanged("0.0100")
        viewModel.confirmPick()
        advanceUntilIdle()

        assertEquals(1, gateway.confirmCalls)
        assertEquals(PickingCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertTrue(viewModel.state.value.isIntentFrozen)
        assertEquals(PickingIntentMetadataStatus.UnknownOutcome, store.intent?.status)
        assertEquals("0.0100", store.intent?.command?.confirmation()?.quantity?.toPlainString())
        assertNull(viewModel.state.value.confirmedFulfillment)

        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(2, gateway.confirmCalls)
        assertEquals(1, gateway.keys.distinct().size)
        assertEquals(gateway.commands.first(), gateway.commands.last())
        assertEquals(PickingCommandStatus.Confirmed, viewModel.state.value.command)
        assertEquals("0.0100", gateway.commands.last().quantity.toPlainString())
        assertEquals(
            BigDecimal("0.5100"),
            viewModel.state.value.confirmedFulfillment?.lines?.single()?.pickedQuantity
        )
        assertEquals(LOT_ID, viewModel.state.value.confirmedLotId)
        assertNull(store.intent)
    }

    @Test
    fun rapidUnknownReplayTapsDispatchOnlyOneCommandAtATime() = runTest {
        val gateway = FakePickingGateway().apply {
            confirmResults += PickingMutationResult.UnknownOutcome
            confirmResults += PickingMutationResult.UnknownOutcome
        }
        val store = FakePickingMetadataStore()
        val viewModel = readyViewModel(gateway, store)
        viewModel.selectOffer(PHYSICAL_LINE_ID)
        viewModel.lotIdentifierChanged(LOT_ID)
        viewModel.quantityChanged("0.0100")
        viewModel.confirmPick()
        advanceUntilIdle()

        store.saveStarted = CompletableDeferred()
        store.saveGate = CompletableDeferred()
        viewModel.retryUnknownOutcome()
        assertEquals(PickingCommandStatus.PersistingIntent, viewModel.state.value.command)
        viewModel.retryUnknownOutcome()
        runCurrent()
        assertEquals(1, gateway.confirmCalls)

        store.saveGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, gateway.confirmCalls)
        assertEquals(PickingCommandStatus.UnknownOutcome, viewModel.state.value.command)
    }

    @Test
    fun staleAllocationConflictRequiresFreshVersionsBeforeNewDecision() = runTest {
        val gateway = FakePickingGateway().apply {
            confirmResults += PickingMutationResult.StaleVersion
            confirmResults += PickingMutationResult.Confirmed(updatedFulfillment(version = 15))
        }
        val viewModel = readyViewModel(gateway)
        viewModel.selectOffer(PHYSICAL_LINE_ID)
        viewModel.lotIdentifierChanged(LOT_ID)
        viewModel.quantityChanged("0.0100")
        viewModel.confirmPick()
        advanceUntilIdle()

        assertEquals(PickingCommandStatus.StaleVersion, viewModel.state.value.command)
        assertEquals(12L, gateway.commands.single().expectedFulfillmentVersion)
        gateway.loadResults +=
            PickingLoadResult.Loaded(snapshot(version = 14, allocationVersion = 9))
        viewModel.reload()
        advanceUntilIdle()
        assertEquals(PickingCommandStatus.Editing, viewModel.state.value.command)
        viewModel.selectOffer(PHYSICAL_LINE_ID)
        viewModel.lotIdentifierChanged(LOT_ID)
        viewModel.quantityChanged("0.0100")
        viewModel.confirmPick()
        advanceUntilIdle()

        assertEquals(listOf(12L, 14L), gateway.commands.map { it.expectedFulfillmentVersion })
        assertEquals(listOf(8L, 9L), gateway.commands.map { it.allocationVersion })
    }

    @Test
    fun lateMutationResultCannotOverwriteInvalidatedContextAndIntentStaysPending() = runTest {
        val deferred = CompletableDeferred<PickingMutationResult>()
        val gateway = FakePickingGateway().apply { confirmDeferred = deferred }
        val store = FakePickingMetadataStore()
        val viewModel = readyViewModel(gateway, store)
        viewModel.selectOffer(PHYSICAL_LINE_ID)
        viewModel.lotIdentifierChanged(LOT_ID)
        viewModel.quantityChanged("0.0100")
        viewModel.confirmPick()
        runCurrent()
        assertEquals(1, gateway.confirmCalls)
        val key = store.intent?.idempotencyKey

        viewModel.invalidate()
        deferred.complete(PickingMutationResult.Confirmed(updatedFulfillment()))
        advanceUntilIdle()

        assertEquals(PickingLoadStatus.SessionInvalidated, viewModel.state.value.loadStatus)
        assertEquals(PickingCommandStatus.Editing, viewModel.state.value.command)
        assertNull(viewModel.state.value.confirmedFulfillment)
        assertEquals(key, store.intent?.idempotencyKey)
        assertEquals(PickingIntentMetadataStatus.Pending, store.intent?.status)
    }

    @Test
    fun reconstructedPendingIntentBecomesUnknownAndReplaysOnlyOriginalCommand() = runTest {
        val scope = authority().scope
        val frozen = command("0.0100")
        val store = FakePickingMetadataStore().apply {
            intent = PickingIntentMetadata(
                scope,
                "restored-pick-key",
                PickingIntentCommand.Confirm(frozen),
                PickingIntentMetadataStatus.Pending
            )
        }
        val gateway = FakePickingGateway().apply {
            confirmResults += PickingMutationResult.Confirmed(updatedFulfillment())
        }
        val viewModel = PickingViewModel(gateway, store)
        viewModel.activate(authority(epoch = 2), FULFILLMENT_ID)
        advanceUntilIdle()

        assertEquals(PickingCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertEquals(PickingIntentMetadataStatus.UnknownOutcome, store.intent?.status)
        assertEquals(0, gateway.confirmCalls)
        viewModel.retryUnknownOutcome()
        advanceUntilIdle()
        assertEquals(1, gateway.confirmCalls)
        assertEquals("restored-pick-key", gateway.keys.single())
        assertEquals(frozen, gateway.commands.single())
    }

    @Test
    fun unavailableMetadataNeverDispatchesMutationButStillLoadsServerFacts() = runTest {
        val gateway = FakePickingGateway()
        val viewModel = readyViewModel(gateway, FakePickingMetadataStore(available = false))
        viewModel.selectOffer(PHYSICAL_LINE_ID)
        viewModel.lotIdentifierChanged(LOT_ID)
        viewModel.quantityChanged("0.0100")
        viewModel.confirmPick()
        advanceUntilIdle()

        assertEquals(PickingLoadStatus.Ready, viewModel.state.value.loadStatus)
        assertEquals(PickingMetadataStatus.Unavailable, viewModel.state.value.metadata)
        assertEquals(0, gateway.confirmCalls)
        assertEquals(
            PickingValidationError.MetadataUnavailable,
            viewModel.state.value.validationError
        )
    }

    private suspend fun TestScope.readyViewModel(
        gateway: FakePickingGateway,
        store: FakePickingMetadataStore = FakePickingMetadataStore()
    ): PickingViewModel {
        val viewModel = PickingViewModel(gateway, store)
        viewModel.activate(authority(), FULFILLMENT_ID)
        advanceUntilIdle()
        return viewModel
    }

    private fun snapshot(
        version: Long = 12,
        allocationVersion: Long = 8,
        allocationStatus: String = "ALLOCATED"
    ) = PickingFulfillmentSnapshot(
        fulfillment = FulfillmentPickingSnapshot(
            id = FULFILLMENT_ID,
            status = "PICKING",
            version = version,
            lines = listOf(
                FulfillmentPickingLine(
                    FULFILLMENT_LINE_ID,
                    SKU_ID,
                    "CAT-0017",
                    BigDecimal("4.000"),
                    BigDecimal("0.500"),
                    BigDecimal("3.500"),
                    "KG"
                )
            )
        ),
        allocation = PhysicalAllocationProjection(
            allocationId = ALLOCATION_ID,
            status = allocationStatus,
            version = allocationVersion,
            asOf = Instant.parse("2026-09-30T15:00:00Z"),
            lines = listOf(
                PhysicalAllocationLineProjection(
                    PHYSICAL_LINE_ID,
                    SKU_ID,
                    "CAT-0017",
                    WAREHOUSE_ID,
                    null,
                    LOT_ID,
                    BigDecimal("1.000"),
                    BigDecimal("0.250"),
                    BigDecimal("0.250"),
                    BigDecimal("0.500"),
                    "KG",
                    LocalDate.parse("2027-06-30")
                )
            )
        )
    )

    private fun updatedFulfillment(version: Long = 13) =
        snapshot(version = version).fulfillment.copy(
            lines = listOf(
                snapshot(version = version).fulfillment.lines.single().copy(
                    pickedQuantity = BigDecimal("0.5100"),
                    remainingQuantity = BigDecimal("3.4900")
                )
            )
        )

    private fun command(quantity: String) = PickingConfirmationCommand(
        fulfillmentId = FULFILLMENT_ID,
        expectedFulfillmentVersion = 12,
        allocationVersion = 8,
        fulfillmentLineId = FULFILLMENT_LINE_ID,
        skuId = SKU_ID,
        physicalAllocationLineId = PHYSICAL_LINE_ID,
        lotId = LOT_ID,
        warehouseId = WAREHOUSE_ID,
        quantity = BigDecimal(quantity),
        unit = "KG"
    )

    private fun authority(epoch: Long = 1) = PickingAuthority(
        userId = USER_ID,
        tenantId = TENANT_ID,
        workspaceId = WORKSPACE_ID,
        membershipId = MEMBERSHIP_ID,
        permissions = setOf("fulfillment.read", "fulfillment.manage"),
        authorityEpoch = epoch
    )

    private inner class FakePickingGateway : PickingGateway {
        var currentSnapshot = snapshot()
        var loadResults = mutableListOf<PickingLoadResult>()
        var confirmResults = mutableListOf<PickingMutationResult>()
        var confirmDeferred: CompletableDeferred<PickingMutationResult>? = null
        var confirmCalls = 0
        val commands = mutableListOf<PickingConfirmationCommand>()
        val keys = mutableListOf<String>()

        override suspend fun load(
            fulfillmentId: String,
            authority: PickingAuthority
        ): PickingLoadResult =
            loadResults.removeFirstOrNull() ?: PickingLoadResult.Loaded(currentSnapshot)

        override suspend fun startPicking(
            command: PickingIntentCommand.Start,
            idempotencyKey: String,
            authority: PickingAuthority
        ): PickingMutationResult = PickingMutationResult.UnknownOutcome

        override suspend fun confirmPicking(
            command: PickingConfirmationCommand,
            idempotencyKey: String,
            authority: PickingAuthority
        ): PickingMutationResult {
            confirmCalls++
            commands += command
            keys += idempotencyKey
            confirmDeferred?.let { return it.await() }
            return confirmResults.removeFirstOrNull() ?: PickingMutationResult.UnknownOutcome
        }
    }

    private class FakePickingMetadataStore(private val available: Boolean = true) :
        PickingMetadataStore {
        var intent: PickingIntentMetadata? = null
        var saveStarted: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null

        override suspend fun loadIntent(
            scope: PickingScopeIdentity
        ): PickingMetadataRead<PickingIntentMetadata> = if (available) {
            PickingMetadataRead.Available(intent?.takeIf { it.scope == scope })
        } else {
            PickingMetadataRead.Unavailable
        }

        override suspend fun saveIntent(intent: PickingIntentMetadata): PickingMetadataWrite {
            saveStarted?.complete(Unit)
            saveGate?.await()
            return if (available) {
                this.intent = intent
                PickingMetadataWrite.Saved
            } else {
                PickingMetadataWrite.Unavailable
            }
        }

        override suspend fun clearIntent(
            scope: PickingScopeIdentity,
            idempotencyKey: String
        ): PickingMetadataWrite = if (available) {
            if (intent?.scope == scope && intent?.idempotencyKey == idempotencyKey) intent = null
            PickingMetadataWrite.Saved
        } else {
            PickingMetadataWrite.Unavailable
        }
    }

    private fun PickingIntentCommand.confirmation(): PickingConfirmationCommand? =
        (this as? PickingIntentCommand.Confirm)?.request

    private companion object {
        const val USER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val TENANT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413102"
        const val WORKSPACE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413103"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413104"
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val FULFILLMENT_LINE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413202"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413203"
        const val PHYSICAL_LINE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413204"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413205"
        const val WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413206"
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413207"
        const val OTHER_LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413208"
    }
}
