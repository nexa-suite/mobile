package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.storage.picking

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickingMetadataStoreCoreTest {
    @Test
    fun codecRoundTripsStartAndExactVersionedConfirmation() {
        val scope = scope()
        val confirmation = intent(scope)
        val start = PickingIntentMetadataRecord(
            scope,
            "start-key",
            PickingCommandRecord.Start("fulfillment-9", 7),
            PickingIntentStatus.UnknownOutcome
        )
        val snapshot = PickingMetadataSnapshot(scope, confirmation)

        val restored = PickingMetadataCodec.decode(PickingMetadataCodec.encode(snapshot))
        val restoredStart = PickingMetadataCodec.decode(
            PickingMetadataCodec.encode(PickingMetadataSnapshot(scope, start))
        ).intent
        val restoredConfirm = restored.intent!!.command as PickingCommandRecord.Confirm

        assertEquals(snapshot, restored)
        assertEquals(start, restoredStart)
        assertEquals(11L, restoredConfirm.expectedFulfillmentVersion)
        assertEquals(4L, restoredConfirm.expectedAllocationVersion)
        assertEquals("1.250", restoredConfirm.quantity)
    }

    @Test
    fun codecRejectsUnknownSchemaTruncationAndTrailingBytes() {
        val valid = PickingMetadataCodec.encode(PickingMetadataSnapshot(scope(), null))

        assertFails { PickingMetadataCodec.decode(valid.copyOf(valid.size - 1)) }
        assertFails { PickingMetadataCodec.decode(valid + 1) }
        assertFails { PickingMetadataCodec.decode(valid.also { it[4] = 2 }) }
    }

    @Test
    fun pickingFilesAndAuthenticatedDomainAreSeparateAndUseFullScope() {
        val first = PickingMetadataScope("ab", "c", "d", "e")
        val second = PickingMetadataScope("a", "bc", "d", "e")
        // Receiving v1 storage binding golden values keep the namespaces distinct
        // without exposing another context's persistence implementation.
        val receivingFileKey = "df6b03f89f7b473027db1bda3a198071c1406bfb86ee9104838b6026d1f22a94"
        val receivingAdditionalData = byteArrayOf(0,0,0,23,78,69,88,65,45,82,69,67,69,73,86,73,78,71,45,77,69,84,65,68,65,84,65,1,0,0,0,2,97,98,0,0,0,1,99,0,0,0,1,100,0,0,0,1,101)

        assertNotEquals(PickingScopeBinding.fileKey(first), PickingScopeBinding.fileKey(second))
        assertNotEquals(
            PickingScopeBinding.fileKey(first),
            receivingFileKey
        )
        assertNotEquals(
            PickingScopeBinding.additionalData(first).toList(),
            receivingAdditionalData.toList()
        )
        assertTrue(PickingScopeBinding.fileKey(first).matches(Regex("[a-f0-9]{64}")))
        assertFalse(PickingScopeBinding.fileKey(first).contains("ab"))
    }

    @Test
    fun pendingReconstructionBecomesUnknownAndFrozenKeyBodyAndVersionsCannotChange() = runBlocking {
        val bytes = ConcurrentHashMap<String, ByteArray>()
        val firstStore = store(FakeStorage("shared-location", bytes))
        val scope = scope()
        val pending = intent(scope)
        assertEquals(PickingMetadataWrite.Saved, firstStore.saveIntent(pending))

        val reconstructedStore = store(FakeStorage("shared-location", bytes))
        val restored = (
            reconstructedStore.loadIntent(
                scope
            ) as PickingMetadataRead.Available
            ).value!!
        assertEquals(PickingIntentStatus.UnknownOutcome, restored.status)
        assertEquals(pending.idempotencyKey, restored.idempotencyKey)
        assertEquals(pending.command, restored.command)
        assertEquals(
            PickingMetadataWrite.Saved,
            reconstructedStore.saveIntent(restored.copy(status = PickingIntentStatus.Pending))
        )
        assertEquals(
            PickingMetadataWrite.Conflict,
            reconstructedStore.saveIntent(
                restored.copy(
                    status = PickingIntentStatus.Pending,
                    command = (restored.command as PickingCommandRecord.Confirm).copy(
                        expectedAllocationVersion = 5
                    )
                )
            )
        )
        assertEquals(
            PickingMetadataWrite.Conflict,
            reconstructedStore.saveIntent(
                restored.copy(idempotencyKey = "new-key", status = PickingIntentStatus.Pending)
            )
        )
    }

    @Test
    fun staleClearCannotEraseIntentAndDifferentScopesRemainIndependent() = runBlocking {
        val store = store()
        val current = intent(scope())
        val other = scope().copy(workspaceId = "workspace-other")
        assertEquals(PickingMetadataWrite.Saved, store.saveIntent(current))
        assertEquals(PickingMetadataRead.Available(null), store.loadIntent(other))
        assertEquals(PickingMetadataWrite.Stale, store.clearIntent(current.scope, "old-key"))
        assertEquals(
            current.copy(status = PickingIntentStatus.UnknownOutcome),
            (store.loadIntent(current.scope) as PickingMetadataRead.Available).value
        )
    }

    @Test
    fun separateStoresSerializeSameScopeOperations() = runBlocking {
        val records = ConcurrentHashMap<String, ByteArray>()
        val first = store(FakeStorage("shared-lock", records))
        val second = store(FakeStorage("shared-lock", records))
        val pending = intent(scope())
        val results = (0 until 60).map {
            async(Dispatchers.Default) {
                if (it % 2 == 0) {
                    first.saveIntent(pending)
                } else {
                    second.loadIntent(pending.scope)
                }
            }
        }.awaitAll()

        assertTrue(
            results.all {
                it is PickingMetadataWrite.Saved ||
                    it is PickingMetadataRead.Available<*>
            }
        )
        assertEquals(
            pending.command,
            (first.loadIntent(pending.scope) as PickingMetadataRead.Available).value?.command
        )
    }

    private fun store(storage: FakeStorage = FakeStorage()) =
        PickingMetadataStoreCore(storage, FakeCipher(), Dispatchers.IO)

    private fun scope() = PickingMetadataScope("user-1", "tenant-2", "workspace-3", "member-4")

    private fun intent(scope: PickingMetadataScope) = PickingIntentMetadataRecord(
        scope = scope,
        idempotencyKey = "pick-key-1",
        command = PickingCommandRecord.Confirm(
            fulfillmentId = "fulfillment-9",
            expectedFulfillmentVersion = 11,
            expectedAllocationVersion = 4,
            fulfillmentLineId = "fulfillment-line-1",
            skuId = "sku-1",
            physicalAllocationLineId = "allocation-line-3",
            lotId = "lot-19",
            warehouseId = "warehouse-2",
            quantity = "1.250",
            unit = "box"
        ),
        status = PickingIntentStatus.Pending
    )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected malformed picking metadata to be rejected")
        } catch (_: Exception) {
            // Expected.
        }
    }

    private class FakeStorage(
        override val lockNamespace: String = "picking-fake-${System.identityHashCode(Any())}",
        private val records: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap()
    ) : PickingRecordStorage {
        override fun read(fileKey: String): ByteArray? = records[fileKey]?.clone()

        override fun write(fileKey: String, encryptedRecord: ByteArray) {
            records[fileKey] = encryptedRecord.clone()
        }
    }

    private class FakeCipher : PickingRecordCipher {
        override fun encrypt(scope: PickingMetadataScope, plaintext: ByteArray): ByteArray =
            PickingScopeBinding.additionalData(scope) + plaintext

        override fun decrypt(scope: PickingMetadataScope, encryptedRecord: ByteArray): ByteArray {
            val aad = PickingScopeBinding.additionalData(scope)
            require(encryptedRecord.size > aad.size)
            require(encryptedRecord.copyOfRange(0, aad.size).contentEquals(aad))
            return encryptedRecord.copyOfRange(aad.size, encryptedRecord.size)
        }
    }
}
