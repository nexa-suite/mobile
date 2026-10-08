@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionCurrentResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.LotSubstitutionGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.LotSubstitutionMetadataStore
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionAlternative
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionCurrentFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionRequest
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LotSubstitutionViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun freezesBeforePostAndManualRetryReusesOriginalCommand() = runTest {
        val events = mutableListOf<String>()
        val metadata = MemoryMetadataStore(events)
        val gateway = FakeGateway(events).apply {
            outcomes += LotSubstitutionResult.UnknownOutcome
            outcomes += LotSubstitutionResult.Requested(request())
        }
        val viewModel =
            LotSubstitutionViewModel(gateway, metadata, TestWarehouseFrozenPayloadCodec) { KEY }
        val work = work()
        viewModel.activate(authority(), work)
        advanceUntilIdle()
        viewModel.selectAlternative(ALTERNATIVE_LOT_ID)
        viewModel.updateReason("Expected lot could not supply prepared work")

        viewModel.requestSubstitution()
        advanceUntilIdle()

        assertEquals(listOf("freeze", "post", "mark-unknown"), events)
        assertEquals(LotSubstitutionCommandStatus.UnknownOutcome, viewModel.state.value.status)
        val frozen = requireNotNull(metadata.intent)
        assertTrue(frozen.frozenBody.contains("\"allocationVersion\"").not())
        assertTrue(frozen.frozenBody.contains("\"physicalAllocationLineId\":\"$LINE_ID\""))

        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(listOf("freeze", "post", "mark-unknown", "post", "clear"), events)
        assertEquals(2, gateway.submitted.size)
        assertEquals(frozen.idempotencyKey, gateway.submitted[1].idempotencyKey)
        assertEquals(frozen.frozenBody, gateway.submitted[1].frozenBody)
        assertEquals(frozen.work.allocationVersion, gateway.submitted[1].work.allocationVersion)
        assertEquals(LotSubstitutionCommandStatus.Requested, viewModel.state.value.status)
        assertEquals("REQUESTED", viewModel.state.value.request?.status)
        assertEquals(work, viewModel.state.value.work)
    }

    @Test
    fun restoredPendingRequestIsNotAutomaticallyReplayed() = runTest {
        val events = mutableListOf<String>()
        val scope = authority().scope
        val pending = intent(scope)
        val metadata = MemoryMetadataStore(events).apply { intent = pending }
        val gateway = FakeGateway(events).apply {
            outcomes += LotSubstitutionResult.Requested(request())
        }
        val viewModel = LotSubstitutionViewModel(gateway, metadata, TestWarehouseFrozenPayloadCodec)

        viewModel.activate(authority(), work())
        advanceUntilIdle()

        assertTrue(gateway.submitted.isEmpty())
        assertEquals(LotSubstitutionCommandStatus.UnknownOutcome, viewModel.state.value.status)
        assertEquals(LotSubstitutionIntentStatus.UnknownOutcome, metadata.intent?.status)
        assertTrue(events.contains("mark-unknown"))

        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(1, gateway.submitted.size)
        assertEquals(pending.idempotencyKey, gateway.submitted.single().idempotencyKey)
        assertEquals(pending.frozenBody, gateway.submitted.single().frozenBody)
    }

    @Test
    fun staleResponseRetainsOriginalWorkAndDoesNotApplyAlternative() = runTest {
        val events = mutableListOf<String>()
        val metadata = MemoryMetadataStore(events)
        val gateway = FakeGateway(events).apply {
            outcomes += LotSubstitutionResult.Stale(currentAllocationVersion = 9)
        }
        val viewModel =
            LotSubstitutionViewModel(gateway, metadata, TestWarehouseFrozenPayloadCodec) { KEY }
        val original = work()
        viewModel.activate(authority(), original)
        advanceUntilIdle()
        viewModel.selectAlternative(ALTERNATIVE_LOT_ID)
        viewModel.updateReason("Expected lot could not supply prepared work")
        viewModel.requestSubstitution()
        advanceUntilIdle()

        assertEquals(LotSubstitutionCommandStatus.Stale, viewModel.state.value.status)
        assertEquals(original, viewModel.state.value.work)
        assertFalse(viewModel.state.value.canRequest)
        assertTrue(viewModel.state.value.notice?.contains("versión 9") == true)
        assertEquals(1, gateway.submitted.size)
    }

    @Test
    fun requestRequiresAuthorizedPermissionAndEligibleSameSkuWarehouseLot() = runTest {
        val events = mutableListOf<String>()
        val metadata = MemoryMetadataStore(events)
        val gateway = FakeGateway(events)
        val viewModel =
            LotSubstitutionViewModel(gateway, metadata, TestWarehouseFrozenPayloadCodec) { KEY }
        viewModel.activate(authority(permissions = setOf("warehouse:read")), work())
        advanceUntilIdle()
        viewModel.selectAlternative(ALTERNATIVE_LOT_ID)
        viewModel.updateReason("Reason")
        assertFalse(viewModel.state.value.canRequest)
        viewModel.requestSubstitution()
        advanceUntilIdle()
        assertTrue(gateway.submitted.isEmpty())

        viewModel.deactivate()
        viewModel.activate(authority(), work())
        advanceUntilIdle()
        viewModel.selectAlternative(WRONG_SKU_LOT_ID)
        viewModel.updateReason("Reason")
        assertFalse(viewModel.state.value.canRequest)
        viewModel.requestSubstitution()
        advanceUntilIdle()
        assertTrue(gateway.submitted.isEmpty())
    }

    private class MemoryMetadataStore(private val events: MutableList<String>) :
        LotSubstitutionMetadataStore {
        var intent: LotSubstitutionIntent? = null

        override suspend fun load(
            scope: LotSubstitutionScopeIdentity
        ): LotSubstitutionMetadataRead = if (intent != null && intent?.scope != scope) {
            LotSubstitutionMetadataRead.Unavailable
        } else {
            LotSubstitutionMetadataRead.Available(intent)
        }

        override suspend fun freeze(intent: LotSubstitutionIntent): LotSubstitutionMetadataWrite {
            events += "freeze"
            if (this.intent != null && this.intent?.sameFrozenCommand(intent) != true) {
                return LotSubstitutionMetadataWrite.Unavailable
            }
            this.intent = intent
            return LotSubstitutionMetadataWrite.Saved
        }

        override suspend fun markUnknown(
            scope: LotSubstitutionScopeIdentity,
            idempotencyKey: String
        ): LotSubstitutionMetadataWrite {
            events += "mark-unknown"
            val current = intent ?: return LotSubstitutionMetadataWrite.Unavailable
            if (current.scope != scope ||
                current.idempotencyKey != idempotencyKey
            ) {
                return LotSubstitutionMetadataWrite.Unavailable
            }
            intent = current.copy(status = LotSubstitutionIntentStatus.UnknownOutcome)
            return LotSubstitutionMetadataWrite.Saved
        }

        override suspend fun clear(
            scope: LotSubstitutionScopeIdentity,
            idempotencyKey: String
        ): LotSubstitutionMetadataWrite {
            events += "clear"
            if (intent?.scope != scope ||
                intent?.idempotencyKey != idempotencyKey
            ) {
                return LotSubstitutionMetadataWrite.Unavailable
            }
            intent = null
            return LotSubstitutionMetadataWrite.Saved
        }
    }

    private inner class FakeGateway(private val events: MutableList<String>) :
        LotSubstitutionGateway {
        val submitted = mutableListOf<LotSubstitutionIntent>()
        val outcomes = ArrayDeque<LotSubstitutionResult>()

        override suspend fun alternatives(
            work: LotSubstitutionWork,
            authority: LotSubstitutionAuthority
        ): LotSubstitutionLookupResult = LotSubstitutionLookupResult.Alternatives(
            listOf(
                alternative(ALTERNATIVE_LOT_ID),
                alternative(WRONG_SKU_LOT_ID, sku = OTHER_SKU_ID)
            )
        )

        override suspend fun request(
            intent: LotSubstitutionIntent,
            authority: LotSubstitutionAuthority
        ): LotSubstitutionResult {
            events += "post"
            submitted += intent
            return outcomes.removeFirstOrNull() ?: LotSubstitutionResult.UnknownOutcome
        }

        override suspend fun currentAllocation(
            work: LotSubstitutionWork,
            authority: LotSubstitutionAuthority
        ): LotSubstitutionCurrentResult = LotSubstitutionCurrentResult.Current(
            LotSubstitutionCurrentFacts(ALLOCATION_ID, 8, EXPECTED_LOT_ID, "4", "EA")
        )
    }

    private fun authority(permissions: Set<String> = setOf("warehouse:read", "inventory.adjust")) =
        LotSubstitutionAuthority(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBERSHIP_ID,
            permissions,
            authorityEpoch = 7
        )

    private fun work() = LotSubstitutionWork(
        FULFILLMENT_ID, ALLOCATION_ID, LINE_ID, SKU_ID, "CAT-0042", EXPECTED_LOT_ID,
        WAREHOUSE_ID, ZONE_ID, "4.000", "EA", allocationVersion = 8
    )

    private fun alternative(id: String, sku: String = SKU_ID) = LotSubstitutionAlternative(
        id, WAREHOUSE_ID, ZONE_ID, sku,
        "CAT-0042", "BATCH-2", "2027-03-31", "8.000", "EA", "AVAILABLE", 11
    )

    private fun intent(scope: LotSubstitutionScopeIdentity) = LotSubstitutionIntent(
        scope,
        KEY,
        work(),
        ALTERNATIVE_LOT_ID,
        "Expected lot could not supply prepared work",
        "{\"fulfillmentId\":\"$FULFILLMENT_ID\",\"allocationId\":\"$ALLOCATION_ID\",\"physicalAllocationLineId\":\"$LINE_ID\",\"expectedLotId\":\"$EXPECTED_LOT_ID\",\"alternativeLotId\":\"$ALTERNATIVE_LOT_ID\",\"quantity\":4.000,\"unit\":\"EA\",\"reason\":\"Expected lot could not supply prepared work\"}",
        LotSubstitutionIntentStatus.Pending
    )

    private fun request() = LotSubstitutionRequest(
        REQUEST_ID,
        EXPECTED_LOT_ID,
        ALTERNATIVE_LOT_ID,
        "4.000",
        "Expected lot could not supply prepared work",
        "REQUESTED",
        8
    )

    private companion object {
        const val USER_ID = "00000000-0000-4000-8000-000000000001"
        const val TENANT_ID = "00000000-0000-4000-8000-000000000002"
        const val WORKSPACE_ID = "00000000-0000-4000-8000-000000000003"
        const val MEMBERSHIP_ID = "00000000-0000-4000-8000-000000000004"
        const val WAREHOUSE_ID = "00000000-0000-4000-8000-000000000005"
        const val ZONE_ID = "00000000-0000-4000-8000-000000000006"
        const val SKU_ID = "00000000-0000-4000-8000-000000000007"
        const val OTHER_SKU_ID = "00000000-0000-4000-8000-000000000008"
        const val EXPECTED_LOT_ID = "00000000-0000-4000-8000-000000000009"
        const val ALTERNATIVE_LOT_ID = "00000000-0000-4000-8000-000000000010"
        const val WRONG_SKU_LOT_ID = "00000000-0000-4000-8000-000000000011"
        const val ALLOCATION_ID = "00000000-0000-4000-8000-000000000012"
        const val LINE_ID = "00000000-0000-4000-8000-000000000013"
        const val FULFILLMENT_ID = "00000000-0000-4000-8000-000000000014"
        const val REQUEST_ID = "00000000-0000-4000-8000-000000000015"
        const val KEY = "00000000-0000-4000-8000-000000000016"
    }
}
