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

class NexaCustomerDeliveryInstructionsGatewayTest {
    @Test fun strongVersionAndServerSourceRemainBoundToOrder() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("ETag", "\"3\"").setBody(response())
            )
            val result = gateway(
                server
            ).read(ORDER) as CustomerDeliveryInstructionsNetworkResult.Current
            assertEquals("BUYER", result.snapshot.instructions.single().sourceKind)
            assertTrue(!result.snapshot.editable)
            assertEquals(
                "/api/v1/sales-orders/$ORDER/customer-delivery-instructions",
                server.takeRequest().path
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("ETag", "W/\"3\"").setBody(response())
            )
            assertTrue(
                gateway(server).read(ORDER) is CustomerDeliveryInstructionsNetworkResult.Failed
            )
        }
    }

    @Test fun mutationUsesFrozenBodyVersionAndKeyWithoutAutomaticNetworkRetry() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val body = NexaCustomerDeliveryInstructionsGateway.body(
                INSTRUCTION,
                "COLD_CHAIN",
                "Keep chilled",
                "Customer call"
            )
            val result = gateway(
                server
            ).publish(
                ORDER,
                3,
                "same-key",
                body
            ) as CustomerDeliveryInstructionsNetworkResult.Failed
            assertTrue(result.unknownOutcome)
            val request = server.takeRequest()
            assertEquals("\"3\"", request.getHeader("If-Match"))
            assertEquals("same-key", request.getHeader("Idempotency-Key"))
            assertEquals(body, request.body.readUtf8())
            assertEquals(1, server.requestCount)
        }
    }
    private fun gateway(server: MockWebServer) = NexaCustomerDeliveryInstructionsGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeTokens()
        )
    )
    private fun response() =
        """{"salesOrderId":"$ORDER","version":3,"editable":false,"instructions":[{"id":"$INSTRUCTION","instructionVersion":1,"kind":"COLD_CHAIN","content":"Keep chilled","sourceKind":"BUYER","sourceReference":null,"recordedByMembershipId":"$MEMBER","recordedAt":"2026-10-01T17:30:00Z"}]}"""
    private class FakeTokens : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("session-1", generation = 1, epoch = 1)
        override suspend fun currentAccess(): AccessTokenLease = lease
        override suspend fun isEpochCurrent(epoch: Long): Boolean = lease.epoch == epoch
        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
    }

    private companion object {
        const val ORDER = "22222222-2222-4222-8222-222222222222"
        const val INSTRUCTION = "33333333-3333-4333-8333-333333333333"
        const val MEMBER = "55555555-5555-4555-8555-555555555555"
    }
}
