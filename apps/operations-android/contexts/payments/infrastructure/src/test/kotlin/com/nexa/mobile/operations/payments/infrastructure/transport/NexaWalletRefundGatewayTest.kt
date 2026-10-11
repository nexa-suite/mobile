package com.nexa.mobile.operations.payments.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.payments.application.refund.WalletRefundAuthority
import com.nexa.mobile.operations.payments.application.refund.WalletRefundCandidatesRead
import com.nexa.mobile.operations.payments.application.refund.WalletRefundSubmission
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaWalletRefundGatewayTest {
    @Test
    fun candidatePagePreservesServerDecimalsAndUsesExactPageContract() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                response(
                    """{"items":[{"salesOrderId":"$ORDER_ID","orderNumber":"SO-42","currency":"PEN","consumedPEN":40.0000,"refundedPEN":1.2500,"refundablePEN":12.3456}],"page":0,"size":25,"total":1}"""
                )
            )

            val result = gateway(server).candidates(authority, 0, 25)
            assertTrue(result is WalletRefundCandidatesRead.Available)
            val candidate = (result as WalletRefundCandidatesRead.Available).page.items.single()
            assertEquals("40.0000", candidate.consumedPEN.toPlainString())
            assertEquals("1.2500", candidate.refundedPEN.toPlainString())
            assertEquals("12.3456", candidate.refundablePEN.toPlainString())

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(
                "/api/v1/sales-orders/wallet-refund-candidates?page=0&size=25",
                request.path
            )
            assertEquals("Bearer access-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun postedCommandSendsFrozenUnquotedDecimalAndStableIdempotencyKey() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                response(
                    """{"salesOrderId":"$ORDER_ID","refundId":"$REFUND_ID","ledgerEntryId":"$LEDGER_ID","amountPEN":12.3456,"currency":"PEN","status":"POSTED"}"""
                )
            )
            val transport = gateway(server)
            val reason = "Cliente pidió \"revisión\"\nantes de devolver"
            val intent = transport.freezeIntent(ORDER_ID, "12.3456", reason)

            val result = transport.submit(authority, intent)
            assertTrue(result is WalletRefundSubmission.Posted)
            val receipt = (result as WalletRefundSubmission.Posted).receipt
            assertEquals("12.3456", receipt.amountPEN.toPlainString())
            assertEquals("POSTED", receipt.status)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/sales-orders/$ORDER_ID/wallet-refunds", request.path)
            assertEquals(intent.idempotencyKey, request.getHeader("Idempotency-Key"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val amount = body.getValue("amountPEN").jsonPrimitive
            assertFalse(amount.isString)
            assertEquals("12.3456", amount.content)
            assertEquals(
                reason,
                body.getValue("reason").jsonPrimitive.content
            )
            assertEquals("Bearer access-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun stringMoneyAndMismatchedPostedAmountFailClosed() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                response(
                    """{"items":[{"salesOrderId":"$ORDER_ID","orderNumber":"SO-42","currency":"PEN","consumedPEN":"40.00","refundedPEN":0,"refundablePEN":12.00}],"page":0,"size":25,"total":1}"""
                )
            )
            val candidatePage = gateway(server).candidates(authority, 0, 25)
            assertEquals(WalletRefundCandidatesRead.ProtocolFailure, candidatePage)
            server.takeRequest()

            server.enqueue(
                response(
                    """{"salesOrderId":"$ORDER_ID","refundId":"$REFUND_ID","ledgerEntryId":"$LEDGER_ID","amountPEN":12.30,"currency":"PEN","status":"POSTED"}"""
                )
            )
            val transport = gateway(server)
            val intent = transport.freezeIntent(ORDER_ID, "12.3456", "Motivo")
            assertEquals(WalletRefundSubmission.UnknownOutcome, transport.submit(authority, intent))
        }
    }

    @Test
    fun serverFailureAfterMutationRemainsUnknownOutcome() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))

            val transport = gateway(server)
            val intent = transport.freezeIntent(ORDER_ID, "12.3456", "Motivo")
            val result = transport.submit(authority, intent)

            assertEquals(WalletRefundSubmission.UnknownOutcome, result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(intent.idempotencyKey, request.getHeader("Idempotency-Key"))
        }
    }

    private fun gateway(server: MockWebServer): NexaWalletRefundGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaWalletRefundGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), TestTokens())
        )
    }

    private fun response(body: String) = MockResponse()
        .setBody(body)
        .addHeader("Content-Type", "application/json")

    private class TestTokens : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)

        override suspend fun currentAccess() = AccessTokenLease("access-token", 1, 4)

        override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease) = null

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit

        override suspend fun isEpochCurrent(epoch: Long) = epoch == 4L
    }

    private companion object {
        const val ORDER_ID = "1f7820ad-e752-469c-8e79-f1f0c32baead"
        const val REFUND_ID = "d48c9a7b-954e-4356-992d-bc6222ddfd4f"
        const val LEDGER_ID = "f4f8ca2c-9c1b-4429-92ed-5040d6292ced"
        val authority = WalletRefundAuthority(
            VerifiedOperationsIdentity(
                "user",
                "tenant",
                "workspace",
                "membership",
                setOf("payment.reconcile"),
                setOf("COMPANY_OWNER")
            ),
            authorityEpoch = 1
        )
    }
}
