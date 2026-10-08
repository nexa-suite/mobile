package com.nexa.mobile.operations.inventoryavailability.application.model.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.CycleCountCorrection
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.CycleCountLot
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.CycleCountRecord

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
