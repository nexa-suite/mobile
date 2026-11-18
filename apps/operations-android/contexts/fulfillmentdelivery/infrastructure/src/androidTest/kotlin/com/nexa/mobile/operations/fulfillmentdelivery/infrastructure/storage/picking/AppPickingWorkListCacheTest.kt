package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.storage.picking

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCacheRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCacheWrite
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkItem
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkPage
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.AppPickingWorkListCache
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPickingWorkListCacheTest {
    @Test
    fun reconstructedCacheSurvivesStoreReopenEncryptedAndBoundToFullScope() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val scope =
            PickingScopeIdentity(
                "user-$suffix",
                "tenant-$suffix",
                "workspace-$suffix",
                "member-$suffix"
            )
        val scoped = ScopedMetadataScope(
            scope.userId,
            scope.tenantId,
            scope.workspaceId,
            scope.membershipId
        )
        val first = AppPickingWorkListCache(
            AndroidScopedMetadataStore(context, ScopedMetadataPurpose.PickingWorkListCache)
        )
        val directory = File(
            context.noBackupFilesDir,
            "scoped-metadata/${ScopedMetadataPurpose.PickingWorkListCache.name}"
        )
        val existingRecords = directory.listFiles()?.map(File::getName)?.toSet().orEmpty()
        assertEquals(
            PickingWorkListCacheWrite.Saved,
            first.saveServerConfirmedPage(
                authority(scope),
                sessionEpoch = 1,
                page = page()
            )
        )

        val record = directory.listFiles()!!.single { it.name !in existingRecords }
        val ciphertext = record.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(ciphertext.contains(FULFILLMENT_ID))
        assertFalse(ciphertext.contains("ORDER-2048"))
        assertFalse(record.name.contains(scope.tenantId))

        val reopened = AppPickingWorkListCache(
            AndroidScopedMetadataStore(context, ScopedMetadataPurpose.PickingWorkListCache)
        )
        val cached = reopened.load(authority(scope), page = 0) as PickingWorkListCacheRead.Available
        assertEquals(FULFILLMENT_ID, cached.value?.items?.single()?.fulfillmentId)
        assertEquals("ORDER-2048", cached.value?.items?.single()?.orderNumber)
        assertEquals(
            PickingWorkListCacheRead.Available(null),
            reopened.load(authority(scope.copy(workspaceId = "other-workspace")), page = 0)
        )

        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.PickingWorkListCache)
            .clear(scoped)
        Unit
    }

    private fun page() = PickingWorkPage(
        items = listOf(
            PickingWorkItem(
                fulfillmentId = FULFILLMENT_ID,
                salesOrderId = SALES_ORDER_ID,
                status = "ALLOCATED",
                version = 12,
                physicalAllocationId = ALLOCATION_ID,
                allocationVersion = 8,
                lineCount = 2,
                orderNumber = "ORDER-2048"
            )
        ),
        page = 0,
        size = 25,
        totalItems = 1,
        asOf = Instant.parse("2026-11-16T12:00:00Z")
    )

    private fun authority(scope: PickingScopeIdentity) = PickingAuthority(
        userId = scope.userId,
        tenantId = scope.tenantId,
        workspaceId = scope.workspaceId,
        membershipId = scope.membershipId,
        permissions = setOf("fulfillment.read"),
        authorityEpoch = 1
    )

    private companion object {
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413005"
        const val SALES_ORDER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413006"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413007"
    }
}
