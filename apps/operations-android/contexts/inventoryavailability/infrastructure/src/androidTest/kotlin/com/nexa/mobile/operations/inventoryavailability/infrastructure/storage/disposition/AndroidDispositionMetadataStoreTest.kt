package com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.disposition

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
class AndroidDispositionMetadataStoreTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var keyAlias: String
    private lateinit var scope: DispositionMetadataScope
    private lateinit var store: AndroidDispositionMetadataStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        directory = File(context.noBackupFilesDir, "disposition-test-$suffix")
        keyAlias = "com.nexa.mobile.operations.test.disposition.$suffix"
        scope =
            DispositionMetadataScope(
                "user-$suffix",
                "tenant-$suffix",
                "workspace-$suffix",
                "member-$suffix"
            )
        store = AndroidDispositionMetadataStore(context, directory.name, keyAlias)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    @Test
    fun reconstructedStoreRetainsExactPendingCommandAndUnconfirmedDraft() = runBlocking {
        val intent = pendingIntent(scope)
        assertEquals(DispositionMetadataWrite.Saved, store.saveDraft(scope, draft()))
        assertEquals(DispositionMetadataWrite.Saved, store.saveIntent(intent))
        val file = directory.listFiles()!!.single()
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(intent.idempotencyKey))

        val reconstructed = AndroidDispositionMetadataStore(context, directory.name, keyAlias)
        assertEquals(
            intent,
            (reconstructed.loadIntent(scope) as DispositionMetadataRead.Available).value
        )
        assertEquals(
            draft(),
            (reconstructed.loadDraft(scope) as DispositionMetadataRead.Available).value
        )
        assertTrue(directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(file.name.contains(scope.userId))
    }

    @Test
    fun scopeIsolationAndCiphertextCopyFailClosed() = runBlocking {
        assertEquals(DispositionMetadataWrite.Saved, store.saveIntent(pendingIntent(scope)))
        val other = scope.copy(workspaceId = "different-workspace")
        assertEquals(DispositionMetadataRead.Available(null), store.loadIntent(other))

        val source = recordFile(scope)
        val target = recordFile(other)
        target.parentFile!!.mkdirs()
        target.writeBytes(source.readBytes())
        assertEquals(DispositionMetadataRead.Unavailable, store.loadIntent(other))
    }

    @Test
    fun corruptedRecordAndMissingKeystoreKeyCannotBeOverwritten() = runBlocking {
        assertEquals(DispositionMetadataWrite.Saved, store.saveDraft(scope, draft()))
        val file = recordFile(scope)
        val original = file.readBytes()
        val corrupt = original.clone().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        file.writeBytes(corrupt)

        assertEquals(DispositionMetadataRead.Unavailable, store.loadDraft(scope))
        assertEquals(
            DispositionMetadataWrite.Unavailable,
            store.saveDraft(scope, draft("replacement"))
        )
        assertTrue(file.readBytes().contentEquals(corrupt))

        file.writeBytes(original)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        assertEquals(DispositionMetadataRead.Unavailable, store.loadDraft(scope))
        assertEquals(
            DispositionMetadataWrite.Unavailable,
            store.saveDraft(scope, draft("replacement"))
        )
        assertTrue(file.readBytes().contentEquals(original))
    }

    @Test
    fun expectedKeyClearCannotDeleteNewerIntent() = runBlocking {
        val old = pendingIntent(scope, "old-key")
        val newer = pendingIntent(scope, "new-key")
        assertEquals(DispositionMetadataWrite.Saved, store.saveIntent(old))
        assertEquals(DispositionMetadataWrite.Saved, store.clearIntent(scope, old.idempotencyKey))
        assertEquals(DispositionMetadataWrite.Saved, store.saveIntent(newer))

        assertEquals(DispositionMetadataWrite.Stale, store.clearIntent(scope, old.idempotencyKey))
        assertEquals(newer, (store.loadIntent(scope) as DispositionMetadataRead.Available).value)
    }

    private fun recordFile(scope: DispositionMetadataScope) =
        File(directory, "${DispositionScopeBinding.fileKey(scope)}.record")

    private fun draft(reason: String = "disconnected note") = DispositionDraftRecord(
        "11111111-1111-4111-8111-111111111111",
        StoredLotDisposition.HOLD,
        reason
    )

    private fun pendingIntent(scope: DispositionMetadataScope, key: String = "stable-key") =
        DispositionIntentRecord(
            scope,
            key,
            DispositionCommandPayload(
                "11111111-1111-4111-8111-111111111111",
                StoredLotDisposition.RELEASE,
                "temperature check confirmed",
                23
            ),
            DispositionIntentStatus.Pending
        )
}
