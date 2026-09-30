package com.nexa.mobile.operations.core.local.receiving

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
class AndroidReceivingMetadataStoreTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var keyAlias: String
    private lateinit var scope: ReceivingMetadataScope
    private lateinit var store: AndroidReceivingMetadataStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        directory = File(context.noBackupFilesDir, "receiving-test-$suffix")
        keyAlias = "com.nexa.mobile.operations.test.receiving.$suffix"
        scope =
            ReceivingMetadataScope(
                "user-$suffix",
                "tenant-$suffix",
                "workspace-$suffix",
                "member-$suffix"
            )
        store = AndroidReceivingMetadataStore(context, directory.name, keyAlias)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    @Test
    fun reconstructedStoreRetainsExactPendingPayloadAndDraft() = runBlocking {
        val intent = pendingIntent(scope)
        assertEquals(ReceivingMetadataWrite.Saved, store.saveDraft(scope, draft()))
        assertEquals(ReceivingMetadataWrite.Saved, store.saveIntent(intent))
        assertFalse(
            directory.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1)
                .contains(intent.idempotencyKey)
        )

        val reconstructedStore = AndroidReceivingMetadataStore(context, directory.name, keyAlias)

        assertEquals(
            intent,
            (reconstructedStore.loadIntent(scope) as ReceivingMetadataRead.Available).value
        )
        assertEquals(
            draft(),
            (reconstructedStore.loadDraft(scope) as ReceivingMetadataRead.Available).value
        )
        assertTrue(directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(directory.listFiles()!!.single().name.contains(scope.userId))
    }

    @Test
    fun recordsAreIsolatedByFullScopeAndCopiedCiphertextCannotCrossScope() = runBlocking {
        val otherScope = scope.copy(workspaceId = "other-workspace")
        assertEquals(ReceivingMetadataWrite.Saved, store.saveIntent(pendingIntent(scope)))
        assertEquals(ReceivingMetadataRead.Available(null), store.loadIntent(otherScope))

        val sourceFile = recordFile(scope)
        val targetFile = recordFile(otherScope)
        targetFile.parentFile!!.mkdirs()
        targetFile.writeBytes(sourceFile.readBytes())
        assertEquals(ReceivingMetadataRead.Unavailable, store.loadIntent(otherScope))
    }

    @Test
    fun corruptedRecordAndMissingKeyFailClosedWithoutOverwritingBytes() = runBlocking {
        assertEquals(ReceivingMetadataWrite.Saved, store.saveDraft(scope, draft()))
        val file = recordFile(scope)
        val original = file.readBytes()
        val corrupt = original.clone().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        file.writeBytes(corrupt)

        assertEquals(ReceivingMetadataRead.Unavailable, store.loadDraft(scope))
        assertEquals(
            ReceivingMetadataWrite.Unavailable,
            store.saveDraft(scope, draft(notes = "replacement"))
        )
        assertTrue(file.readBytes().contentEquals(corrupt))

        file.writeBytes(original)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        assertEquals(ReceivingMetadataRead.Unavailable, store.loadDraft(scope))
        assertEquals(
            ReceivingMetadataWrite.Unavailable,
            store.saveDraft(scope, draft(notes = "replacement"))
        )
        assertTrue(file.readBytes().contentEquals(original))
        assertFalse(
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }.containsAlias(keyAlias)
        )
    }

    @Test
    fun staleExpectedKeyCannotClearNewerFrozenIntent() = runBlocking {
        val first = pendingIntent(scope, "old-key")
        val newer = pendingIntent(scope, "new-key")
        assertEquals(ReceivingMetadataWrite.Saved, store.saveIntent(first))
        assertEquals(ReceivingMetadataWrite.Saved, store.clearIntent(scope, first.idempotencyKey))
        assertEquals(ReceivingMetadataWrite.Saved, store.saveIntent(newer))

        assertEquals(ReceivingMetadataWrite.Stale, store.clearIntent(scope, first.idempotencyKey))
        assertEquals(newer, (store.loadIntent(scope) as ReceivingMetadataRead.Available).value)
    }

    private fun recordFile(scope: ReceivingMetadataScope): File =
        File(directory, "${ReceivingScopeBinding.fileKey(scope)}.record")

    private fun draft(notes: String? = null) = ReceivingDraftMetadataRecord(
        selectedProduct = ReceivingProductReferenceMetadata(
            "CAT-42",
            null,
            "Bolts",
            "BOLT-42",
            "box"
        ),
        warehouseId = "warehouse-7",
        zoneId = "zone-3",
        batchNumber = "lot-100",
        expirationDateText = "2027-02-03",
        quantityText = "12.50",
        unit = "box",
        temperatureReadingText = "4.5",
        notes = notes
    )

    private fun pendingIntent(scope: ReceivingMetadataScope, key: String = "idem-key-1") =
        ReceivingIntentMetadataRecord(
            scope = scope,
            idempotencyKey = key,
            payload = ReceivingIntentPayload(
                warehouseId = "warehouse-7",
                zoneId = "zone-3",
                catalogItemId = "CAT-42",
                skuId = "8b5ce96c-6dfc-47c0-933a-ae24bba75d29",
                batchNumber = "lot-100",
                expirationDate = "2027-02-03",
                quantity = "12.50",
                unit = "box",
                temperatureReading = "4.5",
                notes = "fragile"
            ),
            status = ReceivingIntentStatus.Pending
        )
}
