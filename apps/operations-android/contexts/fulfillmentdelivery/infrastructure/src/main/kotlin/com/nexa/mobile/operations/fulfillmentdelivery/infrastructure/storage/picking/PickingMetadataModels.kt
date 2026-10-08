package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.storage.picking

/** Local partition only; it never carries permissions, session tokens, or server facts. */
data class PickingMetadataScope(
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

    override fun toString(): String = "PickingMetadataScope(REDACTED)"

    internal companion object {
        const val MAX_SCOPE_FIELD_BYTES = 256
    }
}

/** Immutable request data for the two picking mutations supported by the API. */
sealed interface PickingCommandRecord {
    val fulfillmentId: String
    val expectedFulfillmentVersion: Long

    data class Start(
        override val fulfillmentId: String,
        override val expectedFulfillmentVersion: Long
    ) : PickingCommandRecord {
        override fun toString(): String = "PickingCommandRecord.Start(REDACTED)"
    }

    data class Confirm(
        override val fulfillmentId: String,
        override val expectedFulfillmentVersion: Long,
        val expectedAllocationVersion: Long,
        val fulfillmentLineId: String,
        val skuId: String,
        val physicalAllocationLineId: String,
        val lotId: String,
        val warehouseId: String,
        val quantity: String,
        val unit: String
    ) : PickingCommandRecord {
        init {
            require(quantity.isNotBlank())
            require(java.math.BigDecimal(quantity).signum() > 0)
        }

        override fun toString(): String = "PickingCommandRecord.Confirm(REDACTED)"
    }
}

enum class PickingIntentStatus { Pending, UnknownOutcome }

data class PickingIntentMetadataRecord(
    val scope: PickingMetadataScope,
    val idempotencyKey: String,
    val command: PickingCommandRecord,
    val status: PickingIntentStatus
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(idempotencyKey.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES)
        require(command.fulfillmentId.isNotBlank())
        require(command.expectedFulfillmentVersion >= 0)
        if (command is PickingCommandRecord.Confirm) {
            require(command.expectedAllocationVersion >= 0)
            listOf(
                command.fulfillmentLineId,
                command.skuId,
                command.physicalAllocationLineId,
                command.lotId,
                command.warehouseId,
                command.unit
            ).forEach { require(it.isNotBlank()) }
        }
    }

    override fun toString(): String = "PickingIntentMetadataRecord(status=$status, key=REDACTED)"

    internal companion object {
        const val MAX_KEY_BYTES = 160
    }
}

sealed interface PickingMetadataRead<out T> {
    data class Available<T>(val value: T?) : PickingMetadataRead<T>
    data object Unavailable : PickingMetadataRead<Nothing>
}

sealed interface PickingMetadataWrite {
    data object Saved : PickingMetadataWrite
    data object Conflict : PickingMetadataWrite
    data object Stale : PickingMetadataWrite
    data object Unavailable : PickingMetadataWrite
}

interface PickingMetadataStore {
    suspend fun loadIntent(
        scope: PickingMetadataScope
    ): PickingMetadataRead<PickingIntentMetadataRecord>

    suspend fun saveIntent(intent: PickingIntentMetadataRecord): PickingMetadataWrite

    suspend fun clearIntent(
        scope: PickingMetadataScope,
        expectedIdempotencyKey: String
    ): PickingMetadataWrite
}
