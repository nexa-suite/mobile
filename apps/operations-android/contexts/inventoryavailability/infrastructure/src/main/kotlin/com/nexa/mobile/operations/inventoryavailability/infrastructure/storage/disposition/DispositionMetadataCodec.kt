package com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.disposition

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class DispositionMetadataSnapshot(
    val scope: DispositionMetadataScope,
    val draft: DispositionDraftRecord?,
    val intent: DispositionIntentRecord?
)

internal object DispositionMetadataCodec {
    private const val SCHEMA_VERSION = 2
    private const val MAX_RECORD_BYTES = 64 * 1024
    private const val MAX_FIELD_BYTES = 16 * 1024
    private val MAGIC =
        byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'D'.code.toByte(), 'M'.code.toByte())

    fun encode(snapshot: DispositionMetadataSnapshot): ByteArray =
        ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeByte(SCHEMA_VERSION)
                output.writeScope(snapshot.scope)
                output.writeBoolean(snapshot.draft != null)
                snapshot.draft?.let { output.writeDraft(it) }
                output.writeBoolean(snapshot.intent != null)
                snapshot.intent?.let { output.writeIntent(it) }
            }
            bytes.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
        }

    fun decode(bytes: ByteArray): DispositionMetadataSnapshot {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RECORD_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            val schemaVersion = input.readUnsignedByte()
            require(magic.contentEquals(MAGIC) && schemaVersion in 1..SCHEMA_VERSION)
            val scope = input.readScope()
            val draft = if (input.readBoolean()) input.readDraft() else null
            val intent = if (input.readBoolean()) input.readIntent(scope, schemaVersion) else null
            require(input.available() == 0)
            return DispositionMetadataSnapshot(scope, draft, intent)
        }
    }

    private fun DataOutputStream.writeScope(scope: DispositionMetadataScope) {
        writeField(scope.userId, DispositionMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.tenantId, DispositionMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.workspaceId, DispositionMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.membershipId, DispositionMetadataScope.MAX_SCOPE_FIELD_BYTES)
    }

    private fun DataInputStream.readScope() = DispositionMetadataScope(
        readField(DispositionMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(DispositionMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(DispositionMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(DispositionMetadataScope.MAX_SCOPE_FIELD_BYTES)
    )

    private fun DataOutputStream.writeDraft(draft: DispositionDraftRecord) {
        writeNullableField(draft.lotId, DispositionDraftRecord.MAX_DRAFT_LOT_ID_BYTES)
        writeByte(draft.disposition?.ordinal?.plus(1) ?: 0)
        writeField(draft.reason, DispositionDraftRecord.MAX_REASON_BYTES)
    }

    private fun DataInputStream.readDraft(): DispositionDraftRecord {
        val lotId = readNullableField(DispositionDraftRecord.MAX_DRAFT_LOT_ID_BYTES)
        val disposition = readDispositionOrNull(readUnsignedByte())
        val reason = readField(DispositionDraftRecord.MAX_REASON_BYTES)
        return DispositionDraftRecord(lotId, disposition, reason)
    }

    private fun DataOutputStream.writeIntent(intent: DispositionIntentRecord) {
        writeField(intent.idempotencyKey, DispositionIntentRecord.MAX_KEY_BYTES)
        writeField(intent.payload.lotId, 64)
        writeByte(intent.payload.disposition.ordinal + 1)
        writeField(intent.payload.reason, DispositionDraftRecord.MAX_REASON_BYTES)
        writeLong(intent.payload.expectedVersion)
        writeBoolean(intent.payload.affectedQuantity != null)
        intent.payload.affectedQuantity?.let { quantity ->
            writeField(quantity, DispositionCommandPayload.MAX_QUANTITY_BYTES)
            writeField(requireNotNull(intent.payload.temperatureEvaluationId), 64)
        }
        writeByte(
            when (intent.status) {
                DispositionIntentStatus.Pending -> 1
                DispositionIntentStatus.UnknownOutcome -> 2
                DispositionIntentStatus.PreconditionFailed -> 3
                DispositionIntentStatus.Conflict -> 4
                DispositionIntentStatus.Rejected -> 5
            }
        )
    }

    private fun DataInputStream.readIntent(
        scope: DispositionMetadataScope,
        schemaVersion: Int
    ): DispositionIntentRecord {
        val key = readField(DispositionIntentRecord.MAX_KEY_BYTES)
        val lotId = readField(64)
        val disposition = readDispositionOrNull(readUnsignedByte()) ?: error("Missing disposition")
        val reason = readField(DispositionDraftRecord.MAX_REASON_BYTES)
        val version = readLong()
        val hasPartialEvaluation = schemaVersion >= 2 && readBoolean()
        val affectedQuantity = if (hasPartialEvaluation) {
            readField(DispositionCommandPayload.MAX_QUANTITY_BYTES)
        } else {
            null
        }
        val temperatureEvaluationId = if (hasPartialEvaluation) readField(64) else null
        val status = when (readUnsignedByte()) {
            1 -> DispositionIntentStatus.Pending
            2 -> DispositionIntentStatus.UnknownOutcome
            3 -> DispositionIntentStatus.PreconditionFailed
            4 -> DispositionIntentStatus.Conflict
            5 -> DispositionIntentStatus.Rejected
            else -> error("Unknown disposition intent status")
        }
        return DispositionIntentRecord(
            scope,
            key,
            DispositionCommandPayload(
                lotId,
                disposition,
                reason,
                version,
                affectedQuantity,
                temperatureEvaluationId
            ),
            status
        )
    }

    private fun readDispositionOrNull(value: Int): StoredLotDisposition? = when (value) {
        0 -> null
        1 -> StoredLotDisposition.RELEASE
        2 -> StoredLotDisposition.HOLD
        3 -> StoredLotDisposition.WASTE
        4 -> StoredLotDisposition.RETURN_TO_SUPPLIER
        else -> error("Unknown disposition code")
    }

    private fun DataOutputStream.writeNullableField(value: String?, maxBytes: Int) {
        writeBoolean(value != null)
        value?.let { writeField(it, maxBytes) }
    }

    private fun DataInputStream.readNullableField(maxBytes: Int): String? =
        if (readBoolean()) readField(maxBytes) else null

    private fun DataOutputStream.writeField(value: String, maxBytes: Int) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= maxBytes && encoded.size <= MAX_FIELD_BYTES)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readField(maxBytes: Int): String {
        val size = readInt()
        require(size in 0..minOf(maxBytes, MAX_FIELD_BYTES) && size <= available())
        val encoded = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    }
}

/** Length-prefixed identity hashing and AAD prevent ambiguous scope concatenation. */
internal object DispositionScopeBinding {
    private const val AAD_DOMAIN = "NEXA-LOT-DISPOSITION-METADATA"
    private const val ENVELOPE_SCHEMA = 1

    fun fileKey(scope: DispositionMetadataScope): String =
        MessageDigest.getInstance("SHA-256").digest(scopeBytes(scope)).toHex()

    fun additionalData(scope: DispositionMetadataScope): ByteArray {
        val domain = AAD_DOMAIN.toByteArray(Charsets.UTF_8)
        val scoped = scopeBytes(scope)
        return ByteBuffer.allocate(4 + domain.size + 1 + scoped.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(domain.size)
            .put(domain)
            .put(ENVELOPE_SCHEMA.toByte())
            .put(scoped)
            .array()
    }

    private fun scopeBytes(scope: DispositionMetadataScope): ByteArray {
        val fields = listOf(scope.userId, scope.tenantId, scope.workspaceId, scope.membershipId)
            .map { it.toByteArray(Charsets.UTF_8) }
        return ByteBuffer.allocate(fields.sumOf { 4 + it.size })
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                fields.forEach {
                    putInt(it.size)
                    put(it)
                }
            }
            .array()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
