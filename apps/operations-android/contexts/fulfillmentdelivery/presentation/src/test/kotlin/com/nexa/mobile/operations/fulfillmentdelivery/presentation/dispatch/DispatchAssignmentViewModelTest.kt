package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchAssignmentSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDriverCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
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
class DispatchAssignmentViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun assignsOnlyASelectedCurrentEligibleDriverAgainstServerVersions() = runTest {
        val events = mutableListOf<String>()
        val metadata = FakeMetadataStore(events)
        val gateway = FakeGateway(snapshot(), events)
        val viewModel = DispatchAssignmentViewModel(
            gateway,
            metadata,
            commandKey = { "command-key" }
        )

        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        assertEquals(DispatchAssignmentStatus.Current, viewModel.state.value.status)
        assertFalse(viewModel.state.value.canAssign)
        viewModel.selectDriver(MEMBERSHIP_ID)
        assertTrue(viewModel.state.value.canAssign)

        viewModel.selectDriver(OTHER_MEMBERSHIP_ID)
        assertEquals(MEMBERSHIP_ID, viewModel.state.value.selectedMembershipId)
        viewModel.selectDriver(MEMBERSHIP_ID)
        viewModel.assign()
        runCurrent()

        assertEquals(DispatchAssignmentStatus.Current, viewModel.state.value.status)
        assertEquals(assignment(), viewModel.state.value.assignment)
        assertFalse(viewModel.state.value.canAssign)
        assertEquals(FULFILLMENT_ID, gateway.assignedFulfillment?.fulfillmentId)
        assertEquals(12L, gateway.assignedFulfillment?.fulfillmentVersion)
        assertEquals(7L, gateway.assignedFulfillment?.physicalAllocationVersion)
        assertEquals(MEMBERSHIP_ID, gateway.assignedMembershipId)
        assertEquals("command-key", gateway.idempotencyKey)
        assertEquals(listOf("save", "post", "clear"), events)
    }

    @Test
    fun losingPermissionsAtSameAuthorityEpochClearsDriversAndAssignment() = runTest {
        val assignedSnapshot = snapshot().copy(
            readiness = readiness().copy(fulfillmentVersion = 13),
            assignment = assignment()
        )
        val metadata = FakeMetadataStore()
        val unresolved = intent(DispatchAssignmentIntentStatus.UnknownOutcome)
        metadata.saveIntent(unresolved)
        val gateway = FakeGateway(assignedSnapshot)
        val viewModel = DispatchAssignmentViewModel(gateway, metadata)
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        assertEquals(assignment(), viewModel.state.value.assignment)

        viewModel.activate(FULFILLMENT_ID, context(permissions = emptySet()))

        assertEquals(7L, viewModel.state.value.authorityEpoch)
        assertEquals(DispatchAssignmentStatus.PermissionUnknown, viewModel.state.value.status)
        assertTrue(viewModel.state.value.candidates.isEmpty())
        assertNull(viewModel.state.value.assignment)
        assertFalse(viewModel.state.value.canAssign)
        assertEquals(unresolved, metadata.current(unresolved.scope, FULFILLMENT_ID))
    }

    @Test
    fun assignmentResponseAfterRouteClosesCannotRestoreAuthorityData() = runTest {
        val metadata = FakeMetadataStore()
        val gateway = FakeGateway(snapshot())
        val delayed = CompletableDeferred<DispatchAssignmentGatewayResult>()
        gateway.assignResult = { delayed.await() }
        val viewModel = DispatchAssignmentViewModel(gateway, metadata)
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        viewModel.selectDriver(MEMBERSHIP_ID)
        viewModel.assign()
        runCurrent()
        assertEquals(DispatchAssignmentStatus.Saving, viewModel.state.value.status)

        viewModel.deactivate()
        delayed.complete(DispatchAssignmentGatewayResult.Assigned(assignment()))
        runCurrent()

        assertEquals(DispatchAssignmentUiState(), viewModel.state.value)
        assertNull(viewModel.state.value.assignment)
        assertEquals(
            DispatchAssignmentIntentStatus.Pending,
            metadata.current(scope(), FULFILLMENT_ID)?.status
        )
    }

    @Test
    fun restoredPendingCommandBecomesUnknownAndOnlyManualReplaySendsFrozenBody() = runTest {
        val metadata = FakeMetadataStore()
        val frozen = intent(DispatchAssignmentIntentStatus.Pending)
        metadata.saveIntent(frozen)
        val gateway = FakeGateway(snapshot())
        val viewModel = DispatchAssignmentViewModel(gateway, metadata)

        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()

        assertEquals(DispatchAssignmentStatus.UnknownOutcome, viewModel.state.value.status)
        assertTrue(viewModel.state.value.canReplay)
        assertNull(gateway.replayedIntent)
        assertEquals("command-key", metadata.current(scope(), FULFILLMENT_ID)?.idempotencyKey)

        viewModel.retryUnknownOutcome()
        runCurrent()

        assertEquals(frozen.idempotencyKey, gateway.replayedIntent?.idempotencyKey)
        assertEquals(
            frozen.expectedFulfillmentVersion,
            gateway.replayedIntent?.expectedFulfillmentVersion
        )
        assertEquals(frozen.physicalAllocationId, gateway.replayedIntent?.physicalAllocationId)
        assertEquals(
            frozen.physicalAllocationVersion,
            gateway.replayedIntent?.physicalAllocationVersion
        )
        assertEquals(
            frozen.responsibleMembershipId,
            gateway.replayedIntent?.responsibleMembershipId
        )
        assertNull(metadata.current(scope(), FULFILLMENT_ID))
        assertEquals(DispatchAssignmentStatus.Current, viewModel.state.value.status)
    }

    @Test
    fun unavailableMetadataPreventsAssignmentRequest() = runTest {
        val metadata = FakeMetadataStore().apply {
            saveResult =
                DispatchAssignmentMetadataWrite.Unavailable
        }
        val gateway = FakeGateway(snapshot())
        val viewModel = DispatchAssignmentViewModel(gateway, metadata)
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        viewModel.selectDriver(MEMBERSHIP_ID)

        viewModel.assign()
        runCurrent()

        assertEquals(DispatchAssignmentStatus.ServiceUnavailable, viewModel.state.value.status)
        assertNull(gateway.assignedFulfillment)
    }

    private fun context(
        permissions: Set<String> = setOf("dispatch.read", "logistics.read", "dispatch.assign")
    ) = DispatchAuthorityContext(
        authorityEpoch = 7,
        identity = DispatchAuthorityIdentity(
            userId = USER_ID,
            tenantId = TENANT_ID,
            workspaceId = WORKSPACE_ID,
            membershipId = ACTOR_MEMBERSHIP_ID,
            permissions = permissions
        )
    )

    private fun readiness() = DispatchReadiness(
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

    private fun candidate() =
        DispatchDriverCandidate(MEMBERSHIP_ID, "driver@example.test", "Driver One")

    private fun assignment() = PreparedFulfillmentDriverAssignment(
        id = ASSIGNMENT_ID,
        fulfillmentId = FULFILLMENT_ID,
        fulfillmentVersion = 13,
        physicalAllocationId = ALLOCATION_ID,
        physicalAllocationVersion = 7,
        responsibleMembershipId = MEMBERSHIP_ID,
        responsibleDisplayName = "Driver One",
        assignedAt = AS_OF,
        deliveryId = null
    )

    private fun snapshot() = DispatchAssignmentSnapshot(readiness(), listOf(candidate()), null)

    private fun scope() =
        DispatchAssignmentScopeIdentity(USER_ID, TENANT_ID, WORKSPACE_ID, ACTOR_MEMBERSHIP_ID)

    private fun intent(status: DispatchAssignmentIntentStatus) = DispatchAssignmentIntent(
        scope = scope(),
        fulfillmentId = FULFILLMENT_ID,
        expectedFulfillmentVersion = 12,
        physicalAllocationId = ALLOCATION_ID,
        physicalAllocationVersion = 7,
        responsibleMembershipId = MEMBERSHIP_ID,
        idempotencyKey = "command-key",
        status = status
    )

    private class FakeGateway(
        private val value: DispatchAssignmentSnapshot,
        private val events: MutableList<String> = mutableListOf()
    ) : DispatchAssignmentGateway {
        var assignedFulfillment: DispatchReadiness? = null
        var assignedMembershipId: String? = null
        var idempotencyKey: String? = null
        var replayedIntent: DispatchAssignmentIntent? = null
        var assignResult: suspend () -> DispatchAssignmentGatewayResult = {
            DispatchAssignmentGatewayResult.Assigned(
                PreparedFulfillmentDriverAssignment(
                    id = ASSIGNMENT_ID,
                    fulfillmentId = FULFILLMENT_ID,
                    fulfillmentVersion = 13,
                    physicalAllocationId = ALLOCATION_ID,
                    physicalAllocationVersion = 7,
                    responsibleMembershipId = MEMBERSHIP_ID,
                    responsibleDisplayName = "Driver One",
                    assignedAt = AS_OF,
                    deliveryId = null
                )
            )
        }

        override suspend fun load(fulfillmentId: String, context: DispatchAuthorityContext) =
            DispatchAssignmentGatewayResult.Snapshot(value)

        override suspend fun assign(
            fulfillment: DispatchReadiness,
            responsibleMembershipId: String,
            context: DispatchAuthorityContext,
            idempotencyKey: String
        ): DispatchAssignmentGatewayResult {
            events += "post"
            assignedFulfillment = fulfillment
            assignedMembershipId = responsibleMembershipId
            this.idempotencyKey = idempotencyKey
            return assignResult()
        }

        override suspend fun replay(
            intent: DispatchAssignmentIntent,
            context: DispatchAuthorityContext
        ): DispatchAssignmentGatewayResult {
            events += "replay"
            replayedIntent = intent
            return assignResult()
        }
    }

    private class FakeMetadataStore(private val events: MutableList<String> = mutableListOf()) :
        DispatchAssignmentMetadataStore {
        private val intents =
            mutableMapOf<Pair<DispatchAssignmentScopeIdentity, String>, DispatchAssignmentIntent>()
        var saveResult = DispatchAssignmentMetadataWrite.Saved

        override suspend fun loadIntent(
            scope: DispatchAssignmentScopeIdentity,
            fulfillmentId: String
        ): DispatchAssignmentMetadataRead {
            val key = scope to fulfillmentId
            val current = intents[key]
            if (current?.status == DispatchAssignmentIntentStatus.Pending) {
                val recovered = current.copy(status = DispatchAssignmentIntentStatus.UnknownOutcome)
                intents[key] = recovered
                return DispatchAssignmentMetadataRead.Available(recovered)
            }
            return DispatchAssignmentMetadataRead.Available(current)
        }

        override suspend fun saveIntent(
            intent: DispatchAssignmentIntent
        ): DispatchAssignmentMetadataWrite {
            events += "save"
            if (saveResult != DispatchAssignmentMetadataWrite.Saved) return saveResult
            val key = intent.scope to intent.fulfillmentId
            val existing = intents[key]
            if (existing != null && existing.copy(status = intent.status) != intent) {
                return DispatchAssignmentMetadataWrite.Conflict
            }
            if (existing?.status == DispatchAssignmentIntentStatus.UnknownOutcome &&
                intent.status == DispatchAssignmentIntentStatus.Pending
            ) {
                return DispatchAssignmentMetadataWrite.Conflict
            }
            intents[key] = intent
            return DispatchAssignmentMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DispatchAssignmentScopeIdentity,
            fulfillmentId: String,
            idempotencyKey: String
        ): DispatchAssignmentMetadataWrite {
            events += "clear"
            val key = scope to fulfillmentId
            val existing = intents[key] ?: return DispatchAssignmentMetadataWrite.Saved
            if (existing.idempotencyKey !=
                idempotencyKey
            ) {
                return DispatchAssignmentMetadataWrite.Stale
            }
            intents.remove(key)
            return DispatchAssignmentMetadataWrite.Saved
        }

        fun current(scope: DispatchAssignmentScopeIdentity, fulfillmentId: String) =
            intents[scope to fulfillmentId]
    }

    private companion object {
        val AS_OF = Instant.parse("2026-09-30T10:15:30Z")
        const val USER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413901"
        const val TENANT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413902"
        const val WORKSPACE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413903"
        const val ACTOR_MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413904"
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413104"
        const val OTHER_MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413103"
        const val ASSIGNMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413105"
    }
}
