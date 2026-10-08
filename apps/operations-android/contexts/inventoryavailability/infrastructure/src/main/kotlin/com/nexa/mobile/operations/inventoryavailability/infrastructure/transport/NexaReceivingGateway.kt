package com.nexa.mobile.operations.inventoryavailability.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

private const val WAREHOUSES_PATH = "/api/v1/warehouses"
private const val INBOUND_RECEIPTS_PATH = "/api/v1/inventory/inbound-receipts"
private const val BUSINESS_EVIDENCE_PATH = "/api/v1/business-document-evidence"
private const val RECEIVING_EVIDENCE_SUBJECT = "WAREHOUSE"
private const val MAX_RECEIVING_EVIDENCE_BYTES = 10L * 1024L * 1024L
private const val RECEIVING_PAGE_SIZE = 100
private const val MAX_RECEIVING_PAGES = 100
private val receivingJson = Json { ignoreUnknownKeys = true }
private val uuidPattern = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val catalogItemPattern = Regex("(?i)CAT-[A-Z0-9-]{1,63}")

data class ReceivingWarehouseProjection(
    val id: String,
    val code: String,
    val name: String,
    val status: String
)

data class ReceivingZoneProjection(
    val id: String,
    val warehouseId: String,
    val code: String,
    val name: String,
    val status: String
)

data class InboundReceiptCommand(
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val expirationDate: LocalDate,
    val quantity: BigDecimal,
    val unit: String,
    val temperatureReading: BigDecimal? = null,
    val notes: String? = null,
    val temperatureEvidenceObjectId: String? = null
) {
    init {
        require(uuidPattern.matches(warehouseId) && uuidPattern.matches(zoneId))
        require(catalogItemId.isNullOrBlank().not() || skuId.isNullOrBlank().not())
        require(catalogItemId == null || catalogItemPattern.matches(catalogItemId))
        require(skuId == null || uuidPattern.matches(skuId))
        require(batchNumber.isNotBlank() && quantity.signum() > 0 && unit.isNotBlank())
        require(
            temperatureEvidenceObjectId == null || uuidPattern.matches(temperatureEvidenceObjectId)
        )
    }
}

data class ReceivingLotProjection(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val expirationDate: LocalDate,
    val receivedAt: String,
    val onHand: BigDecimal,
    val reserved: BigDecimal,
    val available: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String =
        "ReceivingLotProjection(id=REDACTED, quantity=$onHand, unit=$unit)"
}

data class ReceivingEvidenceProjection(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String,
    val declaredContentType: String,
    val checksumSha256: String?,
    val byteSize: Long
) {
    override fun toString(): String =
        "ReceivingEvidenceProjection(status=$lifecycleStatus, bytes=$byteSize)"
}

sealed interface ReceivingNetworkOutcome {
    data class Warehouses(val items: List<ReceivingWarehouseProjection>) : ReceivingNetworkOutcome
    data class Zones(val items: List<ReceivingZoneProjection>) : ReceivingNetworkOutcome
    data class Confirmed(val lot: ReceivingLotProjection) : ReceivingNetworkOutcome
    data class EvidenceUploaded(val evidence: ReceivingEvidenceProjection) : ReceivingNetworkOutcome
    data class EvidenceStatus(val evidence: ReceivingEvidenceProjection) : ReceivingNetworkOutcome
    data class Rejected(val code: String?) : ReceivingNetworkOutcome
    data object UnknownOutcome : ReceivingNetworkOutcome
    data object NetworkUnavailable : ReceivingNetworkOutcome
    data object ServiceUnavailable : ReceivingNetworkOutcome
    data object PermissionDenied : ReceivingNetworkOutcome
    data object ContextInvalidated : ReceivingNetworkOutcome
    data object SessionInvalidated : ReceivingNetworkOutcome
}

