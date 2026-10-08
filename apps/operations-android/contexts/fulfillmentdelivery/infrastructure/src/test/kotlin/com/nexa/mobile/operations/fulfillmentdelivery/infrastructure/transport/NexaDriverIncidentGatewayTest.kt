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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaDriverIncidentGatewayTest {
    @Test
    fun recordPostsExactBodyAndParsesServerAttributionAndEvidence() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"8\"")
                    .setBody(
                        response(version = 8, replayed = false, evidence = "[\"$EVIDENCE_ID\"]")
                    )
            )
            val command = command()

            val result = gateway(server).record(command) as DriverIncidentNetworkOutcome.Recorded

            assertEquals(INCIDENT_ID, result.incident.id)
            assertEquals("DELAY", result.incident.type)
            assertEquals("WARNING", result.incident.severity)
            assertEquals(OPERATIONAL_EXCEPTION_ID, result.incident.operationalExceptionId)
            assertEquals(MEMBERSHIP_ID, result.incident.recordedByMembershipId)
            assertEquals(listOf(EVIDENCE_ID), result.incident.evidenceObjectIds)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/driver/deliveries/$DELIVERY_ID/attempts/$ATTEMPT_ID/incidents",
                request.path
            )
            assertEquals("incident-key-1", request.getHeader("Idempotency-Key"))
            assertEquals("\"7\"", request.getHeader("If-Match"))
            assertEquals(command.frozenBody, request.body.readUtf8())
        }
    }

    @Test
    fun replayKeepsSameKeyVersionAndFrozenPayloadAndUncertainPostIsNotRetried() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"8\"").setBody(response(version = 8, replayed = true))
            )
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val command = command()

            val replay = gateway(server).record(command)
            val unknown = gateway(server).record(command)

            assertTrue(replay is DriverIncidentNetworkOutcome.Recorded)
            assertEquals(DriverIncidentNetworkOutcome.UnknownOutcome, unknown)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("incident-key-1", request.getHeader("Idempotency-Key"))
                assertEquals("\"7\"", request.getHeader("If-Match"))
                assertEquals(command.frozenBody, request.body.readUtf8())
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun invalidFrozenBodyNeverLeavesDevice() = runTest {
        MockWebServer().use { server ->
            server.start()
            val result = gateway(server).record(command().copy(frozenBody = "{}"))
            assertEquals(DriverIncidentNetworkOutcome.Unavailable, result)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun preTypeIntentReplaysExactUnclassifiedBodyWithoutAddingClassification() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setHeader(
                        "ETag",
                        "\"7\""
                    ).setBody(legacyResponse(version = 7, replayed = true))
            )
            val legacy = command().copy(
                type = null,
                frozenBody = listOf(
                    """{"reason":"Road closure",""",
                    """"description":"Route blocked at the north entrance",""",
                    """"place":"North entrance"}"""
                ).joinToString(separator = "")
            )

            val result = gateway(server).record(legacy) as DriverIncidentNetworkOutcome.Recorded

            assertEquals(null, result.incident.type)
            assertEquals(null, result.incident.severity)
            assertEquals(null, result.incident.operationalExceptionId)
            val request = server.takeRequest()
            assertEquals(legacy.frozenBody, request.body.readUtf8())
            assertEquals("incident-key-1", request.getHeader("Idempotency-Key"))
            assertEquals("\"7\"", request.getHeader("If-Match"))
        }
    }

    @Test
    fun typedRequestRejectsUnclassifiedResponseAsUnknown() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                    .setHeader(
                        "ETag",
                        "\"8\""
                    ).setBody(legacyResponse(version = 8, replayed = false))
            )

            assertEquals(
                DriverIncidentNetworkOutcome.UnknownOutcome,
                gateway(server).record(command())
            )
        }
    }

    private fun gateway(server: MockWebServer) = NexaDriverIncidentGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeTokens()
        )
    )

    private fun command() = DriverIncidentWireCommand(
        DELIVERY_ID, ATTEMPT_ID, 7, "incident-key-1", "Road closure",
        "Route blocked at the north entrance", "North entrance",
        """{"type":"DELAY","reason":"Road closure","description":"Route blocked at the north entrance","place":"North entrance"}""",
        "DELAY"
    )

    private fun response(version: Long, replayed: Boolean, evidence: String = "[]") =
        """{"id":"$INCIDENT_ID","deliveryId":"$DELIVERY_ID","attemptId":"$ATTEMPT_ID","reason":"Road closure","description":"Route blocked at the north entrance","place":"North entrance","recordedByMembershipId":"$MEMBERSHIP_ID","recordedAt":"2026-10-01T17:00:00Z","evidenceObjectIds":$evidence,"deliveryVersion":$version,"replayed":$replayed,"type":"DELAY","severity":"WARNING","operationalExceptionId":"$OPERATIONAL_EXCEPTION_ID"}"""

    private fun legacyResponse(version: Long, replayed: Boolean) =
        """{"id":"$INCIDENT_ID","deliveryId":"$DELIVERY_ID","attemptId":"$ATTEMPT_ID","reason":"Road closure","description":"Route blocked at the north entrance","place":"North entrance","recordedByMembershipId":"$MEMBERSHIP_ID","recordedAt":"2026-10-01T17:00:00Z","evidenceObjectIds":[],"deliveryVersion":$version,"replayed":$replayed}"""

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
        const val ATTEMPT_ID = "33333333-3333-4333-8333-333333333333"
        const val INCIDENT_ID = "44444444-4444-4444-8444-444444444444"
        const val MEMBERSHIP_ID = "55555555-5555-4555-8555-555555555555"
        const val EVIDENCE_ID = "66666666-6666-4666-8666-666666666666"
        const val OPERATIONAL_EXCEPTION_ID = "77777777-7777-4777-8777-777777777777"
    }
}
