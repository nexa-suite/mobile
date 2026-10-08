package com.nexa.mobile.operations.feature.warehouse.model

data class CycleCountScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
    }
    override fun toString(): String = "CycleCountScope(REDACTED)"
}

data class CycleCountAuthority(
    val scope: CycleCountScope,
    val authorityEpoch: Long,
    val permissions: Set<String>
) {
    init {
        require(authorityEpoch > 0)
    }
}

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
    override fun toString(): String =
        "CycleCountLot(status=$status, version=$version, stock=REDACTED)"
}

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
    override fun toString(): String =
        "CycleCountRecord(status=$status, version=$lotVersion, quantities=REDACTED)"
}

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
    override fun toString(): String =
        "CycleCountCorrection(version=$lotVersionAfter, quantities=REDACTED)"
}

data class CycleCountIntent(
    val scope: CycleCountScope,
    val idempotencyKey: String,
    val lot: CycleCountLot,
    val observedQuantityText: String,
    val frozenBody: String,
    val status: CycleCountIntentStatus
) {
    init {
        require(
            idempotencyKey.isNotBlank() &&
                idempotencyKey.length <= 160 && frozenBody.isNotBlank() &&
                frozenBody.length <= 512
        )
    }
    fun sameFrozenCommand(other: CycleCountIntent): Boolean =
        scope == other.scope && idempotencyKey == other.idempotencyKey &&
            lot == other.lot && observedQuantityText == other.observedQuantityText &&
            frozenBody == other.frozenBody
    override fun toString(): String = "CycleCountIntent(status=$status, key=REDACTED)"
}

enum class CycleCountIntentStatus {
    Pending,
    UnknownOutcome
}

data class CycleCountCorrectionIntent(
    val scope: CycleCountScope,
    val idempotencyKey: String,
    val count: CycleCountRecord,
    val expectedLotVersion: Long,
    val status: CycleCountIntentStatus
) {
    init {
        require(
            idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 && expectedLotVersion >= 0
        )
    }
    fun sameFrozenCommand(other: CycleCountCorrectionIntent): Boolean = scope == other.scope &&
        idempotencyKey == other.idempotencyKey && count == other.count &&
        expectedLotVersion == other.expectedLotVersion
    override fun toString(): String = "CycleCountCorrectionIntent(status=$status, key=REDACTED)"
}

sealed interface CycleCountLookupResult {
    data class Lots(val items: List<CycleCountLot>, val page: Int, val total: Long) :
        CycleCountLookupResult
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

enum class CycleCountMetadataWrite {
    Saved,
    Unavailable
}
