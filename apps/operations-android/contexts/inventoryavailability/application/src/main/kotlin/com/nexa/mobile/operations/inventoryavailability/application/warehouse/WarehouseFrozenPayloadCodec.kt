package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferRequest
import java.math.BigDecimal

/** Serializes a client command once so durable retries reuse its exact request bytes. */
interface WarehouseFrozenPayloadCodec {
    fun stockTransferPayload(request: StockTransferRequest): String

    fun cycleCountPayload(quantity: BigDecimal, unit: String): String

    fun lotSubstitutionPayload(
        work: LotSubstitutionWork,
        alternativeLotId: String,
        quantity: BigDecimal,
        reason: String
    ): String
}
