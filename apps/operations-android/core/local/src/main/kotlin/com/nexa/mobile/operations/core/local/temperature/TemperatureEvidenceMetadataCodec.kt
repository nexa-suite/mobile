package com.nexa.mobile.operations.core.local.temperature

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
    private const val SCHEMA_VERSION = 1
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
            require(magic.contentEquals(MAGIC) && input.readUnsignedByte() == SCHEMA_VERSION)
            val scope = input.readScope()
            val draft = if (input.readBoolean()) input.readDraft() else null
            val intent = if (input.readBoolean()) input.readIntent(scope) else null
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
    }

    private fun DataInputStream.readDraft() = TemperatureEvidenceDraftRecord(
        subjectType = StoredTemperatureSubjectType.entries[readUnsignedByte()],
        subjectIdText = readField(TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES),
        valueText = readField(TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES),
        unit = StoredTemperatureUnit.entries[readUnsignedByte()],
        occurredAtText = readField(TemperatureEvidenceDraftRecord.MAX_TIME_BYTES)
    )

    private fun DataOutputStream.writeIntent(intent: TemperatureEvidenceIntentRecord) {
        writeField(intent.idempotencyKey, TemperatureEvidenceIntentRecord.MAX_KEY_BYTES)
        val payload = intent.payload
        writeByte(payload.subjectType.ordinal)
        writeField(payload.subjectId, TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES)
        writeField(payload.value, TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES)
        writeByte(payload.unit.ordinal)
        writeField(payload.occurredAt, TemperatureEvidenceDraftRecord.MAX_TIME_BYTES)
        writeByte(
            when (intent.status) {
                TemperatureEvidenceIntentStatus.Pending -> 1
                TemperatureEvidenceIntentStatus.UnknownOutcome -> 2
            }
        )
    }

    private fun DataInputStream.readIntent(
        scope: TemperatureMetadataScope
    ): TemperatureEvidenceIntentRecord {
        val key = readField(TemperatureEvidenceIntentRecord.MAX_KEY_BYTES)
        val payload = TemperatureEvidenceCommandPayload(
            subjectType = StoredTemperatureSubjectType.entries[readUnsignedByte()],
            subjectId = readField(TemperatureEvidenceDraftRecord.MAX_SUBJECT_BYTES),
            value = readField(TemperatureEvidenceDraftRecord.MAX_VALUE_BYTES),
            unit = StoredTemperatureUnit.entries[readUnsignedByte()],
            occurredAt = readField(TemperatureEvidenceDraftRecord.MAX_TIME_BYTES)
        )
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
