package com.nexa.mobile.operations.core.local.receiving

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface ReceivingRecordStorage {
    val lockNamespace: String

    fun read(fileKey: String): ByteArray?

    fun write(fileKey: String, encryptedRecord: ByteArray)
}

internal interface ReceivingRecordCipher {
    fun encrypt(scope: ReceivingMetadataScope, plaintext: ByteArray): ByteArray

    fun decrypt(scope: ReceivingMetadataScope, encryptedRecord: ByteArray): ByteArray
}

/** Serialized scope-local read/modify/write logic, kept independent for JVM contract tests. */
internal class ReceivingMetadataStoreCore(
    private val storage: ReceivingRecordStorage,
    private val cipher: ReceivingRecordCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ReceivingMetadataStore {
    override suspend fun loadDraft(
        scope: ReceivingMetadataScope
    ): ReceivingMetadataRead<ReceivingDraftMetadataRecord> = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                is SnapshotRead.Available -> ReceivingMetadataRead.Available(read.value.draft)
                SnapshotRead.Unavailable -> ReceivingMetadataRead.Unavailable
            }
        }
    }

    override suspend fun saveDraft(
        scope: ReceivingMetadataScope,
        draft: ReceivingDraftMetadataRecord
    ): ReceivingMetadataWrite = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                SnapshotRead.Unavailable -> ReceivingMetadataWrite.Unavailable
                is SnapshotRead.Available -> writeLocked(read.value.copy(draft = draft))
            }
        }
    }

    override suspend fun loadIntent(
        scope: ReceivingMetadataScope
    ): ReceivingMetadataRead<ReceivingIntentMetadataRecord> = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                is SnapshotRead.Available -> ReceivingMetadataRead.Available(read.value.intent)
                SnapshotRead.Unavailable -> ReceivingMetadataRead.Unavailable
            }
        }
    }

    override suspend fun saveIntent(intent: ReceivingIntentMetadataRecord): ReceivingMetadataWrite =
        withContext(ioDispatcher) {
            val scope = intent.scope
            mutexFor(scope).withLock {
                when (val read = readLocked(scope)) {
                    SnapshotRead.Unavailable -> ReceivingMetadataWrite.Unavailable

                    is SnapshotRead.Available -> {
                        val current = read.value.intent
                        when {
                            current == null -> writeLocked(read.value.copy(intent = intent))

                            current.idempotencyKey != intent.idempotencyKey ||
                                current.payload != intent.payload -> ReceivingMetadataWrite.Conflict

                            current == intent -> ReceivingMetadataWrite.Saved

                            else -> writeLocked(read.value.copy(intent = intent))
                        }
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: ReceivingMetadataScope,
        expectedIdempotencyKey: String
    ): ReceivingMetadataWrite = withContext(ioDispatcher) {
        if (expectedIdempotencyKey.isBlank() ||
            expectedIdempotencyKey.toByteArray(Charsets.UTF_8).size >
            ReceivingIntentMetadataRecord.MAX_KEY_BYTES
        ) {
            return@withContext ReceivingMetadataWrite.Stale
        }
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                SnapshotRead.Unavailable -> ReceivingMetadataWrite.Unavailable

                is SnapshotRead.Available -> {
                    val current = read.value.intent
                    when {
                        current == null -> ReceivingMetadataWrite.Saved

                        current.idempotencyKey != expectedIdempotencyKey ->
                            ReceivingMetadataWrite.Stale

                        else -> writeLocked(read.value.copy(intent = null))
                    }
                }
            }
        }
    }

    private fun readLocked(scope: ReceivingMetadataScope): SnapshotRead = try {
        val key = ReceivingScopeBinding.fileKey(scope)
        val encrypted = storage.read(key)
        if (encrypted == null) {
            SnapshotRead.Available(
                ReceivingMetadataSnapshot(scope, draft = null, intent = null)
            )
        } else {
            val snapshot = ReceivingMetadataCodec.decode(cipher.decrypt(scope, encrypted))
            if (snapshot.scope != scope) {
                SnapshotRead.Unavailable
            } else {
                SnapshotRead.Available(snapshot)
            }
        }
    } catch (_: Exception) {
        SnapshotRead.Unavailable
    }

    private fun writeLocked(snapshot: ReceivingMetadataSnapshot): ReceivingMetadataWrite = try {
        val plaintext = ReceivingMetadataCodec.encode(snapshot)
        val encrypted = cipher.encrypt(snapshot.scope, plaintext)
        storage.write(ReceivingScopeBinding.fileKey(snapshot.scope), encrypted)
        ReceivingMetadataWrite.Saved
    } catch (_: Exception) {
        ReceivingMetadataWrite.Unavailable
    }

    private fun mutexFor(scope: ReceivingMetadataScope): Mutex = scopeLocks.computeIfAbsent(
        "${storage.lockNamespace}:${ReceivingScopeBinding.fileKey(scope)}"
    ) { Mutex() }

    private sealed interface SnapshotRead {
        data class Available(val value: ReceivingMetadataSnapshot) : SnapshotRead
        data object Unavailable : SnapshotRead
    }

    private companion object {
        /** Shared by store instances so an app-process read/modify/write cannot lose a field. */
        val scopeLocks = ConcurrentHashMap<String, Mutex>()
    }
}
