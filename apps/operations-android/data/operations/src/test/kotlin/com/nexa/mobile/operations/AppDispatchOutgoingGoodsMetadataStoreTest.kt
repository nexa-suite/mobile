package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.data.AppDispatchOutgoingGoodsMetadataStore
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsCommand as OutgoingGoodsCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsCommandType as OutgoingGoodsCommandType
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsIntent as OutgoingGoodsIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsIntentStatus as OutgoingGoodsIntentStatus
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsMetadataRead as OutgoingGoodsMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsMetadataWrite as OutgoingGoodsMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsObservation as OutgoingGoodsObservation
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsScopeIdentity as OutgoingGoodsScopeIdentity
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDispatchOutgoingGoodsMetadataStoreTest {
    @Test
    fun encryptedPurposeAdapterRestoresExactBodyVersionsAndKeyAsUnknownOutcome() = runTest {
        val local = FakeScopedMetadataStore()
        val scope = OutgoingGoodsScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        val command = OutgoingGoodsCommand(
            fulfillmentId = FULFILLMENT,
            expectedFulfillmentVersion = 12,
            physicalAllocationId = ALLOCATION,
            physicalAllocationVersion = 7,
            observations = listOf(OutgoingGoodsObservation(LINE, LOT, BigDecimal("2.50"))),
            idempotencyKey = "outgoing-check-key",
            exactRequestBody = listOf(
                """{"physicalAllocationId":"$ALLOCATION","physicalAllocationVersion":7,""",
                """"observations":[{"physicalAllocationLineId":"$LINE",""",
                """"observedLotId":"$LOT","observedQuantity":2.50}]}"""
            ).joinToString(separator = "")
        )
        val pending = OutgoingGoodsIntent(scope, command)
        val first = AppDispatchOutgoingGoodsMetadataStore(local)
        assertEquals(OutgoingGoodsMetadataWrite.Saved, first.saveIntent(pending))

        val restored = AppDispatchOutgoingGoodsMetadataStore(local)
        val read = restored.loadIntent(scope, FULFILLMENT)
        assertEquals(
            OutgoingGoodsMetadataRead.Available(
                pending.copy(status = OutgoingGoodsIntentStatus.UnknownOutcome)
            ),
            read
        )
        assertEquals(
            OutgoingGoodsMetadataWrite.Conflict,
            restored.saveIntent(pending.copy(command = command.copy(idempotencyKey = "new-key")))
        )
        assertEquals(
            OutgoingGoodsMetadataWrite.Stale,
            restored.clearIntent(scope, FULFILLMENT, "other-key")
        )
        assertEquals(
            OutgoingGoodsMetadataWrite.Saved,
            restored.clearIntent(scope, FULFILLMENT, command.idempotencyKey)
        )
        assertEquals(
            OutgoingGoodsMetadataRead.Available(null),
            restored.loadIntent(scope, FULFILLMENT)
        )
    }

    @Test
    fun discrepancyResolutionPersistsExactReasonAndReferencesAsUnknownOutcome() = runTest {
        val local = FakeScopedMetadataStore()
        val scope = OutgoingGoodsScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        val command = OutgoingGoodsCommand(
            fulfillmentId = FULFILLMENT,
            expectedFulfillmentVersion = 12,
            physicalAllocationId = ALLOCATION,
            physicalAllocationVersion = 7,
            observations = emptyList(),
            idempotencyKey = "outgoing-resolution-key",
            exactRequestBody = listOf(
                """{"physicalAllocationId":"$ALLOCATION","physicalAllocationVersion":7,""",
                """"discrepancyCheckId":"$DISCREPANCY","matchingCheckId":"$MATCH",""",
                """"reason":"Recount confirmed the allocated goods."}"""
            ).joinToString(separator = ""),
            type = OutgoingGoodsCommandType.ResolveDiscrepancy,
            discrepancyCheckId = DISCREPANCY,
            matchingCheckId = MATCH,
            reason = "Recount confirmed the allocated goods."
        )
        val pending = OutgoingGoodsIntent(scope, command)
        val first = AppDispatchOutgoingGoodsMetadataStore(local)
        assertEquals(OutgoingGoodsMetadataWrite.Saved, first.saveIntent(pending))

        val recovered = AppDispatchOutgoingGoodsMetadataStore(local).loadIntent(scope, FULFILLMENT)
        assertEquals(
            OutgoingGoodsMetadataRead.Available(
                pending.copy(status = OutgoingGoodsIntentStatus.UnknownOutcome)
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
