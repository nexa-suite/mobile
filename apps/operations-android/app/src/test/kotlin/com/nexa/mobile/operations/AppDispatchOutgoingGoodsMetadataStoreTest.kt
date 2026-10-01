package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCommandType
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsIntentStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsObservation
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsScopeIdentity
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDispatchOutgoingGoodsMetadataStoreTest {
    @Test
    fun encryptedPurposeAdapterRestoresExactBodyVersionsAndKeyAsUnknownOutcome() = runTest {
        val local = FakeScopedMetadataStore()
        val scope = DispatchOutgoingGoodsScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        val command = DispatchOutgoingGoodsCommand(
            fulfillmentId = FULFILLMENT,
            expectedFulfillmentVersion = 12,
            physicalAllocationId = ALLOCATION,
            physicalAllocationVersion = 7,
            observations = listOf(DispatchOutgoingGoodsObservation(LINE, LOT, BigDecimal("2.50"))),
            idempotencyKey = "outgoing-check-key",
            exactRequestBody = """{"physicalAllocationId":"$ALLOCATION","physicalAllocationVersion":7,"observations":[{"physicalAllocationLineId":"$LINE","observedLotId":"$LOT","observedQuantity":2.50}]}"""
        )
        val pending = DispatchOutgoingGoodsIntent(scope, command)
        val first = AppDispatchOutgoingGoodsMetadataStore(local)
        assertEquals(DispatchOutgoingGoodsMetadataWrite.Saved, first.saveIntent(pending))

        val restored = AppDispatchOutgoingGoodsMetadataStore(local)
        val read = restored.loadIntent(scope, FULFILLMENT)
        assertEquals(
            DispatchOutgoingGoodsMetadataRead.Available(
                pending.copy(status = DispatchOutgoingGoodsIntentStatus.UnknownOutcome)
            ),
            read
        )
        assertEquals(
            DispatchOutgoingGoodsMetadataWrite.Conflict,
            restored.saveIntent(pending.copy(command = command.copy(idempotencyKey = "new-key")))
        )
        assertEquals(
            DispatchOutgoingGoodsMetadataWrite.Stale,
            restored.clearIntent(scope, FULFILLMENT, "other-key")
        )
        assertEquals(
            DispatchOutgoingGoodsMetadataWrite.Saved,
            restored.clearIntent(scope, FULFILLMENT, command.idempotencyKey)
        )
        assertEquals(
            DispatchOutgoingGoodsMetadataRead.Available(null),
            restored.loadIntent(scope, FULFILLMENT)
        )
    }

    @Test
    fun discrepancyResolutionPersistsExactReasonAndReferencesAsUnknownOutcome() = runTest {
        val local = FakeScopedMetadataStore()
        val scope = DispatchOutgoingGoodsScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        val command = DispatchOutgoingGoodsCommand(
            fulfillmentId = FULFILLMENT,
            expectedFulfillmentVersion = 12,
            physicalAllocationId = ALLOCATION,
            physicalAllocationVersion = 7,
            observations = emptyList(),
            idempotencyKey = "outgoing-resolution-key",
            exactRequestBody = """{"physicalAllocationId":"$ALLOCATION","physicalAllocationVersion":7,"discrepancyCheckId":"$DISCREPANCY","matchingCheckId":"$MATCH","reason":"Recount confirmed the allocated goods."}""",
            type = DispatchOutgoingGoodsCommandType.ResolveDiscrepancy,
            discrepancyCheckId = DISCREPANCY,
            matchingCheckId = MATCH,
            reason = "Recount confirmed the allocated goods."
        )
        val pending = DispatchOutgoingGoodsIntent(scope, command)
        val first = AppDispatchOutgoingGoodsMetadataStore(local)
        assertEquals(DispatchOutgoingGoodsMetadataWrite.Saved, first.saveIntent(pending))

        val recovered = AppDispatchOutgoingGoodsMetadataStore(local).loadIntent(scope, FULFILLMENT)
        assertEquals(
            DispatchOutgoingGoodsMetadataRead.Available(
                pending.copy(status = DispatchOutgoingGoodsIntentStatus.UnknownOutcome)
            ),
            recovered
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
        const val MEMBERSHIP = "actor-membership-id"
        const val FULFILLMENT = "11111111-1111-4111-8111-111111111111"
        const val ALLOCATION = "22222222-2222-4222-8222-222222222222"
        const val LINE = "33333333-3333-4333-8333-333333333333"
        const val LOT = "55555555-5555-4555-8555-555555555555"
        const val DISCREPANCY = "88888888-8888-4888-8888-888888888888"
        const val MATCH = "77777777-7777-4777-8777-777777777777"
    }
}
