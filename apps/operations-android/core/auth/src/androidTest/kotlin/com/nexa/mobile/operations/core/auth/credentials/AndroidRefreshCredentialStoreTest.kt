package com.nexa.mobile.operations.core.auth.credentials

import android.content.Context
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRefreshCredentialStoreTest {
    private lateinit var context: Context
    private lateinit var recordFile: File
    private lateinit var alias: String
    private lateinit var store: AndroidRefreshCredentialStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        recordFile = File(context.noBackupFilesDir, "test-session-$suffix.record")
        alias = "com.nexa.mobile.operations.test-session.$suffix"
        store = AndroidRefreshCredentialStore(context, recordFile.name, alias)
    }

    @After
    fun tearDown() {
        runBlocking { store.clear() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun readyToInFlightToReadyNeverPersistsReusableOldCredential() = runBlocking {
        val first = "synthetic-refresh-one-${UUID.randomUUID()}"
        val second = "synthetic-refresh-two-${UUID.randomUUID()}"
        assertTrue(recordFile.parentFile!!.canonicalPath == context.noBackupFilesDir.canonicalPath)
        assertEquals(StoredRefreshCredential.Missing, store.read())

        store.writeReady(first)
        assertEquals(StoredRefreshCredential.Ready(first), store.read())
        assertFalse(recordFile.readText(Charsets.ISO_8859_1).contains(first))

        assertEquals(StoredRefreshCredential.Ready(first), store.takeForRefresh())
        assertEquals(StoredRefreshCredential.InFlight, store.read())
        assertEquals(StoredRefreshCredential.InFlight, store.takeForRefresh())
        assertFalse(recordFile.readText(Charsets.ISO_8859_1).contains(first))

        store.writeReady(second)
        assertEquals(StoredRefreshCredential.Ready(second), store.read())
        assertFalse(recordFile.readText(Charsets.ISO_8859_1).contains(second))
    }

    @Test
    fun repeatedEncryptionUsesDistinctIvAndCiphertext() = runBlocking {
        val value = "synthetic-refresh-${UUID.randomUUID()}"
        store.writeReady(value)
        val first = recordFile.readBytes()
        store.writeReady(value)
        val second = recordFile.readBytes()

        assertEquals(12, first[5].toInt())
        assertEquals(12, second[5].toInt())
        assertNotEquals(first.sliceArray(6..17).toList(), second.sliceArray(6..17).toList())
        assertFalse(first.contentEquals(second))
        assertEquals(StoredRefreshCredential.Ready(value), store.read())
    }

    @Test
    fun modifiedIvCiphertextAndTagFailClosed() = runBlocking {
        val value = "synthetic-refresh-${UUID.randomUUID()}"
        for (changedIndex in listOf(6, 24, -1)) {
            store.writeReady(value)
            val bytes = recordFile.readBytes()
            val index = if (changedIndex < 0) bytes.lastIndex else changedIndex
            bytes[index] = (bytes[index].toInt() xor 1).toByte()
            recordFile.writeBytes(bytes)
            assertEquals(StoredRefreshCredential.Unusable, store.read())
            assertFalse(recordFile.exists())
        }
    }

    @Test
    fun truncatedUnknownSchemaAndMissingKeyFailClosed() = runBlocking {
        val value = "synthetic-refresh-${UUID.randomUUID()}"
        store.writeReady(value)
        val valid = recordFile.readBytes()

        recordFile.writeBytes(valid.copyOf(7))
        assertEquals(StoredRefreshCredential.Unusable, store.read())

        store.writeReady(value)
        val unknown = recordFile.readBytes().also { it[4] = 2 }
        recordFile.writeBytes(unknown)
        assertEquals(StoredRefreshCredential.Unusable, store.read())

        store.writeReady(value)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        assertEquals(StoredRefreshCredential.Unusable, store.read())
        assertFalse(recordFile.exists())
    }

    @Test
    fun interruptedAtomicWriteRecoversCompletePreviousRecord() = runBlocking {
        val value = "synthetic-refresh-${UUID.randomUUID()}"
        store.writeReady(value)
        val original = recordFile.readBytes()
        val atomic = AtomicFile(recordFile)
        val stream = atomic.startWrite()
        stream.write(byteArrayOf(0, 1, 2, 3))
        atomic.failWrite(stream)

        assertArrayEquals(original, recordFile.readBytes())
        assertEquals(StoredRefreshCredential.Ready(value), store.read())
    }

    @Test
    fun clearInvalidatesDedicatedKeyAndRejectsRestoredCiphertext() = runBlocking {
        val value = "synthetic-refresh-${UUID.randomUUID()}"
        store.writeReady(value)
        val oldCiphertext = recordFile.readBytes()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias(alias))

        store.clear()
        assertFalse(recordFile.exists())
        assertFalse(File("${recordFile.path}.bak").exists())
        val reloadedKeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(reloadedKeyStore.containsAlias(alias))

        recordFile.writeBytes(oldCiphertext)
        assertEquals(StoredRefreshCredential.Unusable, store.read())
        assertFalse(recordFile.exists())
    }
}
