package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverCommand
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.FulfillmentHandoffEvidenceProjection
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchHandoverEvidenceCorrelationTest {
    @Test
    fun immediateDispatchRequiresCurrentEvidenceFromTheVerifiedMembership() {
        val evidence = evidence()

        assertTrue(evidence.correlatesToDispatch(command(), DELIVERY_ID, DISPATCH_MEMBERSHIP_ID))
        assertFalse(evidence.correlatesToDispatch(command(), DELIVERY_ID, OTHER_MEMBERSHIP_ID))
        assertFalse(
            evidence.copy(dispatchActorMembershipId = null).correlatesToDispatch(
                command(),
                DELIVERY_ID,
                DISPATCH_MEMBERSHIP_ID
            )
        )
        assertFalse(
            evidence.copy(current = false).correlatesToDispatch(
                command(),
                DELIVERY_ID,
                DISPATCH_MEMBERSHIP_ID
            )
        )
    }

    private fun evidence() = FulfillmentHandoffEvidenceProjection(
        id = EVIDENCE_ID,
        fulfillmentId = FULFILLMENT_ID,
        fulfillmentVersion = 13,
        deliveryId = DELIVERY_ID,
        warehouseActorMembershipId = null,
        dispatchActorMembershipId = DISPATCH_MEMBERSHIP_ID,
        driverAssignmentId = ASSIGNMENT_ID,
        driverMembershipId = DRIVER_MEMBERSHIP_ID,
        physicalAllocationId = ALLOCATION_ID,
        physicalAllocationVersion = 7,
        outgoingGoodsCheckId = CHECK_ID,
        occurredAt = Instant.parse("2026-09-30T10:15:30Z"),
        current = true
    )

    private fun command() = DispatchHandoverCommand(
        fulfillmentId = FULFILLMENT_ID,
        expectedFulfillmentVersion = 12,
        physicalAllocationId = ALLOCATION_ID,
        physicalAllocationVersion = 7,
        driverAssignmentId = ASSIGNMENT_ID,
        driverAssignmentVersion = 12,
        outgoingGoodsCheckId = CHECK_ID,
        idempotencyKey = "dispatch-key",
        exactRequestBody = "{}"
    )

    private companion object {
        const val FULFILLMENT_ID = "11111111-1111-4111-8111-111111111111"
        const val DELIVERY_ID = "88888888-8888-4888-8888-888888888888"
        const val EVIDENCE_ID = "99999999-9999-4999-8999-999999999999"
        const val DISPATCH_MEMBERSHIP_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val OTHER_MEMBERSHIP_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val DRIVER_MEMBERSHIP_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val ALLOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val ASSIGNMENT_ID = "33333333-3333-4333-8333-333333333333"
        const val CHECK_ID = "44444444-4444-4444-8444-444444444444"
    }
}
