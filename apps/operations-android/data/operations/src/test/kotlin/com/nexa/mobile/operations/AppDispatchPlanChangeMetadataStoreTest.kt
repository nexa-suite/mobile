package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.data.AppDispatchPlanChangeMetadataStore
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeIntentStatus as PlanChangeIntentStatus
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeMetadataRead as PlanChangeMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeMetadataWrite as PlanChangeMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeScopeIdentity as PlanChangeScopeIdentity
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDispatchPlanChangeMetadataStoreTest {
    @Test
    fun pendingExactBodyAndVersionsRestoreAsManualUnknownOutcome() = runTest {
        val records = FakeScopedMetadataStore()
        val scope = PlanChangeScopeIdentity(USER, TENANT, WORKSPACE, ACTOR)
        val pending = intent(scope, PlanChangeIntentStatus.Pending)
        assertEquals(
            PlanChangeMetadataWrite.Saved,
            AppDispatchPlanChangeMetadataStore(records).saveIntent(pending)
        )

        val restored = AppDispatchPlanChangeMetadataStore(records)
        val read = restored.loadIntent(scope, FULFILLMENT)
        assertEquals(
            PlanChangeMetadataRead.Available(
                pending.copy(status = PlanChangeIntentStatus.UnknownOutcome)
            ),
            read
        )
        assertEquals(
            PlanChangeMetadataWrite.Conflict,
            restored.saveIntent(pending.copy(requestBody = "{}"))
        )
        assertEquals(
            PlanChangeMetadataWrite.Stale,
            restored.clearIntent(scope, FULFILLMENT, "different-key")
        )
        assertEquals(
            PlanChangeMetadataWrite.Saved,
            restored.clearIntent(scope, FULFILLMENT, pending.idempotencyKey)
        )
        assertEquals(
            PlanChangeMetadataRead.Available(null),
            restored.loadIntent(scope, FULFILLMENT)
        )
    }

    private fun intent(scope: PlanChangeScopeIdentity, status: PlanChangeIntentStatus) =
        DispatchPlanChangeIntent(
            scope = scope,
            fulfillmentId = FULFILLMENT,
            expectedFulfillmentVersion = 13,
            expectedAssignmentId = ASSIGNMENT,
            expectedAssignmentVersion = 13,
            physicalAllocationId = ALLOCATION,
            physicalAllocationVersion = 7,
            requestedMembershipId = null,
            requestedDispatchAt = Instant.parse("2026-10-01T15:30:00Z"),
            resultResponsibleMembershipId = DRIVER,
            resultPlannedDispatchAt = Instant.parse("2026-10-01T15:30:00Z"),
            requestBody = "{\"original\":true}",
            idempotencyKey = "same-key",
            status = status
        )

    private class FakeScopedMetadataStore : ScopedMetadataStore {
        private val records = mutableMapOf<ScopedMetadataScope, String>()

        override suspend fun load(scope: ScopedMetadataScope) =
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
        const val ACTOR = "actor-membership-id"
        const val FULFILLMENT = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val ASSIGNMENT = "b8c24a46-57d9-4f64-8fa7-6a641b413105"
        const val ALLOCATION = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val DRIVER = "b8c24a46-57d9-4f64-8fa7-6a641b413104"
    }
}
