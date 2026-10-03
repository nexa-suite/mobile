package com.nexa.mobile.operations.feature.warehouse

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispositionViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun submitFreezesTypedPayloadAndShowsOnlyConfirmedServerProjection() = runTest {
        val gateway = FakeDispositionGateway().apply {
            lotResults += DispositionGatewayResult.Lot(lot(version = 17))
            disposeResults += DispositionGatewayResult.Confirmed(
                lot(version = 18, onHand = "0.00", available = "0.00", status = "DEPLETED")
            )
        }
        val store = MemoryDispositionMetadataStore()
        val viewModel = DispositionViewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()
        viewModel.lotIdChanged(LOT_ID)
        viewModel.loadLot()
        advanceUntilIdle()
        viewModel.selectDisposition(LotDispositionAction.WASTE)
        viewModel.reasonChanged("  damaged seal  ")
        assertTrue(viewModel.state.value.canSubmit)

        viewModel.submit()
        advanceUntilIdle()

        val submitted = gateway.submissions.single()
        assertEquals(LOT_ID, submitted.command.lotId)
        assertEquals(LotDispositionAction.WASTE, submitted.command.disposition)
        assertEquals("damaged seal", submitted.command.reason)
        assertEquals(17L, submitted.command.expectedVersion)
        assertTrue(submitted.idempotencyKey.isNotBlank())
        assertEquals(DispositionCommandStatus.Confirmed, viewModel.state.value.commandStatus)
        assertEquals(BigDecimal("0.00"), viewModel.state.value.lotFacts?.available)
        assertEquals(BigDecimal("2.25"), viewModel.state.value.lotFacts?.reserved)
        assertNull(viewModel.state.value.intent)
    }

    @Test
    fun disconnectedReasonNoteIsExplicitlyUnconfirmedAndDoesNotCallMutation() = runTest {
        val gateway = FakeDispositionGateway()
        val store = MemoryDispositionMetadataStore()
        val viewModel = DispositionViewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()
        viewModel.lotIdChanged("lot id entered offline")
        viewModel.selectDisposition(LotDispositionAction.HOLD)
        viewModel.reasonChanged("temperature log needs review")

        viewModel.saveLocalNote()
        advanceUntilIdle()

        assertEquals(
            DispositionCommandStatus.NoteSavedUnconfirmed,
            viewModel.state.value.commandStatus
        )
        assertTrue(viewModel.state.value.noteSaved)
        assertTrue(viewModel.state.value.lotFacts == null)
        assertTrue(gateway.submissions.isEmpty())
        assertTrue(store.intents.isEmpty())
    }

    @Test
    fun pendingIntentReconstructsUnknownAndExplicitReplayKeepsSameKeyBodyAndVersion() = runTest {
        val store = MemoryDispositionMetadataStore()
        val firstGateway = FakeDispositionGateway().apply {
            lotResults += DispositionGatewayResult.Lot(lot(version = 7))
            disposeResults += DispositionGatewayResult.UnknownOutcome
        }
        val first = DispositionViewModel(firstGateway, store)
        first.activate(authority())
        advanceUntilIdle()
        first.seedPartialDisposition(LOT_ID, EVALUATION_ID, BigDecimal("2.5000"))
        advanceUntilIdle()
        first.selectDisposition(LotDispositionAction.HOLD)
        first.reasonChanged("review complete")
        first.submit()
        advanceUntilIdle()
        val initial = firstGateway.submissions.single()
        assertEquals(BigDecimal("2.5000"), initial.command.partialEvaluation?.affectedQuantity)
        assertEquals(DispositionCommandStatus.UnknownOutcome, first.state.value.commandStatus)

        val secondGateway = FakeDispositionGateway().apply {
            disposeResults += DispositionGatewayResult.Confirmed(lot(version = 8))
        }
        val restored = DispositionViewModel(secondGateway, store)
        restored.activate(authority(epoch = 2))
        advanceUntilIdle()
        assertEquals(DispositionCommandStatus.UnknownOutcome, restored.state.value.commandStatus)
        assertEquals(
            initial.command.partialEvaluation,
            restored.state.value.intent?.command?.partialEvaluation
        )
        assertFalse(secondGateway.submissions.isNotEmpty())
        assertTrue(restored.state.value.canReplay)

        restored.replayUnknownOutcome()
        advanceUntilIdle()

        val replay = secondGateway.submissions.single()
        assertEquals(initial.idempotencyKey, replay.idempotencyKey)
        assertEquals(initial.command, replay.command)
        assertEquals(DispositionCommandStatus.Confirmed, restored.state.value.commandStatus)
    }

    @Test
    fun partialHoldOnAvailableLotUsesFreshVersionAndVerifiedWastePermission() = runTest {
        val gateway = FakeDispositionGateway().apply {
            lotResults += DispositionGatewayResult.Lot(lot(version = 22, status = "AVAILABLE"))
            disposeResults += DispositionGatewayResult.Confirmed(
                lot(version = 23, status = "AVAILABLE", available = "10.25")
            )
        }
        val store = MemoryDispositionMetadataStore()
        val viewModel = DispositionViewModel(gateway, store)
        viewModel.activate(
            authority(permissions = setOf("inventory.read", "inventory.waste"))
        )
        advanceUntilIdle()

        viewModel.seedPartialDisposition(LOT_ID, EVALUATION_ID, BigDecimal("1.2500"))
        advanceUntilIdle()
        assertEquals(DispositionLotStatus.Current, viewModel.state.value.lotStatus)
        assertEquals("AVAILABLE", viewModel.state.value.lotFacts?.status)
        assertEquals(22L, viewModel.state.value.lotFacts?.version)
        viewModel.selectDisposition(LotDispositionAction.HOLD)
        viewModel.reasonChanged("temperature review")
        assertTrue(viewModel.state.value.canSubmit)

        viewModel.submit()
        advanceUntilIdle()

        val submitted = gateway.submissions.single().command
        assertEquals(LotDispositionAction.HOLD, submitted.disposition)
        assertEquals(22L, submitted.expectedVersion)
        assertEquals(BigDecimal("1.2500"), submitted.partialEvaluation?.affectedQuantity)
        assertEquals(EVALUATION_ID, submitted.partialEvaluation?.temperatureEvaluationId)
        assertEquals("AVAILABLE", viewModel.state.value.lotFacts?.status)
        assertEquals(BigDecimal("10.25"), viewModel.state.value.lotFacts?.available)

        val readOnly = DispositionViewModel(
            FakeDispositionGateway().apply {
                lotResults += DispositionGatewayResult.Lot(lot(version = 22, status = "AVAILABLE"))
            },
            MemoryDispositionMetadataStore()
        )
        readOnly.activate(authority(permissions = setOf("inventory.read")))
        advanceUntilIdle()
        readOnly.seedPartialDisposition(LOT_ID, EVALUATION_ID, BigDecimal("1.2500"))
        advanceUntilIdle()
        readOnly.selectDisposition(LotDispositionAction.HOLD)
        readOnly.reasonChanged("temperature review")
        assertFalse(readOnly.state.value.canSubmit)
    }

    @Test
    fun staleVersionRequiresFreshReadAndExplicitNewDecisionWithNewKey() = runTest {
        val gateway = FakeDispositionGateway().apply {
            lotResults += DispositionGatewayResult.Lot(lot(version = 3))
            disposeResults += DispositionGatewayResult.PreconditionFailed
            lotResults += DispositionGatewayResult.Lot(lot(version = 4, status = "HOLD"))
            disposeResults +=
                DispositionGatewayResult.Confirmed(lot(version = 5, status = "AVAILABLE"))
        }
        val viewModel = DispositionViewModel(gateway, MemoryDispositionMetadataStore())
        viewModel.activate(authority())
        advanceUntilIdle()
        viewModel.lotIdChanged(LOT_ID)
        viewModel.loadLot()
        advanceUntilIdle()
        viewModel.selectDisposition(LotDispositionAction.HOLD)
        viewModel.reasonChanged("inspect")
        viewModel.submit()
        advanceUntilIdle()
        val oldKey = gateway.submissions.single().idempotencyKey
        assertEquals(
            DispositionCommandStatus.PreconditionFailed,
            viewModel.state.value.commandStatus
        )
        assertFalse(viewModel.state.value.canReplay)

        viewModel.loadLot()
        advanceUntilIdle()
        assertEquals(4L, viewModel.state.value.lotFacts?.version)
        assertTrue(viewModel.state.value.canStartNewDecision)
        viewModel.startNewDecision()
        advanceUntilIdle()
        viewModel.submit()
        advanceUntilIdle()

        val newRequest = gateway.submissions.last()
        assertEquals(4L, newRequest.command.expectedVersion)
        assertFalse(oldKey == newRequest.idempotencyKey)
        assertEquals(DispositionCommandStatus.Confirmed, viewModel.state.value.commandStatus)
    }

    @Test
    fun permissionHintDoesNotEnableUnauthorizedDispositionAndServerStillOwnsOutcome() = runTest {
        val gateway = FakeDispositionGateway().apply {
            lotResults +=
                DispositionGatewayResult.Lot(lot())
        }
        val viewModel = DispositionViewModel(gateway, MemoryDispositionMetadataStore())
        viewModel.activate(authority(permissions = setOf("inventory.read", "inventory.release")))
        advanceUntilIdle()
        viewModel.lotIdChanged(LOT_ID)
        viewModel.loadLot()
        advanceUntilIdle()
        viewModel.selectDisposition(LotDispositionAction.WASTE)
        viewModel.reasonChanged("dispose")

        assertFalse(viewModel.state.value.canSubmit)
        viewModel.submit()
        advanceUntilIdle()
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun responseFromOldAuthorityEpochCannotRestoreOldLotFacts() = runTest {
        val delayed = CompletableDeferred<DispositionGatewayResult>()
        val gateway = FakeDispositionGateway().apply {
            lotHandlers += { delayed.await() }
            lotResults += DispositionGatewayResult.Lot(lot(id = OTHER_LOT_ID, version = 9))
        }
        val viewModel = DispositionViewModel(gateway, MemoryDispositionMetadataStore())
        viewModel.activate(authority(epoch = 1))
        advanceUntilIdle()
        viewModel.lotIdChanged(LOT_ID)
        viewModel.loadLot()
        runCurrent()

        viewModel.activate(authority(epoch = 2, workspaceId = "workspace-b"))
        advanceUntilIdle()
        delayed.complete(DispositionGatewayResult.Lot(lot()))
        advanceUntilIdle()

        assertEquals(2L, viewModel.state.value.authorityEpoch)
        assertNull(viewModel.state.value.lotFacts)
        assertEquals("", viewModel.state.value.lotIdText)
    }

    @Test
    fun conflictingIntentCannotBeReplacedAndNoAutomaticReplayOccursOnActivate() = runTest {
        val store = MemoryDispositionMetadataStore()
        val current = DispositionIntentMetadata(
            authority().scope,
            "existing-key",
            LotDispositionCommand(LOT_ID, LotDispositionAction.HOLD, "keep", 2),
            DispositionIntentMetadataStatus.Conflict
        )
        store.intents[current.scope] = current
        val gateway = FakeDispositionGateway()
        val viewModel = DispositionViewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()

        assertEquals(DispositionCommandStatus.Conflict, viewModel.state.value.commandStatus)
        viewModel.seedPartialDisposition(OTHER_LOT_ID, EVALUATION_ID, BigDecimal("1.0"))
        assertEquals(current, viewModel.state.value.intent)
        assertEquals(LOT_ID, viewModel.state.value.lotIdText)
        assertTrue(gateway.submissions.isEmpty())
        assertFalse(viewModel.state.value.canReplay)
    }

    private fun authority(
        epoch: Long = 1,
        workspaceId: String = "workspace-1",
        permissions: Set<String> = setOf("inventory.read", "inventory.release", "inventory.waste")
    ) = DispositionAuthority("user-1", "tenant-1", workspaceId, "membership-1", permissions, epoch)

    private fun lot(
        id: String = LOT_ID,
        version: Long = 2,
        onHand: String = "12.50",
        available: String = "10.25",
        status: String = "HOLD"
    ) = DispositionLotFacts(
        id = id,
        warehouseId = "warehouse-1",
        zoneId = "zone-1",
        catalogItemId = "CAT-42",
        skuId = "b8c24a46-57d9-4f64-8fa7-6a641b413301",
        batchNumber = "LOT-17",
        expirationDate = LocalDate.parse("2027-02-15"),
        receivedAt = Instant.parse("2026-09-29T18:00:00Z"),
        onHand = BigDecimal(onHand),
        reserved = BigDecimal("2.25"),
        available = BigDecimal(available),
        unit = "EA",
        status = status,
        version = version
    )

    private class FakeDispositionGateway : DispositionGateway {
        val lotResults = ArrayDeque<DispositionGatewayResult>()
        val lotHandlers = ArrayDeque<suspend () -> DispositionGatewayResult>()
        val disposeResults = ArrayDeque<DispositionGatewayResult>()
        val submissions = mutableListOf<Submission>()

        override suspend fun lot(
            lotId: String,
            authority: DispositionAuthority
        ): DispositionGatewayResult = if (lotHandlers.isNotEmpty()) {
            lotHandlers.removeFirst().invoke()
        } else {
            lotResults.removeFirst()
        }

        override suspend fun dispose(
            command: LotDispositionCommand,
            idempotencyKey: String,
            authority: DispositionAuthority
        ): DispositionGatewayResult {
            submissions += Submission(command, idempotencyKey)
            return disposeResults.removeFirst()
        }
    }

    private data class Submission(val command: LotDispositionCommand, val idempotencyKey: String)

    private class MemoryDispositionMetadataStore : DispositionMetadataStore {
        val drafts = mutableMapOf<DispositionScopeIdentity, DispositionDraftMetadata>()
        val intents = mutableMapOf<DispositionScopeIdentity, DispositionIntentMetadata>()

        override suspend fun loadDraft(scope: DispositionScopeIdentity) =
            DispositionMetadataRead.Available(drafts[scope])

        override suspend fun saveDraft(
            scope: DispositionScopeIdentity,
            draft: DispositionDraftMetadata
        ) = DispositionMetadataWrite.Saved.also { drafts[scope] = draft }

        override suspend fun loadIntent(scope: DispositionScopeIdentity) =
            DispositionMetadataRead.Available(intents[scope])

        override suspend fun saveIntent(
            intent: DispositionIntentMetadata
        ): DispositionMetadataWrite {
            val existing = intents[intent.scope]
            if (existing != null &&
                (
                    existing.idempotencyKey != intent.idempotencyKey ||
                        existing.command != intent.command
                    )
            ) {
                return DispositionMetadataWrite.Unavailable
            }
            intents[intent.scope] = intent
            return DispositionMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DispositionScopeIdentity,
            expectedIdempotencyKey: String
        ): DispositionMetadataWrite {
            val current = intents[scope] ?: return DispositionMetadataWrite.Saved
            if (current.idempotencyKey !=
                expectedIdempotencyKey
            ) {
                return DispositionMetadataWrite.Unavailable
            }
            intents.remove(scope)
            return DispositionMetadataWrite.Saved
        }
    }

    private companion object {
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val OTHER_LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413402"
        const val EVALUATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
    }
}
