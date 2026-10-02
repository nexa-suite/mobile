@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexa.mobile.operations.feature.warehouse

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CycleCountViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun freezesExactCountBodyBeforePostAndExplicitRetryReusesCommand() = runTest {
        val events = mutableListOf<String>()
        val metadata = MemoryMetadataStore(events)
        val gateway = FakeGateway().apply {
            recordResults += CycleCountResult.UnknownOutcome
            recordResults += CycleCountResult.Recorded(recordedCount)
        }
        val viewModel = CycleCountViewModel(gateway, metadata)
        viewModel.activate(authority())
        advanceUntilIdle()
        viewModel.selectLot(LOT_ID)
        advanceUntilIdle()
        viewModel.observeQuantityChanged("4.250")
        advanceUntilIdle()

        viewModel.recordCount()
        advanceUntilIdle()

        assertEquals(listOf("freeze-count", "mark-count-unknown"), events)
        assertEquals(1, gateway.countCommands.size)
        val frozen = requireNotNull(metadata.work?.countIntent)
        assertEquals("{\"observedQuantity\":4.250,\"unit\":\"EA\"}", frozen.frozenBody)
        assertEquals(CycleCountCommandStatus.UnknownOutcome, viewModel.state.value.countCommand)

        viewModel.retryCountUnknownOutcome()
        advanceUntilIdle()

        assertEquals(
            listOf("freeze-count", "mark-count-unknown", "complete-count"),
            events
        )
        assertEquals(2, gateway.countCommands.size)
        assertTrue(gateway.countCommands[0].sameFrozenCommand(gateway.countCommands[1]))
        assertEquals(frozen.frozenBody, gateway.countCommands[1].frozenBody)
        assertEquals(CycleCountCommandStatus.Recorded, viewModel.state.value.countCommand)
    }

    @Test
    fun restoredPendingCountBecomesUnknownWithoutAutomaticPostAndLoadsAllPagesOnRequest() =
        runTest {
            val scope = authority().scope
            val pending = CycleCountIntent(
                scope = scope,
                idempotencyKey = "restored-key",
                lot = lot(LOT_ID),
                observedQuantityText = "4",
                frozenBody = "{\"observedQuantity\":4,\"unit\":\"EA\"}",
                status = CycleCountIntentStatus.Pending
            )
            val events = mutableListOf<String>()
            val metadata = MemoryMetadataStore(events).apply {
                work = CycleCountStoredWork(scope, LOT_ID, "4", countIntent = pending)
            }
            val gateway = FakeGateway().apply {
                lotsPages[0] = CycleCountLookupResult.Lots(listOf(lot(LOT_ID)), 0, 2)
                lotsPages[1] = CycleCountLookupResult.Lots(listOf(lot(SECOND_LOT_ID)), 1, 2)
            }
            val viewModel = CycleCountViewModel(gateway, metadata)
            viewModel.activate(authority())
            advanceUntilIdle()

            assertTrue(gateway.countCommands.isEmpty())
            assertEquals(CycleCountCommandStatus.UnknownOutcome, viewModel.state.value.countCommand)
            assertEquals(CycleCountIntentStatus.UnknownOutcome, metadata.work?.countIntent?.status)
            assertEquals(0, viewModel.state.value.lotPage)
            assertEquals(2L, viewModel.state.value.lotTotal)
            assertTrue(viewModel.state.value.hasMoreLots)
            assertTrue(events.contains("mark-count-unknown"))

            viewModel.loadMoreLots()
            advanceUntilIdle()
            assertEquals(1, viewModel.state.value.lotPage)
            assertFalse(viewModel.state.value.hasMoreLots)
            assertEquals(
                listOf(LOT_ID, SECOND_LOT_ID),
                viewModel.state.value.lots.map(CycleCountLot::id)
            )
        }

    @Test
    fun countAndCorrectionCommandsRequireTheirExactVerifiedPermissionSet() = runTest {
        val gateway = FakeGateway()
        val viewModel = CycleCountViewModel(gateway, MemoryMetadataStore(mutableListOf()))
        viewModel.activate(authority(permissions = setOf("warehouse:write")))
        advanceUntilIdle()
        viewModel.selectLot(LOT_ID)
        viewModel.observeQuantityChanged("4")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.canRecord)
        assertFalse(viewModel.state.value.canApplyCorrection)
        viewModel.applyCorrection()
        advanceUntilIdle()
        assertTrue(gateway.correctionCommands.isEmpty())

        viewModel.deactivate()
        viewModel.activate(authority(permissions = setOf("warehouse:write", "inventory.adjust")))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.canWriteCounts)
        assertTrue(viewModel.state.value.canApplyInventoryCorrection)
    }

    private fun authority(
        permissions: Set<String> = setOf("warehouse:read", "warehouse:write", "inventory.adjust")
    ) = CycleCountAuthority(
        CycleCountScope(USER_ID, TENANT_ID, WORKSPACE_ID, MEMBERSHIP_ID),
        authorityEpoch = 9,
        permissions = permissions
    )

    private fun lot(id: String) = CycleCountLot(
        id, WAREHOUSE_ID, ZONE_ID, "CAT-0042", "BATCH-9", "2027-03-31",
        "5.000", "0", "5.000", "EA", "AVAILABLE", 7
    )

    private inner class FakeGateway : CycleCountGateway {
        val lotsPages = mutableMapOf<Int, CycleCountLookupResult>()
        val countCommands = mutableListOf<CycleCountIntent>()
        val correctionCommands = mutableListOf<CycleCountCorrectionIntent>()
        val recordResults = mutableListOf<CycleCountResult>()

        override suspend fun lots(
            authority: CycleCountAuthority,
            page: Int
        ): CycleCountLookupResult =
            lotsPages[page] ?: CycleCountLookupResult.Lots(listOf(lot(LOT_ID)), page, 1)

        override suspend fun record(
            intent: CycleCountIntent,
            authority: CycleCountAuthority
        ): CycleCountResult {
            countCommands += intent
            return recordResults.removeFirstOrNull() ?: CycleCountResult.UnknownOutcome
        }

        override suspend fun applyCorrection(
            intent: CycleCountCorrectionIntent,
            authority: CycleCountAuthority
        ): CycleCountResult {
            correctionCommands += intent
            return CycleCountResult.UnknownOutcome
        }
    }

    private class MemoryMetadataStore(private val events: MutableList<String>) :
        CycleCountMetadataStore {
        var work: CycleCountStoredWork? = null

        override suspend fun load(scope: CycleCountScope): CycleCountMetadataRead =
            CycleCountMetadataRead.Available(work?.takeIf { it.scope == scope })

        override suspend fun saveDraft(work: CycleCountStoredWork): CycleCountMetadataWrite {
            this.work = work.copy(
                countIntent = this.work?.countIntent,
                recordedCount = this.work?.recordedCount,
                correctionIntent = this.work?.correctionIntent,
                appliedCorrection = this.work?.appliedCorrection
            )
            return CycleCountMetadataWrite.Saved
        }

        override suspend fun freezeCount(intent: CycleCountIntent): CycleCountMetadataWrite {
            events += "freeze-count"
            work = (work ?: CycleCountStoredWork(intent.scope, null, "")).copy(
                selectedLotId = intent.lot.id,
                observedQuantityText = intent.observedQuantityText,
                countIntent = intent,
                recordedCount = null,
                correctionIntent = null,
                appliedCorrection = null
            )
            return CycleCountMetadataWrite.Saved
        }

        override suspend fun markCountUnknown(
            scope: CycleCountScope,
            idempotencyKey: String
        ): CycleCountMetadataWrite {
            events += "mark-count-unknown"
            val current = work?.countIntent ?: return CycleCountMetadataWrite.Unavailable
            if (current.idempotencyKey != idempotencyKey) return CycleCountMetadataWrite.Unavailable
            work =
                requireNotNull(
                    work
                ).copy(countIntent = current.copy(status = CycleCountIntentStatus.UnknownOutcome))
            return CycleCountMetadataWrite.Saved
        }

        override suspend fun completeCount(
            scope: CycleCountScope,
            idempotencyKey: String,
            count: CycleCountRecord
        ): CycleCountMetadataWrite {
            events += "complete-count"
            if (work?.countIntent?.idempotencyKey !=
                idempotencyKey
            ) {
                return CycleCountMetadataWrite.Unavailable
            }
            work = requireNotNull(work).copy(countIntent = null, recordedCount = count)
            return CycleCountMetadataWrite.Saved
        }

        override suspend fun clearCountIntent(scope: CycleCountScope, idempotencyKey: String) =
            CycleCountMetadataWrite.Unavailable

        override suspend fun freezeCorrection(intent: CycleCountCorrectionIntent) =
            CycleCountMetadataWrite.Saved
        override suspend fun markCorrectionUnknown(scope: CycleCountScope, idempotencyKey: String) =
            CycleCountMetadataWrite.Saved
        override suspend fun completeCorrection(
            scope: CycleCountScope,
            idempotencyKey: String,
            correction: CycleCountCorrection
        ) = CycleCountMetadataWrite.Saved
        override suspend fun clearCorrectionIntent(scope: CycleCountScope, idempotencyKey: String) =
            CycleCountMetadataWrite.Unavailable
        override suspend fun clearStaleCount(scope: CycleCountScope, countId: String) =
            CycleCountMetadataWrite.Unavailable
    }

    private companion object {
        const val USER_ID = "00000000-0000-4000-8000-000000000001"
        const val TENANT_ID = "00000000-0000-4000-8000-000000000002"
        const val WORKSPACE_ID = "00000000-0000-4000-8000-000000000003"
        const val MEMBERSHIP_ID = "00000000-0000-4000-8000-000000000004"
        const val WAREHOUSE_ID = "00000000-0000-4000-8000-000000000005"
        const val ZONE_ID = "00000000-0000-4000-8000-000000000006"
        const val LOT_ID = "00000000-0000-4000-8000-000000000007"
        const val SECOND_LOT_ID = "00000000-0000-4000-8000-000000000008"
        val recordedCount = CycleCountRecord(
            id = "00000000-0000-4000-8000-000000000009",
            lotId = LOT_ID, warehouseId = WAREHOUSE_ID, zoneId = ZONE_ID,
            lotVersion = 7, expectedQuantityText = "5", observedQuantityText = "4.25", unit = "EA",
            status = "REQUESTED",
            actorMembershipId =
            MEMBERSHIP_ID,
            recordedAt = "2026-09-30T10:00:00Z"
        )
    }
}
