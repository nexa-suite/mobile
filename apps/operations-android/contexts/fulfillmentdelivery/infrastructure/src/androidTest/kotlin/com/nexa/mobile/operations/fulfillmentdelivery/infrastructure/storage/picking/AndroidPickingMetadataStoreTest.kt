package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.storage.picking

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPickingMetadataStoreTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var keyAlias: String
    private lateinit var scope: PickingMetadataScope
    private lateinit var store: AndroidPickingMetadataStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        directory = File(context.noBackupFilesDir, "picking-test-$suffix")
        keyAlias = "com.nexa.mobile.operations.test.picking.$suffix"
        scope =
            PickingMetadataScope(
                "user-$suffix",
                "tenant-$suffix",
                "workspace-$suffix",
                "member-$suffix"
            )
        store = AndroidPickingMetadataStore(context, directory.name, keyAlias)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    @Test
    fun reconstructedStoreRetainsEncryptedFrozenCommandAsUnknownOutcome() = runBlocking {
        val intent = pendingIntent(scope)
        assertEquals(PickingMetadataWrite.Saved, store.saveIntent(intent))
        val ciphertext = directory.listFiles()!!.single().readBytes()
        val rendered = ciphertext.toString(Charsets.ISO_8859_1)
        assertFalse(rendered.contains(intent.idempotencyKey))
        assertFalse(rendered.contains(intent.command.fulfillmentId))
        assertFalse(rendered.contains(scope.tenantId))
        assertFalse(rendered.contains("fulfillment.manage"))
        assertFalse(rendered.contains("warehouse:write"))

        val reconstructed = AndroidPickingMetadataStore(context, directory.name, keyAlias)
        val restored = (reconstructed.loadIntent(scope) as PickingMetadataRead.Available).value!!
        assertEquals(PickingIntentStatus.UnknownOutcome, restored.status)
        assertEquals(intent.idempotencyKey, restored.idempotencyKey)
        assertEquals(intent.command, restored.command)
        assertTrue(directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(directory.listFiles()!!.single().name.contains(scope.userId))
    }

    @Test
    fun copiedCiphertextCannotCrossFullScope() = runBlocking {
        assertEquals(PickingMetadataWrite.Saved, store.saveIntent(pendingIntent(scope)))
        val otherScope = scope.copy(workspaceId = "workspace-other")
        assertEquals(PickingMetadataRead.Available(null), store.loadIntent(otherScope))
        val source = recordFile(scope)
        val target = recordFile(otherScope)
        target.parentFile!!.mkdirs()
        target.writeBytes(source.readBytes())

        assertEquals(PickingMetadataRead.Unavailable, store.loadIntent(otherScope))
    }

    private fun recordFile(scope: PickingMetadataScope): File =
        File(directory, "${PickingScopeBinding.fileKey(scope)}.record")

    private fun pendingIntent(scope: PickingMetadataScope) = PickingIntentMetadataRecord(
        scope = scope,
        idempotencyKey = "pick-key-sensitive-01",
        command = PickingCommandRecord.Confirm(
            fulfillmentId = "fulfillment-sensitive-7",
            expectedFulfillmentVersion = 12,
            expectedAllocationVersion = 5,
            fulfillmentLineId = "line-1",
            skuId = "sku-2",
            physicalAllocationLineId = "allocation-line-3",
            lotId = "lot-sensitive-9",
            warehouseId = "warehouse-4",
            quantity = "1.250",
            unit = "box"
        ),
        status = PickingIntentStatus.Pending
    )
}
