package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.storage.picking

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class PickingMetadataSnapshot(
    val scope: PickingMetadataScope,
    val intent: PickingIntentMetadataRecord?
)

internal object PickingMetadataCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_RECORD_BYTES = 64 * 1024
    private const val MAX_FIELD_BYTES = 8 * 1024
    private val MAGIC =
        byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte())

    fun encode(snapshot: PickingMetadataSnapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeByte(SCHEMA_VERSION)
            output.writeScope(snapshot.scope)
            output.writeBoolean(snapshot.intent != null)
            snapshot.intent?.let { output.writeIntent(it) }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
    }

    fun decode(bytes: ByteArray): PickingMetadataSnapshot {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RECORD_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC) && input.readUnsignedByte() == SCHEMA_VERSION)
            val scope = input.readScope()
            val intent = if (input.readBoolean()) input.readIntent(scope) else null
            require(input.available() == 0)
            return PickingMetadataSnapshot(scope, intent)
        }
    }

    private fun DataOutputStream.writeScope(scope: PickingMetadataScope) {
        writeField(scope.userId, PickingMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.tenantId, PickingMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.workspaceId, PickingMetadataScope.MAX_SCOPE_FIELD_BYTES)
        writeField(scope.membershipId, PickingMetadataScope.MAX_SCOPE_FIELD_BYTES)
    }

    private fun DataInputStream.readScope() = PickingMetadataScope(
        readField(PickingMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(PickingMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(PickingMetadataScope.MAX_SCOPE_FIELD_BYTES),
        readField(PickingMetadataScope.MAX_SCOPE_FIELD_BYTES)
    )

    private fun DataOutputStream.writeIntent(intent: PickingIntentMetadataRecord) {
        writeField(intent.idempotencyKey, PickingIntentMetadataRecord.MAX_KEY_BYTES)
        when (val command = intent.command) {
            is PickingCommandRecord.Start -> {
                writeByte(1)
                writeCommon(command)
            }

            is PickingCommandRecord.Confirm -> {
                writeByte(2)
                writeCommon(command)
                writeLong(command.expectedAllocationVersion)
                writeField(command.fulfillmentLineId, 256)
                writeField(command.skuId, 256)
                writeField(command.physicalAllocationLineId, 256)
                writeField(command.lotId, 256)
                writeField(command.warehouseId, 256)
                writeField(command.quantity, 128)
                writeField(command.unit, 128)
            }
        }
        writeByte(
            when (intent.status) {
                PickingIntentStatus.Pending -> 1
                PickingIntentStatus.UnknownOutcome -> 2
            }
        )
    }

    private fun DataOutputStream.writeCommon(command: PickingCommandRecord) {
        writeField(command.fulfillmentId, 256)
        writeLong(command.expectedFulfillmentVersion)
    }

    private fun DataInputStream.readIntent(
        scope: PickingMetadataScope
    ): PickingIntentMetadataRecord {
        val key = readField(PickingIntentMetadataRecord.MAX_KEY_BYTES)
        val command = when (readUnsignedByte()) {
            1 -> readCommon().let { (fulfillmentId, version) ->
                PickingCommandRecord.Start(fulfillmentId, version)
            }

            2 -> {
                val (fulfillmentId, fulfillmentVersion) = readCommon()
                PickingCommandRecord.Confirm(
                    fulfillmentId = fulfillmentId,
                    expectedFulfillmentVersion = fulfillmentVersion,
                    expectedAllocationVersion = readLong(),
                    fulfillmentLineId = readField(256),
                    skuId = readField(256),
                    physicalAllocationLineId = readField(256),
                    lotId = readField(256),
                    warehouseId = readField(256),
                    quantity = readField(128),
                    unit = readField(128)
                )
            }

            else -> error("Unknown picking command type")
        }
        val status = when (readUnsignedByte()) {
            1 -> PickingIntentStatus.Pending
            2 -> PickingIntentStatus.UnknownOutcome
            else -> error("Unknown picking intent status")
        }
        return PickingIntentMetadataRecord(scope, key, command, status)
    }

    private fun DataInputStream.readCommon(): Pair<String, Long> =
        readField(256) to readLong().also { require(it >= 0) }

    private fun DataOutputStream.writeField(value: String, maxBytes: Int) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= maxBytes && encoded.size <= MAX_FIELD_BYTES)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readField(maxBytes: Int): String {
        val size = readInt()
        require(size in 1..minOf(maxBytes, MAX_FIELD_BYTES) && size <= available())
        val encoded = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    }
}

/** Separate hashed file namespace and AES-GCM domain from receiving metadata. */
internal object PickingScopeBinding {
    private const val AAD_DOMAIN = "NEXA-PICKING-METADATA"
    private const val ENVELOPE_SCHEMA = 1

    fun fileKey(scope: PickingMetadataScope): String =
        MessageDigest.getInstance("SHA-256").digest(additionalData(scope)).toHex()

    fun additionalData(scope: PickingMetadataScope): ByteArray {
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

    private fun scopeBytes(scope: PickingMetadataScope): ByteArray {
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
