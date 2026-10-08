package com.nexa.mobile.operations.inventoryavailability.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val INVENTORY_TRANSFERS_PATH = "/api/v1/inventory/transfers"
private val stockTransferJson = Json { ignoreUnknownKeys = true }

/** Server facts for a requested movement. A REQUESTED transfer has not moved or received stock. */
data class StockTransferProjection(
    val id: String,
    val sourceWarehouseId: String,
    val sourceZoneId: String,
    val sourceLotId: String,
    val destinationWarehouseId: String,
    val destinationZoneId: String,
    val destinationLotId: String?,
    val skuId: String?,
    val catalogItemId: String?,
    val requestedQuantity: BigDecimal,
    val transferredQuantity: BigDecimal,
    val mode: String,
    val unit: String,
    val status: String,
    val reason: String,
    val sourceVersionBefore: Long,
    val sourceVersionAfter: Long?,
    val destinationVersionAfter: Long?,
    val version: Long,
    val dispatchedAt: String?,
    val receivedAt: String?,
    val batchNumber: String? = null,
    val expirationDate: String? = null
) {
    override fun toString(): String =
        "StockTransferProjection(status=$status, version=$version, quantity=REDACTED)"
}

sealed interface StockTransferNetworkOutcome {
    data class Confirmed(val transfer: StockTransferProjection) : StockTransferNetworkOutcome
    data class Rejected(val code: String?) : StockTransferNetworkOutcome
    data object UnknownOutcome : StockTransferNetworkOutcome
    data object PreconditionFailed : StockTransferNetworkOutcome
    data object Conflict : StockTransferNetworkOutcome
    data object NetworkUnavailable : StockTransferNetworkOutcome
    data object ServiceUnavailable : StockTransferNetworkOutcome
    data object PermissionDenied : StockTransferNetworkOutcome
    data object ContextInvalidated : StockTransferNetworkOutcome
    data object SessionInvalidated : StockTransferNetworkOutcome
}

/** Server-attributed observation of what arrived; it does not receive stock or close a transfer. */
data class StockTransferReceiptObservationProjection(
    val observationId: String,
    val transferId: String,
    val transferVersion: Long,
    val sourceWarehouseId: String,
    val sourceZoneId: String,
    val sourceLotId: String,
    val destinationWarehouseId: String,
    val destinationZoneId: String,
    val expectedBatchNumber: String,
    val expectedExpirationDate: String?,
    val expectedQuantity: BigDecimal,
    val expectedUnit: String,
    val observedBatchNumber: String,
    val observedExpirationDate: String?,
    val observedQuantity: BigDecimal,
    val observedUnit: String,
    val hasDifference: Boolean,
    val actorMembershipId: String,
    val recordedAt: String
) {
    override fun toString(): String =
        "StockTransferReceiptObservationProjection(id=$observationId, hasDifference=$hasDifference, quantities=REDACTED)"
}

sealed interface StockTransferReceiptObservationNetworkOutcome {
    data class Recorded(val observation: StockTransferReceiptObservationProjection) :
        StockTransferReceiptObservationNetworkOutcome
    data class Rejected(val code: String?) : StockTransferReceiptObservationNetworkOutcome
    data object UnknownOutcome : StockTransferReceiptObservationNetworkOutcome
    data object PreconditionFailed : StockTransferReceiptObservationNetworkOutcome
    data object Conflict : StockTransferReceiptObservationNetworkOutcome
    data object NetworkUnavailable : StockTransferReceiptObservationNetworkOutcome
    data object ServiceUnavailable : StockTransferReceiptObservationNetworkOutcome
    data object PermissionDenied : StockTransferReceiptObservationNetworkOutcome
    data object ContextInvalidated : StockTransferReceiptObservationNetworkOutcome
    data object SessionInvalidated : StockTransferReceiptObservationNetworkOutcome
}

