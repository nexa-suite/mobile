package com.nexa.mobile.operations.core.local.receiving

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

class ReceivingMetadataStoreCoreTest {
    @Test
    fun codecRoundTripsPlainDraftAndFrozenIntentWithoutMergingCatalogAndSkuIds() {
        val scope = scope()
        val draft = draft()
        val intent = intent(scope)
        val snapshot = ReceivingMetadataSnapshot(scope, draft, intent)

        val restored = ReceivingMetadataCodec.decode(ReceivingMetadataCodec.encode(snapshot))

        assertEquals(snapshot, restored)
        assertEquals("CAT-42", restored.draft?.selectedProduct?.catalogItemId)
        assertEquals("8b5ce96c-6dfc-47c0-933a-ae24bba75d29", restored.intent?.payload?.skuId)
        assertNotEquals(restored.intent?.payload?.catalogItemId, restored.intent?.payload?.skuId)
    }

    @Test
    fun codecRejectsUnknownSchemaTruncationAndTrailingBytes() {
        val valid = ReceivingMetadataCodec.encode(ReceivingMetadataSnapshot(scope(), null, null))

        assertFails { ReceivingMetadataCodec.decode(valid.copyOf(valid.size - 1)) }
        assertFails { ReceivingMetadataCodec.decode(valid + 1) }
        assertFails { ReceivingMetadataCodec.decode(valid.also { it[4] = 2 }) }
    }

