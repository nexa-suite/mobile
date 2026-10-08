package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

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

class NexaOperationalDeliveryInstructionsGatewayTest {
    @Test
    fun readsDispatchInstructionSetUsingDeliveryVersionEtag() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"9\"")
                    .setBody(instructionsResponse())
            )

            val result = gateway(server).currentInstructions(DELIVERY_ID)
                as OperationalDeliveryInstructionsNetworkResult.Current

            assertEquals(DELIVERY_ID, result.value.deliveryId)
            assertEquals(9L, result.value.deliveryVersion)
            assertEquals(4L, result.value.instructionSetVersion)
            assertEquals("BUYER", result.value.instructions.first().sourceKind)
            assertEquals("OPERATIONAL_DISPATCH", result.value.instructions.last().sourceKind)
            assertTrue(result.value.instructions.last().critical)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/deliveries/$DELIVERY_ID/instructions", request.path)
        }
    }

    @Test
    fun publishUsesFrozenBodyDeliveryIfMatchAndIdempotencyKey() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"10\"")
                    .setBody(publishedResponse())
            )
            val body = listOf(
                """{"instructionId":"$INSTRUCTION_ID",""",
                """"kind":"COLD_CHAIN","content":"Keep chilled"}"""
            ).joinToString(separator = "")

            val result = gateway(server).publish(DELIVERY_ID, 9, "dispatch-instruction-key", body)
                as OperationalDeliveryInstructionsNetworkResult.Published

            assertEquals(INSTRUCTION_ID, result.value.instructionId)
            assertEquals(10L, result.value.deliveryVersion)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/deliveries/$DELIVERY_ID/instructions", request.path)
            assertEquals("\"9\"", request.getHeader("If-Match"))
            assertEquals("dispatch-instruction-key", request.getHeader("Idempotency-Key"))
            assertEquals(body, request.body.readUtf8())
        }
    }

    @Test
    fun staleVersionIsDefiniteAndInvalidFrozenBodyIsNeverSent() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(412)
                    .setHeader("Content-Type", "application/problem+json")
                    .setBody("""{"status":412,"code":"PRECONDITION_FAILED"}""")
            )
            val stale = gateway(server).publish(
                DELIVERY_ID,
                9,
                "dispatch-instruction-key",
                """{"kind":"NORMAL","content":"Use the front entrance"}"""
            )
            val invalid = gateway(server).publish(DELIVERY_ID, 9, "another-key", "{}")
            assertEquals(OperationalDeliveryInstructionsNetworkResult.StaleVersion, stale)
            assertEquals(OperationalDeliveryInstructionsNetworkResult.ServiceUnavailable, invalid)
            assertEquals(1, server.requestCount)
        }
    }

    private fun gateway(server: MockWebServer) = NexaOperationalDeliveryInstructionsGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeTokens()
        )
    )

    private fun instructionsResponse() =
        """{"deliveryId":"$DELIVERY_ID","deliveryVersion":9,"instructionSetVersion":4,"instructions":[{"id":"$BUYER_INSTRUCTION_ID","kind":"CUSTOMER_SAFETY","content":"Use the rear entrance","instructionVersion":1,"critical":true,"acknowledged":false,"acknowledgedAt":null,"acknowledgedByMembershipId":null,"sourceKind":"BUYER","recordedByMembershipId":"$MEMBERSHIP_ID","recordedAt":"2026-10-01T17:30:00Z"},{"id":"$INSTRUCTION_ID","kind":"COLD_CHAIN","content":"Keep chilled","instructionVersion":2,"critical":true,"acknowledged":true,"acknowledgedAt":"2026-10-01T17:31:00Z","acknowledgedByMembershipId":"$MEMBERSHIP_ID","sourceKind":"OPERATIONAL_DISPATCH","recordedByMembershipId":"$MEMBERSHIP_ID","recordedAt":"2026-10-01T17:30:00Z"}]}"""

    private fun publishedResponse() =
        """{"deliveryId":"$DELIVERY_ID","instructionId":"$INSTRUCTION_ID","kind":"COLD_CHAIN","content":"Keep chilled","instructionVersion":3,"critical":true,"deliveryVersion":10,"instructionSetVersion":5,"replayed":false}"""

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
        const val DELIVERY_ID = "22222222-2222-4222-8222-222222222222"
        const val INSTRUCTION_ID = "33333333-3333-4333-8333-333333333333"
        const val BUYER_INSTRUCTION_ID = "44444444-4444-4444-8444-444444444444"
        const val MEMBERSHIP_ID = "55555555-5555-4555-8555-555555555555"
    }
}
