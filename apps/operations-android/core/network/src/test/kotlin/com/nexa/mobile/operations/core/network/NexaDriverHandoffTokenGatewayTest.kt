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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaDriverHandoffTokenGatewayTest {
    @Test
    fun issueUsesFrozenAttemptBodyAndReplayDoesNotExposeToken() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(response(token = "\"683291\"")))
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(response(token = "null")))
            val gateway = gateway(server)
            val body = """{"attemptId":"$ATTEMPT_ID"}"""

            val first = gateway.issue(DELIVERY_ID, ATTEMPT_ID, KEY, body) as DriverHandoffTokenNetworkOutcome.Issued
            val replay = gateway.issue(DELIVERY_ID, ATTEMPT_ID, KEY, body) as DriverHandoffTokenNetworkOutcome.Issued

            assertEquals("683291", first.value.token)
            assertNull(replay.value.token)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/v1/deliveries/$DELIVERY_ID/handoff-tokens", request.path)
                assertEquals(KEY, request.getHeader("Idempotency-Key"))
                assertEquals(body, request.body.readUtf8())
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun rejectsDifferentBodyBeforeNetwork() = runTest {
        MockWebServer().use { server ->
            server.start()
            val result = gateway(server).issue(DELIVERY_ID, ATTEMPT_ID, KEY, "{}")
            assertEquals(DriverHandoffTokenNetworkOutcome.Unavailable, result)
            assertEquals(0, server.requestCount)
            assertTrue(server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) == null)
        }
    }

    private fun gateway(server: MockWebServer) = NexaDriverHandoffTokenGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeTokens()
        )
    )

    private fun response(token: String) =
        """{"handoffId":"$HANDOFF_ID","deliveryId":"$DELIVERY_ID","attemptId":"$ATTEMPT_ID","expiresAt":"2099-10-01T17:00:00Z","status":"ACTIVE","token":$token}"""

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
        const val ATTEMPT_ID = "33333333-3333-4333-8333-333333333333"
        const val HANDOFF_ID = "44444444-4444-4444-8444-444444444444"
        const val KEY = "handoff-command-key"
    }
}