/** Protected native transport adapter for warehouse lookup and inbound receipt commands. */
class NexaReceivingGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun warehouses(): ReceivingNetworkOutcome {
        val items = mutableListOf<ReceivingWarehouseProjection>()
        var page = 0
        var total = Long.MAX_VALUE
        while (page < MAX_RECEIVING_PAGES && items.size.toLong() < total) {
            val path = "$WAREHOUSES_PATH?page=$page&size=$RECEIVING_PAGE_SIZE&sort=code,asc"
            when (
                val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, path))
            ) {
                is ProtectedResult.Failure -> return result.error.toReceivingLookupOutcome()

                is ProtectedResult.Success -> {
                    val response = result.body.decode<WarehousePageWire>()
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    val wireItems = response.items
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    val responsePage = response.page
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    val responseSize = response.size
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    total = response.total
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    if (responsePage != page || responseSize !in 1..RECEIVING_PAGE_SIZE ||
                        total < 0 || wireItems.size > responseSize
                    ) {
                        return ReceivingNetworkOutcome.ServiceUnavailable
                    }
                    wireItems.forEach { wire ->
                        items += wire.toWarehouseProjection()
                            ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    }
                    if (wireItems.isEmpty() && items.size.toLong() < total) {
                        return ReceivingNetworkOutcome.ServiceUnavailable
                    }
                    page++
                }
            }
        }
        if (items.size.toLong() < total) return ReceivingNetworkOutcome.ServiceUnavailable
        return ReceivingNetworkOutcome.Warehouses(items.toList())
    }

    suspend fun zones(warehouseId: String): ReceivingNetworkOutcome {
        if (!uuidPattern.matches(warehouseId)) return ReceivingNetworkOutcome.ServiceUnavailable
        val items = mutableListOf<ReceivingZoneProjection>()
        var page = 0
        var total = Long.MAX_VALUE
        while (page < MAX_RECEIVING_PAGES && items.size.toLong() < total) {
            val path = "$WAREHOUSES_PATH/$warehouseId/zones?page=$page&size=$RECEIVING_PAGE_SIZE"
            when (
                val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, path))
            ) {
                is ProtectedResult.Failure -> return result.error.toReceivingLookupOutcome()

                is ProtectedResult.Success -> {
                    val response = result.body.decode<ZonePageWire>()
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    val wireItems = response.items
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    val responsePage = response.page
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    val responseSize = response.size
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    total = response.total
                        ?: return ReceivingNetworkOutcome.ServiceUnavailable
                    if (responsePage != page || responseSize !in 1..RECEIVING_PAGE_SIZE ||
                        total < 0 || wireItems.size > responseSize
                    ) {
                        return ReceivingNetworkOutcome.ServiceUnavailable
                    }
                    wireItems.forEach { wire ->
                        val zone = wire.toZoneProjection()
                            ?: return ReceivingNetworkOutcome.ServiceUnavailable
                        if (zone.warehouseId != warehouseId) {
                            return ReceivingNetworkOutcome.ServiceUnavailable
                        }
                        items += zone
                    }
                    if (wireItems.isEmpty() && items.size.toLong() < total) {
                        return ReceivingNetworkOutcome.ServiceUnavailable
                    }
                    page++
                }
            }
        }
        if (items.size.toLong() < total) return ReceivingNetworkOutcome.ServiceUnavailable
        return ReceivingNetworkOutcome.Zones(items.toList())
    }

    suspend fun receive(
        command: InboundReceiptCommand,
        idempotencyKey: String
    ): ReceivingNetworkOutcome {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 160) {
            return ReceivingNetworkOutcome.ServiceUnavailable
        }
        val result = protectedCalls.execute(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = INBOUND_RECEIPTS_PATH,
                payload = command.toJson().toString(),
                idempotencyKey = idempotencyKey
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toReceiptOutcome()

            is ProtectedResult.Success -> {
                if (result.status != HTTP_CREATED) return ReceivingNetworkOutcome.UnknownOutcome
                val lot = result.body.decode<LotWire>()?.toProjection()
                    ?: return ReceivingNetworkOutcome.UnknownOutcome
                if (lot.id.isBlank() || lot.warehouseId != command.warehouseId ||
                    lot.zoneId != command.zoneId ||
                    (command.catalogItemId != null && lot.catalogItemId != command.catalogItemId) ||
                    (command.skuId != null && lot.skuId != command.skuId) ||
                    lot.batchNumber != command.batchNumber ||
                    lot.expirationDate != command.expirationDate ||
                    lot.onHand.compareTo(command.quantity) != 0 ||
                    !lot.unit.equals(command.unit, ignoreCase = true)
                ) {
                    ReceivingNetworkOutcome.UnknownOutcome
                } else {
                    ReceivingNetworkOutcome.Confirmed(lot)
                }
            }
        }
    }

    suspend fun uploadTemperatureEvidence(
        warehouseId: String,
        idempotencyKey: String,
        file: File,
        originalFilename: String,
        declaredContentType: String,
        byteSize: Long,
        checksumSha256: String
    ): ReceivingNetworkOutcome {
        if (!uuidPattern.matches(warehouseId) || idempotencyKey.isBlank() ||
            idempotencyKey.length > 160 || !file.isFile || file.length() != byteSize ||
            byteSize !in 1..MAX_RECEIVING_EVIDENCE_BYTES ||
            declaredContentType !in setOf("image/jpeg", "image/png", "image/webp") ||
            !checksumSha256.matches(Regex("[0-9a-f]{64}")) || originalFilename.isBlank() ||
            originalFilename.length > 255 ||
            originalFilename.any { it == '\r' || it == '\n' || it == '/' || it == '\\' }
        ) {
            return ReceivingNetworkOutcome.Rejected("INVALID_EVIDENCE")
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("subjectType", RECEIVING_EVIDENCE_SUBJECT)
            .addFormDataPart("subjectId", warehouseId)
            .addFormDataPart(
                "file",
                originalFilename,
                file.asRequestBody(declaredContentType.toMediaType())
            )
            .build()
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = BUSINESS_EVIDENCE_PATH,
                    idempotencyKey = idempotencyKey,
                    requestBody = body
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toReceiptOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in setOf(200, 201)) return ReceivingNetworkOutcome.UnknownOutcome
                val evidence = result.body.decode<EvidenceWire>()?.toProjection()
                    ?: return ReceivingNetworkOutcome.UnknownOutcome
                if (evidence.subjectType != RECEIVING_EVIDENCE_SUBJECT ||
                    evidence.subjectId != warehouseId ||
                    evidence.declaredContentType != declaredContentType ||
                    evidence.byteSize != byteSize
                ) {
                    return ReceivingNetworkOutcome.UnknownOutcome
                }
                if (evidence.checksumSha256 != null && evidence.checksumSha256 != checksumSha256) {
                    ReceivingNetworkOutcome.Rejected("IDEMPOTENCY_PAYLOAD_CONFLICT")
                } else {
                    ReceivingNetworkOutcome.EvidenceUploaded(evidence)
                }
            }
        }
    }

    suspend fun temperatureEvidenceStatus(
        evidenceId: String,
        warehouseId: String
    ): ReceivingNetworkOutcome {
        if (!uuidPattern.matches(evidenceId) || !uuidPattern.matches(warehouseId)) {
            return ReceivingNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, "$BUSINESS_EVIDENCE_PATH/$evidenceId")
            )
        ) {
            is ProtectedResult.Failure -> result.error.toReceivingLookupOutcome()

            is ProtectedResult.Success -> {
                val evidence = result.body.decode<EvidenceWire>()?.toProjection()
                    ?: return ReceivingNetworkOutcome.ServiceUnavailable
                if (evidence.id != evidenceId ||
                    evidence.subjectType != RECEIVING_EVIDENCE_SUBJECT ||
                    evidence.subjectId != warehouseId
                ) {
                    ReceivingNetworkOutcome.ServiceUnavailable
                } else {
                    ReceivingNetworkOutcome.EvidenceStatus(evidence)
                }
            }
        }
    }

    private fun InboundReceiptCommand.toJson(): JsonObject = buildJsonObject {
        put("warehouseId", warehouseId)
        put("zoneId", zoneId)
        catalogItemId?.let { put("catalogItemId", it) }
        skuId?.let { put("skuId", it) }
        put("batchNumber", batchNumber)
        put("expirationDate", expirationDate.toString())
        // JsonPrimitive(Number) writes BigDecimal directly, preserving numeric JSON and scale.
        put("quantity", JsonPrimitive(quantity))
        put("unit", unit)
        temperatureReading?.let { put("temperatureReading", JsonPrimitive(it)) }
        notes?.let { put("notes", it) }
        temperatureEvidenceObjectId?.let { put("temperatureEvidenceObjectId", it) }
    }

    private fun ClientFailure.toReceivingLookupOutcome(): ReceivingNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> ReceivingNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            ReceivingNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> ReceivingNetworkOutcome.PermissionDenied

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            ReceivingNetworkOutcome.NetworkUnavailable

        else -> ReceivingNetworkOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toReceiptOutcome(): ReceivingNetworkOutcome = when {
        kind == FailureKind.UnknownOutcome -> ReceivingNetworkOutcome.UnknownOutcome

        kind == FailureKind.AuthenticationRequired -> ReceivingNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            ReceivingNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> ReceivingNetworkOutcome.PermissionDenied

        httpStatus in 400..499 -> ReceivingNetworkOutcome.Rejected(problemCode)

        else -> ReceivingNetworkOutcome.UnknownOutcome
    }

    private fun WarehouseWire.toWarehouseProjection(): ReceivingWarehouseProjection? {
        val safeId = id.requiredText() ?: return null
        val safeCode = code.requiredText() ?: return null
        val safeName = name.requiredText() ?: return null
        val safeStatus = status.requiredText() ?: return null
        if (!uuidPattern.matches(safeId)) return null
        return ReceivingWarehouseProjection(safeId, safeCode, safeName, safeStatus)
    }

    private fun ZoneWire.toZoneProjection(): ReceivingZoneProjection? {
        val safeId = id.requiredText() ?: return null
        val safeWarehouseId = warehouseId.requiredText() ?: return null
        val safeCode = code.requiredText() ?: return null
        val safeName = name.requiredText() ?: return null
        val safeStatus = status.requiredText() ?: return null
        if (!uuidPattern.matches(safeId) || !uuidPattern.matches(safeWarehouseId)) return null
        return ReceivingZoneProjection(safeId, safeWarehouseId, safeCode, safeName, safeStatus)
    }

    private fun LotWire.toProjection(): ReceivingLotProjection? {
        val safeId = id.requiredText() ?: return null
        val safeWarehouse = warehouseId.requiredText() ?: return null
        val safeZone = zoneId.requiredText() ?: return null
        val safeBatch = batchNumber.requiredText() ?: return null
        val safeReceived = receivedAt.requiredText() ?: return null
        try {
            Instant.parse(safeReceived)
        } catch (_: DateTimeParseException) {
            return null
        }
        val safeUnit = unit.requiredText() ?: return null
        val safeStatus = status.requiredText() ?: return null
        val expiry = expirationDate.requiredText()?.parseLocalDate() ?: return null
        val onHandValue = onHand.decimalValue() ?: return null
        val reservedValue = reserved.decimalValue() ?: return null
        val availableValue = available.decimalValue() ?: return null
        val safeVersion = version ?: return null
        if (!uuidPattern.matches(safeId) || !uuidPattern.matches(safeWarehouse) ||
            !uuidPattern.matches(safeZone) || safeVersion < 0 ||
            (catalogItemId != null && !catalogItemPattern.matches(catalogItemId)) ||
            (skuId != null && !uuidPattern.matches(skuId))
        ) {
            return null
        }
        return ReceivingLotProjection(
            id = safeId,
            warehouseId = safeWarehouse,
            zoneId = safeZone,
            catalogItemId = catalogItemId,
            skuId = skuId,
            batchNumber = safeBatch,
            expirationDate = expiry,
            receivedAt = safeReceived,
            onHand = onHandValue,
            reserved = reservedValue,
            available = availableValue,
            unit = safeUnit,
            status = safeStatus,
            version = safeVersion
        )
    }

    private fun EvidenceWire.toProjection(): ReceivingEvidenceProjection? {
        val safeId = id.requiredText() ?: return null
        val safeSubjectType = subjectType.requiredText() ?: return null
        val safeSubjectId = subjectId.requiredText() ?: return null
        val safeStatus = lifecycleStatus.requiredText() ?: return null
        val safeContentType = declaredContentType.requiredText() ?: return null
        val safeBytes = byteSize?.takeIf { it in 1..MAX_RECEIVING_EVIDENCE_BYTES } ?: return null
        if (!uuidPattern.matches(safeId) || !uuidPattern.matches(safeSubjectId) ||
            safeContentType !in setOf("image/jpeg", "image/png", "image/webp") ||
            (checksumSha256 != null && !checksumSha256.matches(Regex("[0-9a-f]{64}")))
        ) {
            return null
        }
        return ReceivingEvidenceProjection(
            safeId,
            safeSubjectType,
            safeSubjectId,
            safeStatus,
            safeContentType,
            checksumSha256,
            safeBytes
        )
    }

    private fun JsonElement?.decimalValue(): BigDecimal? = try {
        when (this) {
            null, JsonNull -> null
            is JsonPrimitive -> BigDecimal(content)
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }

    private fun String.parseLocalDate(): LocalDate? = try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun String?.requiredText(): String? = this?.takeIf(String::isNotBlank)

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { receivingJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
        const val HTTP_CREATED = 201
    }
}

