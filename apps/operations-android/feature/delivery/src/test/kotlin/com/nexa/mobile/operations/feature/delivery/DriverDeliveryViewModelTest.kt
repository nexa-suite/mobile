package com.nexa.mobile.operations.feature.delivery

import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriverDeliveryViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun startRefreshesCurrentDeliveryAndShowsOnlyConfirmedAttemptFacts() = runTest {
        val gateway = FakeDriverDeliveryGateway()
        val metadata = FakeDriverAttemptMetadataStore()
        val viewModel = DriverDeliveryViewModel(gateway, metadata) { "start-key" }
        viewModel.activate(AUTHORITY)
        advanceUntilIdle()
        viewModel.selectDelivery(DELIVERY_ID)
        advanceUntilIdle()

        viewModel.beginSelectedDelivery()
        advanceUntilIdle()

        assertEquals(2, gateway.detailCalls)
        assertEquals(1, gateway.commands.size)
        assertEquals(DriverDeliveryCommandStatus.Started, viewModel.state.value.commandStatus)
        assertEquals(ATTEMPT_ID, viewModel.state.value.selectedDelivery?.activeAttempt?.id)
        assertEquals("Server destination", viewModel.state.value.authorizedDirectionsDestination)
        assertEquals("start-key", gateway.commands.single().idempotencyKey)
        assertEquals(9L, gateway.commands.single().expectedVersion)
    }

    @Test
    fun unknownStartRetainsSameKeyAndPayloadForExplicitReplay() = runTest {
        val gateway = FakeDriverDeliveryGateway().apply {
            startResults += DriverAttemptStartResult.UnknownOutcome
            startResults += DriverAttemptStartResult.Started(
                activeDelivery(version = 10),
                activeAttempt()
            )
        }
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore()
        ) { "stable-start-key" }
        activateAndSelect(viewModel)

        viewModel.beginSelectedDelivery()
        advanceUntilIdle()

        assertEquals(
            DriverDeliveryCommandStatus.UnknownOutcome,
            viewModel.state.value.commandStatus
        )
        assertNull(viewModel.state.value.selectedDelivery?.activeAttempt)
        assertEquals(1, gateway.commands.size)
        viewModel.retryUnknownStart()
        advanceUntilIdle()

        assertEquals(2, gateway.commands.size)
        assertEquals(gateway.commands.first(), gateway.commands.last())
        assertEquals(DriverDeliveryCommandStatus.Started, viewModel.state.value.commandStatus)
        assertEquals(ATTEMPT_ID, viewModel.state.value.selectedDelivery?.activeAttempt?.id)
    }

    @Test
    fun rapidBeginTapsIssueOnlyOneCommandUntilFreshDetailReturns() = runTest {
        val gateway = FakeDriverDeliveryGateway()
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore()
        ) { "one-start-key" }
        activateAndSelect(viewModel)
        val detailGate = CompletableDeferred<DriverDeliveryLoadResult>()
        gateway.detailBlock = { detailGate.await() }

        viewModel.beginSelectedDelivery()
        viewModel.beginSelectedDelivery()
        runCurrent()

        assertEquals(
            DriverDeliveryCommandStatus.CheckingCurrent,
            viewModel.state.value.commandStatus
        )
        assertEquals(2, gateway.detailCalls)
        assertTrue(gateway.commands.isEmpty())

        detailGate.complete(DriverDeliveryLoadResult.DetailLoaded(delivery()))
        advanceUntilIdle()
        assertEquals(1, gateway.commands.size)
    }

    @Test
    fun lateStartFromOldAuthorityCannotOverwriteNewActivation() = runTest {
        val gateway = FakeDriverDeliveryGateway()
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore()
        ) { "late-key" }
        activateAndSelect(viewModel)
        val startGate = CompletableDeferred<DriverAttemptStartResult>()
        gateway.startBlock = { _, _ -> startGate.await() }

        viewModel.beginSelectedDelivery()
        runCurrent()
        assertEquals(DriverDeliveryCommandStatus.Pending, viewModel.state.value.commandStatus)

        viewModel.activate(OTHER_AUTHORITY)
        advanceUntilIdle()
        startGate.complete(
            DriverAttemptStartResult.Started(activeDelivery(10), activeAttempt())
        )
        runCurrent()

        assertEquals(OTHER_AUTHORITY.authorityEpoch, viewModel.state.value.authorityEpoch)
        assertEquals(DriverDeliveryLoadStatus.Ready, viewModel.state.value.listStatus)
        assertEquals(delivery(), viewModel.state.value.selectedDelivery)
        assertEquals(
            DriverDeliveryCommandStatus.UnknownOutcome,
            viewModel.state.value.commandStatus
        )
        assertNull(viewModel.state.value.selectedDelivery?.activeAttempt)
        assertNotNull(viewModel.state.value.deliveries.singleOrNull())
    }

    @Test
    fun permissionGatesAreIndependentAndInvalidationClearsServerFacts() = runTest {
        val gateway = FakeDriverDeliveryGateway()
        val readOnly = AUTHORITY.copy(permissions = setOf("dispatch.read"))
        val viewModel = DriverDeliveryViewModel(gateway, FakeDriverAttemptMetadataStore())
        viewModel.activate(readOnly)
        advanceUntilIdle()
        viewModel.selectDelivery(DELIVERY_ID)
        advanceUntilIdle()
        viewModel.beginSelectedDelivery()
        advanceUntilIdle()

        assertEquals(0, gateway.commands.size)
        assertTrue(viewModel.state.value.canRead)
        assertEquals(false, viewModel.state.value.canStart)
        viewModel.invalidate()
        assertTrue(viewModel.state.value.deliveries.isEmpty())
        assertNull(viewModel.state.value.selectedDelivery)
        assertNull(viewModel.state.value.authorizedDirectionsDestination)
    }

    @Test
    fun startRequiresDurableIntentAndRetryKeepsItsIdentity() = runTest {
        val gateway = FakeDriverDeliveryGateway()
        val metadata = FakeDriverAttemptMetadataStore().apply {
            writeResult = DriverAttemptMetadataWrite.Unavailable
        }
        var keys = 0
        val viewModel = DriverDeliveryViewModel(gateway, metadata) {
            keys++
            "durable-start-key"
        }
        activateAndSelect(viewModel)

        viewModel.beginSelectedDelivery()
        advanceUntilIdle()
        assertTrue(gateway.commands.isEmpty())
        assertEquals(
            DriverDeliveryCommandStatus.PersistenceUnavailable,
            viewModel.state.value.commandStatus
        )

        metadata.writeResult = DriverAttemptMetadataWrite.Saved
        viewModel.retryUnknownStart()
        advanceUntilIdle()
        assertEquals(1, gateway.commands.size)
        assertEquals("durable-start-key", gateway.commands.single().idempotencyKey)
        assertEquals(9L, gateway.commands.single().expectedVersion)
        assertEquals(1, keys)
    }

    @Test
    fun processReconstructionRestoresUnknownAndReplaysSameKeyAndVersion() = runTest {
        val gateway = FakeDriverDeliveryGateway()
        val metadata = FakeDriverAttemptMetadataStore().apply {
            stored = DriverAttemptIntentMetadata(
                AUTHORITY.scopeIdentity,
                "recovered-key",
                DELIVERY_ID,
                9,
                DriverAttemptMetadataStatus.Pending
            )
        }
        val viewModel = DriverDeliveryViewModel(gateway, metadata)

        viewModel.activate(AUTHORITY)
        advanceUntilIdle()

        assertEquals(
            DriverDeliveryCommandStatus.UnknownOutcome,
            viewModel.state.value.commandStatus
        )
        assertEquals(DriverAttemptMetadataStatus.UnknownOutcome, metadata.stored?.status)
        viewModel.retryUnknownStart()
        advanceUntilIdle()

        assertEquals(
            DriverAttemptStartCommand(DELIVERY_ID, 9, "recovered-key"),
            gateway.commands.single()
        )
        assertEquals(DriverDeliveryCommandStatus.Started, viewModel.state.value.commandStatus)
    }

    @Test
    fun outcomePersistsExactDecimalIntentBeforePostAndReplaysSameCommand() = runTest {
        val line = DriverDeliveryOutcomeLine(
            FULFILLMENT_LINE_ID, SKU_ID, "CAT-100", BigDecimal("2.000"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("2.000"), "UNIT"
        )
        val metadata = FakeDriverOutcomeMetadataStore()
        val gateway = FakeDriverDeliveryGateway().apply {
            detailBlock = { DriverDeliveryLoadResult.DetailLoaded(activeDelivery(9).copy(outcomeLines = listOf(line))) }
            outcomeResults += DriverOutcomeResult.UnknownOutcome
            outcomeResults += DriverOutcomeResult.Recorded(
                DriverOutcomeSummary(
                    ATTEMPT_ID, "PARTIAL", "2026-09-30T20:00:00Z", 10, true,
                    listOf(DriverRemainingQuantityLine(FULFILLMENT_LINE_ID, SKU_ID, "CAT-100", BigDecimal("0.750"), "UNIT"))
                )
            )
        }
        var outcomeDispatches = 0
        gateway.beforeOutcome = {
            val expected = if (outcomeDispatches++ == 0) {
                DriverOutcomeIntentStatus.Pending
            } else {
                DriverOutcomeIntentStatus.UnknownOutcome
            }
            assertEquals(expected, metadata.stored?.status)
        }
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore(),
            keyFactory = { "stable-outcome-key" },
            outcomeMetadataStore = metadata,
            timeFactory = { "2026-09-30T20:00:00Z" }
        )
        activateAndSelect(viewModel)

        viewModel.recordOutcome(
            DriverOutcomeKind.PARTIAL,
            deliveredQuantities = mapOf(FULFILLMENT_LINE_ID to "1.250"),
            notes = "Buyer accepted part"
        )
        advanceUntilIdle()

        assertEquals(DriverOutcomeCommandStatus.UnknownOutcome, viewModel.state.value.outcomeCommandStatus)
        assertEquals(1, gateway.outcomeCommands.size)
        val frozen = gateway.outcomeCommands.single()
        assertEquals("stable-outcome-key", frozen.idempotencyKey)
        assertEquals(9L, frozen.expectedVersion)
        assertTrue(frozen.frozenBody.contains("\"attemptedQuantity\":1.250"))
        assertTrue(frozen.frozenBody.contains("\"deliveredQuantity\":1.250"))
        assertFalse(frozen.frozenBody.contains("\"attemptedQuantity\":\"1.250\""))
        assertEquals(DriverOutcomeIntentStatus.UnknownOutcome, metadata.stored?.status)

        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(2, gateway.outcomeCommands.size)
        assertEquals(frozen, gateway.outcomeCommands.last())
        assertEquals(DriverOutcomeCommandStatus.Recorded, viewModel.state.value.outcomeCommandStatus)
        assertEquals("2026-09-30T20:00:00Z", viewModel.state.value.outcomeSummary?.attemptedAt)
        assertNull(metadata.stored)
    }

    @Test
    fun outcomeStorageFailureBlocksPostAndProcessRestoreBecomesUnknown() = runTest {
        val line = DriverDeliveryOutcomeLine(
            FULFILLMENT_LINE_ID, SKU_ID, "CAT-100", BigDecimal("2"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("2"), "UNIT"
        )
        val metadata = FakeDriverOutcomeMetadataStore().apply {
            writeResult = DriverOutcomeMetadataWrite.Unavailable
        }
        val gateway = FakeDriverDeliveryGateway().apply {
            detailBlock = { DriverDeliveryLoadResult.DetailLoaded(activeDelivery(9).copy(outcomeLines = listOf(line))) }
        }
        val viewModel = DriverDeliveryViewModel(
            gateway, FakeDriverAttemptMetadataStore(), keyFactory = { "outcome-storage-key" },
            outcomeMetadataStore = metadata, timeFactory = { "2026-09-30T20:00:00Z" }
        )
        activateAndSelect(viewModel)
        viewModel.recordOutcome(DriverOutcomeKind.DELIVERED)
        advanceUntilIdle()
        assertTrue(gateway.outcomeCommands.isEmpty())
        assertEquals(DriverOutcomeCommandStatus.PersistenceUnavailable, viewModel.state.value.outcomeCommandStatus)

        metadata.writeResult = DriverOutcomeMetadataWrite.Saved
        viewModel.retryUnknownOutcome()
        advanceUntilIdle()
        assertEquals(1, gateway.outcomeCommands.size)

        val persisted = metadata.stored ?: error("expected durable outcome intent")
        metadata.stored = persisted.copy(status = DriverOutcomeIntentStatus.Pending)
        val restoredGateway = FakeDriverDeliveryGateway().apply {
            outcomeResults += DriverOutcomeResult.UnknownOutcome
        }
        val restored = DriverDeliveryViewModel(
            restoredGateway, FakeDriverAttemptMetadataStore(), outcomeMetadataStore = metadata
        )
        restored.activate(AUTHORITY)
        advanceUntilIdle()
        assertEquals(DriverOutcomeCommandStatus.UnknownOutcome, restored.state.value.outcomeCommandStatus)
        assertEquals(DriverOutcomeIntentStatus.UnknownOutcome, metadata.stored?.status)
        restored.retryUnknownOutcome()
        advanceUntilIdle()
        assertEquals(persisted.command, restoredGateway.outcomeCommands.single())
    }

    private suspend fun TestScope.activateAndSelect(viewModel: DriverDeliveryViewModel) {
        viewModel.activate(AUTHORITY)
        advanceUntilIdle()
        viewModel.selectDelivery(DELIVERY_ID)
        advanceUntilIdle()
    }

    private class FakeDriverDeliveryGateway : DriverDeliveryGateway {
        var detailCalls = 0
        val commands = mutableListOf<DriverAttemptStartCommand>()
        val outcomeCommands = mutableListOf<DriverOutcomeCommand>()
        val startResults = ArrayDeque<DriverAttemptStartResult>()
        val outcomeResults = ArrayDeque<DriverOutcomeResult>()
        var beforeOutcome: () -> Unit = {}
        var detailBlock: suspend (String) -> DriverDeliveryLoadResult = { deliveryId ->
            if (deliveryId == DELIVERY_ID) {
                DriverDeliveryLoadResult.DetailLoaded(delivery())
            } else {
                DriverDeliveryLoadResult.NotFound
            }
        }
        var startBlock: suspend (
            DriverAttemptStartCommand,
            DriverDeliveryAuthority
        ) -> DriverAttemptStartResult =
            { command, _ ->
                commands += command
                startResults.removeFirstOrNull()
                    ?: DriverAttemptStartResult.Started(activeDelivery(10), activeAttempt())
            }

        override suspend fun assignedDeliveries(
            authority: DriverDeliveryAuthority
        ): DriverDeliveryLoadResult = DriverDeliveryLoadResult.ListLoaded(listOf(delivery()))

        override suspend fun delivery(
            deliveryId: String,
            authority: DriverDeliveryAuthority
        ): DriverDeliveryLoadResult {
            detailCalls++
            return detailBlock(deliveryId)
        }

        override suspend fun startAttempt(
            command: DriverAttemptStartCommand,
            authority: DriverDeliveryAuthority
        ): DriverAttemptStartResult = startBlock(command, authority).also {
            if (command !in
                commands
            ) {
                commands += command
            }
        }

        override suspend fun recordOutcome(
            command: DriverOutcomeCommand,
            authority: DriverDeliveryAuthority
        ): DriverOutcomeResult {
            beforeOutcome()
            outcomeCommands += command
            return outcomeResults.removeFirstOrNull() ?: DriverOutcomeResult.UnknownOutcome
        }
    }

    private class FakeDriverOutcomeMetadataStore : DriverOutcomeMetadataStore {
        var stored: DriverOutcomeIntentMetadata? = null
        var writeResult: DriverOutcomeMetadataWrite = DriverOutcomeMetadataWrite.Saved

        override suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverOutcomeMetadataRead {
            val current = stored
            if (current?.scope != scope) return DriverOutcomeMetadataRead.Available(null)
            if (current.status == DriverOutcomeIntentStatus.Pending) {
                stored = current.copy(status = DriverOutcomeIntentStatus.UnknownOutcome)
            }
            return DriverOutcomeMetadataRead.Available(stored)
        }

        override suspend fun saveIntent(intent: DriverOutcomeIntentMetadata): DriverOutcomeMetadataWrite {
            if (writeResult != DriverOutcomeMetadataWrite.Saved) return writeResult
            val current = stored
            if (current != null && current.command != intent.command) return DriverOutcomeMetadataWrite.Conflict
            stored = intent
            return DriverOutcomeMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            idempotencyKey: String
        ): DriverOutcomeMetadataWrite {
            if (writeResult != DriverOutcomeMetadataWrite.Saved) return writeResult
            val current = stored ?: return DriverOutcomeMetadataWrite.Saved
            if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                return DriverOutcomeMetadataWrite.Stale
            }
            stored = null
            return DriverOutcomeMetadataWrite.Saved
        }
    }

    private class FakeDriverAttemptMetadataStore : DriverAttemptMetadataStore {
        var stored: DriverAttemptIntentMetadata? = null
        var writeResult: DriverAttemptMetadataWrite = DriverAttemptMetadataWrite.Saved

        override suspend fun loadIntent(
            scope: DriverAttemptScopeIdentity
        ): DriverAttemptMetadataRead {
            if (stored?.scope != scope) return DriverAttemptMetadataRead.Available(null)
            if (stored?.status == DriverAttemptMetadataStatus.Pending) {
                stored = stored?.copy(status = DriverAttemptMetadataStatus.UnknownOutcome)
            }
            return DriverAttemptMetadataRead.Available(stored)
        }

        override suspend fun saveIntent(
            intent: DriverAttemptIntentMetadata
        ): DriverAttemptMetadataWrite {
            if (writeResult != DriverAttemptMetadataWrite.Saved) return writeResult
            val current = stored
            if (current != null &&
                (current.idempotencyKey != intent.idempotencyKey ||
                    current.deliveryId != intent.deliveryId ||
                    current.expectedVersion != intent.expectedVersion ||
                    (current.status == DriverAttemptMetadataStatus.UnknownOutcome &&
                        intent.status == DriverAttemptMetadataStatus.Pending))
            ) {
                return DriverAttemptMetadataWrite.Conflict
            }
            stored = intent
            return DriverAttemptMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            idempotencyKey: String
        ): DriverAttemptMetadataWrite {
            val current = stored ?: return DriverAttemptMetadataWrite.Saved
            if (current.scope != scope || current.idempotencyKey != idempotencyKey) {
                return DriverAttemptMetadataWrite.Stale
            }
            stored = null
            return DriverAttemptMetadataWrite.Saved
        }
    }

    private companion object {
        const val DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val ATTEMPT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413202"
        const val FULFILLMENT_LINE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413207"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413208"
        val AUTHORITY = DriverDeliveryAuthority(
            userId = "b8c24a46-57d9-4f64-8fa7-6a641b413203",
            tenantId = "b8c24a46-57d9-4f64-8fa7-6a641b413204",
            workspaceId = "b8c24a46-57d9-4f64-8fa7-6a641b413205",
            membershipId = "b8c24a46-57d9-4f64-8fa7-6a641b413206",
            permissions = setOf("dispatch.read", "dispatch.start_route"),
            authorityEpoch = 2
        )
        val OTHER_AUTHORITY = AUTHORITY.copy(authorityEpoch = 3)

        fun delivery(version: Long = 9) = DriverDeliverySnapshot(
            id = DELIVERY_ID,
            fulfillmentId = null,
            salesOrderId = null,
            status = "DISPATCHED",
            destination = "Server destination",
            scheduledAt = null,
            dispatchedAt = null,
            deliveredAt = null,
            updatedAt = "2026-09-30T15:00:00Z",
            version = version,
            activeAttempt = null
        )

        fun activeAttempt() = DriverDeliveryAttempt(
            id = ATTEMPT_ID,
            attemptNumber = 1,
            status = "ACTIVE",
            startedByMembershipId = AUTHORITY.membershipId,
            startedAt = "2026-09-30T15:01:00Z"
        )

        fun activeDelivery(version: Long) = delivery(version).copy(activeAttempt = activeAttempt())
    }
}
