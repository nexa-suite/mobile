package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable

data class CycleCountScope(val userId: String, val tenantId: String, val workspaceId: String, val membershipId: String) {
    init { require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank)) }
    override fun toString(): String = "CycleCountScope(REDACTED)"
}

data class CycleCountAuthority(
    val scope: CycleCountScope,
    val authorityEpoch: Long,
    val permissions: Set<String>
) {
    init { require(authorityEpoch > 0) }
}

@Immutable
data class CycleCountLot(
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
) {
    override fun toString(): String = "CycleCountLot(status=$status, version=$version, stock=REDACTED)"
}

@Immutable
data class CycleCountRecord(
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
) {
    override fun toString(): String = "CycleCountRecord(status=$status, version=$lotVersion, quantities=REDACTED)"
}

@Immutable
data class CycleCountCorrection(
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
) {
    override fun toString(): String = "CycleCountCorrection(version=$lotVersionAfter, quantities=REDACTED)"
}

enum class CycleCountLookupStatus { NotRequested, Loading, Ready, Empty, NetworkUnavailable, ServiceUnavailable, PermissionDenied, ContextInvalidated, SessionInvalidated }
enum class CycleCountCommandStatus { Editing, PersistingIntent, Pending, UnknownOutcome, Recorded, Applied, Rejected, PreconditionFailed, Conflict, PermissionDenied, ContextInvalidated, SessionInvalidated }
enum class CycleCountNotice { InvalidQuantity, MetadataUnavailable, NetworkUnavailable, ServiceUnavailable, PermissionDenied, ContextInvalidated, SessionInvalidated, PreconditionFailed, Conflict, CountMatchesStock, CorrectionNotRequested, CurrentFactsUnavailable, QuantityUnitMismatch, StaleCount }

@Immutable
data class CycleCountIntent(
    val scope: CycleCountScope,
    val idempotencyKey: String,
    val lot: CycleCountLot,
    val observedQuantityText: String,
    val frozenBody: String,
    val status: CycleCountIntentStatus
) {
    init { require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 && frozenBody.isNotBlank() && frozenBody.length <= 512) }
    fun sameFrozenCommand(other: CycleCountIntent): Boolean = scope == other.scope && idempotencyKey == other.idempotencyKey &&
        lot == other.lot && observedQuantityText == other.observedQuantityText && frozenBody == other.frozenBody
    override fun toString(): String = "CycleCountIntent(status=$status, key=REDACTED)"
}

enum class CycleCountIntentStatus { Pending, UnknownOutcome }

@Immutable
data class CycleCountCorrectionIntent(
    val scope: CycleCountScope,
    val idempotencyKey: String,
    val count: CycleCountRecord,
    val expectedLotVersion: Long,
    val status: CycleCountIntentStatus
) {
    init { require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 && expectedLotVersion >= 0) }
    fun sameFrozenCommand(other: CycleCountCorrectionIntent): Boolean = scope == other.scope &&
        idempotencyKey == other.idempotencyKey && count == other.count && expectedLotVersion == other.expectedLotVersion
    override fun toString(): String = "CycleCountCorrectionIntent(status=$status, key=REDACTED)"
}

data class CycleCountUiState(
    val authorityEpoch: Long = 0,
    val canWriteCounts: Boolean = false,
    val canApplyInventoryCorrection: Boolean = false,
    val lots: List<CycleCountLot> = emptyList(),
    val lotPage: Int = -1,
    val lotTotal: Long = 0,
    val lotLookup: CycleCountLookupStatus = CycleCountLookupStatus.NotRequested,
    val selectedLotId: String? = null,
    val observedQuantityText: String = "",
    val metadataAvailable: Boolean = false,
    val countCommand: CycleCountCommandStatus = CycleCountCommandStatus.Editing,
    val frozenCountIntent: CycleCountIntent? = null,
    val recordedCount: CycleCountRecord? = null,
    val correctionCommand: CycleCountCommandStatus = CycleCountCommandStatus.Editing,
    val frozenCorrectionIntent: CycleCountCorrectionIntent? = null,
    val appliedCorrection: CycleCountCorrection? = null,
    val notice: CycleCountNotice? = null
) {
    val selectedLot: CycleCountLot? get() = lots.singleOrNull { it.id == selectedLotId }
    val hasMoreLots: Boolean get() = lotPage >= 0 && lots.size.toLong() < lotTotal
    val canRecord: Boolean get() = canWriteCounts && selectedLot != null && metadataAvailable && countCommand == CycleCountCommandStatus.Editing &&
        correctionCommand == CycleCountCommandStatus.Editing &&
        (recordedCount?.status != "REQUESTED" || appliedCorrection != null)
    val canApplyCorrection: Boolean get() = canApplyInventoryCorrection && recordedCount?.status == "REQUESTED" && metadataAvailable &&
        correctionCommand == CycleCountCommandStatus.Editing && appliedCorrection == null
    val isFrozen: Boolean get() = countCommand in setOf(CycleCountCommandStatus.PersistingIntent, CycleCountCommandStatus.Pending,
        CycleCountCommandStatus.UnknownOutcome) || correctionCommand in setOf(CycleCountCommandStatus.PersistingIntent,
        CycleCountCommandStatus.Pending, CycleCountCommandStatus.UnknownOutcome)
    override fun toString(): String = "CycleCountUiState(epoch=$authorityEpoch, lots=${lots.size}, command=$countCommand)"
}

