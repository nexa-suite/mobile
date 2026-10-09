package com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.transport

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
import org.junit.Test

class NexaCustomerGatewayTest {
    @Test fun searchUsesProtectedServerFilterAndPreservesSuspendedRelationship() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setHeader(
                    "Content-Type",
                    "application/json"
                ).setBody(
                    """{"items":[{"id":"12345678-1234-1234-1234-123456789012","code":"C1","businessName":"Customer","status":"SUSPENDED","version":2}],"page":0,"size":25,"total":1}"""
                )
            )
            val endpoint = ApiEndpoint(server.url("/").toString())
            val gateway =
                NexaCustomerGateway(
                    ProtectedCallExecutor(
                        endpoint,
                        ApiHttpClient.create(endpoint),
                        FakeAccessTokenSource()
                    )
                )
            val result = gateway.search("Name & code", 0) as CustomerNetworkResult.Page
            val request = server.takeRequest()
            assertEquals("Name & code", request.requestUrl?.queryParameter("search"))
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertEquals("SUSPENDED", result.value.items.single().status)
            assertEquals(CustomerNetworkResult.Unavailable, gateway.detail("invalid/route"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun distinguishesInvalidAccessContextFromAnOrdinaryAuthorizationDenial() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))
            server.enqueue(problemResponse(403, "PERMISSION_DENIED"))
            val endpoint = ApiEndpoint(server.url("/").toString())
            val gateway = NexaCustomerGateway(
                ProtectedCallExecutor(
                    endpoint,
                    ApiHttpClient.create(endpoint),
                    FakeAccessTokenSource()
                )
            )

            assertEquals(CustomerNetworkResult.ContextInvalidated, gateway.search("A", 0))
            assertEquals(CustomerNetworkResult.PermissionDenied, gateway.search("B", 0))
            assertEquals(2, server.requestCount)
        }
    }

    private fun problemResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody("""{"status":$status,"code":"$code","category":"CLIENT_ERROR"}""")

    private class FakeAccessTokenSource : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("access-1", generation = 1, epoch = 1)

        override suspend fun currentAccess(): AccessTokenLease = lease
        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }
}
