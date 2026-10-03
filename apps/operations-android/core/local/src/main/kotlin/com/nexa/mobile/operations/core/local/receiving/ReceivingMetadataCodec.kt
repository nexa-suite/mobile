package com.nexa.mobile.operations.core.local.receiving

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class ReceivingMetadataSnapshot(
    val scope: ReceivingMetadataScope,
    val draft: ReceivingDraftMetadataRecord?,
    val intent: ReceivingIntentMetadataRecord?
)

internal object ReceivingMetadataCodec {
    private const val SCHEMA_VERSION = 2
    private const val MAX_RECORD_BYTES = 64 * 1024
    private const val MAX_FIELD_BYTES = 8 * 1024
    private val MAGIC =
        byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte())

    fun encode(snapshot: ReceivingMetadataSnapshot): ByteArray {
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

    fun decode(bytes: ByteArray): ReceivingMetadataSnapshot {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RECORD_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            val schemaVersion = input.readUnsignedByte()
            require(magic.contentEquals(MAGIC) && schemaVersion in 1..SCHEMA_VERSION)
            val scope = input.readScope()
            val draft = if (input.readBoolean()) input.readDraft(schemaVersion) else null
            val intent = if (input.readBoolean()) input.readIntent(scope, schemaVersion) else null
            require(input.available() == 0)
            return ReceivingMetadataSnapshot(scope, draft, intent)
        }
    }

    private fun DataOutputStream.writeScope(scope: ReceivingMetadataScope) {
        writeField(scope.userId, ReceivingMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.tenantId, ReceivingMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.workspaceId, ReceivingMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.membershipId, ReceivingMetadataScope.MAX_SCOPE_FIELD_BYTES)
    }

    private fun DataInputStream.readScope() = ReceivingMetadataScope(
        readField(ReceivingMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(ReceivingMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(ReceivingMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(ReceivingMetadataScope.MAX_SCOPE_FIELD_BYTES)
    )

    private fun DataOutputStream.writeDraft(draft: ReceivingDraftMetadataRecord) {
        writeBoolean(draft.selectedProduct != null)
        draft.selectedProduct?.let { product ->
            writeNullableField(product.catalogItemId, 128)
            writeNullableField(product.skuId, 128)
            writeField(product.displayName, 512)
            writeField(product.skuCode, 128)
            writeField(product.unit, 128)
        }
        writeNullableField(draft.warehouseId, 256)
        writeNullableField(draft.zoneId, 256)
        writeField(draft.batchNumber, 512)
        writeField(draft.expirationDateText, 64)
        writeField(draft.quantityText, 128)
        writeField(draft.unit, 128)
        writeField(draft.temperatureReadingText, 128)
        writeNullableField(draft.notes, MAX_FIELD_BYTES)
        writeNullableField(draft.temperatureEvidenceObjectId, 128)
    }

    private fun DataInputStream.readDraft(schemaVersion: Int): ReceivingDraftMetadataRecord {
        val product = if (readBoolean()) {
            ReceivingProductReferenceMetadata(
                readNullableField(128),
                readNullableField(128),
                readField(512),
                readField(128),
                readField(128)
            )
        } else {
            null
        }
        val warehouseId = readNullableField(256)
        val zoneId = readNullableField(256)
        val batchNumber = readField(512)
        val expirationDateText = readField(64)
        val quantityText = readField(128)
        val unit = readField(128)
        val temperatureReadingText = readField(128)
        val notes = readNullableField(MAX_FIELD_BYTES)
        val temperatureEvidenceObjectId = if (schemaVersion >= 2) readNullableField(128) else null
        return ReceivingDraftMetadataRecord(
            selectedProduct = product,
            warehouseId = warehouseId,
            zoneId = zoneId,
            batchNumber = batchNumber,
            expirationDateText = expirationDateText,
            quantityText = quantityText,
            unit = unit,
            temperatureReadingText = temperatureReadingText,
            notes = notes,
            temperatureEvidenceObjectId = temperatureEvidenceObjectId
        )
    }

    private fun DataOutputStream.writeIntent(intent: ReceivingIntentMetadataRecord) {
        writeField(intent.idempotencyKey, ReceivingIntentMetadataRecord.MAX_KEY_BYTES)
        val payload = intent.payload
        writeField(payload.warehouseId, 256)
        writeField(payload.zoneId, 256)
        writeNullableField(payload.catalogItemId, 128)
        writeNullableField(payload.skuId, 128)
        writeField(payload.batchNumber, 512)
        writeField(payload.expirationDate, 64)
        writeField(payload.quantity, 128)
        writeField(payload.unit, 128)
        writeNullableField(payload.temperatureReading, 128)
        writeNullableField(payload.notes, MAX_FIELD_BYTES)
        writeNullableField(payload.temperatureEvidenceObjectId, 128)
        writeByte(
            when (intent.status) {
                ReceivingIntentStatus.Pending -> 1
                ReceivingIntentStatus.UnknownOutcome -> 2
            }
        )
    }

    private fun DataInputStream.readIntent(
        scope: ReceivingMetadataScope,
        schemaVersion: Int
    ): ReceivingIntentMetadataRecord {
        val key = readField(ReceivingIntentMetadataRecord.MAX_KEY_BYTES)
        val warehouseId = readField(256)
        val zoneId = readField(256)
        val catalogItemId = readNullableField(128)
        val skuId = readNullableField(128)
        val batchNumber = readField(512)
        val expirationDate = readField(64)
        val quantity = readField(128)
        val unit = readField(128)
        val temperatureReading = readNullableField(128)
        val notes = readNullableField(MAX_FIELD_BYTES)
        val temperatureEvidenceObjectId = if (schemaVersion >= 2) readNullableField(128) else null
        val payload = ReceivingIntentPayload(
            warehouseId = warehouseId,
            zoneId = zoneId,
            catalogItemId = catalogItemId,
            skuId = skuId,
            batchNumber = batchNumber,
            expirationDate = expirationDate,
            quantity = quantity,
            unit = unit,
            temperatureReading = temperatureReading,
            notes = notes,
            temperatureEvidenceObjectId = temperatureEvidenceObjectId
        )
        val status = when (readUnsignedByte()) {
            1 -> ReceivingIntentStatus.Pending
            2 -> ReceivingIntentStatus.UnknownOutcome
            else -> error("Unknown receiving intent status")
        }
        return ReceivingIntentMetadataRecord(scope, key, payload, status)
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

/** Length-prefixed scope encoding prevents ambiguous identity concatenations. */
internal object ReceivingScopeBinding {
    private const val AAD_DOMAIN = "NEXA-RECEIVING-METADATA"
    private const val ENVELOPE_SCHEMA = 1

    fun fileKey(scope: ReceivingMetadataScope): String =
        MessageDigest.getInstance("SHA-256").digest(scopeBytes(scope)).toHex()

    fun additionalData(scope: ReceivingMetadataScope): ByteArray {
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

    private fun scopeBytes(scope: ReceivingMetadataScope): ByteArray {
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
