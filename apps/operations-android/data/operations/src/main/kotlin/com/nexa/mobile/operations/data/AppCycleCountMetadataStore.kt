package com.nexa.mobile.operations.data

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.warehouse.application.CycleCountMetadataStore
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountCorrection
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountCorrectionIntent
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountIntent
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountIntentStatus
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountLot
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountRecord
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountScope
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountStoredWork
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface CycleCountMetadataBackend {
    suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead
    suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean
}

@Singleton
class AndroidCycleCountMetadataBackend @Inject constructor(@ApplicationContext context: Context) :
    CycleCountMetadataBackend {
    private val store =
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.CycleCountCorrection)
    override suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead = store.load(scope)
    override suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean =
        store.save(scope, payload)
}

/** Typed, scope-bound command metadata; local contents never authorize or assert stock. */
class AppCycleCountMetadataStore(private val backend: CycleCountMetadataBackend) :
    CycleCountMetadataStore {
    override suspend fun load(scope: CycleCountScope): CycleCountMetadataRead =
        withScopeLock(scope) {
            when (val existing = read(scope)) {
                Read.Unavailable -> CycleCountMetadataRead.Unavailable
                is Read.Available -> CycleCountMetadataRead.Available(existing.work)
            }
        }

    override suspend fun saveDraft(work: CycleCountStoredWork): CycleCountMetadataWrite =
        withScopeLock(work.scope) {
            if (!work.isValid()) return@withScopeLock CycleCountMetadataWrite.Unavailable
            val current = when (val existing = read(work.scope)) {
                Read.Unavailable -> return@withScopeLock CycleCountMetadataWrite.Unavailable
                is Read.Available -> existing.work
            }
            if (current?.countIntent != null || current?.correctionIntent != null) {
                return@withScopeLock CycleCountMetadataWrite.Unavailable
            }
            val changed = current != null && (
                current.selectedLotId != work.selectedLotId ||
                    current.observedQuantityText != work.observedQuantityText
                )
            if (changed && current?.recordedCount?.status == REQUESTED &&
                current.appliedCorrection == null
            ) {
                return@withScopeLock CycleCountMetadataWrite.Unavailable
            }
            val toSave = work.copy(
                recordedCount = if (changed) null else work.recordedCount ?: current?.recordedCount,
                appliedCorrection = if (changed) {
                    null
                } else {
                    work.appliedCorrection
                        ?: current?.appliedCorrection
                }
            )
            write(toSave)
        }

    override suspend fun freezeCount(intent: CycleCountIntent): CycleCountMetadataWrite =
        withScopeLock(intent.scope) {
            if (!intent.isValid()) return@withScopeLock CycleCountMetadataWrite.Unavailable
            val current = when (val existing = read(intent.scope)) {
                Read.Unavailable -> return@withScopeLock CycleCountMetadataWrite.Unavailable
                is Read.Available -> existing.work
            }
            val existingIntent = current?.countIntent
            if (existingIntent != null) {
                return@withScopeLock if (existingIntent.sameFrozenCommand(intent)) {
                    write(current.copy(countIntent = intent))
                } else {
                    CycleCountMetadataWrite.Unavailable
                }
            }
            if (current?.correctionIntent != null ||
                (current?.recordedCount?.status == REQUESTED && current.appliedCorrection == null)
            ) {
                return@withScopeLock CycleCountMetadataWrite.Unavailable
            }
            write(
                (current ?: CycleCountStoredWork(intent.scope, null, "")).copy(
                    selectedLotId = intent.lot.id,
                    observedQuantityText = intent.observedQuantityText,
                    countIntent = intent,
                    recordedCount = null,
                    correctionIntent = null,
                    appliedCorrection = null
                )
            )
        }

    override suspend fun markCountUnknown(
        scope: CycleCountScope,
        idempotencyKey: String
    ): CycleCountMetadataWrite = updateCountIntent(scope, idempotencyKey) {
        it.copy(status = CycleCountIntentStatus.UnknownOutcome)
    }

    override suspend fun completeCount(
        scope: CycleCountScope,
        idempotencyKey: String,
        count: CycleCountRecord
    ): CycleCountMetadataWrite = withScopeLock(scope) {
        val current =
            currentForKey(scope, idempotencyKey)
                ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        val intent = current.countIntent ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        if (count.lotId != intent.lot.id || count.warehouseId != intent.lot.warehouseId ||
            count.zoneId != intent.lot.zoneId || count.lotVersion != intent.lot.version ||
            count.actorMembershipId != scope.membershipId || !count.isValid()
        ) {
            return@withScopeLock CycleCountMetadataWrite.Unavailable
        }
        write(
            current.copy(
                countIntent = null,
                recordedCount = count,
                correctionIntent = null,
                appliedCorrection = null
            )
        )
    }

    override suspend fun clearCountIntent(
        scope: CycleCountScope,
        idempotencyKey: String
    ): CycleCountMetadataWrite = withScopeLock(scope) {
        val current =
            currentForKey(scope, idempotencyKey)
                ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        if (current.countIntent?.idempotencyKey !=
            idempotencyKey
        ) {
            return@withScopeLock CycleCountMetadataWrite.Unavailable
        }
        write(current.copy(countIntent = null))
    }

    override suspend fun freezeCorrection(
        intent: CycleCountCorrectionIntent
    ): CycleCountMetadataWrite = withScopeLock(intent.scope) {
        if (!intent.isValid()) return@withScopeLock CycleCountMetadataWrite.Unavailable
        val current = when (val existing = read(intent.scope)) {
            Read.Unavailable -> return@withScopeLock CycleCountMetadataWrite.Unavailable

            is Read.Available ->
                existing.work
                    ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        }
        if (current.recordedCount != intent.count || intent.count.status != REQUESTED ||
            current.countIntent != null || current.appliedCorrection != null
        ) {
            return@withScopeLock CycleCountMetadataWrite.Unavailable
        }
        val existingIntent = current.correctionIntent
        if (existingIntent != null && !existingIntent.sameFrozenCommand(intent)) {
            return@withScopeLock CycleCountMetadataWrite.Unavailable
        }
        write(current.copy(correctionIntent = intent))
    }

    override suspend fun markCorrectionUnknown(
        scope: CycleCountScope,
        idempotencyKey: String
    ): CycleCountMetadataWrite = updateCorrectionIntent(scope, idempotencyKey) {
        it.copy(status = CycleCountIntentStatus.UnknownOutcome)
    }

    override suspend fun completeCorrection(
        scope: CycleCountScope,
        idempotencyKey: String,
        correction: CycleCountCorrection
    ): CycleCountMetadataWrite = withScopeLock(scope) {
        val current =
            currentForKey(scope, idempotencyKey)
                ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        val intent =
            current.correctionIntent ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        if (correction.cycleCountId != intent.count.id || correction.lotId != intent.count.lotId ||
            correction.warehouseId != intent.count.warehouseId ||
            correction.zoneId != intent.count.zoneId ||
            correction.lotVersionBefore != intent.expectedLotVersion ||
            correction.lotVersionAfter != intent.expectedLotVersion + 1 ||
            correction.quantityBeforeText.toBigDecimalOrNull()?.compareTo(
                intent.count.expectedQuantityText.toBigDecimal()
            ) !=
            0 ||
            correction.quantityAfterText.toBigDecimalOrNull()?.compareTo(
                intent.count.observedQuantityText.toBigDecimal()
            ) !=
            0 ||
            correction.actorMembershipId != scope.membershipId || !correction.isValid()
        ) {
            return@withScopeLock CycleCountMetadataWrite.Unavailable
        }
        write(current.copy(correctionIntent = null, appliedCorrection = correction))
    }

    override suspend fun clearCorrectionIntent(
        scope: CycleCountScope,
        idempotencyKey: String
    ): CycleCountMetadataWrite = withScopeLock(scope) {
        val current =
            currentForKey(scope, idempotencyKey)
                ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        if (current.correctionIntent?.idempotencyKey != idempotencyKey) {
            return@withScopeLock CycleCountMetadataWrite.Unavailable
        }
        write(current.copy(correctionIntent = null))
    }

    override suspend fun clearStaleCount(
        scope: CycleCountScope,
        countId: String
    ): CycleCountMetadataWrite = withScopeLock(scope) {
        val current = when (val existing = read(scope)) {
            Read.Unavailable -> return@withScopeLock CycleCountMetadataWrite.Unavailable

            is Read.Available ->
                existing.work
                    ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        }
        if (current.recordedCount?.id != countId || current.countIntent != null ||
            current.correctionIntent != null
        ) {
            return@withScopeLock CycleCountMetadataWrite.Unavailable
        }
        write(
            current.copy(
                selectedLotId = null,
                observedQuantityText = "",
                recordedCount = null,
                appliedCorrection = null
            )
        )
    }

    private suspend fun updateCountIntent(
        scope: CycleCountScope,
        key: String,
        update: (CycleCountIntent) -> CycleCountIntent
    ): CycleCountMetadataWrite = withScopeLock(scope) {
        if (key.isBlank()) return@withScopeLock CycleCountMetadataWrite.Unavailable
        val current =
            currentForKey(scope, key) ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        val intent = current.countIntent ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        if (intent.idempotencyKey != key) return@withScopeLock CycleCountMetadataWrite.Unavailable
        write(current.copy(countIntent = update(intent)))
    }

    private suspend fun updateCorrectionIntent(
        scope: CycleCountScope,
        key: String,
        update: (CycleCountCorrectionIntent) -> CycleCountCorrectionIntent
    ): CycleCountMetadataWrite = withScopeLock(scope) {
        if (key.isBlank()) return@withScopeLock CycleCountMetadataWrite.Unavailable
        val current =
            currentForKey(scope, key) ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        val intent =
            current.correctionIntent ?: return@withScopeLock CycleCountMetadataWrite.Unavailable
        if (intent.idempotencyKey != key) return@withScopeLock CycleCountMetadataWrite.Unavailable
        write(current.copy(correctionIntent = update(intent)))
    }

    private suspend fun currentForKey(scope: CycleCountScope, key: String): CycleCountStoredWork? =
        when (val existing = read(scope)) {
            Read.Unavailable -> null

            is Read.Available -> existing.work?.takeIf {
                it.scope == scope &&
                    (
                        it.countIntent?.idempotencyKey == key ||
                            it.correctionIntent?.idempotencyKey == key
                        )
            }
        }

    private suspend fun read(scope: CycleCountScope): Read = try {
        when (val result = backend.load(scope.toStorageScope())) {
            ScopedMetadataRead.Unavailable -> Read.Unavailable

            is ScopedMetadataRead.Value -> when (val payload = result.payload) {
                null -> Read.Available(null)

                else -> payload.decodeWork()?.takeIf { it.scope == scope }?.let(Read::Available)
                    ?: Read.Unavailable
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Read.Unavailable
    }

    private suspend fun write(work: CycleCountStoredWork): CycleCountMetadataWrite {
        if (!work.isValid()) return CycleCountMetadataWrite.Unavailable
        val encoded = try {
            cycleCountStorageJson.encodeToString(work.toWire())
        } catch (_: SerializationException) {
            return CycleCountMetadataWrite.Unavailable
        }
        return try {
            if (backend.save(work.scope.toStorageScope(), encoded)) {
                CycleCountMetadataWrite.Saved
            } else {
                CycleCountMetadataWrite.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CycleCountMetadataWrite.Unavailable
        }
    }

    private suspend fun <T> withScopeLock(scope: CycleCountScope, block: suspend () -> T): T =
        locks.getOrPut(scope.lockKey()) { Mutex() }.withLock { block() }

    private fun CycleCountScope.toStorageScope() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun CycleCountScope.lockKey(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(userId, tenantId, workspaceId, membershipId).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private sealed interface Read {
        data object Unavailable : Read
        data class Available(val work: CycleCountStoredWork?) : Read
    }

    private companion object {
        const val REQUESTED = "REQUESTED"
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

private val cycleCountStorageJson = Json { ignoreUnknownKeys = false }

private fun CycleCountStoredWork.isValid(): Boolean {
    val selected = selectedLotId
    val countCommand = countIntent
    val count = recordedCount
    val correctionCommand = correctionIntent
    val correction = appliedCorrection
    return scope.isValid() &&
        (selected == null || selected.isUuid()) && observedQuantityText.length <= 64 &&
        (
            observedQuantityText.isEmpty() ||
                observedQuantityText.toBigDecimalOrNull()?.isValidQuantity() == true
            ) &&
        (countCommand == null || (countCommand.isValid() && countCommand.scope == scope)) &&
        (count == null || (count.isValid() && count.actorMembershipId == scope.membershipId)) &&
        (
            correctionCommand == null ||
                (correctionCommand.isValid() && correctionCommand.scope == scope)
            ) &&
        (
            correction == null ||
                (correction.isValid() && correction.actorMembershipId == scope.membershipId)
            )
}

private fun CycleCountScope.isValid(): Boolean =
    listOf(userId, tenantId, workspaceId, membershipId).all { it.isUuid() }

private fun CycleCountIntent.isValid(): Boolean =
    scope.isValid() && idempotencyKey.isValidKey() && lot.isValid() &&
        observedQuantityText.toBigDecimalOrNull()?.isValidQuantity() == true &&
        frozenBody ==
        "{\"observedQuantity\":${observedQuantityText.toBigDecimal().toPlainString()},\"unit\":\"${lot.unit}\"}"

private fun CycleCountCorrectionIntent.isValid(): Boolean =
    scope.isValid() && idempotencyKey.isValidKey() && count.isValid() &&
        expectedLotVersion == count.lotVersion &&
        count.status == "REQUESTED"

private fun CycleCountLot.isValid(): Boolean {
    val itemId = catalogItemId
    return id.isUuid() && warehouseId.isUuid() && zoneId.isUuid() &&
        (itemId == null || Regex("(?i)CAT-[A-Z0-9-]{1,63}").matches(itemId)) &&
        batchNumber.isNotBlank() && batchNumber.length <= 80 &&
        expirationDate.toLocalDateStrict() != null &&
        onHandText.toBigDecimalOrNull()?.isValidQuantity() == true &&
        reservedText.toBigDecimalOrNull()?.isValidQuantity() == true &&
        availableText.toBigDecimalOrNull()?.isValidQuantity() == true && unit.isValidUnit() &&
        status.isNotBlank() && version >= 0
}

private fun CycleCountRecord.isValid(): Boolean =
    id.isUuid() && lotId.isUuid() && warehouseId.isUuid() && zoneId.isUuid() && lotVersion >= 0 &&
        expectedQuantityText.toBigDecimalOrNull()?.isValidQuantity() == true &&
        observedQuantityText.toBigDecimalOrNull()?.isValidQuantity() == true &&
        unit.isValidUnit() &&
        status in setOf("RECORDED", "REQUESTED") && actorMembershipId.isUuid() &&
        recordedAt.toInstantStrict() != null

private fun CycleCountCorrection.isValid(): Boolean =
    id.isUuid() && cycleCountId.isUuid() && lotId.isUuid() && warehouseId.isUuid() &&
        zoneId.isUuid() &&
        lotVersionBefore >= 0 && lotVersionAfter == lotVersionBefore + 1 &&
        quantityBeforeText.toBigDecimalOrNull()?.isValidQuantity() == true &&
        quantityAfterText.toBigDecimalOrNull()?.isValidQuantity() == true &&
        quantityDeltaText.toBigDecimalOrNull()?.compareTo(
            quantityAfterText.toBigDecimal().subtract(quantityBeforeText.toBigDecimal())
        ) == 0 && unit.isValidUnit() && actorMembershipId.isUuid() &&
        recordedAt.toInstantStrict() != null

private fun CycleCountStoredWork.toWire() = StoredCycleCountWork(
    schemaVersion = 1,
    userId = scope.userId,
    tenantId = scope.tenantId,
    workspaceId = scope.workspaceId,
    membershipId = scope.membershipId,
    selectedLotId = selectedLotId,
    observedQuantityText = observedQuantityText,
    countIntent = countIntent?.toWire(),
    recordedCount = recordedCount?.toWire(),
    correctionIntent = correctionIntent?.toWire(),
    appliedCorrection = appliedCorrection?.toWire()
)

private fun CycleCountIntent.toWire() = StoredCycleCountIntent(
    idempotencyKey,
    lot.toWire(),
    observedQuantityText,
    frozenBody,
    status.name
)

private fun CycleCountCorrectionIntent.toWire() = StoredCycleCountCorrectionIntent(
    idempotencyKey,
    count.toWire(),
    expectedLotVersion,
    status.name
)

private fun CycleCountLot.toWire() = StoredCycleCountLot(
    id, warehouseId, zoneId, catalogItemId, batchNumber, expirationDate,
    onHandText, reservedText, availableText, unit, status, version
)

private fun CycleCountRecord.toWire() = StoredCycleCountRecord(
    id, lotId, warehouseId, zoneId, lotVersion, expectedQuantityText, observedQuantityText,
    unit, status, actorMembershipId, recordedAt
)

private fun CycleCountCorrection.toWire() = StoredCycleCountCorrection(
    id, cycleCountId, lotId, warehouseId, zoneId, lotVersionBefore, lotVersionAfter,
    quantityBeforeText, quantityAfterText, quantityDeltaText, unit, actorMembershipId, recordedAt
)

private fun String.decodeWork(): CycleCountStoredWork? = try {
    cycleCountStorageJson.decodeFromString<StoredCycleCountWork>(this).toDomain()
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun StoredCycleCountWork.toDomain(): CycleCountStoredWork? {
    if (schemaVersion != 1) return null
    val scope =
        runCatching { CycleCountScope(userId, tenantId, workspaceId, membershipId) }.getOrNull()
            ?: return null
    val countIntent =
        countIntent?.toDomain(scope) ?: if (this.countIntent != null) return null else null
    val recordedCount =
        recordedCount?.toDomain() ?: if (this.recordedCount != null) return null else null
    val correctionIntent =
        correctionIntent?.toDomain(scope)
            ?: if (this.correctionIntent != null) return null else null
    val appliedCorrection =
        appliedCorrection?.toDomain() ?: if (this.appliedCorrection != null) return null else null
    return CycleCountStoredWork(
        scope,
        selectedLotId,
        observedQuantityText,
        countIntent,
        recordedCount,
        correctionIntent,
        appliedCorrection
    )
        .takeIf(CycleCountStoredWork::isValid)
}

private fun StoredCycleCountIntent.toDomain(scope: CycleCountScope): CycleCountIntent? {
    val domainLot = lot.toDomain() ?: return null
    return runCatching {
        CycleCountIntent(
            scope,
            idempotencyKey,
            domainLot,
            observedQuantityText,
            frozenBody,
            CycleCountIntentStatus.valueOf(status)
        )
    }.getOrNull()?.takeIf(CycleCountIntent::isValid)
}

private fun StoredCycleCountCorrectionIntent.toDomain(
    scope: CycleCountScope
): CycleCountCorrectionIntent? {
    val domainCount = count.toDomain() ?: return null
    return runCatching {
        CycleCountCorrectionIntent(
            scope,
            idempotencyKey,
            domainCount,
            expectedLotVersion,
            CycleCountIntentStatus.valueOf(status)
        )
    }.getOrNull()?.takeIf(CycleCountCorrectionIntent::isValid)
}

private fun StoredCycleCountLot.toDomain(): CycleCountLot? = runCatching {
    CycleCountLot(
        id, warehouseId, zoneId, catalogItemId, batchNumber, expirationDate,
        onHandText, reservedText, availableText, unit, status, version
    )
}.getOrNull()?.takeIf(CycleCountLot::isValid)

private fun StoredCycleCountRecord.toDomain(): CycleCountRecord? = runCatching {
    CycleCountRecord(
        id, lotId, warehouseId, zoneId, lotVersion, expectedQuantityText,
        observedQuantityText, unit, status, actorMembershipId, recordedAt
    )
}.getOrNull()?.takeIf(CycleCountRecord::isValid)

private fun StoredCycleCountCorrection.toDomain(): CycleCountCorrection? = runCatching {
    CycleCountCorrection(
        id, cycleCountId, lotId, warehouseId, zoneId, lotVersionBefore,
        lotVersionAfter, quantityBeforeText, quantityAfterText, quantityDeltaText, unit,
        actorMembershipId, recordedAt
    )
}.getOrNull()?.takeIf(CycleCountCorrection::isValid)

private fun String.isUuid(): Boolean = try {
    UUID.fromString(this).toString().equals(this, ignoreCase = true)
} catch (_: IllegalArgumentException) {
    false
}
private fun String.isValidKey(): Boolean = isNotBlank() && length <= 160
private fun String.isValidUnit(): Boolean =
    isNotBlank() && length <= 32 && uppercase().matches(Regex("[A-Z0-9._/-]+"))
private fun String.isValidQuantity(): Boolean = toBigDecimalOrNull()?.isValidQuantity() == true
private fun BigDecimal.isValidQuantity(): Boolean {
    if (signum() < 0) return false
    val normalized = stripTrailingZeros()
    return maxOf(0, normalized.scale()) <= 4 &&
        maxOf(0, normalized.precision() - normalized.scale()) <= 15
}
private fun String.toLocalDateStrict(): LocalDate? = try {
    LocalDate.parse(this).takeIf {
        it.toString() ==
            this
    }
} catch (_: RuntimeException) {
    null
}
private fun String.toInstantStrict(): Instant? = try {
    Instant.parse(this)
} catch (
    _: RuntimeException
) {
    null
}

@Serializable
private data class StoredCycleCountWork(
    val schemaVersion: Int,
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val selectedLotId: String?,
    val observedQuantityText: String,
    val countIntent: StoredCycleCountIntent?,
    val recordedCount: StoredCycleCountRecord?,
    val correctionIntent: StoredCycleCountCorrectionIntent?,
    val appliedCorrection: StoredCycleCountCorrection?
)

@Serializable private data class StoredCycleCountIntent(
    val idempotencyKey: String,
    val lot: StoredCycleCountLot,
    val observedQuantityText: String,
    val frozenBody: String,
    val status: String
)

@Serializable private data class StoredCycleCountCorrectionIntent(
    val idempotencyKey: String,
    val count: StoredCycleCountRecord,
    val expectedLotVersion: Long,
    val status: String
)

@Serializable private data class StoredCycleCountLot(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val batchNumber: String,
    val expirationDate: String,
    val onHandText: String,
    val reservedText: String,
    val availableText: String,
    val unit: String,
    val status: String,
    val version: Long
)

@Serializable private data class StoredCycleCountRecord(
    val id: String,
    val lotId: String,
    val warehouseId: String,
    val zoneId: String,
    val lotVersion: Long,
    val expectedQuantityText: String,
    val observedQuantityText: String,
    val unit: String,
    val status: String,
    val actorMembershipId: String,
    val recordedAt: String
)

@Serializable private data class StoredCycleCountCorrection(
    val id: String,
    val cycleCountId: String,
    val lotId: String,
    val warehouseId: String,
    val zoneId: String,
    val lotVersionBefore: Long,
    val lotVersionAfter: Long,
    val quantityBeforeText: String,
    val quantityAfterText: String,
    val quantityDeltaText: String,
    val unit: String,
    val actorMembershipId: String,
    val recordedAt: String
)
