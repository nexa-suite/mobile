package com.nexa.mobile.operations.core.local.disposition

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
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

class DispositionMetadataStoreCoreTest {
    @Test
    fun codecRoundTripsScopedDraftAndExactFrozenCommand() {
        val scope = scope()
        val snapshot = DispositionMetadataSnapshot(scope, draft(), intent(scope))

        assertEquals(
            snapshot,
            DispositionMetadataCodec.decode(DispositionMetadataCodec.encode(snapshot))
        )
    }

    @Test
    fun codecRejectsUnknownSchemaTruncationAndTrailingBytes() {
        val valid = DispositionMetadataCodec.encode(
            DispositionMetadataSnapshot(scope(), null, null)
        )

        assertFails { DispositionMetadataCodec.decode(valid.copyOf(valid.size - 1)) }
        assertFails { DispositionMetadataCodec.decode(valid + 1) }
        assertFails { DispositionMetadataCodec.decode(valid.also { it[4] = 3 }) }
    }

    @Test
    fun schemaOneFrozenIntentDecodesAsWholeLotWithoutLosingItsKeyOrVersion() {
        val scope = scope()
        val decoded = DispositionMetadataCodec.decode(legacyV1Record())

        assertEquals(
            DispositionMetadataSnapshot(
                scope,
                null,
                legacyIntent(scope)
            ),
            decoded
        )
    }

    @Test
    fun frozenPartialPayloadRequiresValidEvaluationPairAndNumericPrecision() {
        assertFails {
            DispositionCommandPayload(
                LOT_ID,
                StoredLotDisposition.HOLD,
                "temperature review",
                17,
                "1.2500",
                null
            )
        }
        assertFails {
            DispositionCommandPayload(
                LOT_ID,
                StoredLotDisposition.HOLD,
                "temperature review",
                17,
                "1000000000000000.0000",
                EVALUATION_ID
            )
        }
    }

