package com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedCommercialCustomerQueryTest {
    @Test
    fun detailPreservesServerRelationshipFactsAndUsesTheScopedProtectedRoute() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, customerBody(status = "SUSPENDED", version = 7)))
            val query = query(server)

            val result = query.detail(CUSTOMER_ID)
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/api/v1/client-accounts/$CUSTOMER_ID", request.path)
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertTrue(result is CommercialCustomerRead.Detail)
            val facts = (result as CommercialCustomerRead.Detail).value
            assertEquals("North Star Foods", facts.businessName)
            assertEquals("SUSPENDED", facts.status)
            assertEquals(7L, facts.version)
        }
    }

    @Test
    fun mismatchedCustomerAndMalformedOrInvalidDetailsDoNotBecomeCustomerFacts() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, customerBody(id = OTHER_CUSTOMER_ID)))
            server.enqueue(jsonResponse(200, "not-json"))
            server.enqueue(jsonResponse(200, customerBody(status = "UNKNOWN")))
            server.enqueue(jsonResponse(200, customerBody(version = -1)))
            val query = query(server)

            repeat(4) {
                assertEquals(CommercialCustomerRead.Unavailable, query.detail(CUSTOMER_ID))
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun authenticationAuthorizationNotFoundAndServerErrorsNeverReturnCustomerFacts() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(problemResponse(403, "PERMISSION_DENIED"))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(problemResponse(500, "INTERNAL_ERROR"))
            val query = query(server)

            repeat(4) {
                assertEquals(CommercialCustomerRead.Unavailable, query.detail(CUSTOMER_ID))
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun invalidCustomerIdentifierDoesNotDispatchAProtectedRead() = runTest {
        MockWebServer().use { server ->
            server.start()

            assertEquals(CommercialCustomerRead.Unavailable, query(server).detail("../customer"))
            assertEquals(0, server.requestCount)
        }
    }

    private fun query(server: MockWebServer): AuthorizedCommercialCustomerQuery {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return AuthorizedCommercialCustomerQuery(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), TestAccessTokenSource())
        )
    }

    private fun customerBody(
        id: String = CUSTOMER_ID,
        status: String = "ACTIVE",
        version: Long = 4
    ): String = """
        {
          "id":"$id",
          "code":"C-2048",
          "businessName":"North Star Foods",
          "commercialName":"North Star",
          "status":"$status",
          "version":$version
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
    }
}
