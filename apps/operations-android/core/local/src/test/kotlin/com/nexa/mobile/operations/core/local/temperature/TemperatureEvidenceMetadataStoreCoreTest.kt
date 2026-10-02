package com.nexa.mobile.operations.core.local.temperature

import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureEvidenceMetadataStoreCoreTest {
    @Test
    fun codecRoundTripsDraftAndExactPendingIntent() {
        val scope = scope()
        val snapshot = TemperatureMetadataSnapshot(scope, draft(), intent(scope))

        val restored = TemperatureEvidenceMetadataCodec.decode(
            TemperatureEvidenceMetadataCodec.encode(snapshot)
        )

        assertEquals(snapshot, restored)
        assertEquals("-18.765432100", restored.intent?.payload?.value)
        assertEquals("2026-09-30T15:22:33Z", restored.intent?.payload?.occurredAt)
    }

    @Test
    fun codecRejectsTruncationUnknownSchemaAndTrailingBytes() {
        val encoded = TemperatureEvidenceMetadataCodec.encode(
            TemperatureMetadataSnapshot(scope(), null, null)
        )

        assertFails { TemperatureEvidenceMetadataCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertFails { TemperatureEvidenceMetadataCodec.decode(encoded + 1) }
        assertFails { TemperatureEvidenceMetadataCodec.decode(encoded.also { it[4] = 2 }) }
    }

    @Test
    fun scopeBindingUsesUnambiguousFullIdentityAndDoesNotExposeIdentifiers() {
        val first = TemperatureMetadataScope("ab", "c", "d", "e")
        val second = TemperatureMetadataScope("a", "bc", "d", "e")

        assertNotEquals(
            TemperatureMetadataScopeBinding.fileKey(first),
            TemperatureMetadataScopeBinding.fileKey(second)
        )
        assertTrue(TemperatureMetadataScopeBinding.fileKey(first).matches(Regex("[a-f0-9]{64}")))
        assertNotEquals("ab-c-d-e", TemperatureMetadataScopeBinding.fileKey(first))
        assertNotEquals(
            TemperatureMetadataScopeBinding.additionalData(first).toList(),
            TemperatureMetadataScopeBinding.additionalData(second).toList()
        )
    }

    @Test
    fun keyAndPayloadRemainImmutableUntilExpectedIntentIsCleared() = runBlocking {
        val store = store()
        val scope = scope()
        val pending = intent(scope)
        assertEquals(TemperatureMetadataWrite.Saved, store.saveIntent(pending))
        assertEquals(
            TemperatureMetadataWrite.Saved,
            store.saveIntent(pending.copy(status = TemperatureEvidenceIntentStatus.UnknownOutcome))
        )
        assertEquals(
            TemperatureMetadataWrite.Conflict,
            store.saveIntent(pending.copy(idempotencyKey = "another-key"))
        )
        assertEquals(
            TemperatureMetadataWrite.Conflict,
            store.saveIntent(pending.copy(payload = pending.payload.copy(value = "5")))
        )
        val loaded = (store.loadIntent(scope) as TemperatureMetadataRead.Available).value
        assertEquals(TemperatureEvidenceIntentStatus.UnknownOutcome, loaded?.status)
        assertEquals("-18.765432100", loaded?.payload?.value)
    }

    @Test
    fun draftUpdatesPreserveIntentAndStaleExpectedKeyCannotClearNewerIntent() = runBlocking {
        val store = store()
        val scope = scope()
        val old = intent(scope)
        val next = intent(scope, key = "next-key")
        assertEquals(TemperatureMetadataWrite.Saved, store.saveIntent(old))
        assertEquals(
            TemperatureMetadataWrite.Saved,
            store.saveDraft(scope, draft(value = "-17.0"))
        )
        assertEquals(TemperatureMetadataWrite.Saved, store.clearIntent(scope, old.idempotencyKey))
        assertEquals(TemperatureMetadataWrite.Saved, store.saveIntent(next))

        assertEquals(TemperatureMetadataWrite.Stale, store.clearIntent(scope, old.idempotencyKey))
        assertEquals(next, (store.loadIntent(scope) as TemperatureMetadataRead.Available).value)
        assertEquals(
            "-17.0",
            (store.loadDraft(scope) as TemperatureMetadataRead.Available).value?.valueText
        )
    }

    @Test
    fun corruptAndFailedReadsFailClosedWithoutReplacingUnresolvedBytes() = runBlocking {
        val storage = FakeStorage()
        val store = TemperatureEvidenceMetadataStoreCore(storage, FakeCipher(), Dispatchers.IO)
        val scope = scope()
        val pending = intent(scope)
        assertEquals(TemperatureMetadataWrite.Saved, store.saveIntent(pending))
        val key = TemperatureMetadataScopeBinding.fileKey(scope)
        val original = storage.records.getValue(key).clone()
        val writes = storage.writeCount.get()

        storage.failReads = true
        assertEquals(TemperatureMetadataRead.Unavailable, store.loadIntent(scope))
        assertEquals(
            TemperatureMetadataWrite.Unavailable,
            store.saveIntent(pending.copy(status = TemperatureEvidenceIntentStatus.UnknownOutcome))
        )
        assertEquals(writes, storage.writeCount.get())
        assertTrue(storage.records.getValue(key).contentEquals(original))

        storage.failReads = false
        storage.records[key] = original.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val corrupted = storage.records.getValue(key).clone()
        assertEquals(TemperatureMetadataRead.Unavailable, store.loadIntent(scope))
        assertEquals(
            TemperatureMetadataWrite.Unavailable,
            store.clearIntent(scope, pending.idempotencyKey)
        )
        assertTrue(storage.records.getValue(key).contentEquals(corrupted))
    }

    @Test
    fun separateStoreInstancesSerializeConcurrentReadModifyWriteForSamePathAndScope() =
        runBlocking {
            val records = ConcurrentHashMap<String, ByteArray>()
            val firstStorage = FakeStorage("same-path", records)
            val secondStorage = FakeStorage("same-path", records)
            val first =
                TemperatureEvidenceMetadataStoreCore(firstStorage, FakeCipher(), Dispatchers.IO)
            val second =
                TemperatureEvidenceMetadataStoreCore(secondStorage, FakeCipher(), Dispatchers.IO)
            val scope = scope()
            val results = (0 until 80).map { index ->
                async(Dispatchers.Default) {
                    if (index % 2 == 0) {
                        first.saveDraft(scope, draft(value = "value-$index"))
                    } else {
                        second.saveIntent(intent(scope))
                    }
                }
            }.awaitAll()

            assertTrue(results.all { it == TemperatureMetadataWrite.Saved })
            assertTrue((first.loadDraft(scope) as TemperatureMetadataRead.Available).value != null)
            assertTrue(
                (second.loadIntent(scope) as TemperatureMetadataRead.Available).value != null
            )
        }

    private fun store() =
        TemperatureEvidenceMetadataStoreCore(FakeStorage(), FakeCipher(), Dispatchers.IO)

    private fun scope() = TemperatureMetadataScope("user-1", "tenant-2", "workspace-3", "member-4")

    private fun draft(value: String = "-18.765") = TemperatureEvidenceDraftRecord(
        StoredTemperatureSubjectType.LOT,
        SUBJECT_ID,
        value,
        StoredTemperatureUnit.CELSIUS,
        "2026-09-30T15:22:33Z"
    )

    private fun intent(scope: TemperatureMetadataScope, key: String = "temperature-intent-01") =
        TemperatureEvidenceIntentRecord(
            scope,
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

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected malformed metadata to be rejected")
        } catch (_: Exception) {
            // Expected malformed bytes.
        }
    }

    private class FakeStorage(
        override val lockNamespace: String = "fake-${System.identityHashCode(Any())}",
        val records: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap()
    ) : TemperatureRecordStorage {
        val writeCount = AtomicInteger()
        var failReads = false

        override fun read(fileKey: String): ByteArray? {
            if (failReads) throw FileNotFoundException("Synthetic storage read failure")
            return records[fileKey]?.clone()
        }

        override fun write(fileKey: String, encryptedRecord: ByteArray) {
            records[fileKey] = encryptedRecord.clone()
            writeCount.incrementAndGet()
        }
    }

    private class FakeCipher : TemperatureRecordCipher {
        override fun encrypt(scope: TemperatureMetadataScope, plaintext: ByteArray): ByteArray =
            plaintext.clone()

        override fun decrypt(
            scope: TemperatureMetadataScope,
            encryptedRecord: ByteArray
        ): ByteArray = encryptedRecord.clone()
    }

    private companion object {
        const val SUBJECT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
    }
}
