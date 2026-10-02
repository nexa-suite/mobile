package com.nexa.mobile.operations.core.local.receiving

/** Primitive identity fields used only to partition local, non-authoritative metadata. */
data class ReceivingMetadataScope(
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

    override fun toString(): String = "ReceivingMetadataScope(REDACTED)"

    internal companion object {
        const val MAX_SCOPE_FIELD_BYTES = 256
    }
}

/** A plain reference copied from the receiving UI; catalog IDs and SKU IDs stay distinct. */
data class ReceivingProductReferenceMetadata(
    val catalogItemId: String?,
    val skuId: String?,
    val displayName: String,
    val skuCode: String,
    val unit: String
) {
    init {
        require(!catalogItemId.isNullOrBlank() || !skuId.isNullOrBlank())
        require(displayName.isNotBlank() && skuCode.isNotBlank())
    }

    override fun toString(): String = "ReceivingProductReferenceMetadata(identifiers=REDACTED)"
}

/** Editable form values are retained as entered; this type makes no receipt-validity decision. */
data class ReceivingDraftMetadataRecord(
    val selectedProduct: ReceivingProductReferenceMetadata?,
    val warehouseId: String?,
    val zoneId: String?,
    val batchNumber: String,
    val expirationDateText: String,
    val quantityText: String,
    val unit: String,
    val temperatureReadingText: String,
    val notes: String? = null,
    val temperatureEvidenceObjectId: String? = null
) {
    override fun toString(): String = "ReceivingDraftMetadataRecord(values=REDACTED)"
}

/** Exact command fields frozen with an idempotency key; no server result or stock facts. */
data class ReceivingIntentPayload(
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val expirationDate: String,
    val quantity: String,
    val unit: String,
    val temperatureReading: String? = null,
    val notes: String? = null,
    val temperatureEvidenceObjectId: String? = null
) {
    override fun toString(): String = "ReceivingIntentPayload(REDACTED)"
}

enum class ReceivingIntentStatus { Pending, UnknownOutcome }

data class ReceivingIntentMetadataRecord(
    val scope: ReceivingMetadataScope,
    val idempotencyKey: String,
    val payload: ReceivingIntentPayload,
    val status: ReceivingIntentStatus
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(idempotencyKey.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES)
    }

    override fun toString(): String = "ReceivingIntentMetadataRecord(status=$status, key=REDACTED)"

    internal companion object {
        const val MAX_KEY_BYTES = 160
    }
}

sealed interface ReceivingMetadataRead<out T> {
    data class Available<T>(val value: T?) : ReceivingMetadataRead<T>
    data object Unavailable : ReceivingMetadataRead<Nothing>
}

sealed interface ReceivingMetadataWrite {
    data object Saved : ReceivingMetadataWrite
    data object Conflict : ReceivingMetadataWrite
    data object Stale : ReceivingMetadataWrite
    data object Unavailable : ReceivingMetadataWrite
}

/** Draft and unresolved intent share one atomic, scope-bound record. */
interface ReceivingMetadataStore {
    suspend fun loadDraft(
        scope: ReceivingMetadataScope
    ): ReceivingMetadataRead<ReceivingDraftMetadataRecord>

    suspend fun saveDraft(
        scope: ReceivingMetadataScope,
        draft: ReceivingDraftMetadataRecord
    ): ReceivingMetadataWrite

    suspend fun loadIntent(
        scope: ReceivingMetadataScope
    ): ReceivingMetadataRead<ReceivingIntentMetadataRecord>

    suspend fun saveIntent(intent: ReceivingIntentMetadataRecord): ReceivingMetadataWrite

    suspend fun clearIntent(
        scope: ReceivingMetadataScope,
        expectedIdempotencyKey: String
    ): ReceivingMetadataWrite
}
