package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptIntent
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptIntentStatus
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptMetadataRead
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptMetadataStore
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptTransfer
import com.nexa.mobile.operations.feature.warehouse.StockTransferScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stores only the immutable receipt route identity/version and the facts shown before dispatch. */
internal class AppStockTransferReceiptMetadataStore(
    private val backend: TransferReceiptScopedMetadataBackend
) : StockTransferReceiptMetadataStore {
    override suspend fun loadIntent(scope: StockTransferScope): StockTransferReceiptMetadataRead = withScopeLock(scope) {
        when (val stored = safeLoad(scope)) {
            TransferReceiptScopedRead.Unavailable -> StockTransferReceiptMetadataRead.Unavailable
            is TransferReceiptScopedRead.Value -> when (val payload = stored.payload) {
                null -> StockTransferReceiptMetadataRead.Available(null)
                else -> payload.decodeIntent()?.takeIf { it.scope == scope }
                    ?.let(StockTransferReceiptMetadataRead::Available)
                    ?: StockTransferReceiptMetadataRead.Unavailable
            }
        }
    }

    override suspend fun saveIntent(intent: StockTransferReceiptIntent): StockTransferReceiptMetadataWrite =
        withScopeLock(intent.scope) {
            if (!intent.isValid()) return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
            val current = when (val stored = safeLoad(intent.scope)) {
                TransferReceiptScopedRead.Unavailable ->
                    return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
                is TransferReceiptScopedRead.Value -> when (val payload = stored.payload) {
                    null -> null
                    else -> payload.decodeIntent()?.takeIf { it.scope == intent.scope }
                        ?: return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
                }
            }
            if (current != null && !current.sameFrozenCommand(intent)) {
                return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
            }
            write(intent)
        }

    override suspend fun markUnknownOutcome(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptMetadataWrite = withScopeLock(scope) {
        if (idempotencyKey.isBlank()) return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
        val current = readIntent(scope) ?: return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
        if (current.idempotencyKey != idempotencyKey) return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
        write(current.copy(status = StockTransferReceiptIntentStatus.UnknownOutcome))
    }

    override suspend fun clearIntent(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptMetadataWrite = withScopeLock(scope) {
        if (idempotencyKey.isBlank()) return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
        val current = readIntent(scope) ?: return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
        if (current.idempotencyKey != idempotencyKey) return@withScopeLock StockTransferReceiptMetadataWrite.Unavailable
        if (backend.clear(scope)) StockTransferReceiptMetadataWrite.Saved else StockTransferReceiptMetadataWrite.Unavailable
    }

    private suspend fun readIntent(scope: StockTransferScope): StockTransferReceiptIntent? {
        return when (val stored = safeLoad(scope)) {
            TransferReceiptScopedRead.Unavailable -> null
            is TransferReceiptScopedRead.Value -> when (val payload = stored.payload) {
                null -> null
                else -> payload.decodeIntent()?.takeIf { it.scope == scope } ?: return null
            }
        }
    }

    private suspend fun safeLoad(scope: StockTransferScope) = try {
        backend.load(scope)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TransferReceiptScopedRead.Unavailable
    }

    private suspend fun write(intent: StockTransferReceiptIntent): StockTransferReceiptMetadataWrite {
        val encoded = try {
            transferReceiptJson.encodeToString(intent.toStored())
        } catch (_: SerializationException) {
            return StockTransferReceiptMetadataWrite.Unavailable
        }
        return if (backend.save(intent.scope, encoded)) StockTransferReceiptMetadataWrite.Saved
        else StockTransferReceiptMetadataWrite.Unavailable
    }

    private fun String.decodeIntent(): StockTransferReceiptIntent? = try {
        val stored = transferReceiptJson.decodeFromString<StoredTransferReceiptIntent>(this)
        val transfer = stored.transfer.toTransfer()
        if (stored.schemaVersion != SCHEMA_VERSION || stored.idempotencyKey.isBlank() ||
            stored.idempotencyKey.length > 160 || !transfer.isValid()
        ) null else StockTransferReceiptIntent(
            scope = StockTransferScope(stored.userId, stored.tenantId, stored.workspaceId, stored.membershipId),
            idempotencyKey = stored.idempotencyKey,
            transfer = transfer,
            status = StockTransferReceiptIntentStatus.valueOf(stored.status)
        )
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun StockTransferReceiptIntent.toStored() = StoredTransferReceiptIntent(
        schemaVersion = SCHEMA_VERSION,
        userId = scope.userId,
        tenantId = scope.tenantId,
        workspaceId = scope.workspaceId,
        membershipId = scope.membershipId,
        idempotencyKey = idempotencyKey,
        status = status.name,
        transfer = transfer.toStored()
    )

    private fun StockTransferReceiptTransfer.toStored() = StoredTransferReceiptTransfer(
        id, sourceWarehouseId, sourceZoneId, sourceLotId, destinationWarehouseId, destinationZoneId,
        destinationLotId, skuId, catalogItemId, batchNumber, expirationDate, requestedQuantityText,
        transferredQuantityText, mode, unit, status, reason, sourceVersionBefore, sourceVersionAfter,
        destinationVersionAfter, version, dispatchedAt, receivedAt
    )

    private fun StoredTransferReceiptTransfer.toTransfer() = StockTransferReceiptTransfer(
        id, sourceWarehouseId, sourceZoneId, sourceLotId, destinationWarehouseId, destinationZoneId,
        destinationLotId, skuId, catalogItemId, batchNumber, expirationDate, requestedQuantityText,
        transferredQuantityText, mode, unit, status, reason, sourceVersionBefore, sourceVersionAfter,
        destinationVersionAfter, version, dispatchedAt, receivedAt
    )

    private fun StockTransferReceiptIntent.isValid(): Boolean =
        idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 && transfer.isValid()

    private fun StockTransferReceiptTransfer.isValid(): Boolean =
        id.isUuid() && sourceWarehouseId.isUuid() && sourceZoneId.isUuid() && sourceLotId.isUuid() &&
            destinationWarehouseId.isUuid() && destinationZoneId.isUuid() &&
            (destinationLotId?.let { it.isUuid() } ?: true) &&
            (skuId?.let { it.isUuid() } ?: true) &&
            (catalogItemId?.let { it.matches(CATALOG_ID) } ?: true) &&
            requestedQuantityText.toBigDecimalOrNull()?.signum() == 1 &&
            transferredQuantityText.toBigDecimalOrNull()?.signum() == 1 &&
            unit.isNotBlank() && status.isNotBlank() && version >= 0 && sourceVersionBefore >= 0

    private fun String.isUuid(): Boolean = try {
        java.util.UUID.fromString(this).toString().equals(this, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

    private suspend fun <T> withScopeLock(scope: StockTransferScope, action: suspend () -> T): T =
        locks.getOrPut(scope.lockKey()) { Mutex() }.withLock { action() }

    private fun StockTransferScope.toLocal() = ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun StockTransferScope.lockKey(): String {
        val bytes = listOf(userId, tenantId, workspaceId, membershipId).joinToString("\u0000")
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        val CATALOG_ID = Regex("(?i)CAT-[A-Z0-9-]{1,63}")
        val locks = ConcurrentHashMap<String, Mutex>()
    }

}

@Serializable
private data class StoredTransferReceiptIntent(
    val schemaVersion: Int,
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val idempotencyKey: String,
    val status: String,
    val transfer: StoredTransferReceiptTransfer
)

@Serializable
private data class StoredTransferReceiptTransfer(
    val id: String,
    val sourceWarehouseId: String,
    val sourceZoneId: String,
    val sourceLotId: String,
    val destinationWarehouseId: String,
    val destinationZoneId: String,
    val destinationLotId: String?,
    val skuId: String?,
    val catalogItemId: String?,
    val batchNumber: String?,
    val expirationDate: String?,
    val requestedQuantityText: String,
    val transferredQuantityText: String,
    val mode: String,
    val unit: String,
    val status: String,
    val reason: String,
    val sourceVersionBefore: Long,
    val sourceVersionAfter: Long?,
    val destinationVersionAfter: Long?,
    val version: Long,
    val dispatchedAt: String?,
    val receivedAt: String?
)

private val transferReceiptJson = Json { ignoreUnknownKeys = false }

@Module
@InstallIn(SingletonComponent::class)
internal object AppStockTransferReceiptMetadataBindings {
    @Provides
    @Singleton
    fun stockTransferReceiptMetadataStore(@ApplicationContext context: Context): StockTransferReceiptMetadataStore =
        AppStockTransferReceiptMetadataStore(
            AndroidTransferReceiptScopedMetadataBackend(
                AndroidScopedMetadataStore(context, ScopedMetadataPurpose.StockTransferReceipt)
            )
        )
}

internal sealed interface TransferReceiptScopedRead {
    data class Value(val payload: String?) : TransferReceiptScopedRead
    data object Unavailable : TransferReceiptScopedRead
}

internal interface TransferReceiptScopedMetadataBackend {
    suspend fun load(scope: StockTransferScope): TransferReceiptScopedRead
    suspend fun save(scope: StockTransferScope, payload: String): Boolean
    suspend fun clear(scope: StockTransferScope): Boolean
}

internal class AndroidTransferReceiptScopedMetadataBackend(
    private val store: AndroidScopedMetadataStore
) : TransferReceiptScopedMetadataBackend {
    override suspend fun load(scope: StockTransferScope): TransferReceiptScopedRead =
        when (val result = store.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> TransferReceiptScopedRead.Unavailable
            is ScopedMetadataRead.Value -> TransferReceiptScopedRead.Value(result.payload)
        }

    override suspend fun save(scope: StockTransferScope, payload: String): Boolean = store.save(scope.toLocal(), payload)
    override suspend fun clear(scope: StockTransferScope): Boolean = store.clear(scope.toLocal())

    private fun StockTransferScope.toLocal() = ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
}
