package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionAcknowledgementCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionAcknowledgementResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionsLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstruction
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionAcknowledgementFact
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionAcknowledgementSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionKind
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionsSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.testsupport.TestDeliveryRequestBodyCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriverDeliveryInstructionsViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun normalInstructionNeedsNoAckAndCriticalAckPersistsFullIntentBeforePost() = runTest {
        val events = mutableListOf<String>()
        val store = FakeMetadataStore(events)
        val gateway = FakeGateway(events)
        val viewModel = viewModel(gateway, store)
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        assertEquals(DriverDeliveryInstructionsLoadStatus.Ready, viewModel.state.value.loadStatus)
        assertFalse(viewModel.state.value.canAcknowledgeSelected)
        viewModel.setInstructionSelected(NORMAL_ID, true)
        assertTrue(viewModel.state.value.selectedInstructionIds.isEmpty())
        viewModel.setInstructionSelected(CRITICAL_ID, true)
        assertTrue(viewModel.state.value.canAcknowledgeSelected)
        viewModel.acknowledgeSelected()
        advanceUntilIdle()

        assertEquals(listOf("persist", "post"), events)
        val saved =
            store.savedIntents.singleOrNull() ?: error("Expected encrypted acknowledgement intent")
        assertEquals(USER_ID, saved.scope.userId)
        assertEquals(TENANT_ID, saved.scope.tenantId)
        assertEquals(WORKSPACE_ID, saved.scope.workspaceId)
        assertEquals(MEMBERSHIP_ID, saved.scope.membershipId)
        assertEquals(MEMBERSHIP_ID, saved.initiatedByMembershipId)
        assertEquals("2026-10-01T17:00:00Z", saved.initiatedAt)
        assertEquals(7L, saved.command.instructionSetVersion)
        assertEquals(mapOf(CRITICAL_ID to 5L), saved.command.instructionVersions)
        assertEquals(
            """{"instructionIds":["$CRITICAL_ID"]}""",
            gateway.acknowledged.single().frozenBody
        )
        assertEquals(
            DriverDeliveryInstructionAcknowledgementStatus.Acknowledged,
            viewModel.state.value.acknowledgementStatus
        )
        assertTrue(
            viewModel.state.value.snapshot?.instructions?.single {
                it.id == CRITICAL_ID
            }?.acknowledged ==
                true
        )
        assertEquals(
            "2026-10-01T17:01:00Z",
            viewModel.state.value.snapshot?.instructions
                ?.single { it.id == CRITICAL_ID }?.acknowledgedAt
        )
    }

    @Test
    fun restoredUnknownAcknowledgementWaitsForManualSameKeyRetry() = runTest {
        val store = FakeMetadataStore()
        val original = intent()
        store.intent = original
        val gateway = FakeGateway().apply {
            acknowledgementResult = DriverDeliveryInstructionAcknowledgementResult.Acknowledged(
                summary(replayed = true)
            )
        }
        val viewModel = viewModel(gateway, store)
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        assertEquals(
            DriverDeliveryInstructionAcknowledgementStatus.UnknownOutcome,
            viewModel.state.value.acknowledgementStatus
        )
        assertTrue(gateway.acknowledged.isEmpty())
        viewModel.retryUnknownAcknowledgement()
        advanceUntilIdle()

        assertEquals(listOf(original.command), gateway.acknowledged)
        assertEquals("original-key", gateway.acknowledged.single().idempotencyKey)
        assertEquals(original.command.frozenBody, gateway.acknowledged.single().frozenBody)
        assertTrue(viewModel.state.value.acknowledgementReplayed)
        assertEquals(
            DriverDeliveryInstructionAcknowledgementStatus.Acknowledged,
            viewModel.state.value.acknowledgementStatus
        )
    }

    @Test
    fun deniedManualRetryKeepsUncertainIntentForLaterSameKeyRecovery() = runTest {
        val store = FakeMetadataStore().apply { intent = intent() }
        val gateway = FakeGateway().apply {
            acknowledgementResult = DriverDeliveryInstructionAcknowledgementResult.PermissionDenied
        }
        val viewModel = viewModel(gateway, store)
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        viewModel.retryUnknownAcknowledgement()
        advanceUntilIdle()

        assertEquals("original-key", store.intent?.command?.idempotencyKey)
        assertTrue(viewModel.state.value.hasRecoverableAcknowledgement)
        assertEquals(
            DriverDeliveryInstructionAcknowledgementStatus.UnknownOutcome,
            viewModel.state.value.acknowledgementStatus
        )
    }

    @Test
    fun restoredStaleIntentRefreshesWithoutPostingAndRequiresFreshSelection() = runTest {
        val store = FakeMetadataStore().apply {
            intent = intent().copy(status = DriverDeliveryInstructionIntentStatus.StaleVersion)
        }
        val gateway = FakeGateway().apply {
            readResults += DriverDeliveryInstructionsLoadResult.Loaded(
                snapshot(
                    version = 8,
                    instructionVersion = 6,
                    content = "Use the current cold storage bay"
                )
            )
        }
        val viewModel = viewModel(gateway, store)
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        assertEquals(1, gateway.reads)
        assertTrue(gateway.acknowledged.isEmpty())
        assertEquals(
            DriverDeliveryInstructionAcknowledgementStatus.StaleVersion,
            viewModel.state.value.acknowledgementStatus
        )
        assertEquals(8L, viewModel.state.value.snapshot?.instructionSetVersion)
        assertEquals(null, store.intent)
        assertTrue(viewModel.state.value.selectedInstructionIds.isEmpty())

        viewModel.setInstructionSelected(CRITICAL_ID, true)
        assertTrue(viewModel.state.value.canAcknowledgeSelected)
    }

    @Test
    fun staleAcknowledgementRefreshesAndRequiresNewExplicitDecision() = runTest {
        val store = FakeMetadataStore()
        val gateway = FakeGateway().apply {
            readResults +=
                DriverDeliveryInstructionsLoadResult.Loaded(
                    snapshot(version = 7, instructionVersion = 5)
                )
            readResults +=
                DriverDeliveryInstructionsLoadResult.Loaded(
                    snapshot(
                        version = 8,
                        instructionVersion = 6,
                        content = "Use cold storage bay 2"
                    )
                )
            acknowledgementResult = DriverDeliveryInstructionAcknowledgementResult.StaleVersion
        }
        val keyFactoryCalls = mutableListOf<Int>()
        val viewModel = viewModel(gateway, store, keyFactory = {
            keyFactoryCalls += 1
            "fresh-key-${keyFactoryCalls.size}"
        })
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()
        viewModel.setInstructionSelected(CRITICAL_ID, true)
        viewModel.acknowledgeSelected()
        advanceUntilIdle()

        assertEquals(2, gateway.reads)
        assertEquals(1, gateway.acknowledged.size)
        assertEquals(
            DriverDeliveryInstructionAcknowledgementStatus.StaleVersion,
            viewModel.state.value.acknowledgementStatus
        )
        assertTrue(viewModel.state.value.selectedInstructionIds.isEmpty())
        assertEquals(8L, viewModel.state.value.snapshot?.instructionSetVersion)
        assertEquals(
            "Use cold storage bay 2",
            viewModel.state.value.snapshot?.instructions?.last()?.content
        )
        assertEquals(null, store.intent)

        viewModel.setInstructionSelected(CRITICAL_ID, true)
        gateway.acknowledgementResult = DriverDeliveryInstructionAcknowledgementResult.Acknowledged(
            summary(version = 8, instructionVersion = 6)
        )
        viewModel.acknowledgeSelected()
        advanceUntilIdle()
        assertEquals(2, gateway.acknowledged.size)
        assertEquals("fresh-key-2", gateway.acknowledged.last().idempotencyKey)
        assertEquals(8L, gateway.acknowledged.last().instructionSetVersion)
    }

    @Test
    fun lateReadAfterAuthorityInvalidationDoesNotRestoreInstructions() = runTest {
        val response = CompletableDeferred<DriverDeliveryInstructionsLoadResult>()
        val gateway = FakeGateway().apply { pendingRead = response }
        val viewModel = viewModel(gateway, FakeMetadataStore())
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        runCurrent()
        viewModel.invalidate()
        response.complete(DriverDeliveryInstructionsLoadResult.Loaded(snapshot()))
        runCurrent()

        assertEquals(DriverDeliveryInstructionsUiState(), viewModel.state.value)
    }

    private fun viewModel(
        gateway: FakeGateway,
        store: FakeMetadataStore,
        keyFactory: () -> String = { "new-key" }
    ) = DriverDeliveryInstructionsViewModel(
        gateway,
        store,
        timeFactory = { "2026-10-01T17:00:00Z" },
        keyFactory = keyFactory,
        requestBodyCodec = TestDeliveryRequestBodyCodec()
    )

    private class FakeGateway(private val events: MutableList<String> = mutableListOf()) :
        DriverDeliveryInstructionsGateway {
        val readResults = mutableListOf<DriverDeliveryInstructionsLoadResult>()
        val acknowledged = mutableListOf<DriverDeliveryInstructionAcknowledgementCommand>()
        var pendingRead: CompletableDeferred<DriverDeliveryInstructionsLoadResult>? = null
        var acknowledgementResult: DriverDeliveryInstructionAcknowledgementResult =
            DriverDeliveryInstructionAcknowledgementResult.Acknowledged(summary())
        var reads = 0

        override suspend fun currentInstructions(
            deliveryId: String,
            authority: DriverDeliveryAuthority
        ): DriverDeliveryInstructionsLoadResult {
            reads++
            return pendingRead?.await() ?: readResults.removeFirstOrNull()
                ?: DriverDeliveryInstructionsLoadResult.Loaded(snapshot())
        }

        override suspend fun acknowledgeCriticalInstructions(
            command: DriverDeliveryInstructionAcknowledgementCommand,
            authority: DriverDeliveryAuthority
        ): DriverDeliveryInstructionAcknowledgementResult {
            events += "post"
            acknowledged += command
            return acknowledgementResult
        }
    }

    private class FakeMetadataStore(private val events: MutableList<String> = mutableListOf()) :
        DriverDeliveryInstructionMetadataStore {
        var intent: DriverDeliveryInstructionIntentMetadata? = null
        val savedIntents = mutableListOf<DriverDeliveryInstructionIntentMetadata>()

        override suspend fun loadIntent(
            scope: DriverAttemptScopeIdentity
        ): DriverDeliveryInstructionMetadataRead =
            DriverDeliveryInstructionMetadataRead.Available(intent?.takeIf { it.scope == scope })

        override suspend fun saveIntent(
            intent: DriverDeliveryInstructionIntentMetadata
        ): DriverDeliveryInstructionMetadataWrite {
            events += "persist"
            val current = this.intent
            if (current != null &&
                current.command != intent.command
            ) {
                return DriverDeliveryInstructionMetadataWrite.Conflict
            }
            this.intent = intent
            savedIntents += intent
            return DriverDeliveryInstructionMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            idempotencyKey: String
        ): DriverDeliveryInstructionMetadataWrite {
            val current = intent ?: return DriverDeliveryInstructionMetadataWrite.Saved
            if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                return DriverDeliveryInstructionMetadataWrite.Stale
            }
            intent = null
            return DriverDeliveryInstructionMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val TENANT_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val MEMBERSHIP_ID = "44444444-4444-4444-8444-444444444444"
        const val DELIVERY_ID = "55555555-5555-4555-8555-555555555555"
        const val NORMAL_ID = "66666666-6666-4666-8666-666666666666"
        const val CRITICAL_ID = "77777777-7777-4777-8777-777777777777"
        val AUTHORITY = DriverDeliveryAuthority(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBERSHIP_ID,
            setOf("dispatch.read", "dispatch.start_route"),
            authorityEpoch = 12
        )

        fun snapshot(
            version: Long = 7,
            instructionVersion: Long = 5,
            content: String = "Keep chilled goods below 5 C"
        ) = DriverDeliveryInstructionsSnapshot(
            DELIVERY_ID,
            deliveryVersion = 4,
            instructionSetVersion = version,
            instructions = listOf(
                DriverDeliveryInstruction(
                    NORMAL_ID,
                    DriverDeliveryInstructionKind.NORMAL,
                    "Use the front entrance",
                    2,
                    critical = false,
                    acknowledged = false,
                    acknowledgedAt = null,
                    acknowledgedByMembershipId = null
                ),
                DriverDeliveryInstruction(
                    CRITICAL_ID,
                    DriverDeliveryInstructionKind.COLD_CHAIN,
                    content,
                    instructionVersion,
                    critical = true,
                    acknowledged = false,
                    acknowledgedAt = null,
                    acknowledgedByMembershipId = null
                )
            )
        )

        fun summary(version: Long = 7, instructionVersion: Long = 5, replayed: Boolean = false) =
            DriverDeliveryInstructionAcknowledgementSummary(
                deliveryId = DELIVERY_ID,
                instructionSetVersion = version,
                acknowledgements = listOf(
                    DriverDeliveryInstructionAcknowledgementFact(
                        CRITICAL_ID,
                        instructionVersion,
                        MEMBERSHIP_ID,
                        "2026-10-01T17:01:00Z"
                    )
                ),
                replayed = replayed
            )

        fun intent() = DriverDeliveryInstructionIntentMetadata(
            scope = DriverAttemptScopeIdentity(USER_ID, TENANT_ID, WORKSPACE_ID, MEMBERSHIP_ID),
            command = DriverDeliveryInstructionAcknowledgementCommand(
                DELIVERY_ID,
                7,
                mapOf(CRITICAL_ID to 5),
                "original-key",
                TestDeliveryRequestBodyCodec().driverDeliveryInstructionAcknowledgementBody(
                    listOf(CRITICAL_ID)
                )
            ),
            initiatedByMembershipId = MEMBERSHIP_ID,
            initiatedAt = "2026-10-01T16:59:00Z",
            status = DriverDeliveryInstructionIntentStatus.Pending
        )
    }
}
