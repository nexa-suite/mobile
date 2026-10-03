package com.nexa.mobile.operations.feature.dispatch

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
class DispatchPlanChangeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun freezesExactBodyBeforePostAndRefreshesTheServerCurrentAssignment() = runTest {
        val events = mutableListOf<String>()
        val metadata = FakeMetadata(events)
        val gateway = FakeGateway(snapshot(), events)
        val viewModel = DispatchPlanChangeViewModel(gateway, metadata) { "plan-key" }

        viewModel.activate(FULFILLMENT, context())
        runCurrent()
        assertEquals(DispatchPlanChangeStatus.Current, viewModel.state.value.status)
        assertFalse(viewModel.state.value.canSave)

        viewModel.selectDriver(OTHER_DRIVER)
        viewModel.updatePlannedDispatchAt("2026-10-01T15:30:00Z")
        assertTrue(viewModel.state.value.canSave)
        viewModel.changePlan()
        runCurrent()

        val frozen = gateway.changedIntent ?: error("Plan change was not sent")
        assertEquals(
            """{"expectedAssignmentId":"$ASSIGNMENT","expectedAssignmentVersion":13,"physicalAllocationId":"$ALLOCATION","physicalAllocationVersion":7,"responsibleMembershipId":"$OTHER_DRIVER","plannedDispatchAt":"2026-10-01T15:30:00Z"}""",
            frozen.requestBody
        )
        assertEquals(13L, frozen.expectedFulfillmentVersion)
        assertEquals(13L, frozen.expectedAssignmentVersion)
        assertEquals(listOf("load", "save", "post", "clear", "load"), events)
        assertEquals(OTHER_DRIVER, viewModel.state.value.assignment?.responsibleMembershipId)
        assertEquals(
            Instant.parse("2026-10-01T15:30:00Z"),
            viewModel.state.value.assignment?.plannedDispatchAt
        )
        assertNull(viewModel.state.value.pendingIntent)
    }

    @Test
    fun restoredUncertainCommandRequiresManualExactReplayDespiteNewerCurrentVersion() = runTest {
        val events = mutableListOf<String>()
        val metadata = FakeMetadata(events)
        val frozen = intent(status = DispatchPlanChangeIntentStatus.Pending)
        metadata.saveIntent(frozen)
        events.clear()
        val newerSnapshot = snapshot().let { old ->
            val newerReadiness = old.readiness.copy(fulfillmentVersion = 20)
            val newerAssignment = assignment().copy(
                id = CURRENT_ASSIGNMENT,
                fulfillmentVersion = 20,
                responsibleMembershipId = OTHER_DRIVER,
                responsibleDisplayName = "Driver Two"
            )
            old.copy(
                readiness = newerReadiness,
                assignment = newerAssignment,
                history = listOf(assignment().copy(current = false), newerAssignment)
            )
        }
        val gateway = FakeGateway(newerSnapshot, events).apply {
            replayResult = PreparedFulfillmentDriverAssignment(
                id = REPLAY_ASSIGNMENT,
                fulfillmentId = FULFILLMENT,
                fulfillmentVersion = 14,
                physicalAllocationId = ALLOCATION,
                physicalAllocationVersion = 7,
                responsibleMembershipId = OTHER_DRIVER,
                responsibleDisplayName = "Driver Two",
                assignedAt = AS_OF,
                deliveryId = null,
                plannedDispatchAt = Instant.parse("2026-10-01T15:30:00Z"),
                current = false
            )
        }
        val viewModel = DispatchPlanChangeViewModel(gateway, metadata)

        viewModel.activate(FULFILLMENT, context())
        runCurrent()

        assertEquals(DispatchPlanChangeStatus.UnknownOutcome, viewModel.state.value.status)
        assertTrue(viewModel.state.value.canReplay)
        assertNull(gateway.replayedIntent)
        viewModel.retryUnknownOutcome()
        runCurrent()

        assertEquals(frozen.requestBody, gateway.replayedIntent?.requestBody)
        assertEquals(frozen.idempotencyKey, gateway.replayedIntent?.idempotencyKey)
        assertEquals(13L, gateway.replayedIntent?.expectedFulfillmentVersion)
        assertTrue(events.indexOf("replay") > events.indexOf("save"))
        assertEquals(DispatchPlanChangeStatus.Current, viewModel.state.value.status)
        assertNull(viewModel.state.value.pendingIntent)
    }

    @Test
    fun invalidTimestampAndMissingSchedulePermissionCannotCreateMutation() = runTest {
        val gateway = FakeGateway(snapshot())
        val viewModel = DispatchPlanChangeViewModel(gateway, FakeMetadata())
        viewModel.activate(
            FULFILLMENT,
            context(permissions = setOf("dispatch.read", "dispatch.schedule"))
        )
        runCurrent()
        viewModel.selectDriver(OTHER_DRIVER)
        viewModel.updatePlannedDispatchAt("not-a-time")
        assertTrue(viewModel.state.value.inputInvalid)
        assertFalse(viewModel.state.value.canSave)
        viewModel.changePlan()
        runCurrent()
        assertNull(gateway.changedIntent)
    }

    private fun context(
        permissions: Set<String> = setOf(
            "dispatch.read",
            "dispatch.assign",
            "dispatch.schedule"
        )
    ) = DispatchAuthorityContext(
        authorityEpoch = 4,
        identity = DispatchAuthorityIdentity("user", "tenant", "workspace", "actor", permissions)
    )

    private fun readiness() = DispatchReadiness(
        subjectKind = "PREPARED_FULFILLMENT",
        fulfillmentId = FULFILLMENT,
        fulfillmentVersion = 13,
        fulfillmentStatus = "READY_FOR_DISPATCH",
        physicalAllocationId = ALLOCATION,
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

    private fun assignment() = PreparedFulfillmentDriverAssignment(
        id = ASSIGNMENT,
        fulfillmentId = FULFILLMENT,
        fulfillmentVersion = 13,
        physicalAllocationId = ALLOCATION,
        physicalAllocationVersion = 7,
        responsibleMembershipId = DRIVER,
        responsibleDisplayName = "Driver One",
        assignedAt = AS_OF,
        deliveryId = null
    )

    private fun snapshot(): DispatchPlanChangeSnapshot {
        val current = assignment()
        return DispatchPlanChangeSnapshot(
            readiness(),
            listOf(
                DispatchDriverCandidate(DRIVER, "one@example.test", "Driver One"),
                DispatchDriverCandidate(OTHER_DRIVER, "two@example.test", "Driver Two")
            ),
            current,
            listOf(current)
        )
    }

    private fun intent(status: DispatchPlanChangeIntentStatus) = DispatchPlanChangeIntent(
        scope = DispatchPlanChangeScopeIdentity("user", "tenant", "workspace", "actor"),
        fulfillmentId = FULFILLMENT,
        expectedFulfillmentVersion = 13,
        expectedAssignmentId = ASSIGNMENT,
        expectedAssignmentVersion = 13,
        physicalAllocationId = ALLOCATION,
        physicalAllocationVersion = 7,
        requestedMembershipId = OTHER_DRIVER,
        requestedDispatchAt = Instant.parse("2026-10-01T15:30:00Z"),
        resultResponsibleMembershipId = OTHER_DRIVER,
        resultPlannedDispatchAt = Instant.parse("2026-10-01T15:30:00Z"),
        requestBody = "{\"same\":\"frozen\"}",
        idempotencyKey = "plan-key",
        status = status
    )

    private class FakeGateway(
        var current: DispatchPlanChangeSnapshot,
        private val events: MutableList<String> = mutableListOf()
    ) : DispatchPlanChangeGateway {
        var changedIntent: DispatchPlanChangeIntent? = null
        var replayedIntent: DispatchPlanChangeIntent? = null
        var replayResult: PreparedFulfillmentDriverAssignment? = null

        override suspend fun load(
            fulfillmentId: String,
            context: DispatchAuthorityContext
        ): DispatchPlanChangeGatewayResult {
            events += "load"
            return DispatchPlanChangeGatewayResult.Snapshot(current)
        }

        override suspend fun change(
            readiness: DispatchReadiness,
            assignment: PreparedFulfillmentDriverAssignment,
            intent: DispatchPlanChangeIntent,
            context: DispatchAuthorityContext
        ): DispatchPlanChangeGatewayResult {
            events += "post"
            changedIntent = intent
            val changed = PreparedFulfillmentDriverAssignment(
                id = REPLAY_ASSIGNMENT,
                fulfillmentId = FULFILLMENT,
                fulfillmentVersion = intent.expectedFulfillmentVersion + 1,
                physicalAllocationId = ALLOCATION,
                physicalAllocationVersion = 7,
                responsibleMembershipId = intent.resultResponsibleMembershipId,
                responsibleDisplayName = "Driver Two",
                assignedAt = AS_OF,
                deliveryId = null,
                plannedDispatchAt = intent.resultPlannedDispatchAt
            )
            val nextReadiness = current.readiness.copy(
                fulfillmentVersion = changed.fulfillmentVersion
            )
            current = current.copy(
                readiness = nextReadiness,
                assignment = changed,
                history = current.history.map { it.copy(current = false) } + changed
            )
            return DispatchPlanChangeGatewayResult.Changed(changed)
        }

        override suspend fun replay(
            intent: DispatchPlanChangeIntent,
            context: DispatchAuthorityContext
        ): DispatchPlanChangeGatewayResult {
            events += "replay"
            replayedIntent = intent
            return DispatchPlanChangeGatewayResult.Changed(
                replayResult ?: error("Replay result not configured")
            )
        }
    }

    private class FakeMetadata(private val events: MutableList<String> = mutableListOf()) :
        DispatchPlanChangeMetadataStore {
        private val intents =
            mutableMapOf<Pair<DispatchPlanChangeScopeIdentity, String>, DispatchPlanChangeIntent>()

        override suspend fun loadIntent(
            scope: DispatchPlanChangeScopeIdentity,
            fulfillmentId: String
        ): DispatchPlanChangeMetadataRead = DispatchPlanChangeMetadataRead.Available(
            intents[scope to fulfillmentId]
        )

        override suspend fun saveIntent(
            intent: DispatchPlanChangeIntent
        ): DispatchPlanChangeMetadataWrite {
            events += "save"
            val key = intent.scope to intent.fulfillmentId
            val current = intents[key]
            if (current != null && current.copy(status = intent.status) != intent) {
                return DispatchPlanChangeMetadataWrite.Conflict
            }
            intents[key] = intent
            return DispatchPlanChangeMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DispatchPlanChangeScopeIdentity,
            fulfillmentId: String,
            idempotencyKey: String
        ): DispatchPlanChangeMetadataWrite {
            events += "clear"
            val key = scope to fulfillmentId
            val current = intents[key] ?: return DispatchPlanChangeMetadataWrite.Saved
            if (current.idempotencyKey != idempotencyKey) {
                return DispatchPlanChangeMetadataWrite.Stale
            }
            intents.remove(key)
            return DispatchPlanChangeMetadataWrite.Saved
        }
    }

    private companion object {
        const val FULFILLMENT = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val ALLOCATION = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val DRIVER = "b8c24a46-57d9-4f64-8fa7-6a641b413104"
        const val OTHER_DRIVER = "b8c24a46-57d9-4f64-8fa7-6a641b413106"
        const val ASSIGNMENT = "b8c24a46-57d9-4f64-8fa7-6a641b413105"
        const val CURRENT_ASSIGNMENT = "b8c24a46-57d9-4f64-8fa7-6a641b413107"
        const val REPLAY_ASSIGNMENT = "b8c24a46-57d9-4f64-8fa7-6a641b413108"
        val AS_OF = Instant.parse("2026-09-30T10:15:30Z")
    }
}
