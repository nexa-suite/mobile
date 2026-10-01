package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import java.math.BigDecimal

/** Work captured from the current protected Picking allocation detail. */
@Immutable
data class LotSubstitutionWork(
    val fulfillmentId: String,
    val allocationId: String,
    val allocationLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val expectedLotId: String,
    val warehouseId: String,
    val zoneId: String?,
    val preparedQuantityText: String,
    val unit: String,
    val allocationVersion: Long
) {
    override fun toString(): String =
        "LotSubstitutionWork(allocationVersion=$allocationVersion, quantity=REDACTED)"
}

/** Current stock facts for a possible alternative. The server revalidates every fact on submit. */
@Immutable
data class LotSubstitutionAlternative(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val skuId: String?,
    val catalogItemId: String?,
    val batchNumber: String,
    val expirationDate: String,
    val availableText: String,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String =
        "LotSubstitutionAlternative(status=$status, quantity=REDACTED, version=$version)"
}

/** Server-created request fact. It never claims that the allocation was changed. */
@Immutable
data class LotSubstitutionRequest(
    val id: String,
    val expectedLotId: String,
    val alternativeLotId: String,
    val quantityText: String,
    val reason: String,
    val status: String,
    val currentAllocationVersion: Long
) {
    override fun toString(): String =
        "LotSubstitutionRequest(status=$status, version=$currentAllocationVersion, quantity=REDACTED)"
}

enum class LotSubstitutionCommandStatus {
    Editing,
    LoadingAlternatives,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Requested,
    Rejected,
    Stale,
    Conflict,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    ServiceUnavailable
}

enum class LotSubstitutionLookupStatus {
    NotRequested,
    Loading,
    Ready,
    Empty,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class LotSubstitutionIntentStatus { Pending, UnknownOutcome }

@Immutable
data class LotSubstitutionIntent(
    val scope: PickingScopeIdentity,
    val idempotencyKey: String,
    val work: LotSubstitutionWork,
    val alternativeLotId: String,
    val reason: String,
    val frozenBody: String,
    val status: LotSubstitutionIntentStatus
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(alternativeLotId.isNotBlank() && reason.isNotBlank())
        require(frozenBody.isNotBlank() && frozenBody.length <= 4_096)
    }

    fun sameFrozenCommand(other: LotSubstitutionIntent): Boolean =
        scope == other.scope && idempotencyKey == other.idempotencyKey && work == other.work &&
            alternativeLotId == other.alternativeLotId && reason == other.reason && frozenBody == other.frozenBody

    override fun toString(): String = "LotSubstitutionIntent(status=$status, key=REDACTED)"
}

@Immutable
data class LotSubstitutionUiState(
    val authorityEpoch: Long = 0,
    val work: LotSubstitutionWork? = null,
    val alternatives: List<LotSubstitutionAlternative> = emptyList(),
    val alternativeLookup: LotSubstitutionLookupStatus = LotSubstitutionLookupStatus.NotRequested,
    val selectedAlternativeId: String? = null,
    val reasonText: String = "",
    val metadataAvailable: Boolean = false,
    val status: LotSubstitutionCommandStatus = LotSubstitutionCommandStatus.Editing,
    val request: LotSubstitutionRequest? = null,
    val frozenIntent: LotSubstitutionIntent? = null,
    val notice: String? = null,
    val hasMoreAlternatives: Boolean = false,
    val canRequest: Boolean = false,
    val currentAllocation: LotSubstitutionCurrentFacts? = null
) {
    val selectedAlternative: LotSubstitutionAlternative?
        get() = alternatives.singleOrNull { it.id == selectedAlternativeId }

    override fun toString(): String =
        "LotSubstitutionUiState(epoch=$authorityEpoch, alternatives=${alternatives.size}, status=$status)"
}

sealed interface LotSubstitutionLookupResult {
    data class Alternatives(val items: List<LotSubstitutionAlternative>) : LotSubstitutionLookupResult
    data object NetworkUnavailable : LotSubstitutionLookupResult
    data object ServiceUnavailable : LotSubstitutionLookupResult
    data object PermissionDenied : LotSubstitutionLookupResult
    data object ContextInvalidated : LotSubstitutionLookupResult
    data object SessionInvalidated : LotSubstitutionLookupResult
}