    @Test
    fun scopeBindingUsesAllIdentityFieldsWithoutPuttingThemInFilenames() {
        val first = DispositionMetadataScope("ab", "c", "d", "e")
        val second = DispositionMetadataScope("a", "bc", "d", "e")

        assertNotEquals(
            DispositionScopeBinding.fileKey(first),
            DispositionScopeBinding.fileKey(second)
        )
        assertTrue(DispositionScopeBinding.fileKey(first).matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(
            DispositionScopeBinding.additionalData(first).toList(),
            DispositionScopeBinding.additionalData(second).toList()
        )
    }

    @Test
    fun pendingKeyAndPayloadCannotChangeButSameIntentCanBecomeUnknown() = runBlocking {
        val store = store()
        val pending = intent(scope())
        assertEquals(DispositionMetadataWrite.Saved, store.saveIntent(pending))
        assertEquals(
            DispositionMetadataWrite.Saved,
            store.saveIntent(pending.copy(status = DispositionIntentStatus.UnknownOutcome))
        )
        assertEquals(
            DispositionMetadataWrite.Conflict,
            store.saveIntent(pending.copy(idempotencyKey = "different-key"))
        )
        assertEquals(
            DispositionMetadataWrite.Conflict,
            store.saveIntent(
                pending.copy(payload = pending.payload.copy(reason = "changed reason"))
            )
        )
        assertEquals(
            DispositionMetadataWrite.Conflict,
            store.saveIntent(
                pending.copy(payload = pending.payload.copy(affectedQuantity = "1.5000"))
            )
        )
        assertEquals(
            DispositionIntentStatus.UnknownOutcome,
            (store.loadIntent(pending.scope) as DispositionMetadataRead.Available).value?.status
        )
    }

    @Test
    fun draftUpdatesPreserveIntentAndStaleKeyCannotClearNewerRecord() = runBlocking {
        val store = store()
        val scope = scope()
        val first = intent(scope)
        val newer = intent(scope, key = "newer-key")
        assertEquals(DispositionMetadataWrite.Saved, store.saveIntent(first))
        assertEquals(DispositionMetadataWrite.Saved, store.saveDraft(scope, draft("saved offline")))
        assertEquals(first, (store.loadIntent(scope) as DispositionMetadataRead.Available).value)
        assertEquals(DispositionMetadataWrite.Saved, store.clearIntent(scope, first.idempotencyKey))
        assertEquals(DispositionMetadataWrite.Saved, store.saveIntent(newer))

        assertEquals(DispositionMetadataWrite.Stale, store.clearIntent(scope, first.idempotencyKey))
        assertEquals(newer, (store.loadIntent(scope) as DispositionMetadataRead.Available).value)
    }

    @Test
    fun failedExistingReadIsUnavailableAndNeverOverwritten() = runBlocking {
        val storage = FakeStorage()
        val store = DispositionMetadataStoreCore(storage, FakeCipher())
        val scope = scope()
        val original = intent(scope)
        assertEquals(DispositionMetadataWrite.Saved, store.saveIntent(original))
        val key = DispositionScopeBinding.fileKey(scope)
        val bytesBefore = storage.records.getValue(key).clone()
        storage.failReads = true

        assertEquals(DispositionMetadataRead.Unavailable, store.loadIntent(scope))
        assertEquals(
            DispositionMetadataWrite.Unavailable,
            store.saveDraft(scope, draft("must not replace unknown bytes"))
        )
        assertTrue(storage.records.getValue(key).contentEquals(bytesBefore))
        assertEquals(1, storage.writeCount.get())
    }

    @Test
    fun differentScopesDoNotReadEachOthersIntent() = runBlocking {
        val store = store()
        val first = scope()
        val other = first.copy(membershipId = "different-member")
        assertEquals(DispositionMetadataWrite.Saved, store.saveIntent(intent(first)))

        assertEquals(DispositionMetadataRead.Available(null), store.loadIntent(other))
    }

    @Test
    fun instancesForSameStorageNamespaceSerializeDraftAndIntentChanges() = runBlocking {
        val records = ConcurrentHashMap<String, ByteArray>()
        val first = DispositionMetadataStoreCore(FakeStorage("shared-dir", records), FakeCipher())
        val second = DispositionMetadataStoreCore(FakeStorage("shared-dir", records), FakeCipher())
        val scope = scope()
        val results = (0 until 60).map { index ->
            async(Dispatchers.Default) {
                if (index % 2 == 0) {
                    first.saveDraft(scope, draft("note-$index"))
                } else {
                    second.saveIntent(intent(scope))
                }
            }
        }.awaitAll()

        assertTrue(results.all { it == DispositionMetadataWrite.Saved })
        assertTrue((first.loadDraft(scope) as DispositionMetadataRead.Available).value != null)
        assertTrue((second.loadIntent(scope) as DispositionMetadataRead.Available).value != null)
    }

    private fun store() = DispositionMetadataStoreCore(FakeStorage(), FakeCipher())
    private fun scope() = DispositionMetadataScope("user-1", "tenant-2", "workspace-3", "member-4")
    private fun draft(reason: String = "") =
        DispositionDraftRecord(LOT_ID, StoredLotDisposition.HOLD, reason)
    private fun intent(scope: DispositionMetadataScope, key: String = "stable-key-1") =
        DispositionIntentRecord(
            scope,
            key,
            DispositionCommandPayload(
                LOT_ID,
                StoredLotDisposition.RETURN_TO_SUPPLIER,
                "seal broken",
                17,
                "1.2500",
                EVALUATION_ID
            ),
            DispositionIntentStatus.Pending
        )

    private fun legacyIntent(scope: DispositionMetadataScope) = DispositionIntentRecord(
        scope,
        "legacy-key",
        DispositionCommandPayload(
            LOT_ID,
            StoredLotDisposition.RETURN_TO_SUPPLIER,
            "seal broken",
            17,
            null,
            null
        ),
        DispositionIntentStatus.Pending
    )

    private fun legacyV1Record(): ByteArray = ByteArrayOutputStream().let { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeByte('N'.code)
            output.writeByte('X'.code)
            output.writeByte('D'.code)
            output.writeByte('M'.code)
            output.writeByte(1)
            listOf(
                "user-1",
                "tenant-2",
                "workspace-3",
                "member-4"
            ).forEach { output.writeLegacyField(it) }
            output.writeBoolean(false)
            output.writeBoolean(true)
            output.writeLegacyField("legacy-key")
            output.writeLegacyField(LOT_ID)
            output.writeByte(StoredLotDisposition.RETURN_TO_SUPPLIER.ordinal + 1)
            output.writeLegacyField("seal broken")
            output.writeLong(17)
            output.writeByte(1)
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeLegacyField(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected malformed metadata to be rejected")
        } catch (_: Exception) {
            // Expected.
        }
    }

    private class FakeStorage(
        override val lockNamespace: String = "test-${System.identityHashCode(Any())}",
        val records: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap()
    ) : DispositionRecordStorage {
        val writeCount = AtomicInteger()
        var failReads = false
        override fun read(fileKey: String): ByteArray? {
            if (failReads) throw IOException("Synthetic storage failure")
            return records[fileKey]?.clone()
        }
        override fun write(fileKey: String, encryptedRecord: ByteArray) {
            records[fileKey] = encryptedRecord.clone()
            writeCount.incrementAndGet()
        }
    }

    private class FakeCipher : DispositionRecordCipher {
        override fun encrypt(scope: DispositionMetadataScope, plaintext: ByteArray): ByteArray =
            plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

        override fun decrypt(
            scope: DispositionMetadataScope,
            encryptedRecord: ByteArray
        ): ByteArray = encryptedRecord.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    private companion object {
        const val LOT_ID = "11111111-1111-4111-8111-111111111111"
        const val EVALUATION_ID = "22222222-2222-4222-8222-222222222222"
    }
}
