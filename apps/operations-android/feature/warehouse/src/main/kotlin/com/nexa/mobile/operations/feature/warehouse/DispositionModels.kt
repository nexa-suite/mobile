package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Current verified identity snapshot; its permissions are UI hints, never server authority. */
@Immutable
data class DispositionAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
        require(authorityEpoch > 0)
    }

    val scope: DispositionScopeIdentity
        get() = DispositionScopeIdentity(userId, tenantId, workspaceId, membershipId)

    val canReadLots: Boolean
        get() = permissions.any { it in LOT_READ_PERMISSIONS }

    fun canRecord(disposition: LotDispositionAction): Boolean = when (disposition) {
        LotDispositionAction.RELEASE -> "inventory.release" in permissions

        LotDispositionAction.HOLD,
        LotDispositionAction.WASTE,
        LotDispositionAction.RETURN_TO_SUPPLIER -> "inventory.waste" in permissions
    }

    override fun toString(): String =
        "DispositionAuthority(scope=REDACTED, permissions=${permissions.size})"

    private companion object {
        val LOT_READ_PERMISSIONS = setOf("inventory.read", "warehouse.read", "warehouse:read")
    }
}

@Immutable
data class DispositionScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispositionScopeIdentity(REDACTED)"
}

enum class LotDispositionAction { RELEASE, HOLD, WASTE, RETURN_TO_SUPPLIER }

/** Explicit temperature-evaluation scope for a partial disposition. */
@Immutable
data class PartialDispositionEvaluation(
    val temperatureEvaluationId: String,
    val affectedQuantity: BigDecimal
) {
    init {
        require(DISPOSITION_UUID_PATTERN.matches(temperatureEvaluationId))
        require(
            affectedQuantity.signum() > 0 && affectedQuantity.scale() <= MAX_QUANTITY_SCALE &&
                affectedQuantity.fitsDispositionPrecision()
        )
    }

    override fun toString(): String = "PartialDispositionEvaluation(values=REDACTED)"

    companion object {
        const val MAX_QUANTITY_SCALE = 4
        const val MAX_QUANTITY_PRECISION = 19
    }
}

private fun BigDecimal.fitsDispositionPrecision(): Boolean {
    val precisionAtScale = precision().toLong() +
        (PartialDispositionEvaluation.MAX_QUANTITY_SCALE.toLong() - scale().toLong())
    return precisionAtScale <= PartialDispositionEvaluation.MAX_QUANTITY_PRECISION
}

/** Actual server lot projection; quantities are not derived or persisted locally. */
@Immutable
data class DispositionLotFacts(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val expirationDate: LocalDate,
    val receivedAt: Instant,
    val onHand: BigDecimal,
    val reserved: BigDecimal,
    val available: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String =
        "DispositionLotFacts(id=REDACTED, status=$status, version=$version)"
}

/** Frozen body and optimistic version sent to the existing lot-disposition route. */
@Immutable
data class LotDispositionCommand(
    val lotId: String,
    val disposition: LotDispositionAction,
    val reason: String,
    val expectedVersion: Long,
    val partialEvaluation: PartialDispositionEvaluation? = null
) {
    init {
        require(lotId.isUuid())
        require(reason.isNotBlank() && reason == reason.trim() && reason.length <= 2_000)
        require(expectedVersion >= 0)
    }

    override fun toString(): String =
        "LotDispositionCommand(lotId=REDACTED, disposition=$disposition, " +
            "partial=${partialEvaluation != null}, reason=REDACTED)"
}

sealed interface DispositionGatewayResult {
    data class Lot(val facts: DispositionLotFacts) : DispositionGatewayResult
    data class Confirmed(val facts: DispositionLotFacts) : DispositionGatewayResult
    data class Rejected(val code: String?) : DispositionGatewayResult
    data object UnknownOutcome : DispositionGatewayResult
    data object PreconditionFailed : DispositionGatewayResult
    data object Conflict : DispositionGatewayResult
    data object NetworkUnavailable : DispositionGatewayResult
    data object ServiceUnavailable : DispositionGatewayResult
    data object PermissionDenied : DispositionGatewayResult
    data object ContextInvalidated : DispositionGatewayResult
    data object SessionInvalidated : DispositionGatewayResult
}

/** Feature port. App adapter must bind each call to current session and full verified scope. */
interface DispositionGateway {
    suspend fun lot(lotId: String, authority: DispositionAuthority): DispositionGatewayResult

    suspend fun dispose(
        command: LotDispositionCommand,
        idempotencyKey: String,
        authority: DispositionAuthority
    ): DispositionGatewayResult
}

@Immutable
data class DispositionDraftMetadata(
    val lotIdText: String,
    val disposition: LotDispositionAction?,
    val reason: String
) {
    override fun toString(): String = "DispositionDraftMetadata(values=REDACTED)"
}

enum class DispositionIntentMetadataStatus {
    Pending,
    UnknownOutcome,
    PreconditionFailed,
    Conflict,
    Rejected
}

/** Persists only the exact scope-bound command and stable key, never its result or authority. */
@Immutable
data class DispositionIntentMetadata(
    val scope: DispositionScopeIdentity,
    val idempotencyKey: String,
    val command: LotDispositionCommand,
    val status: DispositionIntentMetadataStatus
) {
    override fun toString(): String = "DispositionIntentMetadata(status=$status, key=REDACTED)"
}

sealed interface DispositionMetadataRead<out T> {
    data class Available<T>(val value: T?) : DispositionMetadataRead<T>
    data object Unavailable : DispositionMetadataRead<Nothing>
}

enum class DispositionMetadataWrite { Saved, Unavailable }

interface DispositionMetadataStore {
    suspend fun loadDraft(
        scope: DispositionScopeIdentity
    ): DispositionMetadataRead<DispositionDraftMetadata>

    suspend fun saveDraft(
        scope: DispositionScopeIdentity,
        draft: DispositionDraftMetadata
    ): DispositionMetadataWrite

    suspend fun loadIntent(
        scope: DispositionScopeIdentity
    ): DispositionMetadataRead<DispositionIntentMetadata>

    suspend fun saveIntent(intent: DispositionIntentMetadata): DispositionMetadataWrite

    suspend fun clearIntent(
        scope: DispositionScopeIdentity,
        expectedIdempotencyKey: String
    ): DispositionMetadataWrite
}

private val DISPOSITION_UUID_PATTERN =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun String.isUuid(): Boolean = DISPOSITION_UUID_PATTERN.matches(this)
