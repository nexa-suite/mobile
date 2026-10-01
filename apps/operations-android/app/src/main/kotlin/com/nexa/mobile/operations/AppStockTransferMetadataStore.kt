package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.warehouse.StockTransferIntent
import com.nexa.mobile.operations.feature.warehouse.StockTransferMetadataStore
import com.nexa.mobile.operations.feature.warehouse.StockTransferScope
import com.nexa.mobile.operations.feature.warehouse.TransferIntentStatus
import com.nexa.mobile.operations.feature.warehouse.TransferMetadataRead
import com.nexa.mobile.operations.feature.warehouse.TransferMetadataWrite
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Scope-bound durable record contains only the frozen command needed for explicit recovery. */
internal class AppStockTransferMetadataStore(
    private val local: StockTransferScopedMetadataBackend
) : StockTransferMetadataStore {
    override suspend fun loadIntent(
        scope: StockTransferScope
    ): TransferMetadataRead<StockTransferIntent> = withScopeLock(scope) {
        when (val read = local.load(scope)) {
            StockTransferScopedRead.Unavailable -> TransferMetadataRead.Unavailable
            is StockTransferScopedRead.Value -> {
                val payload = read.payload
                if (payload == null) {
                    TransferMetadataRead.Available(null)
                } else {
                    val intent = payload.decode(scope)
                    if (intent == null) TransferMetadataRead.Unavailable
                    else TransferMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(intent: StockTransferIntent): TransferMetadataWrite =
        withScopeLock(intent.scope) {
            val existing = readCurrent(intent.scope)
            when (existing) {
                TransferMetadataRead.Unavailable -> TransferMetadataWrite.Unavailable
                is TransferMetadataRead.Available -> {
                    val current = existing.value
                    if (current != null && !current.sameFrozenCommand(intent)) {
                        TransferMetadataWrite.Unavailable
                    } else {
                        write(intent)
                    }
                }
            }
        }

    override suspend fun markUnknownOutcome(
        scope: StockTransferScope,
        idempotencyKey: String
    ): TransferMetadataWrite = withScopeLock(scope) {
        when (val existing = readCurrent(scope)) {
            TransferMetadataRead.Unavailable -> TransferMetadataWrite.Unavailable
            is TransferMetadataRead.Available -> {
                val intent = existing.value ?: return@withScopeLock TransferMetadataWrite.Unavailable
                if (intent.idempotencyKey != idempotencyKey) {
                    TransferMetadataWrite.Unavailable
                } else {
                    write(intent.copy(status = TransferIntentStatus.UnknownOutcome))
                }
            }
        }
    }

    override suspend fun clearIntent(
        scope: StockTransferScope,
        idempotencyKey: String
    ): TransferMetadataWrite = withScopeLock(scope) {
        when (val existing = readCurrent(scope)) {
            TransferMetadataRead.Unavailable -> TransferMetadataWrite.Unavailable
            is TransferMetadataRead.Available -> {
                val intent = existing.value
                when {
                    intent == null -> TransferMetadataWrite.Saved
                    intent.idempotencyKey != idempotencyKey -> TransferMetadataWrite.Unavailable
                    local.clear(scope) -> TransferMetadataWrite.Saved
                    else -> TransferMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun readCurrent(scope: StockTransferScope): TransferMetadataRead<StockTransferIntent> =
        when (val read = local.load(scope)) {
            StockTransferScopedRead.Unavailable -> TransferMetadataRead.Unavailable
            is StockTransferScopedRead.Value -> {
                val payload = read.payload
                if (payload == null) TransferMetadataRead.Available(null)
                else payload.decode(scope)?.let { TransferMetadataRead.Available(it) }
                    ?: TransferMetadataRead.Unavailable
            }
        }

    private suspend fun write(intent: StockTransferIntent): TransferMetadataWrite =
        if (local.save(intent.scope, intent.encode())) {
            TransferMetadataWrite.Saved
        } else {
            TransferMetadataWrite.Unavailable
        }

    private suspend fun <T> withScopeLock(scope: StockTransferScope, operation: suspend () -> T): T =
        locks.getOrPut(scope.lockKey()) { Mutex() }.withLock { operation() }

    private fun StockTransferIntent.encode(): String = transferMetadataJson.encodeToString(
        StoredTransferIntent(
            schemaVersion = SCHEMA_VERSION,
            idempotencyKey = idempotencyKey,
            frozenPayload = frozenPayload,
            expectedSourceVersion = expectedSourceVersion,
            status = status.name
        )
    )

    private fun String.decode(scope: StockTransferScope): StockTransferIntent? = try {
        transferMetadataJson.decodeFromString<StoredTransferIntent>(this).let { stored ->
            if (stored.schemaVersion != SCHEMA_VERSION || stored.idempotencyKey.isBlank() ||
                stored.idempotencyKey.length > 160 || stored.frozenPayload.isBlank() ||
                stored.frozenPayload.length > MAX_PAYLOAD_CHARS || stored.expectedSourceVersion < 0
            ) {
                null
            } else {
                StockTransferIntent(
                    scope = scope,
                    idempotencyKey = stored.idempotencyKey,
                    frozenPayload = stored.frozenPayload,
                    expectedSourceVersion = stored.expectedSourceVersion,
                    status = TransferIntentStatus.valueOf(stored.status)
                )
            }
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun StockTransferIntent.sameFrozenCommand(other: StockTransferIntent): Boolean =
        scope == other.scope && idempotencyKey == other.idempotencyKey &&
            frozenPayload == other.frozenPayload && expectedSourceVersion == other.expectedSourceVersion

    private fun StockTransferScope.lockKey(): String {
        val bytes = listOf(userId, tenantId, workspaceId, membershipId).joinToString("\u0000")
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_PAYLOAD_CHARS = 16_000
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

@Serializable
private data class StoredTransferIntent(
    val schemaVersion: Int,
    val idempotencyKey: String,
    val frozenPayload: String,
    val expectedSourceVersion: Long,
    val status: String
)

private val transferMetadataJson = Json { ignoreUnknownKeys = false }

@Module
@InstallIn(SingletonComponent::class)
internal object AppStockTransferMetadataBindings {
    @Provides
    @Singleton
    fun stockTransferMetadataStore(@ApplicationContext context: Context): StockTransferMetadataStore =
        AppStockTransferMetadataStore(
            AndroidStockTransferScopedMetadataBackend(
                AndroidScopedMetadataStore(context, ScopedMetadataPurpose.StockTransfer)
            )
        )
}

private fun StockTransferScope.toLocal() =
    ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

internal sealed interface StockTransferScopedRead {
    data class Value(val payload: String?) : StockTransferScopedRead
    data object Unavailable : StockTransferScopedRead
}

internal interface StockTransferScopedMetadataBackend {
    suspend fun load(scope: StockTransferScope): StockTransferScopedRead
    suspend fun save(scope: StockTransferScope, payload: String): Boolean
    suspend fun clear(scope: StockTransferScope): Boolean
}

internal class AndroidStockTransferScopedMetadataBackend(
    private val store: AndroidScopedMetadataStore
) : StockTransferScopedMetadataBackend {
    override suspend fun load(scope: StockTransferScope): StockTransferScopedRead =
        when (val result = store.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> StockTransferScopedRead.Unavailable
            is ScopedMetadataRead.Value -> StockTransferScopedRead.Value(result.payload)
        }

    override suspend fun save(scope: StockTransferScope, payload: String): Boolean =
        store.save(scope.toLocal(), payload)

    override suspend fun clear(scope: StockTransferScope): Boolean = store.clear(scope.toLocal())
}
