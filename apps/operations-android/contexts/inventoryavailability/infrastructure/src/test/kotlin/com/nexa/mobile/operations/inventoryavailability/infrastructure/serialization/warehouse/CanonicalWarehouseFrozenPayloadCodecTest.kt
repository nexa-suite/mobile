package com.nexa.mobile.operations.inventoryavailability.infrastructure.serialization.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferRequest
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalWarehouseFrozenPayloadCodecTest {
    private val codec = CanonicalWarehouseFrozenPayloadCodec()

    @Test
    fun cycleCountRetainsDecimalScaleAndLegacyFieldOrder() {
        assertEquals(
            "{\"observedQuantity\":4.250,\"unit\":\"EA\"}",
            codec.cycleCountPayload(BigDecimal("4.250"), "EA")
        )
    }

    @Test
    fun stockTransferRetainsCanonicalOrderDecimalLexemeAndEscaping() {
        val request = StockTransferRequest(
            sourceLotId = "lot-\"1\"",
            sourceWarehouseId = "warehouse-1",
            sourceZoneId = "source-zone",
            destinationWarehouseId = "warehouse-2",
            destinationZoneId = "destination-zone",
            skuId = "sku-1",
            catalogItemId = "catalog-1",
            quantityText = " 4.250 ",
            unit = "EA\" ",
            reason = "Move \"cold-chain\"\nwith\tcare"
        )

        assertEquals(
            """{"sourceLotId":"lot-\"1\"","sourceWarehouseId":"warehouse-1","sourceZoneId":"source-zone","destinationWarehouseId":"warehouse-2","destinationZoneId":"destination-zone","skuId":"sku-1","catalogItemId":"catalog-1","quantity":4.250,"unit":"EA\" ","reason":"Move \"cold-chain\"\nwith\tcare"}""",
            codec.stockTransferPayload(request)
        )
    }

    @Test
    fun lotSubstitutionRetainsLegacyFieldOrderAndEscaping() {
        val work = LotSubstitutionWork(
            fulfillmentId = "fulfillment-1",
            allocationId = "allocation-1",
            allocationLineId = "line-1",
            skuId = "sku-1",
            catalogItemId = "catalog-1",
            expectedLotId = "expected-1",
            warehouseId = "warehouse-1",
            zoneId = "zone-1",
            preparedQuantityText = "4.000",
            unit = "EA\"",
            allocationVersion = 8
        )

        assertEquals(
            """{"fulfillmentId":"fulfillment-1","allocationId":"allocation-1","physicalAllocationLineId":"line-1","expectedLotId":"expected-1","alternativeLotId":"alternative-1","quantity":4.000,"unit":"EA\"","reason":"Reason \"quoted\"\nand continued"}""",
            codec.lotSubstitutionPayload(
                work,
                alternativeLotId = "alternative-1",
                quantity = BigDecimal("4.000"),
                reason = "Reason \"quoted\"\nand continued"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun stockTransferRejectsNonCanonicalDecimalLexeme() {
        codec.stockTransferPayload(
            StockTransferRequest(
                sourceLotId = "lot",
                sourceWarehouseId = "source",
                sourceZoneId = "source-zone",
                destinationWarehouseId = "destination",
                destinationZoneId = "destination-zone",
                skuId = "sku",
                catalogItemId = null,
                quantityText = "01.0",
                unit = "EA",
                reason = "Move"
            )
        )
    }
}
