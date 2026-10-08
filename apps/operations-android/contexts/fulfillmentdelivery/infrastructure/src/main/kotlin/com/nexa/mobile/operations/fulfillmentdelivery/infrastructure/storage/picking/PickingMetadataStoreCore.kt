package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.storage.picking

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface PickingRecordStorage {
    val lockNamespace: String

    fun read(fileKey: String): ByteArray?

    fun write(fileKey: String, encryptedRecord: ByteArray)
}

internal interface PickingRecordCipher {
    fun encrypt(scope: PickingMetadataScope, plaintext: ByteArray): ByteArray

    fun decrypt(scope: PickingMetadataScope, encryptedRecord: ByteArray): ByteArray
}

/** Scope-local atomic intent logic. Locks are shared across store objects in this app process. */
internal class PickingMetadataStoreCore(
    private val storage: PickingRecordStorage,
    private val cipher: PickingRecordCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PickingMetadataStore {
    override suspend fun loadIntent(
        scope: PickingMetadataScope
    ): PickingMetadataRead<PickingIntentMetadataRecord> = withContext(ioDispatcher) {
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                Read.Unavailable -> PickingMetadataRead.Unavailable

                is Read.Available -> {
                    val intent = read.snapshot.intent
                    if (intent?.status == PickingIntentStatus.Pending) {
                        val unknown = intent.copy(status = PickingIntentStatus.UnknownOutcome)
                        when (writeLocked(read.snapshot.copy(intent = unknown))) {
                            PickingMetadataWrite.Saved -> PickingMetadataRead.Available(unknown)
                            else -> PickingMetadataRead.Unavailable
                        }
                    } else {
                        PickingMetadataRead.Available(intent)
                    }
                }
            }
        }
    }

    override suspend fun saveIntent(intent: PickingIntentMetadataRecord): PickingMetadataWrite =
        withContext(ioDispatcher) {
            val scope = intent.scope
            mutexFor(scope).withLock {
                when (val read = readLocked(scope)) {
                    Read.Unavailable -> PickingMetadataWrite.Unavailable

                    is Read.Available -> {
                        val current = read.snapshot.intent
                        when {
                            current == null -> writeLocked(read.snapshot.copy(intent = intent))

                            current.idempotencyKey != intent.idempotencyKey ||
                                current.command != intent.command -> PickingMetadataWrite.Conflict

                            current == intent -> PickingMetadataWrite.Saved

                            else -> writeLocked(read.snapshot.copy(intent = intent))
                        }
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: PickingMetadataScope,
        expectedIdempotencyKey: String
    ): PickingMetadataWrite = withContext(ioDispatcher) {
        if (expectedIdempotencyKey.isBlank() ||
            expectedIdempotencyKey.toByteArray(Charsets.UTF_8).size >
            PickingIntentMetadataRecord.MAX_KEY_BYTES
        ) {
            return@withContext PickingMetadataWrite.Stale
        }
        mutexFor(scope).withLock {
            when (val read = readLocked(scope)) {
                Read.Unavailable -> PickingMetadataWrite.Unavailable

                is Read.Available -> {
                    val current = read.snapshot.intent
                    when {
                        current == null -> PickingMetadataWrite.Saved

                        current.idempotencyKey != expectedIdempotencyKey ->
                            PickingMetadataWrite.Stale

                        else -> writeLocked(read.snapshot.copy(intent = null))
                    }
                }
            }
        }
    }

    private fun readLocked(scope: PickingMetadataScope): Read = try {
        val encrypted = storage.read(PickingScopeBinding.fileKey(scope))
        if (encrypted == null) {
            Read.Available(PickingMetadataSnapshot(scope, null))
        } else {
            val snapshot = PickingMetadataCodec.decode(cipher.decrypt(scope, encrypted))
            if (snapshot.scope == scope) Read.Available(snapshot) else Read.Unavailable
        }
    } catch (_: Exception) {
        Read.Unavailable
    }

    private fun writeLocked(snapshot: PickingMetadataSnapshot): PickingMetadataWrite = try {
        val plaintext = PickingMetadataCodec.encode(snapshot)
        val encrypted = cipher.encrypt(snapshot.scope, plaintext)
        storage.write(PickingScopeBinding.fileKey(snapshot.scope), encrypted)
        PickingMetadataWrite.Saved
    } catch (_: Exception) {
        PickingMetadataWrite.Unavailable
    }

    private fun mutexFor(scope: PickingMetadataScope): Mutex = scopeLocks.computeIfAbsent(
        "${storage.lockNamespace}:${PickingScopeBinding.fileKey(scope)}"
    ) { Mutex() }

    private sealed interface Read {
        data class Available(val snapshot: PickingMetadataSnapshot) : Read
        data object Unavailable : Read
    }

    private companion object {
        val scopeLocks = ConcurrentHashMap<String, Mutex>()
    }
}
