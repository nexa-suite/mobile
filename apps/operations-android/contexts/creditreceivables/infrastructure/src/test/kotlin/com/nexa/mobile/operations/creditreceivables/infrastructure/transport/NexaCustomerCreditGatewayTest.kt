package com.nexa.mobile.operations.creditreceivables.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.creditreceivables.application.publicapi.CustomerCreditExposureRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaCustomerCreditGatewayTest {
    @Test
    fun validScopedReadPreservesServerCreditFactsWithoutRecalculatingThem() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, creditBody()))
            val query = gateway(server)

            val result = query.credit(CUSTOMER_ID, "PEN")
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals(
                "/api/v1/client-accounts/$CUSTOMER_ID/credit-exposure?currency=PEN",
                request.path
            )
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertTrue(result is CustomerCreditExposureRead.Available)
            val credit = (result as CustomerCreditExposureRead.Available).value
            assertEquals("PEN", credit.currency)
            assertEquals("1000.00", credit.limit)
            assertEquals("100.00", credit.ledgerExposure)
            assertEquals("50.00", credit.outstanding)
            assertEquals("25.00", credit.reserved)
            assertEquals("175.00", credit.used)
            assertEquals("825.00", credit.available)
            assertTrue(credit.active)
            assertEquals(AS_OF, credit.asOf)
        }
    }

    @Test
    fun mismatchedIdentityCurrencyAndMalformedMoneyOrAsOfNeverReturnAvailableCredit() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, creditBody(customerId = OTHER_CUSTOMER_ID)))
            server.enqueue(jsonResponse(200, creditBody(currency = "USD")))
            server.enqueue(jsonResponse(200, creditBody(creditLimit = "not-money")))
            server.enqueue(jsonResponse(200, creditBody(asOf = "yesterday")))
            server.enqueue(jsonResponse(200, "not-json"))
            val query = gateway(server)

            repeat(5) {
                val result = query.credit(CUSTOMER_ID, "PEN")
                assertEquals(CustomerCreditExposureRead.Unavailable, result)
                assertFalse(result is CustomerCreditExposureRead.Available)
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test
    fun authorizationAndNotFoundResponsesDoNotExposeCreditFacts() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(problemResponse(403, "PERMISSION_DENIED"))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(problemResponse(500, "INTERNAL_ERROR"))
            val query = gateway(server)

            assertEquals(CustomerCreditExposureRead.PermissionDenied, query.credit(CUSTOMER_ID, "PEN"))
            assertEquals(CustomerCreditExposureRead.PermissionDenied, query.credit(CUSTOMER_ID, "PEN"))
            assertEquals(CustomerCreditExposureRead.PermissionDenied, query.credit(CUSTOMER_ID, "PEN"))
            assertEquals(CustomerCreditExposureRead.Unavailable, query.credit(CUSTOMER_ID, "PEN"))
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun invalidCustomerOrCurrencyDoesNotDispatchAProtectedRead() = runTest {
        MockWebServer().use { server ->
            server.start()
            val query = gateway(server)

            assertEquals(
                CustomerCreditExposureRead.Unavailable,
                query.credit("not-a-customer-id", "PEN")
            )
            assertEquals(CustomerCreditExposureRead.Unavailable, query.credit(CUSTOMER_ID, "pen"))
            assertEquals(0, server.requestCount)
        }
    }

    private fun gateway(server: MockWebServer): NexaCustomerCreditGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaCustomerCreditGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), TestAccessTokenSource())
        )
    }

    private fun creditBody(
        customerId: String = CUSTOMER_ID,
        currency: String = "PEN",
        creditLimit: String = "1000.00",
        asOf: String = AS_OF
    ): String = """
        {
          "clientAccountId":"$customerId",
          "currency":"$currency",
          "creditLimit":"$creditLimit",
          "ledgerExposure":"100.00",
          "outstandingReceivables":"50.00",
          "reservedExposure":"25.00",
          "used":"175.00",
          "availableCredit":"825.00",
          "active":true,
          "asOf":"$asOf"
        }
    """.trimIndent()

    private fun jsonResponse(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun problemResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody("""{"status":$status,"code":"$code","category":"CLIENT_ERROR"}""")

    private class TestAccessTokenSource : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("access-1", generation = 1, epoch = 1)

        override suspend fun currentAccess(): AccessTokenLease = lease
        override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == lease.epoch
    }

    private companion object {
        const val CUSTOMER_ID = "12345678-1234-1234-1234-123456789012"
        const val OTHER_CUSTOMER_ID = "12345678-1234-1234-1234-123456789013"
        const val AS_OF = "2026-10-08T10:00:00Z"
    }
}
