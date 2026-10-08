package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsObservation
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureCommand
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonDispatchRequestBodyCodecTest {
    private val codec = JsonDispatchRequestBodyCodec()

    @Test
    fun handoverBodyRetainsLegacyFieldOrderAndBytes() {
        val command = DispatchHandoverCommand(
            fulfillmentId = "11111111-1111-4111-8111-111111111111",
            expectedFulfillmentVersion = 8,
            physicalAllocationId = "22222222-2222-4222-8222-222222222222",
            physicalAllocationVersion = 4,
            driverAssignmentId = "33333333-3333-4333-8333-333333333333",
            driverAssignmentVersion = 7,
            outgoingGoodsCheckId = "44444444-4444-4444-8444-444444444444",
            idempotencyKey = "handover-key",
            exactRequestBody = ""
        )

        assertEquals(
            "{\"physicalAllocationId\":\"22222222-2222-4222-8222-222222222222\"," +
                "\"physicalAllocationVersion\":4,\"driverAssignmentId\":\"33333333-3333-4333-8333-333333333333\"," +
                "\"driverAssignmentVersion\":7,\"outgoingGoodsCheckId\":\"44444444-4444-4444-8444-444444444444\"}",
            codec.dispatchHandoverRequestBody(command)
        )
    }

    @Test
    fun temperatureBodyRetainsDecimalAndOptionalFieldOrder() {
        val command = DispatchTemperatureCommand(
            fulfillmentId = "11111111-1111-4111-8111-111111111111",
            expectedFulfillmentVersion = 8,
            lotId = "22222222-2222-4222-8222-222222222222",
            valueCelsius = BigDecimal("5.50"),
            occurredAt = Instant.parse("2026-10-01T10:15:30Z"),
            idempotencyKey = "temperature-key",
            exactRequestBody = "",
            evidenceObjectId = "33333333-3333-4333-8333-333333333333",
            expectedLotVersion = 3
        )

        assertEquals(
            "{\"lotId\":\"22222222-2222-4222-8222-222222222222\",\"value\":5.5," +
                "\"unit\":\"CELSIUS\",\"occurredAt\":\"2026-10-01T10:15:30Z\"," +
                "\"expectedLotVersion\":3,\"evidenceObjectId\":\"33333333-3333-4333-8333-333333333333\"}",
            codec.dispatchTemperatureRequestBody(command)
        )
    }

    @Test
    fun outgoingGoodsBodyRetainsLegacyFieldOrderAndQuantityScale() {
        assertEquals(
            "{\"physicalAllocationId\":\"22222222-2222-4222-8222-222222222222\"," +
                "\"physicalAllocationVersion\":4,\"observations\":[{\"physicalAllocationLineId\":" +
                "\"11111111-1111-4111-8111-111111111111\",\"observedLotId\":null," +
                "\"observedQuantity\":1.250}]}",
            codec.dispatchOutgoingGoodsObservationsRequestBody(
                allocationId = "22222222-2222-4222-8222-222222222222",
                allocationVersion = 4,
                observations = listOf(
                    DispatchOutgoingGoodsObservation(
                        physicalAllocationLineId = "11111111-1111-4111-8111-111111111111",
                        observedLotId = null,
                        observedQuantity = BigDecimal("1.250")
                    )
                )
            )
        )
    }

    @Test
    fun planChangeBodyRetainsFieldOrderAndNullableValues() {
        val assignment = PreparedFulfillmentDriverAssignment(
            id = "11111111-1111-4111-8111-111111111111",
            fulfillmentId = "22222222-2222-4222-8222-222222222222",
            fulfillmentVersion = 9,
            physicalAllocationId = "33333333-3333-4333-8333-333333333333",
            physicalAllocationVersion = 6,
            responsibleMembershipId = "44444444-4444-4444-8444-444444444444",
            responsibleDisplayName = "Driver",
            assignedAt = Instant.parse("2026-10-01T09:00:00Z"),
            deliveryId = null
        )
        val readiness = DispatchReadiness(
            subjectKind = "FULFILLMENT",
            fulfillmentId = assignment.fulfillmentId,
            fulfillmentVersion = assignment.fulfillmentVersion,
            fulfillmentStatus = "READY",
            physicalAllocationId = assignment.physicalAllocationId,
            physicalAllocationStatus = "ALLOCATED",
            physicalAllocationVersion = assignment.physicalAllocationVersion,
            deliveryId = null,
            deliveryStatus = null,
            deliveryVersion = null,
            allocationComplete = true,
            pickingComplete = true,
            pickingEvidenceComplete = true,
            ready = true,
            reasons = emptyList(),
            lines = emptyList(),
            asOf = Instant.parse("2026-10-01T09:00:00Z")
        )

        assertEquals(
            "{\"expectedAssignmentId\":\"11111111-1111-4111-8111-111111111111\"," +
                "\"expectedAssignmentVersion\":9,\"physicalAllocationId\":" +
                "\"33333333-3333-4333-8333-333333333333\",\"physicalAllocationVersion\":6," +
                "\"responsibleMembershipId\":null,\"plannedDispatchAt\":null}",
            codec.dispatchPlanChangeRequestBody(
                assignment = assignment,
                readiness = readiness,
                membershipId = null,
                dispatchAt = null
            )
        )
    }
}
