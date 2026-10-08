package com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.disposition

import java.math.BigDecimal

/** Full identity partition for non-authoritative local disposition metadata. */
data class DispositionMetadataScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        listOf(userId, tenantId, workspaceId, membershipId).forEach {
            require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_SCOPE_FIELD_BYTES)
        }
    }

    override fun toString(): String = "DispositionMetadataScope(REDACTED)"

    internal companion object {
        const val MAX_SCOPE_FIELD_BYTES = 256
    }
}

enum class StoredLotDisposition { RELEASE, HOLD, WASTE, RETURN_TO_SUPPLIER }

/** Editable, explicitly unconfirmed local note. It contains no server stock facts. */
data class DispositionDraftRecord(
    val lotId: String?,
    val disposition: StoredLotDisposition?,
    val reason: String
) {
    init {
        require(lotId == null || lotId.toByteArray(Charsets.UTF_8).size <= MAX_DRAFT_LOT_ID_BYTES)
        require(reason.toByteArray(Charsets.UTF_8).size <= MAX_REASON_BYTES)
    }

    override fun toString(): String = "DispositionDraftRecord(values=REDACTED)"

    internal companion object {
        const val MAX_REASON_BYTES = 16_000
        const val MAX_DRAFT_LOT_ID_BYTES = 512
    }
}

/** Exact, frozen server command needed for explicit same-key replay. */
data class DispositionCommandPayload(
    val lotId: String,
    val disposition: StoredLotDisposition,
    val reason: String,
    val expectedVersion: Long,
    val affectedQuantity: String? = null,
    val temperatureEvaluationId: String? = null
) {
    init {
        require(lotId.isUuid())
        require(reason.isNotBlank() && reason == reason.trim())
        require(reason.length <= 2_000)
        require(reason.toByteArray(Charsets.UTF_8).size <= DispositionDraftRecord.MAX_REASON_BYTES)
        require(expectedVersion >= 0)
        require((affectedQuantity == null) == (temperatureEvaluationId == null))
        if (affectedQuantity != null && temperatureEvaluationId != null) {
            val quantity = affectedQuantity.toBigDecimalOrNull()
            require(
                quantity != null && quantity.signum() > 0 && quantity.scale() <= 4 &&
                    quantity.precision().toLong() + (4L - quantity.scale().toLong()) <= 19 &&
                    quantity.toPlainString() == affectedQuantity
            )
            require(affectedQuantity.toByteArray(Charsets.UTF_8).size <= MAX_QUANTITY_BYTES)
            require(UUID_PATTERN.matches(temperatureEvaluationId))
        }
    }

    override fun toString(): String =
        "DispositionCommandPayload(lotId=REDACTED, disposition=$disposition, " +
            "partial=${affectedQuantity != null}, reason=REDACTED)"

    internal companion object {
        const val MAX_QUANTITY_BYTES = 64
    }
}

enum class DispositionIntentStatus {
    Pending,
    UnknownOutcome,
    PreconditionFailed,
    Conflict,
    Rejected
}

data class DispositionIntentRecord(
    val scope: DispositionMetadataScope,
    val idempotencyKey: String,
    val payload: DispositionCommandPayload,
    val status: DispositionIntentStatus
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(idempotencyKey.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES)
    }

    override fun toString(): String = "DispositionIntentRecord(status=$status, key=REDACTED)"

    internal companion object {
        const val MAX_KEY_BYTES = 160
    }
}

sealed interface DispositionMetadataRead<out T> {
    data class Available<T>(val value: T?) : DispositionMetadataRead<T>
    data object Unavailable : DispositionMetadataRead<Nothing>
}

sealed interface DispositionMetadataWrite {
    data object Saved : DispositionMetadataWrite
    data object Conflict : DispositionMetadataWrite
    data object Stale : DispositionMetadataWrite
    data object Unavailable : DispositionMetadataWrite
}

/** Atomic, scope-bound storage for a local note and at most one unresolved command. */
interface DispositionMetadataStore {
    suspend fun loadDraft(
        scope: DispositionMetadataScope
    ): DispositionMetadataRead<DispositionDraftRecord>

    suspend fun saveDraft(
        scope: DispositionMetadataScope,
        draft: DispositionDraftRecord
    ): DispositionMetadataWrite

    suspend fun loadIntent(
        scope: DispositionMetadataScope
    ): DispositionMetadataRead<DispositionIntentRecord>

    suspend fun saveIntent(intent: DispositionIntentRecord): DispositionMetadataWrite

    suspend fun clearIntent(
        scope: DispositionMetadataScope,
        expectedIdempotencyKey: String
    ): DispositionMetadataWrite
}

private val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun String.isUuid(): Boolean = UUID_PATTERN.matches(this)
