package com.nexa.mobile.operations.core.local.disposition

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface DispositionRecordStorage {
    val lockNamespace: String
    fun read(fileKey: String): ByteArray?
    fun write(fileKey: String, encryptedRecord: ByteArray)
}

internal interface DispositionRecordCipher {
    fun encrypt(scope: DispositionMetadataScope, plaintext: ByteArray): ByteArray
    fun decrypt(scope: DispositionMetadataScope, encryptedRecord: ByteArray): ByteArray
}

/** Durable-record semantics separated from Android APIs for focused JVM contract tests. */
internal class DispositionMetadataStoreCore(
    private val storage: DispositionRecordStorage,
    private val cipher: DispositionRecordCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DispositionMetadataStore {
    override suspend fun loadDraft(
        scope: DispositionMetadataScope
    ): DispositionMetadataRead<DispositionDraftRecord> = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                is SnapshotRead.Available -> DispositionMetadataRead.Available(read.value.draft)
                SnapshotRead.Unavailable -> DispositionMetadataRead.Unavailable
            }
        }
    }

    override suspend fun saveDraft(
        scope: DispositionMetadataScope,
        draft: DispositionDraftRecord
    ): DispositionMetadataWrite = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                SnapshotRead.Unavailable -> DispositionMetadataWrite.Unavailable
                is SnapshotRead.Available -> writeLocked(read.value.copy(draft = draft))
            }
        }
    }

    override suspend fun loadIntent(
        scope: DispositionMetadataScope
    ): DispositionMetadataRead<DispositionIntentRecord> = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                is SnapshotRead.Available -> DispositionMetadataRead.Available(read.value.intent)
                SnapshotRead.Unavailable -> DispositionMetadataRead.Unavailable
            }
        }
    }

    override suspend fun saveIntent(intent: DispositionIntentRecord): DispositionMetadataWrite =
        withContext(ioDispatcher) {
            mutexFor(intent.scope).withLock {
                when (val read = readLocked(intent.scope)) {
                    SnapshotRead.Unavailable -> DispositionMetadataWrite.Unavailable

                    is SnapshotRead.Available -> {
                        val current = read.value.intent
                        when {
                            current == null -> writeLocked(read.value.copy(intent = intent))

                            current.idempotencyKey != intent.idempotencyKey ||
                                current.payload != intent.payload ->
                                DispositionMetadataWrite.Conflict

                            current == intent -> DispositionMetadataWrite.Saved

                            else -> writeLocked(read.value.copy(intent = intent))
                        }
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: DispositionMetadataScope,
        expectedIdempotencyKey: String
    ): DispositionMetadataWrite = withContext(ioDispatcher) {
        if (expectedIdempotencyKey.isBlank() ||
            expectedIdempotencyKey.toByteArray(Charsets.UTF_8).size >
            DispositionIntentRecord.MAX_KEY_BYTES
        ) {
            return@withContext DispositionMetadataWrite.Stale
        }
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                SnapshotRead.Unavailable -> DispositionMetadataWrite.Unavailable

                is SnapshotRead.Available -> {
                    val current = read.value.intent
                    when {
                        current == null -> DispositionMetadataWrite.Saved

                        current.idempotencyKey != expectedIdempotencyKey ->
                            DispositionMetadataWrite.Stale

                        else -> writeLocked(read.value.copy(intent = null))
                    }
                }
            }
        }
    }

    private fun readLocked(scope: DispositionMetadataScope): SnapshotRead = try {
        val encrypted = storage.read(DispositionScopeBinding.fileKey(scope))
        if (encrypted == null) {
            SnapshotRead.Available(DispositionMetadataSnapshot(scope, null, null))
        } else {
            val snapshot = DispositionMetadataCodec.decode(cipher.decrypt(scope, encrypted))
            if (snapshot.scope ==
                scope
            ) {
                SnapshotRead.Available(snapshot)
            } else {
                SnapshotRead.Unavailable
            }
        }
    } catch (_: Exception) {
        SnapshotRead.Unavailable
    }

    private fun writeLocked(snapshot: DispositionMetadataSnapshot): DispositionMetadataWrite = try {
        val encrypted = cipher.encrypt(snapshot.scope, DispositionMetadataCodec.encode(snapshot))
        storage.write(DispositionScopeBinding.fileKey(snapshot.scope), encrypted)
        DispositionMetadataWrite.Saved
    } catch (_: Exception) {
        DispositionMetadataWrite.Unavailable
    }

    private fun mutexFor(scope: DispositionMetadataScope): Mutex = scopeLocks.computeIfAbsent(
        "${storage.lockNamespace}:${DispositionScopeBinding.fileKey(scope)}"
    ) { Mutex() }

    private sealed interface SnapshotRead {
        data class Available(val value: DispositionMetadataSnapshot) : SnapshotRead
        data object Unavailable : SnapshotRead
    }

    private companion object {
        val scopeLocks = ConcurrentHashMap<String, Mutex>()
    }
}
