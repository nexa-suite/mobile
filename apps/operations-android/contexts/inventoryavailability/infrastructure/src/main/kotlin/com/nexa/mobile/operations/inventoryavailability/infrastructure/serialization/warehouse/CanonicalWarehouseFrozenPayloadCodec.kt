package com.nexa.mobile.operations.inventoryavailability.infrastructure.serialization.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseFrozenPayloadCodec
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferRequest
import java.math.BigDecimal

/** Preserves byte shape across the existing inventory mutation routes. */
class CanonicalWarehouseFrozenPayloadCodec : WarehouseFrozenPayloadCodec {
    override fun stockTransferPayload(request: StockTransferRequest): String {
        val quantity = request.quantityText.trim()
        require(quantity.matches(DECIMAL_LEXEME))
        require(BigDecimal(quantity).signum() > 0)
        require(
            request.sourceLotId.isNotBlank() && request.sourceWarehouseId.isNotBlank() &&
                request.sourceZoneId.isNotBlank()
        )
        require(
            request.destinationWarehouseId.isNotBlank() && request.destinationZoneId.isNotBlank()
        )
        require(!request.skuId.isNullOrBlank() || !request.catalogItemId.isNullOrBlank())
        require(
            request.unit.isNotBlank() && request.reason.isNotBlank() &&
                request.reason == request.reason.trim() && request.reason.length <= 2_000
        )
        return buildString {
            append("{\"sourceLotId\":").append(request.sourceLotId.jsonString())
            append(",\"sourceWarehouseId\":").append(request.sourceWarehouseId.jsonString())
            append(",\"sourceZoneId\":").append(request.sourceZoneId.jsonString())
            append(
                ",\"destinationWarehouseId\":"
            ).append(request.destinationWarehouseId.jsonString())
            append(",\"destinationZoneId\":").append(request.destinationZoneId.jsonString())
            request.skuId?.let { append(",\"skuId\":").append(it.jsonString()) }
            request.catalogItemId?.let { append(",\"catalogItemId\":").append(it.jsonString()) }
            append(",\"quantity\":").append(quantity)
            append(",\"unit\":").append(request.unit.jsonString())
            append(",\"reason\":").append(request.reason.jsonString())
            append('}')
        }
    }

    override fun cycleCountPayload(quantity: BigDecimal, unit: String): String =
        "{\"observedQuantity\":${quantity.toPlainString()},\"unit\":\"$unit\"}"

    override fun lotSubstitutionPayload(
        work: LotSubstitutionWork,
        alternativeLotId: String,
        quantity: BigDecimal,
        reason: String
    ): String =
        """{"fulfillmentId":"${work.fulfillmentId}","allocationId":"${work.allocationId}","physicalAllocationLineId":"${work.allocationLineId}","expectedLotId":"${work.expectedLotId}","alternativeLotId":"$alternativeLotId","quantity":${quantity.toPlainString()},"unit":${work.unit.jsonString()},"reason":${reason.jsonString()}}"""

    private fun String.jsonString(): String = buildString {
        append('"')
        for (character in this@jsonString) {
            when (character) {
                '"' -> append("\\\"")

                '\\' -> append("\\\\")

                '\b' -> append("\\b")

                '\u000C' -> append("\\f")

                '\n' -> append("\\n")

                '\r' -> append("\\r")

                '\t' -> append("\\t")

                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private companion object {
        val DECIMAL_LEXEME = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    }
}
