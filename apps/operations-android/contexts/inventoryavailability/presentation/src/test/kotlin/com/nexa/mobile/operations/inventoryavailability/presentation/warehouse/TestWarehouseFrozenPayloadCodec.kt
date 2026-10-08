package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseFrozenPayloadCodec
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferRequest
import java.math.BigDecimal

internal object TestWarehouseFrozenPayloadCodec : WarehouseFrozenPayloadCodec {
    override fun stockTransferPayload(request: StockTransferRequest): String {
        val quantity = request.quantityText.trim()
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

                else ->
                    if (character.code <
                        0x20
                    ) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }
}
