package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDispatchHandoverMetadataStoreTest {
    @Test
    fun storeRejectsCommandWhoseFrozenBodyDoesNotMatchItsFields() = runTest {
        val local = FakeScopedMetadataStore()
        val scope = DispatchOutgoingGoodsScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        val codec = JsonDispatchRequestBodyCodec()
        val command = command(codec)
        val altered = command.copy(exactRequestBody = "{}")
        val store = AppDispatchHandoverMetadataStore(local, codec)

        assertEquals(
            DispatchHandoverMetadataWrite.Conflict,
            store.saveIntent(DispatchHandoverIntent(scope, altered))
        )
        assertEquals(
            DispatchHandoverMetadataRead.Available(null),
            store.loadIntent(scope, FULFILLMENT)
        )
    }

    private fun command(codec: JsonDispatchRequestBodyCodec): DispatchHandoverCommand {
        val draft = DispatchHandoverCommand(
            fulfillmentId = FULFILLMENT,
            expectedFulfillmentVersion = 8,
            physicalAllocationId = ALLOCATION,
            physicalAllocationVersion = 6,
            driverAssignmentId = ASSIGNMENT,
            driverAssignmentVersion = 4,
            outgoingGoodsCheckId = CHECK,
            idempotencyKey = "handover-key",
            exactRequestBody = ""
        )
        return draft.copy(exactRequestBody = codec.dispatchHandoverRequestBody(draft))
    }

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
        const val MEMBERSHIP = "member-one"
        const val FULFILLMENT = "11111111-1111-4111-8111-111111111111"
        const val ALLOCATION = "22222222-2222-4222-8222-222222222222"
        const val ASSIGNMENT = "33333333-3333-4333-8333-333333333333"
        const val CHECK = "44444444-4444-4444-8444-444444444444"
    }
}
