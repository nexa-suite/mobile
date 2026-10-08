package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionKind
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDispatchDeliveryInstructionMetadataStoreTest {
    @Test
    fun storeRejectsMismatchedFrozenBodyAndRecoversTheOriginalBytes() = runTest {
        val local = FakeScopedMetadataStore()
        val codec = JsonDispatchRequestBodyCodec()
        val scope = DispatchDeliveryInstructionScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        val intent = intent(scope, codec)
        val store = AppDispatchDeliveryInstructionMetadataStore(local, codec)

        assertEquals(
            DispatchDeliveryInstructionMetadataWrite.Conflict,
            store.saveIntent(intent.copy(exactRequestBody = "{}"))
        )
        assertEquals(DispatchDeliveryInstructionMetadataWrite.Saved, store.saveIntent(intent))
        assertEquals(
            DispatchDeliveryInstructionMetadataRead.Available(
                intent.copy(status = DispatchDeliveryInstructionIntentStatus.UnknownOutcome)
            ),
            AppDispatchDeliveryInstructionMetadataStore(local, codec)
                .loadIntent(scope, DELIVERY)
        )
    }

    private fun intent(
        scope: DispatchDeliveryInstructionScopeIdentity,
        codec: JsonDispatchRequestBodyCodec
    ): DispatchDeliveryInstructionIntent {
        val kind = DispatchDeliveryInstructionKind.COLD_CHAIN
        val content = "Keep chilled"
        return DispatchDeliveryInstructionIntent(
            scope = scope,
            deliveryId = DELIVERY,
            expectedDeliveryVersion = 9,
            instructionId = INSTRUCTION,
            kind = kind,
            content = content,
            exactRequestBody = codec.dispatchDeliveryInstructionRequestBody(
                INSTRUCTION,
                kind,
                content
            ),
            idempotencyKey = "instruction-key"
        )
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
        const val DELIVERY = "11111111-1111-4111-8111-111111111111"
        const val INSTRUCTION = "22222222-2222-4222-8222-222222222222"
    }
}
