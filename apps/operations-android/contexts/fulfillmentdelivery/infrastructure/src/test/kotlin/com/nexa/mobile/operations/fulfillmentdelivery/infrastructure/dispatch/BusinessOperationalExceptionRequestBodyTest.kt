package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessOperationalExceptionRequestBodyTest {
    @Test
    fun lifecycleBodiesRequireReasonsAndFreezeOnlyContractFields() {
        assertEquals(
            "{\"reason\":\"inspect the report\"}",
            businessOperationalExceptionRequestBody(
                BusinessOperationalExceptionAction.CLAIM,
                reason = " inspect the report "
            )
        )
        assertEquals(
            "{\"reason\":\"recheck\",\"note\":\"contact warehouse\"}",
            businessOperationalExceptionRequestBody(
                BusinessOperationalExceptionAction.FOLLOW_UP,
                followUpNote = "contact warehouse",
                reason = "recheck"
            )
        )
        assertEquals(
            "{\"responsibleMembershipId\":\"$OTHER_MEMBER\",\"reason\":\"handoff to active coordinator\"}",
            businessOperationalExceptionRequestBody(
                BusinessOperationalExceptionAction.REASSIGN,
                responsibleMembershipId = OTHER_MEMBER,
                reason = "handoff to active coordinator"
            )
        )
        assertTrue(
            runCatching {
                businessOperationalExceptionRequestBody(BusinessOperationalExceptionAction.CLOSE)
            }.isFailure
        )
    }

    private companion object {
        const val OTHER_MEMBER = "00000000-0000-0000-0000-000000000014"
    }
}