sealed interface CycleCountLookupResult {
    data class Lots(val items: List<CycleCountLot>, val page: Int, val total: Long) : CycleCountLookupResult
    data object NetworkUnavailable : CycleCountLookupResult
    data object ServiceUnavailable : CycleCountLookupResult
    data object PermissionDenied : CycleCountLookupResult
    data object ContextInvalidated : CycleCountLookupResult
    data object SessionInvalidated : CycleCountLookupResult
}

sealed interface CycleCountResult {
    data class Recorded(val count: CycleCountRecord) : CycleCountResult
    data class Applied(val correction: CycleCountCorrection) : CycleCountResult
    data class Rejected(val code: String?) : CycleCountResult
    data object UnknownOutcome : CycleCountResult
    data object PreconditionFailed : CycleCountResult
    data object Conflict : CycleCountResult
    data object NetworkUnavailable : CycleCountResult
    data object ServiceUnavailable : CycleCountResult
    data object PermissionDenied : CycleCountResult
    data object ContextInvalidated : CycleCountResult
    data object SessionInvalidated : CycleCountResult
}

data class CycleCountStoredWork(
    val scope: CycleCountScope,
    val selectedLotId: String?,
    val observedQuantityText: String,
    val countIntent: CycleCountIntent? = null,
    val recordedCount: CycleCountRecord? = null,
    val correctionIntent: CycleCountCorrectionIntent? = null,
    val appliedCorrection: CycleCountCorrection? = null
)

sealed interface CycleCountMetadataRead {
    data class Available(val value: CycleCountStoredWork?) : CycleCountMetadataRead
    data object Unavailable : CycleCountMetadataRead
}
enum class CycleCountMetadataWrite { Saved, Unavailable }

interface CycleCountMetadataStore {
    suspend fun load(scope: CycleCountScope): CycleCountMetadataRead
    suspend fun saveDraft(work: CycleCountStoredWork): CycleCountMetadataWrite
    suspend fun freezeCount(intent: CycleCountIntent): CycleCountMetadataWrite
    suspend fun markCountUnknown(scope: CycleCountScope, idempotencyKey: String): CycleCountMetadataWrite
    suspend fun completeCount(scope: CycleCountScope, idempotencyKey: String, count: CycleCountRecord): CycleCountMetadataWrite
    suspend fun clearCountIntent(scope: CycleCountScope, idempotencyKey: String): CycleCountMetadataWrite
    suspend fun freezeCorrection(intent: CycleCountCorrectionIntent): CycleCountMetadataWrite
    suspend fun markCorrectionUnknown(scope: CycleCountScope, idempotencyKey: String): CycleCountMetadataWrite
    suspend fun completeCorrection(scope: CycleCountScope, idempotencyKey: String, correction: CycleCountCorrection): CycleCountMetadataWrite
    suspend fun clearCorrectionIntent(scope: CycleCountScope, idempotencyKey: String): CycleCountMetadataWrite
    suspend fun clearStaleCount(scope: CycleCountScope, countId: String): CycleCountMetadataWrite
}

interface CycleCountGateway {
    suspend fun lots(authority: CycleCountAuthority, page: Int = 0): CycleCountLookupResult
    suspend fun record(intent: CycleCountIntent, authority: CycleCountAuthority): CycleCountResult
    suspend fun applyCorrection(intent: CycleCountCorrectionIntent, authority: CycleCountAuthority): CycleCountResult
}
