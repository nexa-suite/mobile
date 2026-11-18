package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCacheRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCacheWrite
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkItem
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkPage
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPickingWorkListCacheTest {
    @Test
    fun restoredSessionWithInitialEpochCanPersistPageAndDenial() = runTest {
        val backend = MemoryScopedMetadataStore()
        val cache = AppPickingWorkListCache(backend)

        assertEquals(
            PickingWorkListCacheWrite.Saved,
            cache.saveServerConfirmedPage(authority(), 0, page())
        )
        val reopened = AppPickingWorkListCache(backend)
        val result = reopened.load(authority(), 0) as PickingWorkListCacheRead.Available
        assertEquals(FULFILLMENT_ID, result.value?.items?.single()?.fulfillmentId)

        assertEquals(PickingWorkListCacheWrite.Saved, reopened.markDenied(authority(), 0))
        assertEquals(
            PickingWorkListCacheRead.Unavailable,
            AppPickingWorkListCache(backend).load(authority(), 0)
        )
        assertEquals(
            PickingWorkListCacheWrite.Saved,
            reopened.saveServerConfirmedPage(authority(), 0, page())
        )
        assertTrue(reopened.load(authority(), 0) is PickingWorkListCacheRead.Available)
    }

    @Test
    fun negativeSessionEpochCannotPersistPageOrDenial() = runTest {
        val backend = MemoryScopedMetadataStore()
        val cache = AppPickingWorkListCache(backend)

        assertEquals(
            PickingWorkListCacheWrite.Unavailable,
            cache.saveServerConfirmedPage(authority(), -1, page())
        )
        assertEquals(PickingWorkListCacheWrite.Unavailable, cache.markDenied(authority(), -1))
        assertTrue(backend.values.isEmpty())
    }

    @Test
    fun reconstructedCacheRetainsOnlyAllowlistedPageForSameScope() = runTest {
        val backend = MemoryScopedMetadataStore()
        val cache = AppPickingWorkListCache(backend)
        val projection = page()

        assertEquals(
            PickingWorkListCacheWrite.Saved,
            cache.saveServerConfirmedPage(authority(), 1, projection)
        )
        val encoded = backend.values.getValue(scope.toLocal())
        assertTrue(encoded.contains("orderNumber"))
        assertFalse(encoded.contains("physicalAllocationId"))
        assertFalse(encoded.contains("allocationVersion"))
        assertFalse(encoded.contains(ALLOCATION_ID))

        val reopened = AppPickingWorkListCache(backend)
        val result = reopened.load(authority(), page = 0) as PickingWorkListCacheRead.Available
        assertEquals("ORDER-2048", result.value?.items?.single()?.orderNumber)
        assertEquals(FULFILLMENT_ID, result.value?.items?.single()?.fulfillmentId)
        assertEquals(
            PickingWorkListCacheRead.Available(null),
            reopened.load(authority(scope.copy(workspaceId = "other-workspace")), page = 0)
        )
    }

    @Test
    fun newerServerSnapshotSupersedesOldAndCorruptPayloadFailsClosed() = runTest {
        val backend = MemoryScopedMetadataStore()
        val cache = AppPickingWorkListCache(backend)
        val current = page(asOf = Instant.parse("2026-11-16T12:00:00Z"))

        assertEquals(
            PickingWorkListCacheWrite.Saved,
            cache.saveServerConfirmedPage(authority(), 1, current)
        )
        val sameTimestampChange = current.copy(
            items = current.items.map { it.copy(version = it.version + 1) }
        )
        assertEquals(
            PickingWorkListCacheWrite.Superseded,
            cache.saveServerConfirmedPage(authority(), 1, sameTimestampChange)
        )
        val retained = cache.load(authority(), page = 0) as PickingWorkListCacheRead.Available
        assertEquals(13L, retained.value?.items?.single()?.version)
        assertEquals(
            PickingWorkListCacheWrite.Superseded,
            cache.saveServerConfirmedPage(
                authority(),
                1,
                page(asOf = Instant.parse("2026-11-16T11:59:00Z"), pageNumber = 1)
            )
        )
        assertEquals(
            PickingWorkListCacheWrite.Saved,
            cache.saveServerConfirmedPage(
                authority(),
                1,
                page(asOf = Instant.parse("2026-11-16T12:01:00Z"), pageNumber = 1)
            )
        )
        assertEquals(
            PickingWorkListCacheRead.Available(null),
            cache.load(authority(), page = 0)
        )
        assertTrue(cache.load(authority(), page = 1) is PickingWorkListCacheRead.Available)

        backend.values[scope.toLocal()] = "{not-json"
        assertEquals(PickingWorkListCacheRead.Unavailable, cache.load(authority(), page = 1))
        assertEquals(
            PickingWorkListCacheWrite.Unavailable,
            cache.saveServerConfirmedPage(authority(), 1, current)
        )
        assertEquals("{not-json", backend.values.getValue(scope.toLocal()))
    }

    @Test
    fun denialFenceHidesStoredPagesUntilSuccessfulServerReadReplacesIt() = runTest {
        val backend = MemoryScopedMetadataStore()
        val cache = AppPickingWorkListCache(backend)
        assertEquals(
            PickingWorkListCacheWrite.Saved,
            cache.saveServerConfirmedPage(authority(), 1, page())
        )

        assertEquals(PickingWorkListCacheWrite.Saved, cache.markDenied(authority(), 1))
        assertEquals(PickingWorkListCacheRead.Unavailable, cache.load(authority(), page = 0))
        assertEquals(
            PickingWorkListCacheRead.Unavailable,
            AppPickingWorkListCache(backend).load(authority(), page = 0)
        )

        assertEquals(
            PickingWorkListCacheWrite.Saved,
            cache.saveServerConfirmedPage(
                authority(epoch = 5),
                2,
                page(asOf = Instant.parse("2026-11-16T12:01:00Z"))
            )
        )
        assertTrue(cache.load(authority(epoch = 5), page = 0) is PickingWorkListCacheRead.Available)
    }

    @Test
    fun cachedPageRequiresExactPermissionSetAndAuthorityEpoch() = runTest {
        val backend = MemoryScopedMetadataStore()
        val cache = AppPickingWorkListCache(backend)

        assertEquals(
            PickingWorkListCacheWrite.Saved,
            cache.saveServerConfirmedPage(authority(), 1, page())
        )
        assertEquals(
            PickingWorkListCacheRead.Unavailable,
            cache.load(authority(epoch = 5), page = 0)
        )
        assertEquals(
            PickingWorkListCacheRead.Unavailable,
            cache.load(authority(permissions = setOf("fulfillment.read", "warehouse:write")), 0)
        )

        assertEquals(
            PickingWorkListCacheWrite.Saved,
            cache.saveServerConfirmedPage(
                authority(epoch = 5),
                2,
                page(asOf = Instant.parse("2026-11-16T11:00:00Z"))
            )
        )
        assertTrue(cache.load(authority(epoch = 5), page = 0) is PickingWorkListCacheRead.Available)
        assertEquals(
            PickingWorkListCacheRead.Unavailable,
            cache.load(authority(), page = 0)
        )
    }

    private fun page(asOf: Instant = Instant.parse("2026-11-16T12:00:00Z"), pageNumber: Int = 0) =
        PickingWorkPage(
            items = listOf(
                PickingWorkItem(
                    fulfillmentId = FULFILLMENT_ID,
                    salesOrderId = SALES_ORDER_ID,
                    status = "PICKED",
                    version = 13,
                    physicalAllocationId = ALLOCATION_ID,
                    allocationVersion = 8,
                    lineCount = 2,
                    orderNumber = "ORDER-2048"
                )
            ),
            page = pageNumber,
            size = 25,
            totalItems = 26,
            asOf = asOf
        )

    private fun PickingScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun authority(
        scope: PickingScopeIdentity = AppPickingWorkListCacheTest.scope,
        epoch: Long = 4,
        permissions: Set<String> = setOf("fulfillment.read")
    ) = PickingAuthority(
        userId = scope.userId,
        tenantId = scope.tenantId,
        workspaceId = scope.workspaceId,
        membershipId = scope.membershipId,
        permissions = permissions,
        authorityEpoch = epoch
    )

    private class MemoryScopedMetadataStore : ScopedMetadataStore {
        val values = mutableMapOf<ScopedMetadataScope, String>()

        override suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead =
            ScopedMetadataRead.Value(values[scope])

        override suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean {
            values[scope] = payload
            return true
        }

        override suspend fun clear(scope: ScopedMetadataScope): Boolean {
            values.remove(scope)
            return true
        }
    }

    private companion object {
        const val USER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val TENANT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413002"
        const val WORKSPACE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413003"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413004"
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413005"
        const val SALES_ORDER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413006"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413007"
        val scope = PickingScopeIdentity(USER_ID, TENANT_ID, WORKSPACE_ID, MEMBERSHIP_ID)
    }
}