sealed interface LotSubstitutionResult {
    data class Requested(val request: LotSubstitutionRequest) : LotSubstitutionResult
    data class Rejected(val code: String?) : LotSubstitutionResult
    data class Stale(val currentAllocationVersion: Long?) : LotSubstitutionResult
    data object Conflict : LotSubstitutionResult
    data object UnknownOutcome : LotSubstitutionResult
    data object NetworkUnavailable : LotSubstitutionResult
    data object ServiceUnavailable : LotSubstitutionResult
    data object PermissionDenied : LotSubstitutionResult
    data object ContextInvalidated : LotSubstitutionResult
    data object SessionInvalidated : LotSubstitutionResult
}

@Immutable
data class LotSubstitutionCurrentFacts(
    val allocationId: String,
    val version: Long,
    val expectedLotId: String?,
    val quantityText: String?,
    val unit: String?
)

sealed interface LotSubstitutionCurrentResult {
    data class Current(val facts: LotSubstitutionCurrentFacts) : LotSubstitutionCurrentResult
    data object NotFound : LotSubstitutionCurrentResult
    data object NetworkUnavailable : LotSubstitutionCurrentResult
    data object ServiceUnavailable : LotSubstitutionCurrentResult
    data object PermissionDenied : LotSubstitutionCurrentResult
    data object ContextInvalidated : LotSubstitutionCurrentResult
    data object SessionInvalidated : LotSubstitutionCurrentResult
}

sealed interface LotSubstitutionMetadataRead {
    data class Available(val value: LotSubstitutionIntent?) : LotSubstitutionMetadataRead
    data object Unavailable : LotSubstitutionMetadataRead
}

enum class LotSubstitutionMetadataWrite { Saved, Unavailable }

/** Stores only the exact scope-bound command needed for explicit recovery. */
interface LotSubstitutionMetadataStore {
    suspend fun load(scope: PickingScopeIdentity): LotSubstitutionMetadataRead
    suspend fun freeze(intent: LotSubstitutionIntent): LotSubstitutionMetadataWrite
    suspend fun markUnknown(scope: PickingScopeIdentity, idempotencyKey: String): LotSubstitutionMetadataWrite
    suspend fun clear(scope: PickingScopeIdentity, idempotencyKey: String): LotSubstitutionMetadataWrite
}

interface LotSubstitutionGateway {
    suspend fun alternatives(
        work: LotSubstitutionWork,
        authority: PickingAuthority
    ): LotSubstitutionLookupResult

    suspend fun request(
        intent: LotSubstitutionIntent,
        authority: PickingAuthority
    ): LotSubstitutionResult

    suspend fun currentAllocation(
        work: LotSubstitutionWork,
        authority: PickingAuthority
    ): LotSubstitutionCurrentResult
}

internal fun LotSubstitutionWork?.isUsable(): Boolean {
    if (this == null) return false
    val quantity = preparedQuantityText.toBigDecimalOrNull() ?: return false
    return UUID_TEXT.matches(fulfillmentId) && UUID_TEXT.matches(allocationId) &&
        UUID_TEXT.matches(allocationLineId) && UUID_TEXT.matches(skuId) &&
        UUID_TEXT.matches(expectedLotId) && UUID_TEXT.matches(warehouseId) &&
        (zoneId == null || UUID_TEXT.matches(zoneId)) &&
        catalogItemId.isNotBlank() && quantity.signum() > 0 && allocationVersion >= 0 && unit.isNotBlank()
}

internal fun LotSubstitutionAlternative?.isEligibleFor(work: LotSubstitutionWork?): Boolean {
    if (this == null || work == null) return false
    val available = availableText.toBigDecimalOrNull() ?: return false
    return UUID_TEXT.matches(id) && id != work.expectedLotId &&
        warehouseId == work.warehouseId && skuId == work.skuId &&
        catalogItemId == work.catalogItemId && unit.equals(work.unit, ignoreCase = true) &&
        status.equals("AVAILABLE", ignoreCase = true) && available >= (work.preparedQuantityText.toBigDecimalOrNull() ?: return false) &&
        version >= 0 && batchNumber.isNotBlank() && expirationDate.isNotBlank()
}

internal fun String.isValidSubstitutionReason(): Boolean =
    isNotBlank() && this == trim() && length <= 2_000 && none(Char::isISOControl)

private val UUID_TEXT = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
