package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class NexaDispatchTransitStartGatewayTest {
    @Test
    fun startTransitUsesProtectedVersionedIdempotentEndpointAndConfirmsResponse() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(18, "IN_TRANSIT"))

            val result = gateway(server).startTransit(command(), replayUnknown = false)
                as DispatchTransitStartNetworkOutcome.Started
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/deliveries/$DELIVERY_ID/transit-starts",
                request.requestUrl?.encodedPath
            )
            assertEquals("", request.body.readUtf8())
            assertEquals("\"17\"", request.getHeader("If-Match"))
            assertEquals("stable-transit-key", request.getHeader("Idempotency-Key"))
            assertEquals("IN_TRANSIT", result.value.deliveryStatus)
            assertEquals(18L, result.value.deliveryVersion)
            assertEquals("\"18\"", result.value.deliveryEtag)
        }
    }

    @Test
    fun unknownReplayAcceptsTheCurrentServerVersionAfterLaterTransitions() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(20, "DELIVERED"))

            val result = gateway(server).startTransit(command(), replayUnknown = true)
                as DispatchTransitStartNetworkOutcome.Started
            val request = server.takeRequest()

            assertEquals("\"17\"", request.getHeader("If-Match"))
            assertEquals("stable-transit-key", request.getHeader("Idempotency-Key"))
            assertEquals("DELIVERED", result.value.deliveryStatus)
            assertEquals(20L, result.value.deliveryVersion)
        }
    }

    @Test
    fun initialStartRequiresInTransitStatusMatchingFulfillmentAndVersionEtag() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(18, "DELIVERED"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("ETag", "\"18\"")
                    .setBody(
                        """{"id":"$DELIVERY_ID","fulfillmentId":"$OTHER_FULFILLMENT_ID","status":"IN_TRANSIT","version":18}"""
                    )
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("ETag", "\"19\"")
                    .setBody(
                        """{"id":"$DELIVERY_ID","fulfillmentId":"$FULFILLMENT_ID","status":"IN_TRANSIT","version":18}"""
                    )
            )

            repeat(3) {
                assertEquals(
                    DispatchTransitStartNetworkOutcome.UnknownOutcome,
                    gateway(server).startTransit(command(), replayUnknown = false)
                )
                server.takeRequest()
            }
        }
    }

    @Test
    fun nonReplayRequiresExactlyOneVersionAdvanceAndMalformedConfirmationStaysUnknown() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(19, "IN_TRANSIT"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("ETag", "\"18\"")
                    .setBody(
                        """{"id":"$DELIVERY_ID","fulfillmentId":"$FULFILLMENT_ID","status":"DISPATCHED","version":18}"""
                    )
            )

            val advanced = gateway(server).startTransit(command(), replayUnknown = false)
            val malformed = gateway(server).startTransit(command(), replayUnknown = false)

            assertEquals(DispatchTransitStartNetworkOutcome.UnknownOutcome, advanced)
            assertEquals(DispatchTransitStartNetworkOutcome.UnknownOutcome, malformed)
        }
    }

    @Test
    fun preconditionFailureIsNotTreatedAsAnUnknownMutation() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(412)
                    .addHeader("Content-Type", "application/problem+json")
                    .setBody("{\"title\":\"Stale delivery\",\"status\":412}")
            )

            val result = gateway(server).startTransit(command(), replayUnknown = false)

            assertEquals(DispatchTransitStartNetworkOutcome.Stale, result)
        }
    }

    @Test
    fun serverFailureAfterPostRemainsUnknownForSafeReplay() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .addHeader("Content-Type", "application/problem+json")
                    .setBody("{\"title\":\"Temporary failure\",\"status\":500}")
            )

            val result = gateway(server).startTransit(command(), replayUnknown = false)

            assertEquals(DispatchTransitStartNetworkOutcome.UnknownOutcome, result)
        }
    }

    @Test
    fun authorizationDenialIsDefinitive() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .addHeader("Content-Type", "application/problem+json")
                    .setBody("{\"title\":\"Forbidden\",\"status\":403}")
            )

            val result = gateway(server).startTransit(command(), replayUnknown = false)

            assertEquals(DispatchTransitStartNetworkOutcome.PermissionDenied, result)
        }
    }

    private fun command() = DispatchTransitStartCommand(
        deliveryId = DELIVERY_ID,
        fulfillmentId = FULFILLMENT_ID,
        deliveryEtag = "\"17\"",
        idempotencyKey = "stable-transit-key"
    )

    private fun jsonResponse(version: Long, status: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .addHeader("ETag", "\"$version\"")
        .setBody(
            """{"id":"$DELIVERY_ID","fulfillmentId":"$FULFILLMENT_ID","status":"$status","version":$version}"""
        )

    private fun gateway(server: MockWebServer): NexaDispatchTransitStartGateway {
        val endpoint = com.nexa.mobile.operations.core.network.ApiEndpoint(
            server.url("/").toString()
        )
        val executor = com.nexa.mobile.operations.core.network.ProtectedCallExecutor(
            endpoint,
            com.nexa.mobile.operations.core.network.ApiHttpClient.create(endpoint),
            FakeAccessTokenSource()
        )
        return NexaDispatchTransitStartGateway(executor)
    }

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

    private companion object {
        const val DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val OTHER_FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413202"
    }
}
