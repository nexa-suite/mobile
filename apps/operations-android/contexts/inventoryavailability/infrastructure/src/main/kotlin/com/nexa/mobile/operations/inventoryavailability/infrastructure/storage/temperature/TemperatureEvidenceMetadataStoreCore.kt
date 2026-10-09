package com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface TemperatureRecordStorage {
    val lockNamespace: String

    fun read(fileKey: String): ByteArray?

    fun write(fileKey: String, encryptedRecord: ByteArray)
}

internal interface TemperatureRecordCipher {
    fun encrypt(scope: TemperatureMetadataScope, plaintext: ByteArray): ByteArray

    fun decrypt(scope: TemperatureMetadataScope, encryptedRecord: ByteArray): ByteArray
}

/** Serialized, typed scope-local read/modify/write behavior, independent for JVM tests. */
internal class TemperatureEvidenceMetadataStoreCore(
    private val storage: TemperatureRecordStorage,
    private val cipher: TemperatureRecordCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TemperatureEvidenceMetadataStore {
    override suspend fun loadDraft(
        scope: TemperatureMetadataScope
    ): TemperatureMetadataRead<TemperatureEvidenceDraftRecord> = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                is SnapshotRead.Available -> TemperatureMetadataRead.Available(read.value.draft)
                SnapshotRead.Unavailable -> TemperatureMetadataRead.Unavailable
            }
        }
    }

    override suspend fun saveDraft(
        scope: TemperatureMetadataScope,
        draft: TemperatureEvidenceDraftRecord
    ): TemperatureMetadataWrite = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                SnapshotRead.Unavailable -> TemperatureMetadataWrite.Unavailable
                is SnapshotRead.Available -> writeLocked(read.value.copy(draft = draft))
            }
        }
    }

    override suspend fun loadIntent(
        scope: TemperatureMetadataScope
    ): TemperatureMetadataRead<TemperatureEvidenceIntentRecord> = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                is SnapshotRead.Available -> TemperatureMetadataRead.Available(read.value.intent)
                SnapshotRead.Unavailable -> TemperatureMetadataRead.Unavailable
            }
        }
    }

    override suspend fun saveIntent(
        intent: TemperatureEvidenceIntentRecord
    ): TemperatureMetadataWrite = withContext(ioDispatcher) {
        val scope = intent.scope
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                SnapshotRead.Unavailable -> TemperatureMetadataWrite.Unavailable

                is SnapshotRead.Available -> {
                    val current = read.value.intent
                    when {
                        current == null -> writeLocked(read.value.copy(intent = intent))

                        current.idempotencyKey != intent.idempotencyKey ||
                            current.payload != intent.payload -> TemperatureMetadataWrite.Conflict

                        current == intent -> TemperatureMetadataWrite.Saved

                        else -> writeLocked(read.value.copy(intent = intent))
                    }
                }
            }
        }
    }

    override suspend fun markUnknownOutcome(
        scope: TemperatureMetadataScope,
        expectedIdempotencyKey: String
    ): TemperatureMetadataWrite = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                SnapshotRead.Unavailable -> TemperatureMetadataWrite.Unavailable

                is SnapshotRead.Available -> {
                    val current = read.value.intent
                    when {
                        current == null || current.idempotencyKey != expectedIdempotencyKey ->
                            TemperatureMetadataWrite.Stale

                        current.status == TemperatureEvidenceIntentStatus.UnknownOutcome ->
                            TemperatureMetadataWrite.Saved

                        else -> writeLocked(
                            read.value.copy(
                                intent = current.copy(
                                    status = TemperatureEvidenceIntentStatus.UnknownOutcome
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun clearIntent(
        scope: TemperatureMetadataScope,
        expectedIdempotencyKey: String
    ): TemperatureMetadataWrite = withContext(ioDispatcher) {
        if (expectedIdempotencyKey.isBlank() ||
            expectedIdempotencyKey.toByteArray(Charsets.UTF_8).size >
            TemperatureEvidenceIntentRecord.MAX_KEY_BYTES
        ) {
            return@withContext TemperatureMetadataWrite.Stale
        }
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                SnapshotRead.Unavailable -> TemperatureMetadataWrite.Unavailable

                is SnapshotRead.Available -> {
                    val current = read.value.intent
                    when {
                        current == null -> TemperatureMetadataWrite.Saved

                        current.idempotencyKey != expectedIdempotencyKey ->
                            TemperatureMetadataWrite.Stale

                        else -> writeLocked(read.value.copy(intent = null))
                    }
                }
            }
        }
    }

    private fun readLocked(scope: TemperatureMetadataScope): SnapshotRead = try {
        val encrypted = storage.read(TemperatureMetadataScopeBinding.fileKey(scope))
        if (encrypted == null) {
            SnapshotRead.Available(TemperatureMetadataSnapshot(scope, draft = null, intent = null))
        } else {
            val snapshot = TemperatureEvidenceMetadataCodec.decode(cipher.decrypt(scope, encrypted))
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

    private fun writeLocked(snapshot: TemperatureMetadataSnapshot): TemperatureMetadataWrite = try {
        val plaintext = TemperatureEvidenceMetadataCodec.encode(snapshot)
        val encrypted = cipher.encrypt(snapshot.scope, plaintext)
        storage.write(TemperatureMetadataScopeBinding.fileKey(snapshot.scope), encrypted)
        TemperatureMetadataWrite.Saved
    } catch (_: Exception) {
        TemperatureMetadataWrite.Unavailable
    }

    private fun mutexFor(scope: TemperatureMetadataScope): Mutex = scopeLocks.computeIfAbsent(
        "${storage.lockNamespace}:${TemperatureMetadataScopeBinding.fileKey(scope)}"
    ) { Mutex() }

    private sealed interface SnapshotRead {
        data class Available(val value: TemperatureMetadataSnapshot) : SnapshotRead
        data object Unavailable : SnapshotRead
    }

    private companion object {
        val scopeLocks = ConcurrentHashMap<String, Mutex>()
    }
}
