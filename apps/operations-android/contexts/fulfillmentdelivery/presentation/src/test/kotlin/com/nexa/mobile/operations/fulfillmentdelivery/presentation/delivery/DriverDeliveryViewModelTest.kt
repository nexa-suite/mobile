package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.presentation.testsupport.TestDeliveryRequestBodyCodec

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverArrivalSummary
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptMetadataStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptStartCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptStartResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryArrivalFact
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryAttempt
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryOutcomeLine
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliverySnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverOutcomeKind
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverOutcomeSummary
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofCreateCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofCreateResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofFileCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofIntentStage
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverProofSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverRemainingQuantityLine
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
        val viewModel = DriverDeliveryViewModel(gateway, metadata, keyFactory = { "start-key" }, requestBodyCodec = TestDeliveryRequestBodyCodec())
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
    fun instructionsEntryRequiresCurrentOperationalAssignmentButNotAnActiveAttempt() = runTest {
        val assigned = delivery()
        val delivered = delivery().copy(id = DELIVERED_DELIVERY_ID, status = "DELIVERED")
        val cancelled = delivery().copy(id = CANCELLED_DELIVERY_ID, status = "CANCELLED")
        val gateway = FakeDriverDeliveryGateway().apply {
            listItems = listOf(assigned, delivered, cancelled)
            detailBlock = { id ->
                when (id) {
                    assigned.id -> DriverDeliveryLoadResult.DetailLoaded(assigned)
                    delivered.id -> DriverDeliveryLoadResult.DetailLoaded(delivered)
                    cancelled.id -> DriverDeliveryLoadResult.DetailLoaded(cancelled)
                    else -> DriverDeliveryLoadResult.NotFound
                }
            }
        }
        val viewModel = DriverDeliveryViewModel(gateway, FakeDriverAttemptMetadataStore(), requestBodyCodec = TestDeliveryRequestBodyCodec())
        viewModel.activate(AUTHORITY)
        advanceUntilIdle()

        viewModel.selectDelivery(assigned.id)
        advanceUntilIdle()
        assertNull(viewModel.state.value.selectedDelivery?.activeAttempt)
        assertEquals(assigned.id, viewModel.state.value.authorizedInstructionsDeliveryId)
        assertEquals(assigned.id, viewModel.state.value.authorizedOperationalExceptionsDeliveryId)

        viewModel.selectDelivery(delivered.id)
        advanceUntilIdle()
        assertNull(viewModel.state.value.authorizedInstructionsDeliveryId)
        assertNull(viewModel.state.value.authorizedOperationalExceptionsDeliveryId)

        viewModel.selectDelivery(cancelled.id)
        advanceUntilIdle()
        assertNull(viewModel.state.value.authorizedInstructionsDeliveryId)
        assertNull(viewModel.state.value.authorizedOperationalExceptionsDeliveryId)
    }

    @Test
    fun directionsWaitForFreshCurrentDetailRatherThanListProjection() = runTest {
        val gateway = FakeDriverDeliveryGateway()
        val detailGate = CompletableDeferred<DriverDeliveryLoadResult>()
        gateway.detailBlock = { detailGate.await() }
        val viewModel = DriverDeliveryViewModel(gateway, FakeDriverAttemptMetadataStore(), requestBodyCodec = TestDeliveryRequestBodyCodec())
        viewModel.activate(AUTHORITY)
        advanceUntilIdle()

        viewModel.selectDelivery(DELIVERY_ID)
        runCurrent()

        assertNull(viewModel.state.value.authorizedDirectionsDestination)
        assertEquals(DriverDeliveryLoadStatus.Loading, viewModel.state.value.detailStatus)
        detailGate.complete(DriverDeliveryLoadResult.DetailLoaded(activeDelivery(9)))
        advanceUntilIdle()

        assertEquals("Server destination", viewModel.state.value.authorizedDirectionsDestination)
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
            FakeDriverAttemptMetadataStore(),
            keyFactory = { "stable-start-key" },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
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
            FakeDriverAttemptMetadataStore(),
            keyFactory = { "one-start-key" },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
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
            FakeDriverAttemptMetadataStore(),
            keyFactory = { "late-key" },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
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
        val viewModel = DriverDeliveryViewModel(gateway, FakeDriverAttemptMetadataStore(), requestBodyCodec = TestDeliveryRequestBodyCodec())
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
        val viewModel = DriverDeliveryViewModel(
            gateway,
            metadata,
            keyFactory = {
                keys++
                "durable-start-key"
            },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
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
        val viewModel = DriverDeliveryViewModel(gateway, metadata, requestBodyCodec = TestDeliveryRequestBodyCodec())

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
    fun arrivalPersistsExactIdentityBeforePostAndKeepsDeliveryOpen() = runTest {
        val metadata = FakeDriverArrivalMetadataStore()
        val confirmed = activeDelivery(10).copy(
            status = "IN_TRANSIT",
            arrival = DriverDeliveryArrivalFact(ARRIVAL_ID, ATTEMPT_ID, ARRIVED_AT)
        )
        val gateway = FakeDriverDeliveryGateway().apply {
            listItems = listOf(activeDelivery(9).copy(status = "IN_TRANSIT"))
            detailBlock =
                {
                    DriverDeliveryLoadResult.DetailLoaded(
                        activeDelivery(9).copy(status = "IN_TRANSIT")
                    )
                }
            arrivalBlock = { command, _ ->
                assertEquals(DriverArrivalIntentStatus.Pending, metadata.stored?.status)
                assertEquals("arrival-stable-key", command.idempotencyKey)
                listItems = listOf(confirmed)
                detailBlock = { DriverDeliveryLoadResult.DetailLoaded(confirmed) }
                DriverArrivalResult.Recorded(
                    DriverArrivalSummary(ARRIVAL_ID, DELIVERY_ID, ATTEMPT_ID, ARRIVED_AT, 10)
                )
            }
        }
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore(),
            arrivalMetadataStore = metadata,
            keyFactory = { "arrival-stable-key" },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        activateAndSelect(viewModel)

        viewModel.signalArrival()
        advanceUntilIdle()

        assertEquals(1, gateway.arrivalCommands.size)
        assertEquals(
            DriverArrivalCommand(
                DELIVERY_ID,
                ATTEMPT_ID,
                9,
                "arrival-stable-key",
                TestDeliveryRequestBodyCodec().driverArrivalBody()
            ),
            gateway.arrivalCommands.single()
        )
        assertEquals(
            DriverArrivalCommandStatus.Recorded,
            viewModel.state.value.arrivalCommandStatus
        )
        assertEquals(ARRIVAL_ID, viewModel.state.value.selectedDelivery?.arrival?.id)
        assertEquals("IN_TRANSIT", viewModel.state.value.selectedDelivery?.status)
        assertEquals(ATTEMPT_ID, viewModel.state.value.selectedDelivery?.activeAttempt?.id)
        assertNull(metadata.stored)
    }

    @Test
    fun uncertainArrivalOnlyReplaysAfterExplicitTapWithSameIdentity() = runTest {
        val metadata = FakeDriverArrivalMetadataStore()
        val gateway = FakeDriverDeliveryGateway().apply {
            listItems = listOf(activeDelivery(9))
            detailBlock = { DriverDeliveryLoadResult.DetailLoaded(activeDelivery(9)) }
            arrivalResults += DriverArrivalResult.UnknownOutcome
            arrivalResults += DriverArrivalResult.Recorded(
                DriverArrivalSummary(ARRIVAL_ID, DELIVERY_ID, ATTEMPT_ID, ARRIVED_AT, 10)
            )
        }
        var keys = 0
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore(),
            arrivalMetadataStore = metadata,
            keyFactory = {
                keys++
                "arrival-unknown-key"
            },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        activateAndSelect(viewModel)

        viewModel.signalArrival()
        advanceUntilIdle()
        assertEquals(
            DriverArrivalCommandStatus.UnknownOutcome,
            viewModel.state.value.arrivalCommandStatus
        )
        assertEquals(DriverArrivalIntentStatus.UnknownOutcome, metadata.stored?.status)
        assertEquals(1, gateway.arrivalCommands.size)

        viewModel.signalArrival()
        advanceUntilIdle()
        assertEquals(1, gateway.arrivalCommands.size)

        viewModel.retryUnknownArrival()
        advanceUntilIdle()
        assertEquals(2, gateway.arrivalCommands.size)
        assertEquals(gateway.arrivalCommands.first(), gateway.arrivalCommands.last())
        assertEquals(1, keys)
        assertEquals(
            DriverArrivalCommandStatus.Recorded,
            viewModel.state.value.arrivalCommandStatus
        )
        assertNull(metadata.stored)
    }

    @Test
    fun restoredPendingArrivalBecomesUnknownAndWaitsForExplicitReplay() = runTest {
        val command = DriverArrivalCommand(
            DELIVERY_ID,
            ATTEMPT_ID,
            9,
            "recovered-arrival-key",
            TestDeliveryRequestBodyCodec().driverArrivalBody()
        )
        val metadata = FakeDriverArrivalMetadataStore().apply {
            stored = DriverArrivalIntentMetadata(
                AUTHORITY.scopeIdentity,
                command,
                DriverArrivalIntentStatus.Pending
            )
        }
        val gateway = FakeDriverDeliveryGateway().apply {
            listItems = listOf(activeDelivery(10))
            arrivalResults += DriverArrivalResult.Recorded(
                DriverArrivalSummary(ARRIVAL_ID, DELIVERY_ID, ATTEMPT_ID, ARRIVED_AT, 10)
            )
        }
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore(),
            arrivalMetadataStore = metadata,
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        viewModel.activate(AUTHORITY)
        advanceUntilIdle()

        assertEquals(
            DriverArrivalCommandStatus.UnknownOutcome,
            viewModel.state.value.arrivalCommandStatus
        )
        assertEquals(DriverArrivalIntentStatus.UnknownOutcome, metadata.stored?.status)
        assertTrue(gateway.arrivalCommands.isEmpty())

        viewModel.retryUnknownArrival()
        advanceUntilIdle()

        assertEquals(listOf(command), gateway.arrivalCommands)
        assertEquals(
            DriverArrivalCommandStatus.Recorded,
            viewModel.state.value.arrivalCommandStatus
        )
        assertNull(metadata.stored)
    }

    @Test
    fun outcomePersistsExactDecimalIntentBeforePostAndReplaysSameCommand() = runTest {
        val line = DriverDeliveryOutcomeLine(
            FULFILLMENT_LINE_ID, SKU_ID, "CAT-100", BigDecimal("2.000"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("2.000"), "UNIT"
        )
        val metadata = FakeDriverOutcomeMetadataStore()
        val gateway = FakeDriverDeliveryGateway().apply {
            detailBlock =
                {
                    DriverDeliveryLoadResult.DetailLoaded(
                        activeDelivery(9).copy(outcomeLines = listOf(line))
                    )
                }
            outcomeResults += DriverOutcomeResult.UnknownOutcome
            outcomeResults += DriverOutcomeResult.Recorded(
                DriverOutcomeSummary(
                    ATTEMPT_ID,
                    "PARTIAL",
                    "2026-09-30T20:00:00Z",
                    10,
                    true,
                    listOf(
                        DriverRemainingQuantityLine(
                            FULFILLMENT_LINE_ID,
                            SKU_ID,
                            "CAT-100",
                            BigDecimal("0.750"),
                            "UNIT"
                        )
                    )
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
            timeFactory = { "2026-09-30T20:00:00Z" },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        activateAndSelect(viewModel)

        viewModel.recordOutcome(
            DriverOutcomeKind.PARTIAL,
            deliveredQuantities = mapOf(FULFILLMENT_LINE_ID to "1.250"),
            notes = "Buyer accepted part"
        )
        advanceUntilIdle()

        assertEquals(
            DriverOutcomeCommandStatus.UnknownOutcome,
            viewModel.state.value.outcomeCommandStatus
        )
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
        assertEquals(
            DriverOutcomeCommandStatus.Recorded,
            viewModel.state.value.outcomeCommandStatus
        )
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
            detailBlock =
                {
                    DriverDeliveryLoadResult.DetailLoaded(
                        activeDelivery(9).copy(outcomeLines = listOf(line))
                    )
                }
        }
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore(),
            keyFactory = { "outcome-storage-key" },
            outcomeMetadataStore = metadata,
            timeFactory = { "2026-09-30T20:00:00Z" },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        activateAndSelect(viewModel)
        viewModel.recordOutcome(DriverOutcomeKind.DELIVERED)
        advanceUntilIdle()
        assertTrue(gateway.outcomeCommands.isEmpty())
        assertEquals(
            DriverOutcomeCommandStatus.PersistenceUnavailable,
            viewModel.state.value.outcomeCommandStatus
        )

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
            restoredGateway,
            FakeDriverAttemptMetadataStore(),
            outcomeMetadataStore = metadata,
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        restored.activate(AUTHORITY)
        advanceUntilIdle()
        assertEquals(
            DriverOutcomeCommandStatus.UnknownOutcome,
            restored.state.value.outcomeCommandStatus
        )
        assertEquals(DriverOutcomeIntentStatus.UnknownOutcome, metadata.stored?.status)
        restored.retryUnknownOutcome()
        advanceUntilIdle()
        assertEquals(persisted.command, restoredGateway.outcomeCommands.single())
    }

    @Test
    fun proofCreationPersistsBeforePostAndUnknownOutcomeReplaysSameIdentity() = runTest {
        val line = DriverDeliveryOutcomeLine(
            FULFILLMENT_LINE_ID, SKU_ID, "CAT-100", BigDecimal("2"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("2"), "UNIT"
        )
        val delivered = activeDelivery(10).copy(
            status = "DELIVERED",
            outcomeLines = listOf(
                line.copy(deliveredQuantity = BigDecimal("2"), remainingQuantity = BigDecimal.ZERO)
            )
        )
        val gateway = FakeDriverDeliveryGateway().apply {
            listItems = listOf(activeDelivery(9).copy(outcomeLines = listOf(line)))
            detailBlock =
                {
                    DriverDeliveryLoadResult.DetailLoaded(
                        activeDelivery(9).copy(outcomeLines = listOf(line))
                    )
                }
            outcomeBlock = { _, _ ->
                listItems = listOf(delivered)
                detailBlock = { DriverDeliveryLoadResult.DetailLoaded(delivered) }
                DriverOutcomeResult.Recorded(
                    DriverOutcomeSummary(
                        ATTEMPT_ID,
                        "DELIVERED",
                        ARRIVED_AT,
                        10,
                        false,
                        emptyList()
                    )
                )
            }
            proofCreateResults += DriverProofCreateResult.UnknownOutcome
            proofCreateResults += DriverProofCreateResult.Created(
                DriverProofSummary(
                    PROOF_ID, DELIVERY_ID, ATTEMPT_ID, AUTHORITY.membershipId,
                    "PENDING", "Receiver", ARRIVED_AT, null, null, 10
                )
            )
        }
        val proofMetadata = FakeDriverProofMetadataStore()
        gateway.beforeProofCreate = { command ->
            assertEquals(DriverProofIntentStage.CreatingProof, proofMetadata.stored?.stage)
            assertTrue(
                proofMetadata.stored?.status in setOf(
                    DriverProofIntentStatus.Pending,
                    DriverProofIntentStatus.UnknownOutcome
                )
            )
            assertEquals("proof-stable-key", command.idempotencyKey)
            assertEquals(10L, command.expectedVersion)
        }
        var keyIndex = 0
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore(),
            outcomeMetadataStore = FakeDriverOutcomeMetadataStore(),
            timeFactory = { ARRIVED_AT },
            keyFactory = { if (keyIndex++ == 0) "outcome-key" else "proof-stable-key" },
            proofMetadataStore = proofMetadata,
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        val proofAuthority = AUTHORITY.copy(
            permissions = setOf(
                "dispatch.read",
                "dispatch.start_route",
                "document.upload",
                "document.read"
            )
        )
        viewModel.activate(proofAuthority)
        advanceUntilIdle()
        viewModel.selectDelivery(DELIVERY_ID)
        advanceUntilIdle()

        viewModel.recordOutcome(DriverOutcomeKind.DELIVERED)
        advanceUntilIdle()
        assertEquals(
            DriverOutcomeCommandStatus.Recorded,
            viewModel.state.value.outcomeCommandStatus
        )

        viewModel.createProof("Receiver")
        advanceUntilIdle()
        assertEquals(
            DriverProofCommandStatus.UnknownOutcome,
            viewModel.state.value.proofCommandStatus
        )
        assertEquals(DriverProofIntentStatus.UnknownOutcome, proofMetadata.stored?.status)
        assertEquals(1, gateway.proofCreateCommands.size)

        viewModel.retryUnknownProof()
        advanceUntilIdle()

        assertEquals(2, gateway.proofCreateCommands.size)
        assertEquals(gateway.proofCreateCommands.first(), gateway.proofCreateCommands.last())
        assertEquals(
            DriverProofCommandStatus.AwaitingEvidenceSelection,
            viewModel.state.value.proofCommandStatus
        )
        assertEquals(PROOF_ID, viewModel.state.value.proofId)
        assertEquals(DriverProofIntentStage.ProofCreated, proofMetadata.stored?.stage)
    }

    @Test
    fun proofCreateStorageFailureBlocksPost() = runTest {
        val line = DriverDeliveryOutcomeLine(
            FULFILLMENT_LINE_ID, SKU_ID, "CAT-100", BigDecimal("2"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("2"), "UNIT"
        )
        val delivered = activeDelivery(10).copy(status = "DELIVERED", outcomeLines = listOf(line))
        val gateway = FakeDriverDeliveryGateway().apply {
            listItems = listOf(activeDelivery(9).copy(outcomeLines = listOf(line)))
            detailBlock =
                {
                    DriverDeliveryLoadResult.DetailLoaded(
                        activeDelivery(9).copy(outcomeLines = listOf(line))
                    )
                }
            outcomeBlock = { _, _ ->
                listItems = listOf(delivered)
                detailBlock = { DriverDeliveryLoadResult.DetailLoaded(delivered) }
                DriverOutcomeResult.Recorded(
                    DriverOutcomeSummary(
                        ATTEMPT_ID,
                        "DELIVERED",
                        ARRIVED_AT,
                        10,
                        false,
                        emptyList()
                    )
                )
            }
        }
        val metadata = FakeDriverProofMetadataStore().apply {
            writeResult = DriverProofMetadataWrite.Unavailable
        }
        val viewModel = DriverDeliveryViewModel(
            gateway,
            FakeDriverAttemptMetadataStore(),
            outcomeMetadataStore = FakeDriverOutcomeMetadataStore(),
            timeFactory = { ARRIVED_AT },
            keyFactory = { "same-test-key" },
            proofMetadataStore = metadata,
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        viewModel.activate(
            AUTHORITY.copy(
                permissions = setOf(
                    "dispatch.read",
                    "dispatch.start_route",
                    "document.upload"
                )
            )
        )
        advanceUntilIdle()
        viewModel.selectDelivery(DELIVERY_ID)
        advanceUntilIdle()
        viewModel.recordOutcome(DriverOutcomeKind.DELIVERED)
        advanceUntilIdle()

        viewModel.createProof("Receiver")
        advanceUntilIdle()

        assertTrue(gateway.proofCreateCommands.isEmpty())
        assertEquals(
            DriverProofCommandStatus.PersistenceUnavailable,
            viewModel.state.value.proofCommandStatus
        )
    }

    private suspend fun TestScope.activateAndSelect(viewModel: DriverDeliveryViewModel) {
        viewModel.activate(AUTHORITY)
        advanceUntilIdle()
        viewModel.selectDelivery(DELIVERY_ID)
        advanceUntilIdle()
    }

    private class FakeDriverDeliveryGateway : DriverDeliveryGateway {
        var detailCalls = 0
        var listItems = listOf(delivery())
        val commands = mutableListOf<DriverAttemptStartCommand>()
        val outcomeCommands = mutableListOf<DriverOutcomeCommand>()
        val arrivalCommands = mutableListOf<DriverArrivalCommand>()
        val startResults = ArrayDeque<DriverAttemptStartResult>()
        val outcomeResults = ArrayDeque<DriverOutcomeResult>()
        val arrivalResults = ArrayDeque<DriverArrivalResult>()
        val proofCreateCommands = mutableListOf<DriverProofCreateCommand>()
        val proofCreateResults = ArrayDeque<DriverProofCreateResult>()
        var beforeOutcome: () -> Unit = {}
        var beforeProofCreate: (DriverProofCreateCommand) -> Unit = {}
        var outcomeBlock: suspend (
            DriverOutcomeCommand,
            DriverDeliveryAuthority
        ) -> DriverOutcomeResult = { _, _ ->
            outcomeResults.removeFirstOrNull() ?: DriverOutcomeResult.UnknownOutcome
        }
        var arrivalBlock: suspend (
            DriverArrivalCommand,
            DriverDeliveryAuthority
        ) -> DriverArrivalResult =
            { command, _ ->
                arrivalCommands += command
                arrivalResults.removeFirstOrNull() ?: DriverArrivalResult.UnknownOutcome
            }
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
        ): DriverDeliveryLoadResult = DriverDeliveryLoadResult.ListLoaded(listItems)

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
            return outcomeBlock(command, authority)
        }

        override suspend fun signalArrival(
            command: DriverArrivalCommand,
            authority: DriverDeliveryAuthority
        ): DriverArrivalResult = arrivalBlock(command, authority).also {
            if (command !in arrivalCommands) arrivalCommands += command
        }

        override suspend fun createProof(
            command: DriverProofCreateCommand,
            authority: DriverDeliveryAuthority
        ): DriverProofCreateResult {
            beforeProofCreate(command)
            proofCreateCommands += command
            return proofCreateResults.removeFirstOrNull() ?: DriverProofCreateResult.UnknownOutcome
        }
    }

    private class FakeDriverOutcomeMetadataStore : DriverOutcomeMetadataStore {
        var stored: DriverOutcomeIntentMetadata? = null
        var writeResult: DriverOutcomeMetadataWrite = DriverOutcomeMetadataWrite.Saved

        override suspend fun loadIntent(
            scope: DriverAttemptScopeIdentity
        ): DriverOutcomeMetadataRead {
            val current = stored
            if (current?.scope != scope) return DriverOutcomeMetadataRead.Available(null)
            if (current.status == DriverOutcomeIntentStatus.Pending) {
                stored = current.copy(status = DriverOutcomeIntentStatus.UnknownOutcome)
            }
            return DriverOutcomeMetadataRead.Available(stored)
        }

        override suspend fun saveIntent(
            intent: DriverOutcomeIntentMetadata
        ): DriverOutcomeMetadataWrite {
            if (writeResult != DriverOutcomeMetadataWrite.Saved) return writeResult
            val current = stored
            if (current != null &&
                current.command != intent.command
            ) {
                return DriverOutcomeMetadataWrite.Conflict
            }
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
                (
                    current.idempotencyKey != intent.idempotencyKey ||
                        current.deliveryId != intent.deliveryId ||
                        current.expectedVersion != intent.expectedVersion ||
                        (
                            current.status == DriverAttemptMetadataStatus.UnknownOutcome &&
                                intent.status == DriverAttemptMetadataStatus.Pending
                            )
                    )
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

    private class FakeDriverArrivalMetadataStore : DriverArrivalMetadataStore {
        var stored: DriverArrivalIntentMetadata? = null
        var writeResult: DriverArrivalMetadataWrite = DriverArrivalMetadataWrite.Saved

        override suspend fun loadIntent(
            scope: DriverAttemptScopeIdentity
        ): DriverArrivalMetadataRead {
            val current = stored
            if (current?.scope != scope) return DriverArrivalMetadataRead.Available(null)
            if (current.status == DriverArrivalIntentStatus.Pending) {
                stored = current.copy(status = DriverArrivalIntentStatus.UnknownOutcome)
            }
            return DriverArrivalMetadataRead.Available(stored)
        }

        override suspend fun saveIntent(
            intent: DriverArrivalIntentMetadata
        ): DriverArrivalMetadataWrite {
            if (writeResult != DriverArrivalMetadataWrite.Saved) return writeResult
            val current = stored
            if (current != null &&
                current.command != intent.command
            ) {
                return DriverArrivalMetadataWrite.Conflict
            }
            if (current?.status == DriverArrivalIntentStatus.UnknownOutcome &&
                intent.status == DriverArrivalIntentStatus.Pending
            ) {
                return DriverArrivalMetadataWrite.Conflict
            }
            stored = intent
            return DriverArrivalMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            idempotencyKey: String
        ): DriverArrivalMetadataWrite {
            if (writeResult != DriverArrivalMetadataWrite.Saved) return writeResult
            val current = stored ?: return DriverArrivalMetadataWrite.Saved
            if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                return DriverArrivalMetadataWrite.Stale
            }
            stored = null
            return DriverArrivalMetadataWrite.Saved
        }
    }

    private class FakeDriverProofMetadataStore : DriverProofMetadataStore {
        var stored: DriverProofIntentMetadata? = null
        var writeResult: DriverProofMetadataWrite = DriverProofMetadataWrite.Saved

        override suspend fun loadIntent(
            scope: DriverAttemptScopeIdentity
        ): DriverProofMetadataRead {
            val current = stored ?: return DriverProofMetadataRead.Available(null)
            if (current.scope != scope) return DriverProofMetadataRead.Available(null)
            if (current.status == DriverProofIntentStatus.Pending && current.stage in setOf(
                    DriverProofIntentStage.CreatingProof,
                    DriverProofIntentStage.UploadingEvidence,
                    DriverProofIntentStage.AttachingEvidence
                )
            ) {
                stored = current.copy(status = DriverProofIntentStatus.UnknownOutcome)
            }
            return DriverProofMetadataRead.Available(stored)
        }

        override suspend fun saveIntent(
            intent: DriverProofIntentMetadata
        ): DriverProofMetadataWrite {
            if (writeResult != DriverProofMetadataWrite.Saved) return writeResult
            val current = stored
            if (current != null &&
                (
                    current.scope != intent.scope || current.deliveryId != intent.deliveryId ||
                        current.attemptId != intent.attemptId ||
                        current.createIdempotencyKey != intent.createIdempotencyKey ||
                        (
                            current.status == DriverProofIntentStatus.UnknownOutcome &&
                                intent.status == DriverProofIntentStatus.Pending &&
                                current.stage == intent.stage
                            )
                    )
            ) {
                return DriverProofMetadataWrite.Conflict
            }
            stored = intent
            return DriverProofMetadataWrite.Saved
        }

        override suspend fun stageCandidate(
            intent: DriverProofIntentMetadata,
            candidate: DriverProofFileCandidate
        ): DriverProofMetadataWrite = DriverProofMetadataWrite.Unavailable

        override suspend fun loadCandidate(
            intent: DriverProofIntentMetadata
        ): DriverProofFileCandidate? = null

        override suspend fun clearCandidate(intent: DriverProofIntentMetadata): Boolean = true

        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            createIdempotencyKey: String
        ): DriverProofMetadataWrite {
            if (writeResult != DriverProofMetadataWrite.Saved) return writeResult
            val current = stored ?: return DriverProofMetadataWrite.Saved
            if (current.scope != scope || current.createIdempotencyKey != createIdempotencyKey) {
                return DriverProofMetadataWrite.Stale
            }
            stored = null
            return DriverProofMetadataWrite.Saved
        }
    }

    private companion object {
        const val DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val ATTEMPT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413202"
        const val ARRIVAL_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413209"
        const val DELIVERED_DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413211"
        const val CANCELLED_DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413212"
        const val PROOF_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413210"
        const val ARRIVED_AT = "2026-09-30T20:00:00Z"
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