sealed interface StockTransferLookupNetworkOutcome {
    data class Transfer(val item: StockTransferProjection) : StockTransferLookupNetworkOutcome
    data class Page(
        val items: List<StockTransferProjection>,
        val page: Int,
        val size: Int,
        val total: Long
    ) : StockTransferLookupNetworkOutcome
    data object Rejected : StockTransferLookupNetworkOutcome
    data object NetworkUnavailable : StockTransferLookupNetworkOutcome
    data object ServiceUnavailable : StockTransferLookupNetworkOutcome
    data object PermissionDenied : StockTransferLookupNetworkOutcome
    data object ContextInvalidated : StockTransferLookupNetworkOutcome
    data object SessionInvalidated : StockTransferLookupNetworkOutcome
}

/** Sends one frozen, scope-bound request body unchanged with its source-lot version and key. */
class NexaStockTransferGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun transfersForDestination(
        destinationWarehouseId: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE
    ): StockTransferLookupNetworkOutcome {
        if (!destinationWarehouseId.isUuid() || page !in 0..MAX_PAGE || size !in 1..MAX_PAGE_SIZE) {
            return StockTransferLookupNetworkOutcome.Rejected
        }
        val path =
            "$INVENTORY_TRANSFERS_PATH?destinationWarehouseId=$destinationWarehouseId" +
                "&page=$page&size=$size"
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, path)
            )
        ) {
            is ProtectedResult.Failure -> result.error.toStockTransferLookupOutcome()

            is ProtectedResult.Success -> {
                if (result.status !=
                    HTTP_OK
                ) {
                    return StockTransferLookupNetworkOutcome.ServiceUnavailable
                }
                val wire = result.body.decode<TransferPageResponseWire>()
                    ?: return StockTransferLookupNetworkOutcome.ServiceUnavailable
                val items = wire.items?.map { item ->
                    item.toProjection()
                        ?: return StockTransferLookupNetworkOutcome.ServiceUnavailable
                } ?: return StockTransferLookupNetworkOutcome.ServiceUnavailable
                if (wire.page != page || wire.size != size || wire.total == null ||
                    wire.total < items.size ||
                    items.size > size
                ) {
                    StockTransferLookupNetworkOutcome.ServiceUnavailable
                } else {
                    StockTransferLookupNetworkOutcome.Page(items, page, size, wire.total)
                }
            }
        }
    }

    suspend fun transfer(transferId: String): StockTransferLookupNetworkOutcome {
        if (!transferId.isUuid()) return StockTransferLookupNetworkOutcome.Rejected
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, "$INVENTORY_TRANSFERS_PATH/$transferId")
            )
        ) {
            is ProtectedResult.Failure -> result.error.toStockTransferLookupOutcome()

            is ProtectedResult.Success -> {
                if (result.status !=
                    HTTP_OK
                ) {
                    return StockTransferLookupNetworkOutcome.ServiceUnavailable
                }
                val item = result.body.decode<TransferResponseWire>()?.toProjection()
                    ?: return StockTransferLookupNetworkOutcome.ServiceUnavailable
                if (item.id == transferId) {
                    StockTransferLookupNetworkOutcome.Transfer(item)
                } else {
                    StockTransferLookupNetworkOutcome.ServiceUnavailable
                }
            }
        }
    }

    suspend fun receiveTransfer(
        expectedTransfer: StockTransferProjection,
        idempotencyKey: String
    ): StockTransferNetworkOutcome {
        if (expectedTransfer.status != STATUS_IN_TRANSIT || expectedTransfer.version < 0 ||
            expectedTransfer.transferredQuantity.signum() <= 0 || idempotencyKey.isBlank() ||
            idempotencyKey.length > 160 || !expectedTransfer.id.isUuid()
        ) {
            return StockTransferNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$INVENTORY_TRANSFERS_PATH/${expectedTransfer.id}/receipts",
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"${expectedTransfer.version}\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toStockTransferOutcome()

            is ProtectedResult.Success -> {
                if (result.status != HTTP_OK) return StockTransferNetworkOutcome.UnknownOutcome
                val item = result.body.decode<TransferResponseWire>()?.toProjection()
                    ?: return StockTransferNetworkOutcome.UnknownOutcome
                if (item.matchesReceipt(expectedTransfer)) {
                    StockTransferNetworkOutcome.Confirmed(item)
                } else {
                    StockTransferNetworkOutcome.UnknownOutcome
                }
            }
        }
    }

    /** Persists one attributable observation with the selected transfer's own version and a frozen key. */
    suspend fun observeTransferArrival(
        expectedTransfer: StockTransferProjection,
        observedBatchNumber: String,
        observedExpirationDate: String?,
        observedQuantity: BigDecimal,
        unit: String,
        idempotencyKey: String
    ): StockTransferReceiptObservationNetworkOutcome {
        if (expectedTransfer.status != STATUS_IN_TRANSIT || expectedTransfer.version < 0 ||
            expectedTransfer.transferredQuantity.signum() <= 0 ||
            expectedTransfer.dispatchedAt.isNullOrBlank() ||
            expectedTransfer.batchNumber.isNullOrBlank() || !expectedTransfer.id.isUuid() ||
            observedBatchNumber.isBlank() || observedBatchNumber != observedBatchNumber.trim() ||
            observedBatchNumber.length > MAX_BATCH_NUMBER_LENGTH ||
            observedBatchNumber.any(Char::isISOControl) ||
            (observedExpirationDate != null && !observedExpirationDate.isValidLocalDate()) ||
            observedQuantity.signum() < 0 || !unit.equals(
                expectedTransfer.unit,
                ignoreCase = true
            ) ||
            idempotencyKey.isBlank() || idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH
        ) {
            return StockTransferReceiptObservationNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        val payload = try {
            stockTransferJson.encodeToString(
                TransferReceiptObservationRequestWire(
                    observedBatchNumber,
                    observedExpirationDate,
                    observedQuantity.toPlainString(),
                    unit
                )
            )
        } catch (_: SerializationException) {
            return StockTransferReceiptObservationNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$INVENTORY_TRANSFERS_PATH/${expectedTransfer.id}/receipt-observations",
                    payload = payload,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"${expectedTransfer.version}\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toReceiptObservationOutcome()

            is ProtectedResult.Success -> {
                if (result.status !=
                    HTTP_CREATED
                ) {
                    return StockTransferReceiptObservationNetworkOutcome.UnknownOutcome
                }
                val wire = result.body.decode<TransferReceiptObservationResponseWire>()
                    ?: return StockTransferReceiptObservationNetworkOutcome.UnknownOutcome
                val observation = wire.toProjection(
                    expectedTransfer,
                    observedBatchNumber,
                    observedExpirationDate,
                    observedQuantity,
                    unit
                )
                    ?: return StockTransferReceiptObservationNetworkOutcome.UnknownOutcome
                StockTransferReceiptObservationNetworkOutcome.Recorded(observation)
            }
        }
    }

    suspend fun createTransfer(
        frozenPayload: String,
        expectedSourceVersion: Long,
        idempotencyKey: String
    ): StockTransferNetworkOutcome {
        val command = frozenPayload.parseTransferCommand()
            ?: return StockTransferNetworkOutcome.Rejected("INVALID_REQUEST")
        if (expectedSourceVersion < 0 || idempotencyKey.isBlank() || idempotencyKey.length > 160) {
            return StockTransferNetworkOutcome.Rejected("INVALID_REQUEST")
        }

        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = INVENTORY_TRANSFERS_PATH,
                    payload = frozenPayload,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedSourceVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toStockTransferOutcome()

            is ProtectedResult.Success -> {
                if (result.status != HTTP_CREATED) return StockTransferNetworkOutcome.UnknownOutcome
                val wire = result.body.decode<TransferResponseWire>()
                    ?: return StockTransferNetworkOutcome.UnknownOutcome
                val projection = wire.toProjection()
                    ?: return StockTransferNetworkOutcome.UnknownOutcome
                if (!projection.matches(command, expectedSourceVersion)) {
                    StockTransferNetworkOutcome.UnknownOutcome
                } else {
                    StockTransferNetworkOutcome.Confirmed(projection)
                }
            }
        }
    }

    private fun TransferResponseWire.toProjection(): StockTransferProjection? {
        val safeId = id?.takeIf { it.isUuid() } ?: return null
        val safeSourceWarehouse = sourceWarehouseId?.takeIf { it.isUuid() } ?: return null
        val safeSourceZone = sourceZoneId?.takeIf { it.isUuid() } ?: return null
        val safeSourceLot = sourceLotId?.takeIf { it.isUuid() } ?: return null
        val safeDestinationWarehouse = destinationWarehouseId?.takeIf { it.isUuid() } ?: return null
        val safeDestinationZone = destinationZoneId?.takeIf { it.isUuid() } ?: return null
        val safeDestinationLot = destinationLotId?.takeIf { it.isUuid() }
        val safeSku = skuId?.takeIf { it.isUuid() }
        val safeCatalog = catalogItemId?.takeIf(CATALOG_ITEM_ID::matches)
        val requested = requestedQuantity.decimalValue() ?: return null
        val transferred = transferredQuantity.decimalValue() ?: return null
        val safeMode = mode?.takeIf { it == "FULL" || it == "PARTIAL" } ?: return null
        val safeUnit = unit?.takeIf(String::isNotBlank) ?: return null
        val safeStatus = status?.takeIf(String::isNotBlank) ?: return null
        val safeReason = reason?.takeIf(String::isNotBlank) ?: return null
        val safeSourceVersion = sourceVersionBefore?.takeIf { it >= 0 } ?: return null
        val safeVersion = version?.takeIf { it >= 0 } ?: return null
        val safeReceivedAt = receivedAt?.takeIf(String::isNotBlank)
        if ((destinationLotId != null && safeDestinationLot == null) ||
            (skuId != null && safeSku == null) || (catalogItemId != null && safeCatalog == null)
        ) {
            return null
        }
        return StockTransferProjection(
            safeId,
            safeSourceWarehouse,
            safeSourceZone,
            safeSourceLot,
            safeDestinationWarehouse,
            safeDestinationZone,
            safeDestinationLot,
            safeSku,
            safeCatalog,
            requested,
            transferred,
            safeMode,
            safeUnit,
            safeStatus,
            safeReason,
            safeSourceVersion,
            sourceVersionAfter,
            destinationVersionAfter,
            safeVersion,
            dispatchedAt?.takeIf(String::isNotBlank),
            safeReceivedAt,
            batchNumber?.takeIf(String::isNotBlank),
            expirationDate?.takeIf(String::isNotBlank)
        )
    }

    private fun StockTransferProjection.matchesReceipt(expected: StockTransferProjection): Boolean =
        id == expected.id && sourceWarehouseId == expected.sourceWarehouseId &&
            sourceZoneId == expected.sourceZoneId && sourceLotId == expected.sourceLotId &&
            destinationWarehouseId == expected.destinationWarehouseId &&
            destinationZoneId == expected.destinationZoneId &&
            skuId == expected.skuId && catalogItemId == expected.catalogItemId &&
            requestedQuantity.compareTo(expected.requestedQuantity) == 0 &&
            transferredQuantity.compareTo(expected.transferredQuantity) == 0 &&
            mode == expected.mode && unit.equals(expected.unit, ignoreCase = true) &&
            reason == expected.reason &&
            status == STATUS_RECEIVED && destinationLotId != null && receivedAt != null &&
            sourceVersionAfter != null && sourceVersionAfter >= 0 &&
            destinationVersionAfter != null && destinationVersionAfter >= 0 &&
            version > expected.version

    private fun StockTransferProjection.matches(
        command: TransferCommandWire,
        expectedSourceVersion: Long
    ): Boolean =
        sourceLotId == command.sourceLotId && sourceWarehouseId == command.sourceWarehouseId &&
            sourceZoneId == command.sourceZoneId &&
            destinationWarehouseId == command.destinationWarehouseId &&
            destinationZoneId == command.destinationZoneId && skuId == command.skuId &&
            catalogItemId == command.catalogItemId &&
            requestedQuantity.compareTo(command.quantity) == 0 &&
            transferredQuantity.compareTo(BigDecimal.ZERO) == 0 &&
            unit.equals(command.unit, ignoreCase = true) && reason == command.reason &&
            sourceVersionBefore == expectedSourceVersion && status == "REQUESTED" &&
            sourceVersionAfter == null && destinationVersionAfter == null &&
            destinationLotId == null && dispatchedAt == null && receivedAt == null

    private fun ClientFailure.toStockTransferOutcome(): StockTransferNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> StockTransferNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            StockTransferNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure || httpStatus == 404 ->
            StockTransferNetworkOutcome.PermissionDenied

        kind == FailureKind.StaleState || httpStatus == 412 ->
            StockTransferNetworkOutcome.PreconditionFailed

        kind == FailureKind.BusinessConflict || httpStatus == 409 ->
            StockTransferNetworkOutcome.Conflict

        kind == FailureKind.ValidationFailure -> StockTransferNetworkOutcome.Rejected(problemCode)

        kind == FailureKind.UnknownOutcome -> StockTransferNetworkOutcome.UnknownOutcome

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            StockTransferNetworkOutcome.UnknownOutcome

        else -> StockTransferNetworkOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toStockTransferLookupOutcome(): StockTransferLookupNetworkOutcome =
        when {
            kind == FailureKind.AuthenticationRequired ->
                StockTransferLookupNetworkOutcome.SessionInvalidated

            httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
                StockTransferLookupNetworkOutcome.ContextInvalidated

            kind == FailureKind.AuthorizationFailure ->
                StockTransferLookupNetworkOutcome.PermissionDenied

            kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
                StockTransferLookupNetworkOutcome.NetworkUnavailable

            kind == FailureKind.ValidationFailure -> StockTransferLookupNetworkOutcome.Rejected

            else -> StockTransferLookupNetworkOutcome.ServiceUnavailable
        }

    private fun ClientFailure.toReceiptObservationOutcome():
        StockTransferReceiptObservationNetworkOutcome =
        when {
            kind == FailureKind.AuthenticationRequired ->
                StockTransferReceiptObservationNetworkOutcome.SessionInvalidated

            httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
                StockTransferReceiptObservationNetworkOutcome.ContextInvalidated

            kind == FailureKind.AuthorizationFailure || httpStatus == 404 ->
                StockTransferReceiptObservationNetworkOutcome.PermissionDenied

            kind == FailureKind.StaleState || httpStatus == 412 ->
                StockTransferReceiptObservationNetworkOutcome.PreconditionFailed

            kind == FailureKind.BusinessConflict || httpStatus == 409 ->
                StockTransferReceiptObservationNetworkOutcome.Conflict

            kind == FailureKind.ValidationFailure ->
                StockTransferReceiptObservationNetworkOutcome.Rejected(
                    problemCode
                )

            kind == FailureKind.UnknownOutcome || kind == FailureKind.NetworkUnavailable ||
                kind == FailureKind.Timeout ->
                StockTransferReceiptObservationNetworkOutcome.UnknownOutcome

            else -> StockTransferReceiptObservationNetworkOutcome.ServiceUnavailable
        }

    private fun TransferReceiptObservationResponseWire.toProjection(
        expectedTransfer: StockTransferProjection,
        observedBatchNumber: String,
        observedExpirationDate: String?,
        observedQuantity: BigDecimal,
        observedUnit: String
    ): StockTransferReceiptObservationProjection? {
        val safeObservationId = observationId?.takeIf { it.isUuid() } ?: return null
        val safeTransferId = transferId?.takeIf { it.isUuid() } ?: return null
        val safeSourceWarehouse = sourceWarehouseId?.takeIf { it.isUuid() } ?: return null
        val safeSourceZone = sourceZoneId?.takeIf { it.isUuid() } ?: return null
        val safeSourceLot = sourceLotId?.takeIf { it.isUuid() } ?: return null
        val safeDestinationWarehouse = destinationWarehouseId?.takeIf { it.isUuid() } ?: return null
        val safeDestinationZone = destinationZoneId?.takeIf { it.isUuid() } ?: return null
        val expectedBatch = expectedBatchNumber?.takeIf(String::isNotBlank) ?: return null
        val safeExpectedQuantity = expectedQuantity.decimalValue() ?: return null
        val safeExpectedUnit = expectedUnit?.takeIf(String::isNotBlank) ?: return null
        val safeObservedBatch = observedBatchNumber.takeIf(String::isNotBlank) ?: return null
        val safeObservedQuantity = observedQuantity
        val safeObservedUnit = observedUnit.takeIf(String::isNotBlank) ?: return null
        val safeActor = actorMembershipId?.takeIf { it.isUuid() } ?: return null
        val safeRecordedAt = recordedAt?.takeIf { value ->
            try {
                Instant.parse(value)
                true
            } catch (_: RuntimeException) {
                false
            }
        } ?: return null
        if (transferVersion != expectedTransfer.version || safeTransferId != expectedTransfer.id ||
            safeSourceWarehouse != expectedTransfer.sourceWarehouseId ||
            safeSourceZone != expectedTransfer.sourceZoneId ||
            safeSourceLot != expectedTransfer.sourceLotId ||
            safeDestinationWarehouse != expectedTransfer.destinationWarehouseId ||
            safeDestinationZone != expectedTransfer.destinationZoneId ||
            expectedBatch != expectedTransfer.batchNumber ||
            expectedExpirationDate != expectedTransfer.expirationDate ||
            safeExpectedQuantity.compareTo(expectedTransfer.transferredQuantity) != 0 ||
            !safeExpectedUnit.equals(expectedTransfer.unit, ignoreCase = true) ||
            safeObservedBatch != observedBatchNumber ||
            observedExpirationDate != this.observedExpirationDate ||
            safeObservedQuantity.compareTo(observedQuantity) != 0 ||
            !safeObservedUnit.equals(observedUnit, ignoreCase = true) || hasDifference != true
        ) {
            return null
        }
        return StockTransferReceiptObservationProjection(
            safeObservationId, safeTransferId, transferVersion, safeSourceWarehouse, safeSourceZone,
            safeSourceLot, safeDestinationWarehouse, safeDestinationZone, expectedBatch,
            expectedExpirationDate, safeExpectedQuantity, safeExpectedUnit, safeObservedBatch,
            this.observedExpirationDate, safeObservedQuantity, safeObservedUnit, true,
            safeActor, safeRecordedAt
        )
    }

    private fun String.parseTransferCommand(): TransferCommandWire? = try {
        val objectValue = stockTransferJson.parseToJsonElement(this) as? JsonObject ?: return null
        val required = setOf(
            "sourceLotId",
            "sourceWarehouseId",
            "sourceZoneId",
            "destinationWarehouseId",
            "destinationZoneId",
            "quantity",
            "unit",
            "reason"
        )
        val allowed = required + setOf("skuId", "catalogItemId")
        if (!objectValue.keys.containsAll(required) || objectValue.keys.any { it !in allowed }) {
            return null
        }
        val quantity = (objectValue["quantity"] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.content
            ?.let(::BigDecimal)
            ?: return null
        val parsed = TransferCommandWire(
            sourceLotId = objectValue.requiredString("sourceLotId") ?: return null,
            sourceWarehouseId = objectValue.requiredString("sourceWarehouseId") ?: return null,
            sourceZoneId = objectValue.requiredString("sourceZoneId") ?: return null,
            destinationWarehouseId =
                objectValue.requiredString("destinationWarehouseId") ?: return null,
            destinationZoneId = objectValue.requiredString("destinationZoneId") ?: return null,
            skuId = objectValue.optionalString("skuId") ?: return null,
            catalogItemId = objectValue.optionalString("catalogItemId") ?: return null,
            quantity = quantity,
            unit = objectValue.requiredString("unit") ?: return null,
            reason = objectValue.requiredString("reason") ?: return null
        )
        parsed.takeIf { it.isValid() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun TransferCommandWire.isValid(): Boolean =
        sourceLotId.isUuid() && sourceWarehouseId.isUuid() && sourceZoneId.isUuid() &&
            destinationWarehouseId.isUuid() && destinationZoneId.isUuid() &&
            (skuId == null || skuId.isUuid()) &&
            (catalogItemId == null || CATALOG_ITEM_ID.matches(catalogItemId)) &&
            (skuId != null || catalogItemId != null) && quantity.signum() > 0 &&
            unit.isNotBlank() && reason.isNotBlank() && reason == reason.trim() &&
            reason.length <= 2_000

    private fun String.isUuid(): Boolean = try {
        UUID.fromString(this).toString().equals(this, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun String.isValidLocalDate(): Boolean = try {
        java.time.LocalDate.parse(this).toString() == this
    } catch (_: RuntimeException) {
        false
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

    private fun JsonObject.requiredString(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank)

    private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else "\u0000"
        else -> "\u0000"
    }

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { stockTransferJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private data class TransferCommandWire(
        val sourceLotId: String,
        val sourceWarehouseId: String,
        val sourceZoneId: String,
        val destinationWarehouseId: String,
        val destinationZoneId: String,
        val skuId: String? = null,
        val catalogItemId: String? = null,
        val quantity: BigDecimal,
        val unit: String,
        val reason: String
    )

    @Serializable
    private data class TransferReceiptObservationRequestWire(
        val observedBatchNumber: String,
        val observedExpirationDate: String?,
        val observedQuantity: String,
        val unit: String
    )

    @Serializable
    private data class TransferPageResponseWire(
        val items: List<TransferResponseWire>? = null,
        val page: Int? = null,
        val size: Int? = null,
        val total: Long? = null
    )

    @Serializable
    private data class TransferResponseWire(
        val id: String? = null,
        val sourceWarehouseId: String? = null,
        val sourceZoneId: String? = null,
        val sourceLotId: String? = null,
        val destinationWarehouseId: String? = null,
        val destinationZoneId: String? = null,
        val destinationLotId: String? = null,
        val skuId: String? = null,
        val catalogItemId: String? = null,
        val requestedQuantity: JsonElement? = null,
        val transferredQuantity: JsonElement? = null,
        val unit: String? = null,
        val status: String? = null,
        val reason: String? = null,
        val mode: String? = null,
        val sourceVersionBefore: Long? = null,
        val sourceVersionAfter: Long? = null,
        val destinationVersionAfter: Long? = null,
        val version: Long? = null,
        val dispatchedAt: String? = null,
        val receivedAt: String? = null,
        val batchNumber: String? = null,
        val expirationDate: String? = null
    )

    @Serializable
    private data class TransferReceiptObservationResponseWire(
        val observationId: String? = null,
        val transferId: String? = null,
        val transferVersion: Long? = null,
        val sourceWarehouseId: String? = null,
        val sourceZoneId: String? = null,
        val sourceLotId: String? = null,
        val destinationWarehouseId: String? = null,
        val destinationZoneId: String? = null,
        val expectedBatchNumber: String? = null,
        val expectedExpirationDate: String? = null,
        val expectedQuantity: JsonElement? = null,
        val expectedUnit: String? = null,
        val observedBatchNumber: String? = null,
        val observedExpirationDate: String? = null,
        val observedQuantity: JsonElement? = null,
        val observedUnit: String? = null,
        val hasDifference: Boolean? = null,
        val actorMembershipId: String? = null,
        val recordedAt: String? = null
    )

    private companion object {
        val CATALOG_ITEM_ID = Regex("(?i)CAT-[A-Z0-9-]{1,63}")
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
        const val STATUS_IN_TRANSIT = "IN_TRANSIT"
        const val STATUS_RECEIVED = "RECEIVED"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val MAX_BATCH_NUMBER_LENGTH = 80
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 160
        const val DEFAULT_PAGE_SIZE = 25
        const val MAX_PAGE_SIZE = 100
        const val MAX_PAGE = 10_000
    }
}
