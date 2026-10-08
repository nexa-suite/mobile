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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaDriverWorkdayGatewayTest {
    @Test
    fun currentAccepts204AndValidatesStrongVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            val api = gateway(server)
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(DriverWorkdayNetworkResult.Current(null), api.current())
            assertEquals("/api/v1/driver/workdays/current", server.takeRequest().path)

            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("ETag", "\"4\"").setBody(workday())
            )
            assertEquals(
                "ACTIVE",
                (api.current() as DriverWorkdayNetworkResult.Current).workday?.status
            )
            server.takeRequest()

            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("ETag", "W/\"4\"").setBody(workday())
            )
            assertTrue(api.current() is DriverWorkdayNetworkResult.Unavailable)
        }
    }

    @Test
    fun startUsesExactRouteBodyAndIdempotencyKeyWithoutVersionHeader() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(201))
            assertEquals(DriverWorkdayNetworkResult.Accepted, gateway(server).start("start-key"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/driver/workdays", request.path)
            assertEquals("start-key", request.getHeader("Idempotency-Key"))
            assertEquals(null, request.getHeader("If-Match"))
            assertEquals("{\"locationAvailable\":true}", request.body.readUtf8())
        }
    }

    @Test
    fun endAndAvailabilityUseCurrentStrongDeliveryVersionAndExactBodies() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(
                DriverWorkdayNetworkResult.Accepted,
                gateway(server).end(WORKDAY, 4, "end-key")
            )
            val end = server.takeRequest()
            assertEquals("/api/v1/driver/workdays/$WORKDAY/ends", end.path)
            assertEquals("\"4\"", end.getHeader("If-Match"))
            assertEquals("end-key", end.getHeader("Idempotency-Key"))
            assertEquals(0L, end.body.size)

            server.enqueue(MockResponse().setResponseCode(200))
            assertEquals(
                DriverWorkdayNetworkResult.Accepted,
                gateway(server).setLocationAvailability(WORKDAY, 5, false, "availability-key")
            )
            val availability = server.takeRequest()
            assertEquals(
                "/api/v1/driver/workdays/$WORKDAY/location-availability",
                availability.path
            )
            assertEquals("\"5\"", availability.getHeader("If-Match"))
            assertEquals("availability-key", availability.getHeader("Idempotency-Key"))
            assertEquals("{\"locationAvailable\":false}", availability.body.readUtf8())
        }
    }

    @Test
    fun locationSampleIsEphemeralWirePayloadAndServerExpiryIsRequired() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(201).setBody(locationResponse()))
            val sample =
                DriverWorkdayLocationCommand(SAMPLE, -12.05, -77.04, 9.5, "2026-10-01T17:30:00Z")
            val accepted = gateway(
                server
            ).reportLocation(WORKDAY, sample) as DriverWorkdayNetworkResult.LocationAccepted
            assertEquals("2026-10-02T17:30:00Z", accepted.location.expiresAt)
            val request = server.takeRequest()
            assertEquals("/api/v1/driver/workdays/$WORKDAY/locations", request.path)
            assertEquals(null, request.getHeader("Idempotency-Key"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"sampleId\":\"$SAMPLE\""))
            assertTrue(body.contains("\"capturedAt\":\"2026-10-01T17:30:00Z\""))
            assertFalse(body.contains("expiresAt"))

            server.enqueue(
                MockResponse().setResponseCode(
                    201
                ).setBody(locationResponse().replace(",\"expiresAt\":\"2026-10-02T17:30:00Z\"", ""))
            )
            assertTrue(
                gateway(
                    server
                ).reportLocation(WORKDAY, sample) is DriverWorkdayNetworkResult.UnknownOutcome
            )

            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    locationResponse().replace("2026-10-02T17:30:00Z", "2026-10-02T17:30:01Z")
                )
            )
            assertTrue(
                gateway(
                    server
                ).reportLocation(WORKDAY, sample) is DriverWorkdayNetworkResult.UnknownOutcome
            )
        }
    }

    @Test
    fun staleVersionAndNetworkAmbiguityAreDistinctAndNeverRetried() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(412))
            assertEquals(
                DriverWorkdayNetworkResult.StaleVersion,
                gateway(server).setLocationAvailability(WORKDAY, 4, false, "stale-key")
            )
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(
                DriverWorkdayNetworkResult.UnknownOutcome,
                gateway(server).end(WORKDAY, 4, "unknown-key")
            )
            server.takeRequest()
            assertEquals(2, server.requestCount)
        }
    }

    private fun gateway(server: MockWebServer) = NexaDriverWorkdayGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeTokens()
        )
    )

    private fun workday() =
        """{"id":"$WORKDAY","version":4,"status":"ACTIVE","startedAt":"2026-10-01T17:00:00Z","endedAt":null,"locationAvailable":true}"""
    private fun locationResponse() =
        """{"sampleId":"$SAMPLE","latitude":-12.05,"longitude":-77.04,"accuracyMeters":9.5,"capturedAt":"2026-10-01T17:30:00Z","expiresAt":"2026-10-02T17:30:00Z"}"""

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
        const val WORKDAY = "22222222-2222-4222-8222-222222222222"
        const val SAMPLE = "33333333-3333-4333-8333-333333333333"
    }
}
