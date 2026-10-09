package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentIntentStatus as AssignmentIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataRead as AssignmentMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataWrite as AssignmentMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentScopeIdentity as AssignmentScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.AppDispatchAssignmentMetadataStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDispatchAssignmentMetadataStoreTest {
    @Test
    fun pendingCommandSurvivesStoreReconstructionAndCannotChangeOrClearByStaleKey() = runTest {
        val encryptedRecords = FakeScopedMetadataStore()
        val scope = AssignmentScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        val frozen = intent(scope, AssignmentIntentStatus.Pending)
        val first = AppDispatchAssignmentMetadataStore(encryptedRecords)

        assertEquals(AssignmentMetadataWrite.Saved, first.saveIntent(frozen))

        val restored = AppDispatchAssignmentMetadataStore(encryptedRecords)
        val read = restored.loadIntent(scope, FULFILLMENT)
        assertEquals(
            AssignmentMetadataRead.Available(
                frozen.copy(status = AssignmentIntentStatus.UnknownOutcome)
            ),
            read
        )
        assertEquals(
            AssignmentMetadataWrite.Conflict,
            restored.saveIntent(frozen.copy(responsibleMembershipId = OTHER_DRIVER))
        )
        assertEquals(
            AssignmentMetadataWrite.Conflict,
            restored.saveIntent(frozen)
        )
        assertEquals(
            AssignmentMetadataWrite.Stale,
            restored.clearIntent(scope, FULFILLMENT, "different-key")
        )
        assertEquals(
            AssignmentMetadataWrite.Saved,
            restored.clearIntent(scope, FULFILLMENT, frozen.idempotencyKey)
        )
        assertEquals(
            AssignmentMetadataRead.Available(null),
            restored.loadIntent(scope, FULFILLMENT)
        )
    }

    @Test
    fun actorScopeIsBoundToTheFrozenIntent() = runTest {
        val local = FakeScopedMetadataStore()
        val store = AppDispatchAssignmentMetadataStore(local)
        val original = AssignmentScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        assertEquals(AssignmentMetadataWrite.Saved, store.saveIntent(intent(original)))

        val otherActor = original.copy(membershipId = OTHER_ACTOR)

        val read = store.loadIntent(otherActor, FULFILLMENT)
        assertEquals(AssignmentMetadataRead.Available(null), read)
        assertNull((read as AssignmentMetadataRead.Available).intent)
    }

    private fun intent(
        scope: AssignmentScopeIdentity,
        status: AssignmentIntentStatus = AssignmentIntentStatus.Pending
    ) = DispatchAssignmentIntent(
        scope = scope,
        fulfillmentId = FULFILLMENT,
        expectedFulfillmentVersion = 12,
        physicalAllocationId = ALLOCATION,
        physicalAllocationVersion = 7,
        responsibleMembershipId = OTHER_DRIVER,
        idempotencyKey = "dispatch-command-12",
        status = status
    )

    private class FakeScopedMetadataStore : ScopedMetadataStore {
        private val records = mutableMapOf<ScopedMetadataScope, String>()

        override suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead =
            ScopedMetadataRead.Value(records[scope])

        override suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean {
            records[scope] = payload
            return true
        }

        override suspend fun clear(scope: ScopedMetadataScope): Boolean {
            records.remove(scope)
            return true
        }
    }

    private companion object {
        const val USER = "user-id"
        const val TENANT = "tenant-id"
        const val WORKSPACE = "workspace-id"
        const val MEMBERSHIP = "actor-membership-id"
        const val OTHER_ACTOR = "other-actor-membership"
        const val OTHER_DRIVER = "driver-membership-id"
        const val FULFILLMENT = "fulfillment-id"
        const val ALLOCATION = "allocation-id"
    }
}
