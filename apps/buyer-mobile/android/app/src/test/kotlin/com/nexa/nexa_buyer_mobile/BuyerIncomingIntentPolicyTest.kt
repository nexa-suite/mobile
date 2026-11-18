package com.nexa.nexa_buyer_mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuyerIncomingIntentPolicyTest {
    @Test
    fun mainWakeUpKeepsOnlyBoundedFirebaseMessageIdStrings() {
        val route = BuyerIncomingIntentPolicy.sanitize(
            action = BuyerIncomingIntentPolicy.ACTION_MAIN,
            uri = null,
            googleMessageId = "google-id:1",
            messageId = "legacy-id-2",
        ) as BuyerIncomingRoute.Main

        assertEquals(
            mapOf(
                BuyerIncomingIntentPolicy.FCM_GOOGLE_MESSAGE_ID to "google-id:1",
                BuyerIncomingIntentPolicy.FCM_MESSAGE_ID to "legacy-id-2",
            ),
            route.fcmMessageIds,
        )
    }

    @Test
    fun mainWakeUpDropsWrongTypesBlankWhitespaceAndOversizedIds() {
        val route = BuyerIncomingIntentPolicy.sanitize(
            action = BuyerIncomingIntentPolicy.ACTION_MAIN,
            uri = null,
            googleMessageId = String(CharArray(257) { 'x' }),
            messageId = StringBuilder("not-a-string-id"),
        ) as BuyerIncomingRoute.Main

        assertTrue(route.fcmMessageIds.isEmpty())
        assertEquals(
            setOf(BuyerIncomingIntentPolicy.FCM_GOOGLE_MESSAGE_ID),
            (BuyerIncomingIntentPolicy.sanitize(
                action = BuyerIncomingIntentPolicy.ACTION_MAIN,
                uri = null,
                googleMessageId = String(CharArray(256) { 'x' }),
                messageId = "id with spaces",
            ) as BuyerIncomingRoute.Main).fcmMessageIds.keys,
        )
        listOf("", " ", "\n", "id\u0000tail").forEach { rejectedId ->
            val rejectedRoute = BuyerIncomingIntentPolicy.sanitize(
                action = BuyerIncomingIntentPolicy.ACTION_MAIN,
                uri = null,
                googleMessageId = rejectedId,
                messageId = null,
            ) as BuyerIncomingRoute.Main
            assertTrue(rejectedRoute.fcmMessageIds.isEmpty())
        }
    }

    @Test
    fun launcherWakeUpRejectsUrisAndOtherActions() {
        val stripeUri = stripeUri()

        assertNull(
            BuyerIncomingIntentPolicy.sanitize(
                BuyerIncomingIntentPolicy.ACTION_MAIN,
                stripeUri,
                "message-id",
                null,
            ),
        )
        assertNull(
            BuyerIncomingIntentPolicy.sanitize(
                "com.example.UNTRUSTED",
                null,
                "message-id",
                null,
            ),
        )
        assertNull(
            BuyerIncomingIntentPolicy.sanitize(
                BuyerIncomingIntentPolicy.ACTION_VIEW,
                null,
                "message-id",
                null,
            ),
        )
    }

    @Test
    fun stripeReturnRemainsStrictAndDropsMessageIds() {
        val route = BuyerIncomingIntentPolicy.sanitize(
            action = BuyerIncomingIntentPolicy.ACTION_VIEW,
            uri = stripeUri(),
            googleMessageId = "unrelated-id",
            messageId = null,
        )

        assertEquals(BuyerIncomingRoute.StripeReturn, route)
        assertNull(
            BuyerIncomingIntentPolicy.sanitize(
                action = BuyerIncomingIntentPolicy.ACTION_VIEW,
                uri = stripeUri().copy(fragment = "extra"),
                googleMessageId = null,
                messageId = null,
            ),
        )
        assertNull(
            BuyerIncomingIntentPolicy.sanitize(
                action = BuyerIncomingIntentPolicy.ACTION_VIEW,
                uri = stripeUri().copy(encodedPath = "/extra"),
                googleMessageId = null,
                messageId = null,
            ),
        )
        assertNull(
            BuyerIncomingIntentPolicy.sanitize(
                action = BuyerIncomingIntentPolicy.ACTION_VIEW,
                uri = stripeUri().copy(host = "attacker.example"),
                googleMessageId = null,
                messageId = null,
            ),
        )
        assertNull(
            BuyerIncomingIntentPolicy.sanitize(
                action = BuyerIncomingIntentPolicy.ACTION_VIEW,
                uri = stripeUri().copy(userInfo = "attacker"),
                googleMessageId = null,
                messageId = null,
            ),
        )
        assertNull(
            BuyerIncomingIntentPolicy.sanitize(
                action = BuyerIncomingIntentPolicy.ACTION_VIEW,
                uri = stripeUri().copy(port = 443),
                googleMessageId = null,
                messageId = null,
            ),
        )
        assertNull(
            BuyerIncomingIntentPolicy.sanitize(
                action = BuyerIncomingIntentPolicy.ACTION_VIEW,
                uri = stripeUri().copy(scheme = "https"),
                googleMessageId = null,
                messageId = null,
            ),
        )
    }

    private fun stripeUri() = BuyerUriParts(
        scheme = "nexa",
        host = "stripe-redirect",
        encodedPath = null,
        fragment = null,
        userInfo = null,
        port = -1,
    )
}