    @Test
    fun scopeBindingUsesFullLengthPrefixedIdentityAndDoesNotRevealFields() {
        val first = ReceivingMetadataScope("ab", "c", "d", "e")
        val second = ReceivingMetadataScope("a", "bc", "d", "e")

        assertNotEquals(ReceivingScopeBinding.fileKey(first), ReceivingScopeBinding.fileKey(second))
        assertTrue(ReceivingScopeBinding.fileKey(first).matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(ReceivingScopeBinding.fileKey(first), "ab-c-d-e")
        assertNotEquals(
            ReceivingScopeBinding.additionalData(first).toList(),
            ReceivingScopeBinding.additionalData(second).toList()
        )
    }

    @Test
    fun unresolvedIntentKeyAndPayloadAreImmutableButSameIntentCanChangeStatus() = runBlocking {
        val store = store()
        val scope = scope()
        val pending = intent(scope)
        assertEquals(ReceivingMetadataWrite.Saved, store.saveIntent(pending))
        assertEquals(
            ReceivingMetadataWrite.Saved,
            store.saveIntent(pending.copy(status = ReceivingIntentStatus.UnknownOutcome))
        )
        assertEquals(
            ReceivingMetadataWrite.Conflict,
            store.saveIntent(pending.copy(idempotencyKey = "another-key"))
        )
        assertEquals(
            ReceivingMetadataWrite.Conflict,
            store.saveIntent(pending.copy(payload = pending.payload.copy(quantity = "999")))
        )
        assertEquals(
            ReceivingIntentStatus.UnknownOutcome,
            (store.loadIntent(scope) as ReceivingMetadataRead.Available).value?.status
        )
        assertEquals(
            "12.50",
            ((store.loadIntent(scope) as ReceivingMetadataRead.Available).value!!).payload.quantity
        )
    }

    @Test
    fun draftChangesPreserveIntentAndOldExpectedKeyCannotClearNewIntent() = runBlocking {
        val store = store()
        val scope = scope()
        val first = intent(scope)
        val next = intent(scope, key = "new-key")
        assertEquals(ReceivingMetadataWrite.Saved, store.saveIntent(first))
        assertEquals(
            ReceivingMetadataWrite.Saved,
            store.saveDraft(scope, draft(notes = "loading dock"))
        )
        assertEquals(ReceivingMetadataWrite.Saved, store.clearIntent(scope, first.idempotencyKey))
        assertEquals(ReceivingMetadataWrite.Saved, store.saveIntent(next))

        assertEquals(ReceivingMetadataWrite.Stale, store.clearIntent(scope, first.idempotencyKey))
        assertEquals(next, (store.loadIntent(scope) as ReceivingMetadataRead.Available).value)
        assertEquals(
            "loading dock",
            (store.loadDraft(scope) as ReceivingMetadataRead.Available).value?.notes
        )
    }

    @Test
    fun corruptOrUnavailableRecordsCannotBeSilentlyOverwritten() = runBlocking {
        val storage = FakeStorage()
        val cipher = FakeCipher()
        val store = ReceivingMetadataStoreCore(storage, cipher, Dispatchers.IO)
        val scope = scope()
        assertEquals(ReceivingMetadataWrite.Saved, store.saveDraft(scope, draft()))
        val key = ReceivingScopeBinding.fileKey(scope)
        val corrupted = storage.records.getValue(key).clone().also {
            it[0] =
                (it[0].toInt() xor 1).toByte()
        }
        storage.records[key] = corrupted
        val writeCount = storage.writeCount.get()

        assertEquals(ReceivingMetadataRead.Unavailable, store.loadDraft(scope))
        assertEquals(
            ReceivingMetadataWrite.Unavailable,
            store.saveDraft(scope, draft(notes = "replacement"))
        )
        assertEquals(writeCount, storage.writeCount.get())
        assertTrue(storage.records.getValue(key).contentEquals(corrupted))
    }

    @Test
    fun failedStorageReadCannotBeTreatedAsMissingOrOverwritten() = runBlocking {
        val storage = FakeStorage()
        val store = ReceivingMetadataStoreCore(storage, FakeCipher(), Dispatchers.IO)
        val scope = scope()
        assertEquals(ReceivingMetadataWrite.Saved, store.saveDraft(scope, draft()))
        val existing = storage.records.getValue(ReceivingScopeBinding.fileKey(scope)).clone()
        val writeCount = storage.writeCount.get()
        storage.failReads = true

        assertEquals(ReceivingMetadataRead.Unavailable, store.loadDraft(scope))
        assertEquals(
            ReceivingMetadataWrite.Unavailable,
            store.saveDraft(scope, draft(notes = "replacement"))
        )
        assertEquals(writeCount, storage.writeCount.get())
        assertTrue(
            storage.records.getValue(ReceivingScopeBinding.fileKey(scope)).contentEquals(existing)
        )
    }

    @Test
    fun concurrentStoresSerializeDraftAndIntentReadModifyWrite() = runBlocking {
        val records = ConcurrentHashMap<String, ByteArray>()
        val firstStorage = FakeStorage("shared-path", records)
        val secondStorage = FakeStorage("shared-path", records)
        val firstStore = ReceivingMetadataStoreCore(firstStorage, FakeCipher(), Dispatchers.IO)
        val secondStore = ReceivingMetadataStoreCore(secondStorage, FakeCipher(), Dispatchers.IO)
        val scope = scope()
        val results = (0 until 80).map { index ->
            async(Dispatchers.Default) {
                if (index % 2 == 0) {
                    firstStore.saveDraft(scope, draft(notes = "draft-$index"))
                } else {
                    secondStore.saveIntent(intent(scope))
                }
            }
        }.awaitAll()

        assertTrue(results.all { it == ReceivingMetadataWrite.Saved })
        assertTrue((firstStore.loadDraft(scope) as ReceivingMetadataRead.Available).value != null)
        assertTrue((secondStore.loadIntent(scope) as ReceivingMetadataRead.Available).value != null)
    }

    private fun store() = ReceivingMetadataStoreCore(FakeStorage(), FakeCipher(), Dispatchers.IO)

    private fun scope() =
        ReceivingMetadataScope("user-1", "tenant-2", "workspace-3", "membership-4")

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

    private fun intent(scope: ReceivingMetadataScope, key: String = "idem-key-1") =
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

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected malformed metadata to be rejected")
        } catch (_: Exception) {
            // Expected.
        }
    }

    private class FakeStorage(
        override val lockNamespace: String = "fake-${System.identityHashCode(Any())}",
        val records: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap()
    ) : ReceivingRecordStorage {
        val writeCount = AtomicInteger()
        var failReads = false

        override fun read(fileKey: String): ByteArray? {
            if (failReads) throw FileNotFoundException("Synthetic record read failure")
            return records[fileKey]?.clone()
        }

        override fun write(fileKey: String, encryptedRecord: ByteArray) {
            records[fileKey] = encryptedRecord.clone()
            writeCount.incrementAndGet()
        }
    }

    private class FakeCipher : ReceivingRecordCipher {
        override fun encrypt(scope: ReceivingMetadataScope, plaintext: ByteArray): ByteArray =
            plaintext.clone()

        override fun decrypt(scope: ReceivingMetadataScope, encryptedRecord: ByteArray): ByteArray =
            encryptedRecord.clone()
    }
}
