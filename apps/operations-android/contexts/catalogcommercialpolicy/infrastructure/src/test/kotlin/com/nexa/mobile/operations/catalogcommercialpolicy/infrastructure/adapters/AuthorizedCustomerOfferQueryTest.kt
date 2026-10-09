package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters

import com.nexa.mobile.operations.catalogcommercialpolicy.application.publicapi.CustomerOfferRead
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedCustomerOfferQueryTest {
    @Test
    fun quotePreservesServerPriceAndDoesNotClaimSellableAvailability() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, offerBody()))
            val query = query(server)

            val result = query.detail(CUSTOMER_ID, PRODUCT_ID, "2.500")
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals(
                "/api/v1/client-accounts/$CUSTOMER_ID/catalog-offers/$PRODUCT_ID?quantity=2.500",
                request.path
            )
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertTrue(result is CustomerOfferRead.Found)
            val offer = (result as CustomerOfferRead.Found).value
            assertEquals(PRODUCT_ID, offer.catalogItemId)
            assertEquals("North Star Olive Oil", offer.itemName)
            assertEquals("BOTTLE", offer.unitOfMeasure)
            assertEquals("12.340", offer.currentOfferPrice?.amount)
            assertEquals("PEN", offer.currentOfferPrice?.currency)
            assertEquals(AS_OF, offer.pricingAsOf)
        }
    }

    @Test
    fun mismatchedCustomerProductOrQuotedQuantityNeverBecomeAnOffer() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, offerBody(customerId = OTHER_CUSTOMER_ID)))
            server.enqueue(jsonResponse(200, offerBody(responseProductId = "CAT-OTHER")))
            server.enqueue(jsonResponse(200, offerBody(quantity = "3")))
            val query = query(server)

            repeat(3) {
                assertEquals(
                    CustomerOfferRead.Unavailable,
                    query.detail(CUSTOMER_ID, PRODUCT_ID, "2.5")
                )
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun malformedMoneyCurrencyOrAsOfShapeNeverBecomesAQuotedPrice() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, offerBody(amount = "not-money")))
            server.enqueue(jsonResponse(200, offerBody(currency = "US")))
            server.enqueue(jsonResponse(200, offerBody(asOf = "tomorrow")))
            server.enqueue(jsonResponse(200, "not-json"))
            val query = query(server)

            repeat(4) {
                assertEquals(
                    CustomerOfferRead.Unavailable,
                    query.detail(CUSTOMER_ID, PRODUCT_ID, "2.5")
                )
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun authenticationAuthorizationNotFoundAndServerErrorsNeverReturnAnOffer() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(problemResponse(403, "PERMISSION_DENIED"))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(problemResponse(500, "INTERNAL_ERROR"))
            val query = query(server)

            repeat(4) {
                assertEquals(
                    CustomerOfferRead.Unavailable,
                    query.detail(CUSTOMER_ID, PRODUCT_ID, "2.5")
                )
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun invalidCustomerProductOrQuantityDoesNotDispatchAProtectedRead() = runTest {
        MockWebServer().use { server ->
            server.start()
            val query = query(server)

            assertEquals(CustomerOfferRead.Unavailable, query.detail("not-an-id", PRODUCT_ID, "1"))
            assertEquals(
                CustomerOfferRead.Unavailable,
                query.detail(CUSTOMER_ID, "../product", "1")
            )
            assertEquals(CustomerOfferRead.Unavailable, query.detail(CUSTOMER_ID, PRODUCT_ID, "0"))
            assertEquals(0, server.requestCount)
        }
    }

    private fun query(server: MockWebServer): AuthorizedCustomerOfferQuery {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return AuthorizedCustomerOfferQuery(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), TestAccessTokenSource())
        )
    }

    private fun offerBody(
        customerId: String = CUSTOMER_ID,
        responseProductId: String = PRODUCT_ID,
        quantity: String = "2.5",
        amount: String = "12.340",
        currency: String = "PEN",
        asOf: String = AS_OF
    ): String = """
        {
          "clientAccountId":"$customerId",
          "product":{
            "catalogItemId":"$responseProductId",
            "productId":"$responseProductId",
            "itemName":"North Star Olive Oil",
            "status":"ACTIVE",
            "unitOfMeasure":"BOTTLE",
            "availabilityStatus":"OUT_OF_STOCK",
            "sellableAvailability":false,
            "availabilityAsOf":"$AS_OF"
          },
          "quantity":"$quantity",
          "unitPrice":{"amount":"$amount","currency":"$currency"},
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
        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == lease.epoch
    }

    private companion object {
        const val CUSTOMER_ID = "12345678-1234-1234-1234-123456789012"
        const val OTHER_CUSTOMER_ID = "12345678-1234-1234-1234-123456789013"
        const val PRODUCT_ID = "CAT-0001"
        const val AS_OF = "2026-10-08T10:00:00Z"
    }
}
