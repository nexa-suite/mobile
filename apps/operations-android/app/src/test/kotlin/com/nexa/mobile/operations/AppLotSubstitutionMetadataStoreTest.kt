package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionIntent
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionIntentStatus
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionMetadataRead
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionWork
import com.nexa.mobile.operations.feature.warehouse.PickingScopeIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLotSubstitutionMetadataStoreTest {
    @Test
    fun reconstructionKeepsFrozenBodyAndScopeAndGuardsReplacementAndClear() = runTest {
        val backend = MemoryBackend()
        val first = AppLotSubstitutionMetadataStore(backend)
        val original = intent(scope)

        assertEquals(LotSubstitutionMetadataWrite.Saved, first.freeze(original))
        assertEquals(LotSubstitutionMetadataWrite.Saved, first.markUnknown(scope, original.idempotencyKey))

        val reconstructed = AppLotSubstitutionMetadataStore(backend)
        val restored = (reconstructed.load(scope) as LotSubstitutionMetadataRead.Available).value
        assertEquals(original.frozenBody, restored?.frozenBody)
        assertEquals(original.idempotencyKey, restored?.idempotencyKey)
        assertEquals(LotSubstitutionIntentStatus.UnknownOutcome, restored?.status)
        assertTrue(reconstructed.load(otherScope) is LotSubstitutionMetadataRead.Available)
        assertNull((reconstructed.load(otherScope) as LotSubstitutionMetadataRead.Available).value)

        val changed = original.copy(frozenBody = original.frozenBody.replace("4.000", "3.000"))
        assertEquals(LotSubstitutionMetadataWrite.Unavailable, reconstructed.freeze(changed))
        assertEquals(LotSubstitutionMetadataWrite.Unavailable, reconstructed.clear(scope, "stale-key"))
        val unchanged = (reconstructed.load(scope) as LotSubstitutionMetadataRead.Available).value
        assertEquals(original.frozenBody, unchanged?.frozenBody)
        assertEquals(original.idempotencyKey, unchanged?.idempotencyKey)
    }

    @Test
    fun corruptMetadataFailsClosedInsteadOfAllowingAReplacementIntent() = runTest {
        val backend = MemoryBackend().apply { records[backendScope(scope)] = "{broken" }
        val store = AppLotSubstitutionMetadataStore(backend)

        assertEquals(LotSubstitutionMetadataRead.Unavailable, store.load(scope))
        assertEquals(LotSubstitutionMetadataWrite.Unavailable, store.freeze(intent(scope)))
        assertEquals("{broken", backend.records[backendScope(scope)])
    }

    private class MemoryBackend : LotSubstitutionMetadataBackend {
        val records = mutableMapOf<ScopedMetadataScope, String>()
        override suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead =
            ScopedMetadataRead.Value(records[scope])
        override suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean {
            records[scope] = payload
            return true
        }
        override suspend fun clear(scope: ScopedMetadataScope): Boolean = records.remove(scope) != null
    }

    private fun intent(scope: PickingScopeIdentity) = LotSubstitutionIntent(
        scope,
        KEY,
        LotSubstitutionWork(
            FULFILLMENT_ID, ALLOCATION_ID, LINE_ID, SKU_ID, "CAT-0042", EXPECTED_LOT_ID,
            WAREHOUSE_ID, ZONE_ID, "4.000", "EA", 8
        ),
        ALTERNATIVE_LOT_ID,
        "Expected lot could not supply prepared work",
        """{"fulfillmentId":"$FULFILLMENT_ID","allocationId":"$ALLOCATION_ID","physicalAllocationLineId":"$LINE_ID","expectedLotId":"$EXPECTED_LOT_ID","alternativeLotId":"$ALTERNATIVE_LOT_ID","quantity":4.000,"unit":"EA","reason":"Expected lot could not supply prepared work"}""",
        LotSubstitutionIntentStatus.Pending
    )

    private fun backendScope(scope: PickingScopeIdentity) =
        ScopedMetadataScope(scope.userId, scope.tenantId, scope.workspaceId, scope.membershipId)

    private companion object {
        val scope = PickingScopeIdentity(
            "00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000002",
            "00000000-0000-4000-8000-000000000003",
            "00000000-0000-4000-8000-000000000004"
        )
        val otherScope = scope.copy(membershipId = "00000000-0000-4000-8000-000000000099")
        const val WAREHOUSE_ID = "00000000-0000-4000-8000-000000000005"
        const val ZONE_ID = "00000000-0000-4000-8000-000000000006"
        const val SKU_ID = "00000000-0000-4000-8000-000000000007"
        const val EXPECTED_LOT_ID = "00000000-0000-4000-8000-000000000008"
        const val ALTERNATIVE_LOT_ID = "00000000-0000-4000-8000-000000000009"
        const val ALLOCATION_ID = "00000000-0000-4000-8000-000000000010"
        const val LINE_ID = "00000000-0000-4000-8000-000000000011"
        const val FULFILLMENT_ID = "00000000-0000-4000-8000-000000000012"
        const val KEY = "00000000-0000-4000-8000-000000000013"
    }
}
