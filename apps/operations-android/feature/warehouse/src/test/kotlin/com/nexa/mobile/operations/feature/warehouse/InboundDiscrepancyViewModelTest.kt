@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexa.mobile.operations.feature.warehouse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InboundDiscrepancyViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun capturesExactLocalFieldsAndRestoresOnlyWithinFullScope() = runTest {
        val store = MemoryDraftStore()
        val viewModel = viewModel(store)
        viewModel.activate(authority())
        advanceUntilIdle()

        viewModel.productReferenceChanged("supplier label SKU 0042")
        viewModel.lotOrBatchReferenceChanged("batch-2026-09")
        viewModel.kindChanged(InboundDiscrepancyKind.QuantityDifference)
        viewModel.expectedQuantityChanged("12.500")
        viewModel.observedQuantityChanged("11.250")
        viewModel.evidencePlanChanged("Photograph the leaking outer carton when attachment is supported")
        viewModel.observationNotesChanged("Outer carton is wet; no file attached")
        viewModel.saveDraft()
        advanceUntilIdle()

        val saved = requireNotNull(store.records[authority().scope])
        assertEquals("supplier label SKU 0042", saved.productReference)
        assertEquals("batch-2026-09", saved.lotOrBatchReference)
        assertEquals("12.500", saved.expectedQuantityText)
        assertEquals("11.250", saved.observedQuantityText)
        assertEquals("Photograph the leaking outer carton when attachment is supported", saved.evidencePlan)
        assertEquals(1_727_700_000_000, saved.capturedAtDeviceMillis)
        assertEquals(InboundDiscrepancySaveNotice.SavedLocally, viewModel.state.value.notice)
        assertFalse(store.networkCalled)

        val restored = viewModel(store)
        restored.activate(authority())
        advanceUntilIdle()
        assertTrue(restored.state.value.hasSavedDraft)
        assertEquals("12.500", restored.state.value.expectedQuantityText)
        assertEquals("11.250", restored.state.value.observedQuantityText)

        restored.deactivate()
        restored.activate(authority(tenantId = "tenant-b"))
        advanceUntilIdle()
        assertFalse(restored.state.value.hasSavedDraft)
        assertEquals("", restored.state.value.productReference)
    }

    @Test
    fun quantityDifferenceCannotBeSavedWhenQuantitiesAreInvalidOrEqual() = runTest {
        val store = MemoryDraftStore()
        val viewModel = viewModel(store)
        viewModel.activate(authority())
        advanceUntilIdle()
        viewModel.productReferenceChanged("product ref")
        viewModel.lotOrBatchReferenceChanged("lot ref")
        viewModel.kindChanged(InboundDiscrepancyKind.QuantityDifference)
        viewModel.expectedQuantityChanged("4.00")
        viewModel.observedQuantityChanged("4")
        viewModel.saveDraft()
        advanceUntilIdle()
        assertEquals(InboundDiscrepancyValidationError.QuantityDifferenceRequired, viewModel.state.value.validationError)
        assertTrue(store.records.isEmpty())

        viewModel.observedQuantityChanged("-1")
        viewModel.saveDraft()
        advanceUntilIdle()
        assertEquals(InboundDiscrepancyValidationError.QuantityInvalid, viewModel.state.value.validationError)
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun lateLocalWriteDoesNotRestoreStateAfterScopeSwitchAndDiscardUsesExpectedId() = runTest {
        val store = MemoryDraftStore()
        val pending = CompletableDeferred<InboundDiscrepancyDraftWrite>()
        store.nextSave = pending
        val viewModel = viewModel(store)
        viewModel.activate(authority())
        advanceUntilIdle()
        fill(viewModel)
        viewModel.saveDraft()
        advanceUntilIdle()
        viewModel.activate(authority(tenantId = "tenant-b"))
        advanceUntilIdle()
        pending.complete(InboundDiscrepancyDraftWrite.Saved)
        advanceUntilIdle()
        assertEquals(4L, viewModel.state.value.authorityEpoch)
        assertFalse(viewModel.state.value.hasSavedDraft)

        val other = savedDraft("other-draft")
        store.records[authority().scope] = other
        assertEquals(
            InboundDiscrepancyDraftWrite.Conflict,
            store.discard(authority().scope, "stale-id")
        )
        assertEquals(other, store.records[authority().scope])
    }

    private fun fill(viewModel: InboundDiscrepancyViewModel) {
        viewModel.productReferenceChanged("product")
        viewModel.lotOrBatchReferenceChanged("batch")
        viewModel.kindChanged(InboundDiscrepancyKind.Damage)
        viewModel.evidencePlanChanged("Take photo later")
    }

    private fun viewModel(store: MemoryDraftStore) = InboundDiscrepancyViewModel(
        drafts = store,
        newDraftId = { "draft-1" },
        deviceClockMillis = { 1_727_700_000_000 }
    )

    private fun authority(tenantId: String = "tenant-a") = InboundDiscrepancyAuthority(
        InboundDiscrepancyScope("user-a", tenantId, "workspace-a", "membership-a"),
        authorityEpoch = if (tenantId == "tenant-a") 3 else 4
    )

    private fun savedDraft(id: String) = InboundDiscrepancyDraft(
        id = id,
        productReference = "product",
        lotOrBatchReference = "batch",
        kind = InboundDiscrepancyKind.Damage,
        reasonDetails = "",
        expectedQuantityText = "",
        observedQuantityText = "",
        evidencePlan = "Take photo later",
        observationNotes = "",
        capturedAtDeviceMillis = 1_727_700_000_000
    )

    private class MemoryDraftStore : InboundDiscrepancyDraftStore {
        val records = mutableMapOf<InboundDiscrepancyScope, InboundDiscrepancyDraft>()
        var nextSave: CompletableDeferred<InboundDiscrepancyDraftWrite>? = null
        var networkCalled = false

        override suspend fun load(scope: InboundDiscrepancyScope): InboundDiscrepancyDraftRead =
            InboundDiscrepancyDraftRead.Available(records[scope])

        override suspend fun save(
            scope: InboundDiscrepancyScope,
            draft: InboundDiscrepancyDraft
        ): InboundDiscrepancyDraftWrite {
            val pending = nextSave
            if (pending != null) {
                nextSave = null
                val result = pending.await()
                if (result == InboundDiscrepancyDraftWrite.Saved) records[scope] = draft
                return result
            }
            val current = records[scope]
            if (current != null && current.id != draft.id) return InboundDiscrepancyDraftWrite.Conflict
            records[scope] = draft
            return InboundDiscrepancyDraftWrite.Saved
        }

        override suspend fun discard(
            scope: InboundDiscrepancyScope,
            expectedDraftId: String
        ): InboundDiscrepancyDraftWrite {
            if (records[scope]?.id != expectedDraftId) return InboundDiscrepancyDraftWrite.Conflict
            records.remove(scope)
            return InboundDiscrepancyDraftWrite.Discarded
        }
    }
}
