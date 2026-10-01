package com.nexa.mobile.operations.feature.delivery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private suspend fun TestScope.activateAndSelect(viewModel: DriverDeliveryViewModel) {
        viewModel.activate(AUTHORITY)
        advanceUntilIdle()
        viewModel.selectDelivery(DELIVERY_ID)
        advanceUntilIdle()
    }

    private class FakeDriverDeliveryGateway : DriverDeliveryGateway {
        var detailCalls = 0
        val commands = mutableListOf<DriverAttemptStartCommand>()
        val startResults = ArrayDeque<DriverAttemptStartResult>()
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
