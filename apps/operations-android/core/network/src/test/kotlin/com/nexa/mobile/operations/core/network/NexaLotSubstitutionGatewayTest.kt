package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaLotSubstitutionGatewayTest {
    @Test
    fun sendsLiteralFrozenRequestWithCurrentAllocationVersionAndStableKey() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(requestResponse()))
            val gateway = gateway(server)
            val body = frozenBody()

            val result = gateway.request(command(body))
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("/api/v1/inventory/physical-allocation-substitution-requests", request.requestUrl?.encodedPath)
            assertEquals(body, request.body.readUtf8())
            assertEquals(KEY, request.getHeader("Idempotency-Key"))
            assertEquals("\"8\"", request.getHeader("If-Match"))
            assertTrue(result is LotSubstitutionNetworkOutcome.Requested)
        }
    }

    @Test
    fun changedFrozenBodyDoesNotDispatchAndStaleVersionStaysExplicit() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(412)
                    .addHeader("Content-Type", "application/problem+json")
                    .setBody("""{"status":412,"code":"CONCURRENCY_CONFLICT","category":"CONFLICT"}""")
            )
            val gateway = gateway(server)

            assertEquals(
                LotSubstitutionNetworkOutcome.Rejected("INVALID_REQUEST"),
                gateway.request(command("{}"))
            )
            assertEquals(0, server.requestCount)
            assertEquals(LotSubstitutionNetworkOutcome.Stale(), gateway.request(command(frozenBody())))
            assertEquals(1, server.requestCount)
        }
    }

    private fun gateway(server: MockWebServer): NexaLotSubstitutionGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaLotSubstitutionGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), FakeAccessTokenSource())
        )
    }

    private fun command(body: String) = LotSubstitutionCommandNetwork(
        KEY, 8, FULFILLMENT_ID, ALLOCATION_ID, LINE_ID, EXPECTED_LOT_ID, ALTERNATIVE_LOT_ID,
        "4.000", "EA", "Expected lot could not supply prepared work", body
    )

    private fun frozenBody() = """{"fulfillmentId":"$FULFILLMENT_ID","allocationId":"$ALLOCATION_ID","physicalAllocationLineId":"$LINE_ID","expectedLotId":"$EXPECTED_LOT_ID","alternativeLotId":"$ALTERNATIVE_LOT_ID","quantity":4.000,"unit":"EA","reason":"Expected lot could not supply prepared work"}"""

    private fun requestResponse() = """{"id":"$REQUEST_ID","expectedLotId":"$EXPECTED_LOT_ID","alternativeLotId":"$ALTERNATIVE_LOT_ID","quantity":4.000,"reason":"Expected lot could not supply prepared work","status":"REQUESTED","currentAllocationVersion":8}"""

    private fun response(body: String) = MockResponse()
        .setResponseCode(201)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private class FakeAccessTokenSource : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("access-1", generation = 1, epoch = 1)
        override suspend fun currentAccess(): AccessTokenLease = lease
        override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }

    private companion object {
        const val KEY = "00000000-0000-4000-8000-000000000001"
        const val FULFILLMENT_ID = "00000000-0000-4000-8000-000000000002"
        const val ALLOCATION_ID = "00000000-0000-4000-8000-000000000003"
        const val LINE_ID = "00000000-0000-4000-8000-000000000004"
        const val EXPECTED_LOT_ID = "00000000-0000-4000-8000-000000000005"
        const val ALTERNATIVE_LOT_ID = "00000000-0000-4000-8000-000000000006"
        const val REQUEST_ID = "00000000-0000-4000-8000-000000000007"
    }
}
