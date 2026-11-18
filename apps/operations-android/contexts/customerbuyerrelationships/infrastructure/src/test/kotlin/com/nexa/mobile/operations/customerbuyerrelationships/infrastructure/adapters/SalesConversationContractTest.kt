package com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.adapters

import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesPendingMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesConversationContractTest {
    private val message = SalesPendingMessage(
        accountId = "bbd32cea-c3d8-4f89-bde5-fe9941d60c07",
        recipientMembershipId = "d604cf7f-8b83-4775-ae9b-69b7d7b8c0a4",
        body = "Hello",
        idempotencyKey = "2bef4481-9dd0-4ab1-8038-9d784577c9c9"
    )

    @Test
    fun serverConfirmationMustMatchTheStoredMessageBeforeRecoveryIsCleared() {
        val json = """{"id":"7b13ed98-77ab-4926-a150-3d61f2a5b266",""" +
            """"clientAccountId":"${message.accountId}",""" +
            """"buyerMembershipId":"${message.recipientMembershipId}",""" +
            """"senderKind":"SALES","body":"Hello"}"""

        assertTrue(salesMessageResponseMatches(json, message))
        assertFalse(salesMessageResponseMatches(json.replace("SALES", "BUYER"), message))
        assertFalse(salesMessageResponseMatches(json.replace("Hello", "Changed"), message))
        assertFalse(
            salesMessageResponseMatches(
                json.replace(message.accountId, "11111111-1111-4111-8111-111111111111"),
                message
            )
        )
        assertFalse(salesMessageResponseMatches("not-json", message))
    }
}
