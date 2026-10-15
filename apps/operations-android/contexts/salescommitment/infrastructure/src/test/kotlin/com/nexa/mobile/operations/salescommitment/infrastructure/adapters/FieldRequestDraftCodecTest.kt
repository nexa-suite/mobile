package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraft
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraftLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldRequestDraftCodecTest {
    @Test
    fun encryptedDraftPayloadRoundTripsCustomerAndCatalogSnapshots() {
        val draft = FieldRequestDraft(
            customerId = "customer-1",
            lines = listOf(
                FieldRequestDraftLine(
                    catalogItemId = "CAT-0001",
                    productId = "product-1",
                    name = "Pasta",
                    skuCode = "SKU-1",
                    brandName = "Brand",
                    categoryName = "Grocery",
                    familyName = "Pasta",
                    variantName = "Whole grain",
                    unit = "KG",
                    quantity = "2.5",
                    price = "7.90",
                    currency = "PEN",
                    pricingAsOf = "2026-10-15T10:00:00Z",
                    availability = "6",
                    availabilityStatus = "AVAILABLE",
                    availabilityAsOf = "2026-10-15T10:00:00Z"
                )
            ),
            customerName = "Customer",
            customerVersion = 3
        )

        assertEquals(draft, FieldRequestDraftCodec.decode(FieldRequestDraftCodec.encode(draft)))
    }

    @Test
    fun unsupportedSchemaOrOversizedDraftFailsClosed() {
        val encoded = FieldRequestDraftCodec.encode(FieldRequestDraft(customerId = "customer-1"))
        assertNull(FieldRequestDraftCodec.decode(encoded.replace("\"schema\":1", "\"schema\":2")))
        assertNull(FieldRequestDraftCodec.decode("{"))
    }
}
