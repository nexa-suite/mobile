package com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class TemperatureMetadataSnapshot(
    val scope: TemperatureMetadataScope,
    val draft: TemperatureEvidenceDraftRecord?,
    val intent: TemperatureEvidenceIntentRecord?
)

internal object TemperatureEvidenceMetadataCodec {
    private const val SCHEMA_VERSION = 2
    private const val LEGACY_SCHEMA_VERSION = 1
    private const val MAX_RECORD_BYTES = 16 * 1024
    private const val MAX_FIELD_BYTES = 8 * 1024
    private val MAGIC = byteArrayOf(
        'N'.code.toByte(),
        'X'.code.toByte(),
        'T'.code.toByte(),
        'E'.code.toByte()
    )

    fun encode(snapshot: TemperatureMetadataSnapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeByte(SCHEMA_VERSION)
            output.writeScope(snapshot.scope)
            output.writeBoolean(snapshot.draft != null)
            snapshot.draft?.let { output.writeDraft(it) }
            output.writeBoolean(snapshot.intent != null)
            snapshot.intent?.let { output.writeIntent(it) }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
    }

    fun decode(bytes: ByteArray): TemperatureMetadataSnapshot {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RECORD_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC))
            val schemaVersion = input.readUnsignedByte()
            require(schemaVersion in LEGACY_SCHEMA_VERSION..SCHEMA_VERSION)
            val scope = input.readScope()
            val draft = if (input.readBoolean()) input.readDraft(schemaVersion) else null
            val intent = if (input.readBoolean()) input.readIntent(scope, schemaVersion) else null
            require(input.available() == 0)
            return TemperatureMetadataSnapshot(scope, draft, intent)
        }
    }

    private fun DataOutputStream.writeScope(scope: TemperatureMetadataScope) {
        writeField(scope.userId, TemperatureMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.tenantId, TemperatureMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.workspaceId, TemperatureMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.membershipId, TemperatureMetadataScope.MAX_SCOPE_FIELD_BYTES)
    }

    private fun DataInputStream.readScope() = TemperatureMetadataScope(
        readField(TemperatureMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(TemperatureMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(TemperatureMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(TemperatureMetadataScope.MAX_SCOPE_FIELD_BYTES)
    )

    private fun DataOutputStream.writeDraft(draft: TemperatureEvidenceDraftRecord) {
        writeByte(draft.subjectType.ordinal)
        writeField(draft.subjectIdText, TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES)
        writeField(draft.valueText, TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES)
        writeByte(draft.unit.ordinal)
        writeField(draft.occurredAtText, TemperatureEvidenceDraftRecord.MAX_TIME_BYTES)
        writeField(draft.affectedQuantityText, TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES)
        writeField(draft.reasonText, TemperatureEvidenceDraftRecord.MAX_REASON_BYTES)
        writeField(draft.sourceEvidenceIdText, TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES)
        writeNullableField(draft.evidenceObjectId, TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES)
    }

    private fun DataInputStream.readDraft(schemaVersion: Int): TemperatureEvidenceDraftRecord {
        val subjectType = StoredTemperatureSubjectType.entries[readUnsignedByte()]
        val subjectId = readField(TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES)
        val value = readField(TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES)
        val unit = StoredTemperatureUnit.entries[readUnsignedByte()]
        val occurredAt = readField(TemperatureEvidenceDraftRecord.MAX_TIME_BYTES)
        return if (schemaVersion == LEGACY_SCHEMA_VERSION) {
            TemperatureEvidenceDraftRecord(subjectType, subjectId, value, unit, occurredAt)
        } else {
            TemperatureEvidenceDraftRecord(
                subjectType = subjectType,
                subjectIdText = subjectId,
                valueText = value,
                unit = unit,
                occurredAtText = occurredAt,
                affectedQuantityText = readField(TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES),
                reasonText = readField(TemperatureEvidenceDraftRecord.MAX_REASON_BYTES),
                sourceEvidenceIdText = readField(TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES),
                evidenceObjectId = readNullableField(
                    TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES
                )
            )
        }
    }

    private fun DataOutputStream.writeIntent(intent: TemperatureEvidenceIntentRecord) {
        writeField(intent.idempotencyKey, TemperatureEvidenceIntentRecord.MAX_KEY_BYTES)
        val payload = intent.payload
        writeByte(payload.subjectType.ordinal)
        writeField(payload.subjectId, TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES)
        writeField(payload.value, TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES)
        writeByte(payload.unit.ordinal)
        writeField(payload.occurredAt, TemperatureEvidenceDraftRecord.MAX_TIME_BYTES)
        writeNullableField(
            payload.evidenceObjectId,
            TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES
        )
        writeBoolean(payload.expectedLotVersion != null)
        payload.expectedLotVersion?.let(::writeLong)
        writeNullableField(payload.affectedQuantity, TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES)
        writeNullableField(payload.reason, TemperatureEvidenceDraftRecord.MAX_REASON_BYTES)
        writeNullableField(
            payload.sourceEvidenceId,
            TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES
        )
        writeByte(
            when (intent.status) {
                TemperatureEvidenceIntentStatus.Pending -> 1
                TemperatureEvidenceIntentStatus.UnknownOutcome -> 2
            }
        )
    }

    private fun DataInputStream.readIntent(
        scope: TemperatureMetadataScope,
        schemaVersion: Int
    ): TemperatureEvidenceIntentRecord {
        val key = readField(TemperatureEvidenceIntentRecord.MAX_KEY_BYTES)
        val subjectType = StoredTemperatureSubjectType.entries[readUnsignedByte()]
        val subjectId = readField(TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES)
        val value = readField(TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES)
        val unit = StoredTemperatureUnit.entries[readUnsignedByte()]
        val occurredAt = readField(TemperatureEvidenceDraftRecord.MAX_TIME_BYTES)
        val payload = if (schemaVersion == LEGACY_SCHEMA_VERSION) {
            TemperatureEvidenceCommandPayload(subjectType, subjectId, value, unit, occurredAt)
        } else {
            val evidenceObjectId =
                readNullableField(TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES)
            val expectedLotVersion = if (readBoolean()) readLong() else null
            val affectedQuantity = readNullableField(TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES)
            val reason = readNullableField(TemperatureEvidenceDraftRecord.MAX_REASON_BYTES)
            val sourceEvidenceId =
                readNullableField(TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES)
            TemperatureEvidenceCommandPayload(
                subjectType,
                subjectId,
                value,
                unit,
                occurredAt,
                evidenceObjectId,
                expectedLotVersion,
                affectedQuantity,
                reason,
                sourceEvidenceId
            )
        }
        val status = when (readUnsignedByte()) {
            1 -> TemperatureEvidenceIntentStatus.Pending
            2 -> TemperatureEvidenceIntentStatus.UnknownOutcome
            else -> error("Unknown temperature evidence intent status")
        }
        return TemperatureEvidenceIntentRecord(scope, key, payload, status)
    }

    private fun DataOutputStream.writeField(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes && bytes.size <= MAX_FIELD_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableField(value: String?, maxBytes: Int) {
        writeBoolean(value != null)
        value?.let { writeField(it, maxBytes) }
    }

    private fun DataInputStream.readNullableField(maxBytes: Int): String? =
        if (readBoolean()) readField(maxBytes) else null

    private fun DataInputStream.readField(maxBytes: Int): String {
        val size = readInt()
        require(size in 0..minOf(maxBytes, MAX_FIELD_BYTES) && size <= available())
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}

/** Length-prefixed scope encoding avoids ambiguous concatenations. */
internal object TemperatureMetadataScopeBinding {
    private const val AAD_DOMAIN = "NEXA-TEMPERATURE-EVIDENCE-METADATA"
    private const val ENVELOPE_SCHEMA = 1

    fun fileKey(scope: TemperatureMetadataScope): String =
        MessageDigest.getInstance("SHA-256").digest(scopeBytes(scope)).toHex()

    fun additionalData(scope: TemperatureMetadataScope): ByteArray {
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

    private fun scopeBytes(scope: TemperatureMetadataScope): ByteArray {
        val values = listOf(scope.userId, scope.tenantId, scope.workspaceId, scope.membershipId)
        val encoded = values.map { it.toByteArray(Charsets.UTF_8) }
        return ByteBuffer.allocate(encoded.sumOf { 4 + it.size })
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                encoded.forEach {
                    putInt(it.size)
                    put(it)
                }
            }
            .array()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
}