@Serializable
private data class WarehousePageWire(
    val items: List<WarehouseWire>? = null,
    val page: Int? = null,
    val size: Int? = null,
    val total: Long? = null
)

@Serializable
private data class WarehouseWire(
    val id: String? = null,
    val code: String? = null,
    val name: String? = null,
    val status: String? = null
)

@Serializable
private data class ZonePageWire(
    val items: List<ZoneWire>? = null,
    val page: Int? = null,
    val size: Int? = null,
    val total: Long? = null
)

@Serializable
private data class ZoneWire(
    val id: String? = null,
    val warehouseId: String? = null,
    val code: String? = null,
    val name: String? = null,
    val status: String? = null
)

@Serializable
private data class LotWire(
    val id: String? = null,
    val warehouseId: String? = null,
    val zoneId: String? = null,
    val catalogItemId: String? = null,
    val skuId: String? = null,
    val batchNumber: String? = null,
    val expirationDate: String? = null,
    val receivedAt: String? = null,
    val onHand: JsonElement? = null,
    val reserved: JsonElement? = null,
    val available: JsonElement? = null,
    val unit: String? = null,
    val status: String? = null,
    val version: Long? = null
)

@Serializable
private data class EvidenceWire(
    val id: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val lifecycleStatus: String? = null,
    val declaredContentType: String? = null,
    val checksumSha256: String? = null,
    val byteSize: Long? = null
)
