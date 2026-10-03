package com.nexa.mobile.operations.core.local.temperature

/** Partition key for local evidence metadata only; never an authorization source. */
data class TemperatureMetadataScope(
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

    override fun toString(): String = "TemperatureMetadataScope(REDACTED)"

    internal companion object {
        const val MAX_SCOPE_FIELD_BYTES = 256
    }
}

enum class StoredTemperatureSubjectType { LOT, WAREHOUSE }

enum class StoredTemperatureUnit { CELSIUS, FAHRENHEIT }

/** Editable, explicitly unconfirmed field values. */
data class TemperatureEvidenceDraftRecord(
    val subjectType: StoredTemperatureSubjectType,
    val subjectIdText: String,
    val valueText: String,
    val unit: StoredTemperatureUnit,
    val occurredAtText: String,
    val affectedQuantityText: String = "",
    val reasonText: String = "",
    val sourceEvidenceIdText: String = "",
    val evidenceObjectId: String? = null
) {
    init {
        require(subjectIdText.toByteArray(Charsets.UTF_8).size <= MAX_SUBJECT_BYTES)
        require(valueText.toByteArray(Charsets.UTF_8).size <= MAX_VALUE_BYTES)
        require(occurredAtText.toByteArray(Charsets.UTF_8).size <= MAX_TIME_BYTES)
        require(affectedQuantityText.toByteArray(Charsets.UTF_8).size <= MAX_VALUE_BYTES)
        require(reasonText.toByteArray(Charsets.UTF_8).size <= MAX_REASON_BYTES)
        require(sourceEvidenceIdText.toByteArray(Charsets.UTF_8).size <= MAX_SUBJECT_BYTES)
        require(
            evidenceObjectId == null ||
                evidenceObjectId.toByteArray(Charsets.UTF_8).size <= MAX_SUBJECT_BYTES
        )
    }

    override fun toString(): String = "TemperatureEvidenceDraftRecord(values=REDACTED)"

    internal companion object {
        const val MAX_SUBJECT_BYTES = 128
        const val MAX_VALUE_BYTES = 128
        const val MAX_TIME_BYTES = 64
        const val MAX_REASON_BYTES = 2048
    }
}

/** Exact, immutable client command payload retained only for safe idempotent replay. */
data class TemperatureEvidenceCommandPayload(
    val subjectType: StoredTemperatureSubjectType,
    val subjectId: String,
    val value: String,
    val unit: StoredTemperatureUnit,
    val occurredAt: String,
    val evidenceObjectId: String? = null,
    val expectedLotVersion: Long? = null,
    val affectedQuantity: String? = null,
    val reason: String? = null,
    val sourceEvidenceId: String? = null
) {
    init {
        require(subjectId.isNotBlank() && subjectId.toByteArray(Charsets.UTF_8).size <= 128)
        require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 128)
        require(occurredAt.isNotBlank() && occurredAt.toByteArray(Charsets.UTF_8).size <= 64)
        require(
            evidenceObjectId == null || evidenceObjectId.toByteArray(Charsets.UTF_8).size <= 128
        )
        require(expectedLotVersion == null || expectedLotVersion >= 0)
        require(
            affectedQuantity == null || affectedQuantity.toByteArray(Charsets.UTF_8).size <= 128
        )
        require(reason == null || reason.toByteArray(Charsets.UTF_8).size <= 2048)
        require(
            sourceEvidenceId == null || sourceEvidenceId.toByteArray(Charsets.UTF_8).size <= 128
        )
    }

    override fun toString(): String = "TemperatureEvidenceCommandPayload(REDACTED)"
}

enum class TemperatureEvidenceIntentStatus { Pending, UnknownOutcome }

data class TemperatureEvidenceIntentRecord(
    val scope: TemperatureMetadataScope,
    val idempotencyKey: String,
    val payload: TemperatureEvidenceCommandPayload,
    val status: TemperatureEvidenceIntentStatus
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(idempotencyKey.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES)
    }

    override fun toString(): String =
        "TemperatureEvidenceIntentRecord(status=$status, key=REDACTED)"

    internal companion object {
        const val MAX_KEY_BYTES = 160
    }
}

sealed interface TemperatureMetadataRead<out T> {
    data class Available<T>(val value: T?) : TemperatureMetadataRead<T>
    data object Unavailable : TemperatureMetadataRead<Nothing>
}

sealed interface TemperatureMetadataWrite {
    data object Saved : TemperatureMetadataWrite
    data object Conflict : TemperatureMetadataWrite
    data object Stale : TemperatureMetadataWrite
    data object Unavailable : TemperatureMetadataWrite
}

/** One atomic scope-bound record for harmless draft text and one exact unresolved command. */
interface TemperatureEvidenceMetadataStore {
    suspend fun loadDraft(
        scope: TemperatureMetadataScope
    ): TemperatureMetadataRead<TemperatureEvidenceDraftRecord>

    suspend fun saveDraft(
        scope: TemperatureMetadataScope,
        draft: TemperatureEvidenceDraftRecord
    ): TemperatureMetadataWrite

    suspend fun loadIntent(
        scope: TemperatureMetadataScope
    ): TemperatureMetadataRead<TemperatureEvidenceIntentRecord>

    suspend fun saveIntent(intent: TemperatureEvidenceIntentRecord): TemperatureMetadataWrite

    suspend fun markUnknownOutcome(
        scope: TemperatureMetadataScope,
        expectedIdempotencyKey: String
    ): TemperatureMetadataWrite

    suspend fun clearIntent(
        scope: TemperatureMetadataScope,
        expectedIdempotencyKey: String
    ): TemperatureMetadataWrite
}
