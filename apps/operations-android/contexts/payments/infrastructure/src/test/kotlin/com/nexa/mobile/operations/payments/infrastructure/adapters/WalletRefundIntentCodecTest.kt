package com.nexa.mobile.operations.payments.infrastructure.adapters

import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntent
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletRefundIntentCodecTest {
    @Test
    fun codecRetainsExactIntentAndRejectsTamperedPayloadHash() {
        val intent = intent()
        val encoded = WalletRefundIntentCodec.encode(listOf(intent))

        assertEquals(listOf(intent), WalletRefundIntentCodec.decode(encoded))
        assertFalse(intent.toString().contains(intent.reason))
        assertNull(
            WalletRefundIntentCodec.decode(encoded.replace(intent.payloadHash, "0".repeat(64)))
        )
    }

    @Test
    fun codecRejectsDuplicateOrderRecords() {
        val intent = intent()

        assertNull(
            WalletRefundIntentCodec.decode(WalletRefundIntentCodec.encode(listOf(intent, intent)))
        )
    }

    private fun intent(): WalletRefundIntent {
        val payload = """{"amountPEN":12.3456,"reason":"Private test reason"}"""
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return WalletRefundIntent(
            salesOrderId = "1f7820ad-e752-469c-8e79-f1f0c32baead",
            amountPEN = "12.3456",
            reason = "Private test reason",
            idempotencyKey = "1ef849dd-4578-4b8b-a62e-54d3dfbd2cb1",
            payloadHash = hash,
            exactPayload = payload
        )
    }
}
