package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentIntentStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentScopeIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDispatchAssignmentMetadataStoreTest {
    @Test
    fun pendingCommandSurvivesStoreReconstructionAndCannotChangeOrClearByStaleKey() = runTest {
        val encryptedRecords = FakeScopedMetadataStore()
        val scope = DispatchAssignmentScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        val frozen = intent(scope, DispatchAssignmentIntentStatus.Pending)
        val first = AppDispatchAssignmentMetadataStore(encryptedRecords)

        assertEquals(DispatchAssignmentMetadataWrite.Saved, first.saveIntent(frozen))

        val restored = AppDispatchAssignmentMetadataStore(encryptedRecords)
        val read = restored.loadIntent(scope, FULFILLMENT)
        assertEquals(
            DispatchAssignmentMetadataRead.Available(
                frozen.copy(status = DispatchAssignmentIntentStatus.UnknownOutcome)
            ),
            read
        )
        assertEquals(
            DispatchAssignmentMetadataWrite.Conflict,
            restored.saveIntent(frozen.copy(responsibleMembershipId = OTHER_DRIVER))
        )
        assertEquals(
            DispatchAssignmentMetadataWrite.Conflict,
            restored.saveIntent(frozen)
        )
        assertEquals(
            DispatchAssignmentMetadataWrite.Stale,
            restored.clearIntent(scope, FULFILLMENT, "different-key")
        )
        assertEquals(
            DispatchAssignmentMetadataWrite.Saved,
            restored.clearIntent(scope, FULFILLMENT, frozen.idempotencyKey)
        )
        assertEquals(DispatchAssignmentMetadataRead.Available(null), restored.loadIntent(scope, FULFILLMENT))
    }

    @Test
    fun actorScopeIsBoundToTheFrozenIntent() = runTest {
        val local = FakeScopedMetadataStore()
        val store = AppDispatchAssignmentMetadataStore(local)
        val original = DispatchAssignmentScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        assertEquals(DispatchAssignmentMetadataWrite.Saved, store.saveIntent(intent(original)))

        val otherActor = original.copy(membershipId = OTHER_ACTOR)

        val read = store.loadIntent(otherActor, FULFILLMENT)
        assertEquals(DispatchAssignmentMetadataRead.Available(null), read)
        assertNull((read as DispatchAssignmentMetadataRead.Available).intent)
    }

    private fun intent(
        scope: DispatchAssignmentScopeIdentity,
        status: DispatchAssignmentIntentStatus = DispatchAssignmentIntentStatus.Pending
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
