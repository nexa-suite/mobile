package com.nexa.mobile.operations.feature.dispatch

import com.nexa.mobile.operations.feature.dispatch.application.DispatchHandoverGateway
import com.nexa.mobile.operations.feature.dispatch.application.DispatchHandoverMetadataStore
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverReceipt
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverSnapshot
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsAllocation
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsCheck
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsIntentStatus
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.model.PreparedFulfillmentDriverAssignment
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchHandoverViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun storesExactIntentBeforeDispatchAndClearsOnlyAfterConfirmedResult() = runTest {
        val events = mutableListOf<String>()
        val metadata = FakeMetadata(events)
        val gateway =
            FakeGateway(
                events,
                snapshot(12, 7),
                DispatchHandoverGatewayResult.Dispatched(receipt())
            )
        val viewModel = DispatchHandoverViewModel(gateway, metadata, newCommandKey = { KEY })
        viewModel.activate(readiness(12, 7), context())
        runCurrent()

        assertTrue(viewModel.state.value.canConfirm)
        viewModel.confirm()
        runCurrent()

        assertEquals(
            listOf(
                "metadata.load",
                "gateway.load",
                "metadata.save",
                "gateway.dispatch",
                "metadata.clear"
            ),
            events
        )
        val sent = gateway.command ?: error("dispatch not called")
        assertEquals(sent.toRequestBody(), sent.exactRequestBody)
        assertEquals(KEY, sent.idempotencyKey)
        assertNull(metadata.intent)
        assertEquals(DispatchHandoverStatus.Completed, viewModel.state.value.status)
        assertFalse(viewModel.state.value.hasPendingCommand)
    }

    @Test
    fun restoredIntentReplaysUnchangedBodyAfterCurrentFactsAdvance() = runTest {
        val events = mutableListOf<String>()
        val frozen = command()
        val metadata = FakeMetadata(
            events,
            DispatchHandoverIntent(
                scope(),
                frozen,
                DispatchOutgoingGoodsIntentStatus.UnknownOutcome
            )
        )
        val gateway =
            FakeGateway(
                events,
                snapshot(13, 8),
                DispatchHandoverGatewayResult.Dispatched(receipt())
            )
        val viewModel = DispatchHandoverViewModel(gateway, metadata)
        viewModel.activate(readiness(12, 7), context())
        runCurrent()

        assertEquals(DispatchHandoverStatus.UnknownOutcome, viewModel.state.value.status)
        assertTrue(viewModel.state.value.canReplay)
        assertNull(gateway.command)

        viewModel.replayUnknownOutcome()
        runCurrent()

        assertEquals(frozen, gateway.command)
        assertNull(gateway.dispatchSnapshot)
        assertEquals(DispatchHandoverStatus.Completed, viewModel.state.value.status)
    }

    private class FakeGateway(
        private val events: MutableList<String>,
        private val current: DispatchHandoverSnapshot,
        private val dispatchResult: DispatchHandoverGatewayResult
    ) : DispatchHandoverGateway {
        var command: DispatchHandoverCommand? = null
        var dispatchSnapshot: DispatchHandoverSnapshot? = null

        override suspend fun load(
            fulfillment: DispatchReadiness,
            context: DispatchAuthorityContext
        ): DispatchHandoverGatewayResult {
            events += "gateway.load"
            return DispatchHandoverGatewayResult.Snapshot(current)
        }

        override suspend fun dispatch(
            fulfillment: DispatchReadiness,
            snapshot: DispatchHandoverSnapshot?,
            command: DispatchHandoverCommand,
            context: DispatchAuthorityContext
        ): DispatchHandoverGatewayResult {
            events += "gateway.dispatch"
            this.command = command
            dispatchSnapshot = snapshot
            return dispatchResult
        }
    }

    private class FakeMetadata(
        private val events: MutableList<String>,
        var intent: DispatchHandoverIntent? = null
    ) : DispatchHandoverMetadataStore {
        override suspend fun loadIntent(
            scope: DispatchOutgoingGoodsScopeIdentity,
            fulfillmentId: String
        ): DispatchHandoverMetadataRead {
            events += "metadata.load"
            return DispatchHandoverMetadataRead.Available(
                intent?.takeIf {
                    it.scope == scope && it.command.fulfillmentId == fulfillmentId
                }
            )
        }

        override suspend fun saveIntent(
            intent: DispatchHandoverIntent
        ): DispatchHandoverMetadataWrite {
            events += "metadata.save"
            this.intent = intent
            return DispatchHandoverMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DispatchOutgoingGoodsScopeIdentity,
            fulfillmentId: String,
            idempotencyKey: String
        ): DispatchHandoverMetadataWrite {
            events += "metadata.clear"
            if (intent?.command?.idempotencyKey == idempotencyKey) intent = null
            return DispatchHandoverMetadataWrite.Saved
        }
    }

    private fun readiness(version: Long, allocationVersion: Long) = DispatchReadiness(
        subjectKind = "PREPARED_FULFILLMENT",
        fulfillmentId = FULFILLMENT_ID,
        fulfillmentVersion = version,
        fulfillmentStatus = "READY_FOR_DISPATCH",
        physicalAllocationId = ALLOCATION_ID,
        physicalAllocationStatus = "ALLOCATED",
        physicalAllocationVersion = allocationVersion,
        deliveryId = null,
        deliveryStatus = null,
        deliveryVersion = null,
        allocationComplete = true,
        pickingComplete = true,
        pickingEvidenceComplete = true,
        ready = true,
        reasons = emptyList(),
        lines = emptyList(),
        asOf = AS_OF
    )

    private fun snapshot(version: Long, allocationVersion: Long): DispatchHandoverSnapshot {
        val work = readiness(version, allocationVersion)
        return DispatchHandoverSnapshot(
            readiness = work,
            allocation = DispatchOutgoingGoodsAllocation(
                id = ALLOCATION_ID,
                status = "ALLOCATED",
                version = allocationVersion,
                asOf = AS_OF,
                lines = emptyList()
            ),
            outgoingCheck = DispatchOutgoingGoodsCheck(
                id = CHECK_ID, fulfillmentId = FULFILLMENT_ID, fulfillmentVersion = version,
                physicalAllocationId = ALLOCATION_ID, physicalAllocationVersion = allocationVersion,
                matches = true, current = true, openDiscrepancy = false, checkedAt = AS_OF,
                lines = emptyList(), replayed = false
            ),
            driverAssignment = PreparedFulfillmentDriverAssignment(
                id = ASSIGNMENT_ID, fulfillmentId = FULFILLMENT_ID, fulfillmentVersion = version,
                physicalAllocationId = ALLOCATION_ID, physicalAllocationVersion = allocationVersion,
                responsibleMembershipId = DRIVER_MEMBERSHIP_ID, responsibleDisplayName = "Driver",
                assignedAt = AS_OF, deliveryId = null
            )
        )
    }

    private fun command() = DispatchHandoverCommand(
        fulfillmentId = FULFILLMENT_ID,
        expectedFulfillmentVersion = 12,
        physicalAllocationId = ALLOCATION_ID,
        physicalAllocationVersion = 7,
        driverAssignmentId = ASSIGNMENT_ID,
        driverAssignmentVersion = 12,
        outgoingGoodsCheckId = CHECK_ID,
        idempotencyKey = KEY,
        exactRequestBody = ""
    ).let { it.copy(exactRequestBody = it.toRequestBody()) }

    private fun context() = DispatchAuthorityContext(
        authorityEpoch = 3,
        identity = DispatchAuthorityIdentity(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBERSHIP_ID,
            setOf("dispatch.read", "fulfillment.manage")
        )
    )

    private fun scope() =
        DispatchOutgoingGoodsScopeIdentity(USER_ID, TENANT_ID, WORKSPACE_ID, MEMBERSHIP_ID)

    private fun receipt() = DispatchHandoverReceipt(
        FULFILLMENT_ID,
        "HANDED_OVER",
        13,
        DELIVERY_ID,
        "IN_TRANSIT",
        0,
        AS_OF
    )

    private companion object {
        const val USER_ID = "user-1"
        const val TENANT_ID = "tenant-1"
        const val WORKSPACE_ID = "workspace-1"
        const val MEMBERSHIP_ID = "membership-1"
        const val DRIVER_MEMBERSHIP_ID = "driver-membership-1"
        const val FULFILLMENT_ID = "11111111-1111-4111-8111-111111111111"
        const val ALLOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val ASSIGNMENT_ID = "33333333-3333-4333-8333-333333333333"
        const val CHECK_ID = "44444444-4444-4444-8444-444444444444"
        const val DELIVERY_ID = "55555555-5555-4555-8555-555555555555"
        const val KEY = "dispatch-key"
        val AS_OF = Instant.parse("2026-09-30T10:15:30Z")
    }
}
