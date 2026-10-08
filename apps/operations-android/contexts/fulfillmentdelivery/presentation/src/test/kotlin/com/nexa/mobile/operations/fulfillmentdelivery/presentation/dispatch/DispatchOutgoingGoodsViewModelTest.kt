package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.presentation.testsupport.TestDispatchRequestBodyCodec

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsAllocation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCheck
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCheckLine
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsCommand
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCommandType
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsDiscrepancy
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsLine
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsObservation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsResolution
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchOutgoingGoodsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun savesExactIntentBeforePostAndClearsOnlyAfterConfirmedServerResult() = runTest {
        val events = mutableListOf<String>()
        val metadata = FakeMetadata(events)
        val gateway = FakeGateway(events, allocation())
        val viewModel = DispatchOutgoingGoodsViewModel(gateway, metadata, newCommandKey = { KEY }, requestBodyCodec = TestDispatchRequestBodyCodec())
        viewModel.activate(fulfillment(), context())
        runCurrent()
        viewModel.changeObservedLot(LINE_ID, LOT_ID)
        viewModel.changeObservedQuantity(LINE_ID, "2.50")

        viewModel.record()
        runCurrent()

        assertEquals(listOf("load", "read", "save", "post", "clear"), events)
        assertEquals(
            "{\"physicalAllocationId\":\"$ALLOCATION_ID\",\"physicalAllocationVersion\":7," +
                "\"observations\":[{\"physicalAllocationLineId\":\"$LINE_ID\"," +
                "\"observedLotId\":\"$LOT_ID\"," +
                "\"observedQuantity\":2.50}]}",
            gateway.posted?.exactRequestBody
        )
        assertEquals(KEY, gateway.posted?.idempotencyKey)
        assertEquals(DispatchOutgoingGoodsStatus.Current, viewModel.state.value.status)
        assertFalse(viewModel.state.value.hasPendingCommand)
        assertEquals(null, metadata.intent)
    }

    @Test
    fun restoredPendingIntentIsNotSentUntilManualExactReplay() = runTest {
        val events = mutableListOf<String>()
        val frozen = intent(DispatchOutgoingGoodsIntentStatus.UnknownOutcome)
        val metadata = FakeMetadata(events, frozen)
        val gateway = FakeGateway(events, allocation())
        val viewModel = DispatchOutgoingGoodsViewModel(gateway, metadata, requestBodyCodec = TestDispatchRequestBodyCodec())
        viewModel.activate(fulfillment(), context())
        runCurrent()

        assertEquals(DispatchOutgoingGoodsStatus.UnknownOutcome, viewModel.state.value.status)
        assertTrue(viewModel.state.value.hasPendingCommand)
        assertTrue(gateway.posted == null)
        assertEquals(listOf("load", "read"), events)

        viewModel.retryUnknownOutcome()
        runCurrent()

        assertEquals(listOf("load", "read", "post", "clear"), events)
        assertEquals(frozen.command, gateway.posted)
        assertEquals(DispatchOutgoingGoodsStatus.Current, viewModel.state.value.status)
        assertFalse(viewModel.state.value.hasPendingCommand)
    }

    @Test
    fun resolutionRequiresMatchingReinspectionAndSavesExactIntentBeforePost() = runTest {
        val events = mutableListOf<String>()
        val discrepancy = DispatchOutgoingGoodsDiscrepancy(
            DISCREPANCY_ID,
            12,
            ALLOCATION_ID,
            7,
            ACTOR_MEMBERSHIP_ID,
            AS_OF.minusSeconds(60),
            listOf(
                DispatchOutgoingGoodsCheckLine(
                    LINE_ID,
                    LOT_ID,
                    LOT_ID,
                    BigDecimal("2.50"),
                    BigDecimal("3.50"),
                    "each",
                    false
                )
            )
        )
        val current = DispatchOutgoingGoodsCheck(
            CHECK_ID, FULFILLMENT_ID, 12, ALLOCATION_ID, 7, true, true, true, AS_OF,
            listOf(
                DispatchOutgoingGoodsCheckLine(
                    LINE_ID,
                    LOT_ID,
                    LOT_ID,
                    BigDecimal("2.50"),
                    BigDecimal("2.50"),
                    "each",
                    true
                )
            ),
            false, discrepancy
        )
        val gateway = FakeGateway(events, allocation(), current)
        val metadata = FakeMetadata(events)
        val viewModel =
            DispatchOutgoingGoodsViewModel(gateway, metadata, newCommandKey = { RESOLUTION_KEY }, requestBodyCodec = TestDispatchRequestBodyCodec())
        viewModel.activate(fulfillment(), context())
        runCurrent()
        assertFalse(viewModel.state.value.canResolveDiscrepancy)

        viewModel.changeResolutionReason("Recount confirmed the allocated goods.")
        assertTrue(viewModel.state.value.canResolveDiscrepancy)
        viewModel.resolveDiscrepancy()
        runCurrent()

        assertEquals(listOf("load", "read", "save", "resolve", "clear"), events)
        assertEquals(
            DispatchOutgoingGoodsCommandType.ResolveDiscrepancy,
            gateway.resolutionPosted?.type
        )
        assertEquals(
            """{"physicalAllocationId":"$ALLOCATION_ID","physicalAllocationVersion":7,"discrepancyCheckId":"$DISCREPANCY_ID","matchingCheckId":"$CHECK_ID","reason":"Recount confirmed the allocated goods."}""",
            gateway.resolutionPosted?.exactRequestBody
        )
        assertEquals(RESOLUTION_ID, viewModel.state.value.currentResolution?.id)
        assertFalse(viewModel.state.value.currentCheck?.openDiscrepancy ?: true)
        assertEquals(null, metadata.intent)
    }

    private fun context() = DispatchAuthorityContext(
        authorityEpoch = 3,
        identity = DispatchAuthorityIdentity(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            ACTOR_MEMBERSHIP_ID,
            setOf("fulfillment.manage")
        )
    )

    private fun fulfillment() = DispatchReadiness(
        subjectKind = "PREPARED_FULFILLMENT",
        fulfillmentId = FULFILLMENT_ID,
        fulfillmentVersion = 12,
        fulfillmentStatus = "READY_FOR_DISPATCH",
        physicalAllocationId = ALLOCATION_ID,
        physicalAllocationStatus = "ALLOCATED",
        physicalAllocationVersion = 7,
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

    private fun allocation() = DispatchOutgoingGoodsAllocation(
        id = ALLOCATION_ID,
        status = "ALLOCATED",
        version = 7,
        asOf = AS_OF,
        lines = listOf(
            DispatchOutgoingGoodsLine(
                physicalAllocationLineId = LINE_ID,
                skuId = SKU_ID,
                catalogItemId = "CAT-ITEM-1",
                expectedLotId = LOT_ID,
                allocatedQuantity = BigDecimal("2.50"),
                releasedQuantity = BigDecimal.ZERO,
                consumedQuantity = BigDecimal.ZERO,
                remainingQuantity = BigDecimal("2.50"),
                unit = "each"
            )
        )
    )

    private fun intent(status: DispatchOutgoingGoodsIntentStatus) = DispatchOutgoingGoodsIntent(
        scope(),
        DispatchOutgoingGoodsCommand(
            FULFILLMENT_ID,
            12,
            ALLOCATION_ID,
            7,
            listOf(DispatchOutgoingGoodsObservation(LINE_ID, LOT_ID, BigDecimal("2.50"))),
            KEY,
            requestBody()
        ),
        status
    )

    private fun requestBody() =
        "{\"physicalAllocationId\":\"$ALLOCATION_ID\",\"physicalAllocationVersion\":7," +
            "\"observations\":[{\"physicalAllocationLineId\":\"$LINE_ID\"," +
            "\"observedLotId\":\"$LOT_ID\"," +
            "\"observedQuantity\":2.50}]}"

    private fun scope() =
        DispatchOutgoingGoodsScopeIdentity(USER_ID, TENANT_ID, WORKSPACE_ID, ACTOR_MEMBERSHIP_ID)

    private class FakeGateway(
        private val events: MutableList<String>,
        private val allocation: DispatchOutgoingGoodsAllocation,
        private val check: DispatchOutgoingGoodsCheck? = null
    ) : DispatchOutgoingGoodsGateway {
        var posted: DispatchOutgoingGoodsCommand? = null
        var resolutionPosted: DispatchOutgoingGoodsCommand? = null

        override suspend fun load(
            fulfillment: DispatchReadiness,
            context: DispatchAuthorityContext
        ): DispatchOutgoingGoodsGatewayResult {
            events += "read"
            return DispatchOutgoingGoodsGatewayResult.Snapshot(
                DispatchOutgoingGoodsSnapshot(allocation, check)
            )
        }

        override suspend fun record(
            fulfillment: DispatchReadiness,
            allocation: DispatchOutgoingGoodsAllocation,
            command: DispatchOutgoingGoodsCommand,
            context: DispatchAuthorityContext
        ): DispatchOutgoingGoodsGatewayResult {
            events += "post"
            posted = command
            return DispatchOutgoingGoodsGatewayResult.Recorded(
                DispatchOutgoingGoodsCheck(
                    id = CHECK_ID,
                    fulfillmentId = FULFILLMENT_ID,
                    fulfillmentVersion = 12,
                    physicalAllocationId = ALLOCATION_ID,
                    physicalAllocationVersion = 7,
                    matches = true,
                    current = true,
                    openDiscrepancy = false,
                    checkedAt = AS_OF,
                    lines = listOf(
                        DispatchOutgoingGoodsCheckLine(
                            LINE_ID,
                            LOT_ID,
                            LOT_ID,
                            BigDecimal("2.50"),
                            BigDecimal("2.50"),
                            "each",
                            true
                        )
                    ),
                    replayed = false
                )
            )
        }

        override suspend fun resolveDiscrepancy(
            fulfillment: DispatchReadiness,
            command: DispatchOutgoingGoodsCommand,
            context: DispatchAuthorityContext
        ): DispatchOutgoingGoodsGatewayResult {
            events += "resolve"
            resolutionPosted = command
            return DispatchOutgoingGoodsGatewayResult.Resolved(
                DispatchOutgoingGoodsResolution(
                    id = RESOLUTION_ID,
                    fulfillmentId = command.fulfillmentId,
                    fulfillmentVersion = command.expectedFulfillmentVersion,
                    physicalAllocationId = command.physicalAllocationId,
                    physicalAllocationVersion = command.physicalAllocationVersion,
                    discrepancyCheckId = command.discrepancyCheckId.orEmpty(),
                    matchingCheckId = command.matchingCheckId.orEmpty(),
                    actorMembershipId = context.identity?.membershipId.orEmpty(),
                    reason = command.reason.orEmpty(),
                    resolvedAt = AS_OF,
                    current = true,
                    replayed = false
                )
            )
        }
    }

    private class FakeMetadata(
        private val events: MutableList<String>,
        var intent: DispatchOutgoingGoodsIntent? = null
    ) : DispatchOutgoingGoodsMetadataStore {
        override suspend fun loadIntent(
            scope: DispatchOutgoingGoodsScopeIdentity,
            fulfillmentId: String
        ): DispatchOutgoingGoodsMetadataRead {
            events += "load"
            return DispatchOutgoingGoodsMetadataRead.Available(
                intent?.takeIf { it.scope == scope && it.command.fulfillmentId == fulfillmentId }
            )
        }

        override suspend fun saveIntent(
            intent: DispatchOutgoingGoodsIntent
        ): DispatchOutgoingGoodsMetadataWrite {
            events += "save"
            this.intent = intent
            return DispatchOutgoingGoodsMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DispatchOutgoingGoodsScopeIdentity,
            fulfillmentId: String,
            idempotencyKey: String
        ): DispatchOutgoingGoodsMetadataWrite {
            events += "clear"
            if (intent?.command?.idempotencyKey == idempotencyKey) intent = null
            return DispatchOutgoingGoodsMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "user-id"
        const val TENANT_ID = "tenant-id"
        const val WORKSPACE_ID = "workspace-id"
        const val ACTOR_MEMBERSHIP_ID = "actor-membership-id"
        const val FULFILLMENT_ID = "11111111-1111-4111-8111-111111111111"
        const val ALLOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val LINE_ID = "33333333-3333-4333-8333-333333333333"
        const val SKU_ID = "44444444-4444-4444-8444-444444444444"
        const val LOT_ID = "55555555-5555-4555-8555-555555555555"
        const val CHECK_ID = "77777777-7777-4777-8777-777777777777"
        const val RESOLUTION_ID = "66666666-6666-4666-8666-666666666666"
        const val DISCREPANCY_ID = "88888888-8888-4888-8888-888888888888"
        const val KEY = "outgoing-check-key"
        const val RESOLUTION_KEY = "outgoing-resolution-key"
        val AS_OF = Instant.parse("2026-09-30T10:15:30Z")
    }
}
