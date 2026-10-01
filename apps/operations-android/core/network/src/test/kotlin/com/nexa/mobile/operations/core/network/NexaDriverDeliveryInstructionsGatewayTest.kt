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

class NexaDriverDeliveryInstructionsGatewayTest {
    @Test
    fun readParsesCurrentInstructionsAndRequiresStrongMatchingInstructionSetEtag() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"7\"")
                    .setBody(instructionsResponse())
            )

            val result = gateway(server).currentInstructions(DELIVERY_ID)
                as DriverDeliveryInstructionsNetworkOutcome.Loaded

            assertEquals(DELIVERY_ID, result.value.deliveryId)
            assertEquals(4L, result.value.deliveryVersion)
            assertEquals(7L, result.value.instructionSetVersion)
            assertEquals(2, result.value.instructions.size)
            assertEquals("COLD_CHAIN", result.value.instructions.last().kind)
            assertTrue(result.value.instructions.last().critical)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/driver/deliveries/$DELIVERY_ID/instructions", request.path)
        }
    }

    @Test
    fun acknowledgementUsesExactSelectedIdsKeyAndInstructionSetPrecondition() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody(acknowledgementResponse())
            )
            val body = """{"instructionIds":["$CRITICAL_INSTRUCTION_ID"]}"""

            val result = gateway(server).acknowledgeCriticalInstructions(
                DELIVERY_ID, 7, listOf(CRITICAL_INSTRUCTION_ID), "ack-key-1", body
            ) as DriverDeliveryInstructionsNetworkOutcome.Acknowledged

            assertEquals(MEMBERSHIP_ID, result.value.acknowledgements.single().acknowledgedByMembershipId)
            assertEquals("2026-10-01T17:30:00Z", result.value.acknowledgements.single().acknowledgedAt)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/driver/deliveries/$DELIVERY_ID/instruction-acknowledgements",
                request.path
            )
            assertEquals("\"7\"", request.getHeader("If-Match"))
            assertEquals("ack-key-1", request.getHeader("Idempotency-Key"))
            assertEquals(body, request.body.readUtf8())
        }
    }

    @Test
    fun staleAcknowledgementIsReturnedForFreshDecision() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(412)
                    .setHeader("Content-Type", "application/problem+json")
                    .setBody("""{"status":412,"code":"PRECONDITION_FAILED"}""")
            )
            val result = gateway(server).acknowledgeCriticalInstructions(
                DELIVERY_ID, 7, listOf(CRITICAL_INSTRUCTION_ID), "ack-key-1",
                """{"instructionIds":["$CRITICAL_INSTRUCTION_ID"]}"""
            )
            assertEquals(DriverDeliveryInstructionsNetworkOutcome.StaleVersion, result)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun weakEtagAndMutatedFrozenBodyNeverProduceAnUnboundCommand() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "W/\"7\"")
                    .setBody(instructionsResponse())
            )
            val read = gateway(server).currentInstructions(DELIVERY_ID)
            val invalidAck = gateway(server).acknowledgeCriticalInstructions(
                DELIVERY_ID, 7, listOf(CRITICAL_INSTRUCTION_ID), "ack-key-1", "{}"
            )
            assertEquals(DriverDeliveryInstructionsNetworkOutcome.ServiceUnavailable, read)
            assertEquals(DriverDeliveryInstructionsNetworkOutcome.ServiceUnavailable, invalidAck)
            assertEquals(1, server.requestCount)
        }
    }

    private fun gateway(server: MockWebServer) = NexaDriverDeliveryInstructionsGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeTokens()
        )
    )

    private fun instructionsResponse() =
        """{"deliveryId":"$DELIVERY_ID","deliveryVersion":4,"instructionSetVersion":7,"instructions":[{"id":"$NORMAL_INSTRUCTION_ID","kind":"NORMAL","content":"Use the front entrance","instructionVersion":2,"critical":false,"acknowledged":false},{"id":"$CRITICAL_INSTRUCTION_ID","kind":"COLD_CHAIN","content":"Keep chilled goods below 5 C","instructionVersion":3,"critical":true,"acknowledged":false}]}"""

    private fun acknowledgementResponse() =
        """{"deliveryId":"$DELIVERY_ID","instructionSetVersion":7,"acknowledgements":[{"instructionId":"$CRITICAL_INSTRUCTION_ID","instructionVersion":3,"acknowledgedByMembershipId":"$MEMBERSHIP_ID","acknowledgedAt":"2026-10-01T17:30:00Z"}],"replayed":false}"""

    private class FakeTokens : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("session-1", generation = 1, epoch = 1)
        override suspend fun currentAccess(): AccessTokenLease = lease
        override suspend fun isEpochCurrent(epoch: Long): Boolean = lease.epoch == epoch
        override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
    }

    private companion object {
        const val DELIVERY_ID = "22222222-2222-4222-8222-222222222222"
        const val NORMAL_INSTRUCTION_ID = "33333333-3333-4333-8333-333333333333"
        const val CRITICAL_INSTRUCTION_ID = "44444444-4444-4444-8444-444444444444"
        const val MEMBERSHIP_ID = "55555555-5555-4555-8555-555555555555"
    }
}
