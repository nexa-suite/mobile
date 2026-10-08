package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptObservationMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferReceiptTransfer
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Observation metadata has an isolated encrypted purpose and cannot be confused with receipt intent. */
class AppStockTransferReceiptObservationMetadataStore(
    private val backend: TransferReceiptObservationScopedBackend
) : StockTransferReceiptObservationMetadataStore {
    override suspend fun loadIntent(
        scope: StockTransferScope
    ): StockTransferReceiptObservationMetadataRead = withScopeLock(scope) {
        when (val stored = safeLoad(scope)) {
            TransferReceiptObservationScopedRead.Unavailable ->
                StockTransferReceiptObservationMetadataRead.Unavailable

            is TransferReceiptObservationScopedRead.Value -> when (val payload = stored.payload) {
                null -> StockTransferReceiptObservationMetadataRead.Available(null)

                else -> payload.decodeIntent()?.takeIf { it.scope == scope }
                    ?.let(StockTransferReceiptObservationMetadataRead::Available)
                    ?: StockTransferReceiptObservationMetadataRead.Unavailable
            }
        }
    }

    override suspend fun saveIntent(
        intent: StockTransferReceiptObservationIntent
    ): StockTransferReceiptObservationMetadataWrite = withScopeLock(intent.scope) {
        if (!intent.isValid()) {
            return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable
        }
        val current = when (val stored = safeLoad(intent.scope)) {
            TransferReceiptObservationScopedRead.Unavailable ->
                return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable

            is TransferReceiptObservationScopedRead.Value -> when (val payload = stored.payload) {
                null -> null

                else -> payload.decodeIntent()?.takeIf { it.scope == intent.scope }
                    ?: return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable
            }
        }
        if (current != null && !current.sameFrozenCommand(intent)) {
            return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable
        }
        write(intent)
    }

    override suspend fun markUnknownOutcome(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptObservationMetadataWrite = withScopeLock(scope) {
        if (idempotencyKey.isBlank()) {
            return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable
        }
        val current =
            readIntent(scope)
                ?: return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable
        if (current.idempotencyKey != idempotencyKey) {
            return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable
        }
        write(current.copy(status = StockTransferReceiptObservationIntentStatus.UnknownOutcome))
    }

    override suspend fun clearIntent(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptObservationMetadataWrite = withScopeLock(scope) {
        if (idempotencyKey.isBlank()) {
            return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable
        }
        val current =
            readIntent(scope)
                ?: return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable
        if (current.idempotencyKey != idempotencyKey) {
            return@withScopeLock StockTransferReceiptObservationMetadataWrite.Unavailable
        }
        if (backend.clear(scope)) {
            StockTransferReceiptObservationMetadataWrite.Saved
        } else {
            StockTransferReceiptObservationMetadataWrite.Unavailable
        }
    }

    private suspend fun readIntent(
        scope: StockTransferScope
    ): StockTransferReceiptObservationIntent? = when (val stored = safeLoad(scope)) {
        TransferReceiptObservationScopedRead.Unavailable -> null

        is TransferReceiptObservationScopedRead.Value -> when (val payload = stored.payload) {
            null -> null
            else -> payload.decodeIntent()?.takeIf { it.scope == scope }
        }
    }

    private suspend fun safeLoad(scope: StockTransferScope): TransferReceiptObservationScopedRead =
        try {
            backend.load(scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TransferReceiptObservationScopedRead.Unavailable
        }

    private suspend fun write(
        intent: StockTransferReceiptObservationIntent
    ): StockTransferReceiptObservationMetadataWrite {
        val encoded = try {
            observationJson.encodeToString(intent.toStored())
        } catch (_: SerializationException) {
            return StockTransferReceiptObservationMetadataWrite.Unavailable
        }
        return if (backend.save(intent.scope, encoded)) {
            StockTransferReceiptObservationMetadataWrite.Saved
        } else {
            StockTransferReceiptObservationMetadataWrite.Unavailable
        }
    }

    private fun String.decodeIntent(): StockTransferReceiptObservationIntent? = try {
        val stored = observationJson.decodeFromString<StoredReceiptObservationIntent>(this)
        val scope =
            StockTransferScope(
                stored.userId,
                stored.tenantId,
                stored.workspaceId,
                stored.membershipId
            )
        val transfer = stored.transfer.toTransfer()
        val decoded = StockTransferReceiptObservationIntent(
            scope = scope,
            idempotencyKey = stored.idempotencyKey,
            transfer = transfer,
            observedBatchNumber = stored.observedBatchNumber,
            observedExpirationDate = stored.observedExpirationDate,
            observedQuantityText = stored.observedQuantityText,
            observedUnit = stored.observedUnit,
            status = StockTransferReceiptObservationIntentStatus.valueOf(stored.status)
        )
        decoded.takeIf { stored.schemaVersion == SCHEMA_VERSION && it.isValid() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun StockTransferReceiptObservationIntent.toStored() = StoredReceiptObservationIntent(
        schemaVersion = SCHEMA_VERSION,
        userId = scope.userId,
        tenantId = scope.tenantId,
        workspaceId = scope.workspaceId,
        membershipId = scope.membershipId,
        idempotencyKey = idempotencyKey,
        status = status.name,
        transfer = transfer.toStored(),
        observedBatchNumber = observedBatchNumber,
        observedExpirationDate = observedExpirationDate,
        observedQuantityText = observedQuantityText,
        observedUnit = observedUnit
    )

    private fun StockTransferReceiptTransfer.toStored() = StoredReceiptTransfer(
        id, sourceWarehouseId, sourceZoneId, sourceLotId, destinationWarehouseId, destinationZoneId,
        destinationLotId, skuId, catalogItemId, batchNumber, expirationDate, requestedQuantityText,
        transferredQuantityText, mode, unit, status, reason, sourceVersionBefore,
        sourceVersionAfter,
        destinationVersionAfter, version, dispatchedAt, receivedAt
    )

    private fun StoredReceiptTransfer.toTransfer() = StockTransferReceiptTransfer(
        id, sourceWarehouseId, sourceZoneId, sourceLotId, destinationWarehouseId, destinationZoneId,
        destinationLotId, skuId, catalogItemId, batchNumber, expirationDate, requestedQuantityText,
        transferredQuantityText, mode, unit, status, reason, sourceVersionBefore,
        sourceVersionAfter,
        destinationVersionAfter, version, dispatchedAt, receivedAt
    )

    private fun StockTransferReceiptObservationIntent.isValid(): Boolean {
        val date = observedExpirationDate
        return idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 && transfer.isValid() &&
            observedBatchNumber.isNotBlank() && observedBatchNumber == observedBatchNumber.trim() &&
            observedBatchNumber.length <= MAX_BATCH_LENGTH &&
            observedBatchNumber.none(Char::isISOControl) &&
            (date == null || date.isCanonicalLocalDate()) &&
            observedQuantityText.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true &&
            observedUnit.isNotBlank() && observedUnit == observedUnit.trim() &&
            observedUnit.equals(transfer.unit, ignoreCase = true)
    }

    private fun StockTransferReceiptTransfer.isValid(): Boolean =
        id.isUuid() && sourceWarehouseId.isUuid() && sourceZoneId.isUuid() &&
            sourceLotId.isUuid() &&
            destinationWarehouseId.isUuid() && destinationZoneId.isUuid() &&
            (
                destinationLotId?.let {
                    it.isUuid()
                } ?: true
                ) && (skuId?.let { it.isUuid() } ?: true) &&
            (catalogItemId?.let(CATALOG_ID::matches) ?: true) &&
            requestedQuantityText.toBigDecimalOrNull()?.signum() == 1 &&
            transferredQuantityText.toBigDecimalOrNull()?.signum() == 1 &&
            batchNumber?.isNotBlank() == true && status == "IN_TRANSIT" &&
            dispatchedAt?.isNotBlank() == true &&
            unit.isNotBlank() && version >= 0 && sourceVersionBefore >= 0

    private fun String.isUuid(): Boolean = try {
        UUID.fromString(this).toString().equals(this, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun String.isCanonicalLocalDate(): Boolean = try {
        LocalDate.parse(this).toString() == this
    } catch (_: RuntimeException) {
        false
    }

    private suspend fun <T> withScopeLock(scope: StockTransferScope, action: suspend () -> T): T =
        locks.getOrPut(scope.lockKey()) { Mutex() }.withLock { action() }

    private fun StockTransferScope.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun StockTransferScope.lockKey(): String {
        val bytes = listOf(userId, tenantId, workspaceId, membershipId).joinToString("\u0000")
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_BATCH_LENGTH = 80
        val CATALOG_ID = Regex("(?i)CAT-[A-Z0-9-]{1,63}")
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

@Serializable
private data class StoredReceiptObservationIntent(
    val schemaVersion: Int,
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val idempotencyKey: String,
    val status: String,
    val transfer: StoredReceiptTransfer,
    val observedBatchNumber: String,
    val observedExpirationDate: String?,
    val observedQuantityText: String,
    val observedUnit: String
)

@Serializable
private data class StoredReceiptTransfer(
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

private val observationJson = Json { ignoreUnknownKeys = false }

@Module
@InstallIn(SingletonComponent::class)
object AppStockTransferReceiptObservationMetadataBindings {
    @Provides
    @Singleton
    fun stockTransferReceiptObservationMetadataStore(
        @ApplicationContext context: Context
    ): StockTransferReceiptObservationMetadataStore =
        AppStockTransferReceiptObservationMetadataStore(
            AndroidTransferReceiptObservationScopedBackend(
                AndroidScopedMetadataStore(
                    context,
                    ScopedMetadataPurpose.StockTransferReceiptObservation
                )
            )
        )
}

sealed interface TransferReceiptObservationScopedRead {
    data class Value(val payload: String?) : TransferReceiptObservationScopedRead
    data object Unavailable : TransferReceiptObservationScopedRead
}

interface TransferReceiptObservationScopedBackend {
    suspend fun load(scope: StockTransferScope): TransferReceiptObservationScopedRead
    suspend fun save(scope: StockTransferScope, payload: String): Boolean
    suspend fun clear(scope: StockTransferScope): Boolean
}

class AndroidTransferReceiptObservationScopedBackend(
    private val store: AndroidScopedMetadataStore
) : TransferReceiptObservationScopedBackend {
    override suspend fun load(scope: StockTransferScope): TransferReceiptObservationScopedRead =
        when (val stored = store.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> TransferReceiptObservationScopedRead.Unavailable

            is ScopedMetadataRead.Value -> TransferReceiptObservationScopedRead.Value(
                stored.payload
            )
        }

    override suspend fun save(scope: StockTransferScope, payload: String): Boolean =
        store.save(scope.toLocal(), payload)

    override suspend fun clear(scope: StockTransferScope): Boolean = store.clear(scope.toLocal())

    private fun StockTransferScope.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
}
