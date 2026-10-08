package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentType
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverOutcomeKind
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverOutcomeLineDecision
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverProofEvidenceKind
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonDeliveryRequestBodyCodecTest {
    private val codec = JsonDeliveryRequestBodyCodec()

    @Test
    fun incidentBodyKeepsTypedFieldOrderAndJsonEscaping() {
        assertEquals(
            "{\"type\":\"DELAY\",\"reason\":\"Road closure\",\"description\":\"Gate \\\"B\\\"\"," +
                "\"place\":\"North entrance\"}",
            codec.driverIncidentBody(
                DriverIncidentType.DELAY,
                "Road closure",
                "Gate \"B\"",
                "North entrance"
            )
        )
    }

    @Test
    fun resolutionCodecKeepsTheFrozenShapeAndRejectsUnexpectedFieldTypes() {
        val body = codec.driverDeliveryOperationalExceptionResolutionBody("  Path cleared  ")

        assertEquals("{\"resolution\":\"Path cleared\"}", body)
        assertEquals(
            "Path cleared",
            codec.driverDeliveryOperationalExceptionResolutionFromBody(body)
        )
        assertNull(
            codec.driverDeliveryOperationalExceptionResolutionFromBody(
                "{\"resolution\":4}"
            )
        )
    }

    @Test
    fun temperatureReadingRetainsPreciseQuantitiesAndOptionalFieldOrder() {
        assertEquals(
            "{\"fulfillmentLineId\":\"11111111-1111-4111-8111-111111111111\"," +
                "\"skuId\":\"22222222-2222-4222-8222-222222222222\"," +
                "\"affectedQuantity\":1.25,\"value\":-5,\"unit\":\"CELSIUS\"," +
                "\"occurredAt\":\"2026-10-01T10:15:30Z\"," +
                "\"sourceIncidentId\":\"33333333-3333-4333-8333-333333333333\"," +
                "\"evidenceObjectId\":\"44444444-4444-4444-8444-444444444444\"}",
            codec.driverExecutionTemperatureReadingBody(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                BigDecimal("1.250"),
                BigDecimal("-5.00"),
                Instant.parse("2026-10-01T10:15:30Z"),
                "33333333-3333-4333-8333-333333333333",
                "44444444-4444-4444-8444-444444444444"
            )
        )
    }

    @Test
    fun outcomeAndProofBodiesRetainLegacyFieldOrderAndEscaping() {
        assertEquals(
            "{\"outcome\":\"PARTIAL\",\"failureReason\":null," +
                "\"notes\":\"Buyer \\\"accepted\\\"\",\"attemptedAt\":\"2026-10-01T10:15:30Z\"," +
                "\"lines\":[{\"fulfillmentLineId\":\"11111111-1111-4111-8111-111111111111\"," +
                "\"skuId\":\"22222222-2222-4222-8222-222222222222\",\"attemptedQuantity\":1.250," +
                "\"deliveredQuantity\":1.250,\"rejectedQuantity\":0,\"cancelledQuantity\":0," +
                "\"unit\":\"case\"}]}",
            codec.driverOutcomeBody(
                outcome = DriverOutcomeKind.PARTIAL,
                reason = null,
                notes = "Buyer \"accepted\"",
                attemptedAt = "2026-10-01T10:15:30Z",
                lines = listOf(
                    DriverOutcomeLineDecision(
                        fulfillmentLineId = "11111111-1111-4111-8111-111111111111",
                        skuId = "22222222-2222-4222-8222-222222222222",
                        attemptedQuantity = BigDecimal("1.250"),
                        deliveredQuantity = BigDecimal("1.250"),
                        rejectedQuantity = BigDecimal.ZERO,
                        cancelledQuantity = BigDecimal.ZERO,
                        unit = "case"
                    )
                )
            )
        )
        assertEquals(
            "{\"receiverName\":\"Ada Lovelace\",\"capturedAt\":\"2026-10-01T10:15:30Z\"," +
                "\"notes\":\"Front desk\"}",
            codec.driverProofCreateBody("Ada Lovelace", "2026-10-01T10:15:30Z", "Front desk")
        )
        assertEquals(
            "{\"kind\":\"PHOTO\",\"evidenceObjectId\":\"33333333-3333-4333-8333-333333333333\"}",
            codec.driverProofAttachBody(
                DriverProofEvidenceKind.PHOTO,
                "33333333-3333-4333-8333-333333333333"
            )
        )
    }
}
