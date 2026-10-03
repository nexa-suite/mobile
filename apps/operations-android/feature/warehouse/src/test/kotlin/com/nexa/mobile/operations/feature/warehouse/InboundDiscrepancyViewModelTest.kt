@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexa.mobile.operations.feature.warehouse

import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyMutationResult as DiscrepancyMutationResult
import java.time.Instant
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InboundDiscrepancyViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun unknownCreateRestoresFrozenIntentWithoutAutomaticMutationAndExplicitRetryReusesIt() =
        runTest {
            val events = mutableListOf<String>()
            val drafts = MemoryDraftStore(events)
            val gateway = MemoryGateway(events)
            val viewModel = viewModel(gateway, drafts)
            viewModel.activate(authority())
            advanceUntilIdle()
            fillKnownFacts(viewModel)

            viewModel.createCase()
            advanceUntilIdle()
            assertEquals(listOf("save", "create"), events.take(2))
            assertEquals(InboundDiscrepancyFlowStatus.UnknownOutcome, viewModel.state.value.flow)
            assertEquals(
                InboundDiscrepancyPendingAction.CreateCase,
                drafts.records[authority().scope]?.pendingAction
            )
            val original = requireNotNull(gateway.createCommands.singleOrNull())
            assertEquals(original.idempotencyKey, viewModel.state.value.createIdempotencyKey)
            assertEquals(original.frozenBody, viewModel.state.value.createBody)
            assertEquals(
                original.idempotencyKey,
                drafts.records[authority().scope]?.createIdempotencyKey
            )
            assertEquals(
                original.frozenBody,
                drafts.records[authority().scope]?.createBody
            )

            viewModel.deactivate()
            gateway.createResult = DiscrepancyMutationResult.CaseConfirmed(caseFact())
            viewModel.activate(authority(epoch = 4))
            advanceUntilIdle()
            assertEquals(1, gateway.createCommands.size)
            assertEquals(InboundDiscrepancyFlowStatus.UnknownOutcome, viewModel.state.value.flow)
            assertEquals(original.idempotencyKey, viewModel.state.value.createIdempotencyKey)
            assertEquals(original.frozenBody, viewModel.state.value.createBody)
            assertFalse(
                viewModel.state.value.expectedQuantityText ==
                    viewModel.state.value.observedQuantityText
            )

            viewModel.retryPendingAction()
            advanceUntilIdle()
            assertEquals(2, gateway.createCommands.size)
            assertEquals(original, gateway.createCommands.last())
            assertEquals(authority(epoch = 4), gateway.createAuthorities.last())
            assertEquals("case-1", viewModel.state.value.caseId)
            assertEquals("PENDING_EVIDENCE", viewModel.state.value.caseStatus)
            assertTrue(viewModel.state.value.canSelectEvidence)
        }

    private fun fillKnownFacts(viewModel: InboundDiscrepancyViewModel) {
        viewModel.warehouseChanged(WAREHOUSE_ID)
        viewModel.observedSkuChanged(SKU_ID)
        viewModel.expectedSkuChanged(SKU_ID)
        viewModel.kindChanged(InboundDiscrepancyKind.QuantityDifference)
        viewModel.expectedQuantityChanged("5.000")
        viewModel.observedQuantityChanged("4.500")
        viewModel.unitChanged("UNIT")
    }

    private fun viewModel(gateway: MemoryGateway, drafts: MemoryDraftStore) =
        InboundDiscrepancyViewModel(
            gateway = gateway,
            drafts = drafts,
            artifacts = EmptyArtifactStore,
            newDraftId = { "draft-1" },
            newCommandKey = { "create-key-1" },
            deviceClockMillis = { 1_727_700_000_000 }
        )

    private fun authority(epoch: Long = 3) = InboundDiscrepancyAuthority(
        InboundDiscrepancyScope("user-a", "tenant-a", "workspace-a", "membership-a"),
        epoch
    )

    private fun caseFact() = InboundDiscrepancyCase(
        id = "case-1",
        warehouseId = WAREHOUSE_ID,
        expectedSkuId = SKU_ID,
        observedSkuId = SKU_ID,
        expectedBatchReference = null,
        observedBatchReference = null,
        expectedQuantity = "5.000",
        observedQuantity = "4.500",
        unit = "UNIT",
        reason = "QUANTITY_DIFFERENCE",
        observationNotes = null,
        status = "PENDING_EVIDENCE",
        evidenceObjectId = null,
        version = 0,
        recordedByMembershipId = "membership-a",
        recordedAt = Instant.parse("2026-10-01T10:00:00Z"),
        submittedByMembershipId = null,
        submittedAt = null
    )

    private class MemoryDraftStore(private val events: MutableList<String>) :
        InboundDiscrepancyDraftStore {
        val records = mutableMapOf<InboundDiscrepancyScope, InboundDiscrepancyDraft>()
        override suspend fun load(scope: InboundDiscrepancyScope): InboundDiscrepancyDraftRead =
            InboundDiscrepancyDraftRead.Available(records[scope])

        override suspend fun save(
            scope: InboundDiscrepancyScope,
            draft: InboundDiscrepancyDraft
        ): InboundDiscrepancyDraftWrite {
            val current = records[scope]
            if (current != null &&
                current.id != draft.id
            ) {
                return InboundDiscrepancyDraftWrite.Conflict
            }
            events += "save"
            records[scope] = draft
            return InboundDiscrepancyDraftWrite.Saved
        }

        override suspend fun discard(
            scope: InboundDiscrepancyScope,
            expectedDraftId: String
        ): InboundDiscrepancyDraftWrite = InboundDiscrepancyDraftWrite.Conflict
    }

    private class MemoryGateway(private val events: MutableList<String>) :
        InboundDiscrepancyGateway {
        val createCommands = mutableListOf<InboundDiscrepancyCreateCommand>()
        val createAuthorities = mutableListOf<InboundDiscrepancyAuthority>()
        var createResult: DiscrepancyMutationResult = DiscrepancyMutationResult.UnknownOutcome

        override suspend fun createCase(
            command: InboundDiscrepancyCreateCommand,
            authority: InboundDiscrepancyAuthority
        ): DiscrepancyMutationResult {
            events += "create"
            createCommands += command
            createAuthorities += authority
            return createResult
        }

        override suspend fun uploadEvidence(
            command: InboundDiscrepancyUploadCommand,
            authority: InboundDiscrepancyAuthority
        ): DiscrepancyMutationResult = DiscrepancyMutationResult.UnknownOutcome

        override suspend fun evidenceStatus(
            evidenceId: String,
            caseId: String,
            authority: InboundDiscrepancyAuthority
        ): InboundDiscrepancyEvidenceStatusResult =
            InboundDiscrepancyEvidenceStatusResult.ServiceUnavailable

        override suspend fun submitForReview(
            command: InboundDiscrepancySubmitCommand,
            authority: InboundDiscrepancyAuthority
        ): DiscrepancyMutationResult = DiscrepancyMutationResult.UnknownOutcome
    }

    private companion object {
        const val WAREHOUSE_ID = "00000000-0000-0000-0000-000000000050"
        const val SKU_ID = "00000000-0000-0000-0000-000000000051"
    }
}

private object EmptyArtifactStore : InboundDiscrepancyEvidenceArtifactStore {
    override suspend fun stageReturnedSelection(
        selection: InboundDiscrepancySelectionContext,
        candidate: InboundDiscrepancyEvidenceCandidate
    ): InboundDiscrepancyArtifactWrite = InboundDiscrepancyArtifactWrite.Unavailable

    override suspend fun load(
        identity: InboundDiscrepancyArtifactIdentity
    ): InboundDiscrepancyArtifactRead = InboundDiscrepancyArtifactRead.Available(null)

    override suspend fun openForUpload(
        identity: InboundDiscrepancyArtifactIdentity
    ): InboundDiscrepancyEvidenceCandidate? = null
    override fun releaseUploadCandidate(candidate: InboundDiscrepancyEvidenceCandidate) = Unit
    override suspend fun clear(identity: InboundDiscrepancyArtifactIdentity): Boolean = true
}
