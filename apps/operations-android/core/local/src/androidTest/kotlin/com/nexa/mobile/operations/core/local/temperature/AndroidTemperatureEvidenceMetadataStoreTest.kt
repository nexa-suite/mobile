package com.nexa.mobile.operations.core.local.temperature

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
class AndroidTemperatureEvidenceMetadataStoreTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var keyAlias: String
    private lateinit var scope: TemperatureMetadataScope
    private lateinit var store: AndroidTemperatureEvidenceMetadataStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        directory = File(context.noBackupFilesDir, "temperature-evidence-test-$suffix")
        keyAlias = "com.nexa.mobile.operations.test.temperature.$suffix"
        scope =
            TemperatureMetadataScope(
                "user-$suffix",
                "tenant-$suffix",
                "workspace-$suffix",
                "member-$suffix"
            )
        store = AndroidTemperatureEvidenceMetadataStore(context, directory.name, keyAlias)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    @Test
    fun reconstructedStoreRetainsExactKeyPayloadAndCiphertextDoesNotRevealIt() = runBlocking {
        val pending = intent(scope)
        assertEquals(TemperatureMetadataWrite.Saved, store.saveDraft(scope, draft()))
        assertEquals(TemperatureMetadataWrite.Saved, store.saveIntent(pending))
        val file = recordFile(scope)
        val ciphertext = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(ciphertext.contains(pending.idempotencyKey))
        assertFalse(ciphertext.contains(pending.payload.subjectId))
        assertTrue(directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))

        val reconstructed =
            AndroidTemperatureEvidenceMetadataStore(context, directory.name, keyAlias)

        assertEquals(
            pending,
            (reconstructed.loadIntent(scope) as TemperatureMetadataRead.Available).value
        )
        assertEquals(
            draft(),
            (reconstructed.loadDraft(scope) as TemperatureMetadataRead.Available).value
        )
        assertFalse(file.name.contains(scope.userId))
    }

    @Test
    fun scopeIsolationAndCopiedCiphertextCannotCrossTheAuthenticatedScope() = runBlocking {
        val otherScope = scope.copy(workspaceId = "other-workspace")
        assertEquals(TemperatureMetadataWrite.Saved, store.saveIntent(intent(scope)))
        assertEquals(TemperatureMetadataRead.Available(null), store.loadIntent(otherScope))

        val target = recordFile(otherScope)
        target.parentFile!!.mkdirs()
        target.writeBytes(recordFile(scope).readBytes())
        assertEquals(TemperatureMetadataRead.Unavailable, store.loadIntent(otherScope))
    }

    @Test
    fun corruptionAndMissingKeystoreKeyFailClosedWithoutOverwritingPendingBytes() = runBlocking {
        assertEquals(TemperatureMetadataWrite.Saved, store.saveIntent(intent(scope)))
        val file = recordFile(scope)
        val original = file.readBytes()
        val corrupt = original.clone().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        file.writeBytes(corrupt)

        assertEquals(TemperatureMetadataRead.Unavailable, store.loadIntent(scope))
        assertEquals(
            TemperatureMetadataWrite.Unavailable,
            store.clearIntent(scope, "temperature-key")
        )
        assertTrue(file.readBytes().contentEquals(corrupt))

        file.writeBytes(original)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        assertEquals(TemperatureMetadataRead.Unavailable, store.loadIntent(scope))
        assertEquals(
            TemperatureMetadataWrite.Unavailable,
            store.saveDraft(scope, draft(value = "replacement"))
        )
        assertTrue(file.readBytes().contentEquals(original))
    }

    @Test
    fun staleExpectedKeyCannotClearNewerPendingIntent() = runBlocking {
        val first = intent(scope, "old-key")
        val newer = intent(scope, "new-key")
        assertEquals(TemperatureMetadataWrite.Saved, store.saveIntent(first))
        assertEquals(TemperatureMetadataWrite.Saved, store.clearIntent(scope, first.idempotencyKey))
        assertEquals(TemperatureMetadataWrite.Saved, store.saveIntent(newer))

        assertEquals(TemperatureMetadataWrite.Stale, store.clearIntent(scope, first.idempotencyKey))
        assertEquals(
            newer,
            (store.loadIntent(scope) as TemperatureMetadataRead.Available).value
        )
    }

    private fun recordFile(recordScope: TemperatureMetadataScope): File =
        File(directory, "${TemperatureMetadataScopeBinding.fileKey(recordScope)}.record")

    private fun draft(value: String = "-18.765") = TemperatureEvidenceDraftRecord(
        StoredTemperatureSubjectType.LOT,
        SUBJECT_ID,
        value,
        StoredTemperatureUnit.CELSIUS,
        "2026-09-30T15:22:33Z"
    )

    private fun intent(recordScope: TemperatureMetadataScope, key: String = "temperature-key") =
        TemperatureEvidenceIntentRecord(
            recordScope,
            key,
            TemperatureEvidenceCommandPayload(
                StoredTemperatureSubjectType.LOT,
                SUBJECT_ID,
                "-18.765432100",
                StoredTemperatureUnit.CELSIUS,
                "2026-09-30T15:22:33Z"
            ),
            TemperatureEvidenceIntentStatus.Pending
        )

    private companion object {
        const val SUBJECT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
    }
}
