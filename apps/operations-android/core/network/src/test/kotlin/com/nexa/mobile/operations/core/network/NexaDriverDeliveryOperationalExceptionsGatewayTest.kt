package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaDriverDeliveryOperationalExceptionsGatewayTest {
    @Test
    fun readParsesServerClassifiedCasesAndRequiresStrongMatchingDeliveryEtag() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"7\"")
                    .setBody(exceptionsResponse())
            )

            val result = gateway(server).currentExceptions(DELIVERY_ID)
                as DriverOperationalExceptionsNetworkOutcome.Loaded

            assertEquals(DELIVERY_ID, result.value.deliveryId)
            assertEquals(7L, result.value.deliveryVersion)
            assertEquals(2, result.value.exceptions.size)
            assertEquals("BLOCKING", result.value.exceptions.first().severity)
            assertEquals("DRIVER_INCIDENT", result.value.exceptions.first().sourceKind)
            assertEquals("DELIVERY", result.value.exceptions.first().affectedObjectType)
            assertEquals("Road closure", result.value.exceptions.first().reason)
            assertTrue(result.value.exceptions.last().evidenceObjectIds.isNotEmpty())
            assertEquals("CLAIMED", result.value.exceptions.last().status)
            assertEquals(OTHER_MEMBERSHIP_ID, result.value.exceptions.last().responsibleMembershipId)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/driver/deliveries/$DELIVERY_ID/operational-exceptions", request.path)
        }
    }

    @Test
    fun claimAndReviewUseExactRoutesEmptyBodyKeyAndDeliveryVersionPrecondition() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"8\"")
                    .setBody(mutationResponse(version = 8, status = "CLAIMED", replayed = false))
            )
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"9\"")
                    .setBody(mutationResponse(version = 9, status = "UNDER_REVIEW", reviewed = true, replayed = false))
            )
            val gateway = gateway(server)

            val claim = gateway.mutate(
                DELIVERY_ID, EXCEPTION_ID, DriverOperationalExceptionActionTransport.Claim,
                7, "claim-key", "{}"
            ) as DriverOperationalExceptionsNetworkOutcome.Changed
            val claimRequest = server.takeRequest()
            assertEquals("POST", claimRequest.method)
            assertEquals(
                "/api/v1/driver/deliveries/$DELIVERY_ID/operational-exceptions/$EXCEPTION_ID/claims",
                claimRequest.path
            )
            assertEquals("\"7\"", claimRequest.getHeader("If-Match"))
            assertEquals("claim-key", claimRequest.getHeader("Idempotency-Key"))
            assertEquals("{}", claimRequest.body.readUtf8())
            assertEquals("CLAIMED", claim.value.exception.status)

            val review = gateway.mutate(
                DELIVERY_ID, EXCEPTION_ID, DriverOperationalExceptionActionTransport.Review,
                8, "review-key", "{}"
            ) as DriverOperationalExceptionsNetworkOutcome.Changed
            val reviewRequest = server.takeRequest()
            assertEquals(
                "/api/v1/driver/deliveries/$DELIVERY_ID/operational-exceptions/$EXCEPTION_ID/reviews",
                reviewRequest.path
            )
            assertEquals("\"8\"", reviewRequest.getHeader("If-Match"))
            assertEquals("review-key", reviewRequest.getHeader("Idempotency-Key"))
            assertEquals("{}", reviewRequest.body.readUtf8())
            assertEquals("UNDER_REVIEW", review.value.exception.status)
            assertEquals(MEMBERSHIP_ID, review.value.exception.underReviewByMembershipId)
        }
    }

    @Test
    fun warningResolutionAndClosureUseVersionedRoutesAndExactBodyContract() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"8\"")
                    .setBody(
                        mutationResponse(
                            version = 8,
                            status = "RESOLVED",
                            reviewed = true,
                            replayed = false,
                            type = "DELAY",
                            severity = "WARNING",
                            resolution = "Barrier removed",
                            outcome = "WARNING_CONDITION_ADDRESSED"
                        )
                    )
            )
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"9\"")
                    .setBody(
                        mutationResponse(
                            version = 9,
                            status = "CLOSED",
                            reviewed = true,
                            replayed = false,
                            type = "DELAY",
                            severity = "WARNING",
                            resolution = "Barrier removed",
                            outcome = "WARNING_CONDITION_ADDRESSED"
                        )
                    )
            )
            val gateway = gateway(server)

            val resolved = gateway.mutate(
                DELIVERY_ID,
                EXCEPTION_ID,
                DriverOperationalExceptionActionTransport.ResolveWarning,
                7,
                "resolve-key",
                "{\"resolution\":\"Barrier removed\"}"
            ) as DriverOperationalExceptionsNetworkOutcome.Changed
            val resolveRequest = server.takeRequest()
            assertEquals("POST", resolveRequest.method)
            assertEquals(
                "/api/v1/driver/deliveries/$DELIVERY_ID/operational-exceptions/$EXCEPTION_ID/resolutions",
                resolveRequest.path
            )
            assertEquals("\"7\"", resolveRequest.getHeader("If-Match"))
            assertEquals("resolve-key", resolveRequest.getHeader("Idempotency-Key"))
            assertEquals("{\"resolution\":\"Barrier removed\"}", resolveRequest.body.readUtf8())
            assertEquals("RESOLVED", resolved.value.exception.status)
            assertEquals("Barrier removed", resolved.value.exception.resolution)
            assertEquals("WARNING_CONDITION_ADDRESSED", resolved.value.exception.outcome)

            val closed = gateway.mutate(
                DELIVERY_ID,
                EXCEPTION_ID,
                DriverOperationalExceptionActionTransport.CloseWarning,
                8,
                "close-key",
                ""
            ) as DriverOperationalExceptionsNetworkOutcome.Changed
            val closeRequest = server.takeRequest()
            assertEquals(
                "/api/v1/driver/deliveries/$DELIVERY_ID/operational-exceptions/$EXCEPTION_ID/closures",
                closeRequest.path
            )
            assertEquals("\"8\"", closeRequest.getHeader("If-Match"))
            assertEquals("close-key", closeRequest.getHeader("Idempotency-Key"))
            assertEquals("", closeRequest.body.readUtf8())
            assertEquals("CLOSED", closed.value.exception.status)
            assertEquals("Barrier removed", closed.value.exception.resolution)
        }
    }

    @Test
    fun invalidWarningCompletionBodiesAreNotSent() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = gateway(server)

            assertEquals(
                DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable,
                gateway.mutate(
                    DELIVERY_ID,
                    EXCEPTION_ID,
                    DriverOperationalExceptionActionTransport.ResolveWarning,
                    7,
                    "resolve-key",
                    "{}"
                )
            )
            assertEquals(
                DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable,
                gateway.mutate(
                    DELIVERY_ID,
                    EXCEPTION_ID,
                    DriverOperationalExceptionActionTransport.CloseWarning,
                    7,
                    "close-key",
                    "{}"
                )
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun staleVersionAndBusinessConflictRemainExplicitOutcomes() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(412)
                    .setHeader("Content-Type", "application/problem+json")
                    .setBody("""{"status":412,"code":"PRECONDITION_FAILED"}""")
            )
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .setBody("""{"status":409,"code":"OPERATIONAL_EXCEPTION_STATE_CONFLICT"}""")
            )
            val gateway = gateway(server)

            assertEquals(
                DriverOperationalExceptionsNetworkOutcome.StaleVersion,
                gateway.mutate(
                    DELIVERY_ID, EXCEPTION_ID, DriverOperationalExceptionActionTransport.Claim,
                    7, "claim-key", "{}"
                )
            )
            val rejected = gateway.mutate(
                DELIVERY_ID, EXCEPTION_ID, DriverOperationalExceptionActionTransport.Review,
                8, "review-key", "{}"
            ) as DriverOperationalExceptionsNetworkOutcome.Rejected
            assertEquals("OPERATIONAL_EXCEPTION_STATE_CONFLICT", rejected.code)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun weakEtagOrMismatchedResponseEtagCannotProduceAuthoritySnapshot() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "W/\"7\"")
                    .setBody(exceptionsResponse())
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"10\"")
                    .setBody(mutationResponse(version = 9, status = "CLAIMED", replayed = true))
            )
            val gateway = gateway(server)

            assertEquals(
                DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable,
                gateway.currentExceptions(DELIVERY_ID)
            )
            assertEquals(
                DriverOperationalExceptionsNetworkOutcome.UnknownOutcome,
                gateway.mutate(
                    DELIVERY_ID, EXCEPTION_ID, DriverOperationalExceptionActionTransport.Claim,
                    7, "claim-key", "{}"
                )
            )
            assertTrue(server.requestCount == 2)
        }
    }

    private fun gateway(server: MockWebServer) = NexaDriverDeliveryOperationalExceptionsGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeTokens()
        )
    )

    private fun exceptionsResponse() =
        """{"deliveryId":"$DELIVERY_ID","deliveryVersion":7,"exceptions":[{"id":"$EXCEPTION_ID","sourceKind":"DRIVER_INCIDENT","sourceIncidentId":"$INCIDENT_ID","affectedObjectType":"DELIVERY","affectedObjectId":"$DELIVERY_ID","type":"ACCESS_BLOCKED","severity":"BLOCKING","status":"OPEN","reason":"Road closure","description":"The delivery cannot reach its destination.","place":"North entrance","resolution":null,"outcome":null,"reportedByMembershipId":"$MEMBERSHIP_ID","occurredAt":"2026-10-01T16:55:00Z","reportedAt":"2026-10-01T16:58:00Z","responsibleMembershipId":null,"claimedAt":null,"underReviewByMembershipId":null,"underReviewAt":null,"evidenceObjectIds":[]},{"id":"$SECOND_EXCEPTION_ID","sourceKind":"DISPATCH_INCIDENT","sourceIncidentId":"$SECOND_INCIDENT_ID","affectedObjectType":"DELIVERY","affectedObjectId":"$DELIVERY_ID","type":"SITE_ACCESS","severity":"WARNING","status":"CLAIMED","reason":null,"description":"The receiving entrance is temporarily closed.","place":null,"resolution":null,"outcome":null,"reportedByMembershipId":"$MEMBERSHIP_ID","occurredAt":"2026-10-01T16:56:00Z","reportedAt":"2026-10-01T16:59:00Z","responsibleMembershipId":"$OTHER_MEMBERSHIP_ID","claimedAt":"2026-10-01T17:00:00Z","underReviewByMembershipId":null,"underReviewAt":null,"evidenceObjectIds":["$EVIDENCE_ID"]}]}"""

    private fun mutationResponse(
        version: Long,
        status: String,
        reviewed: Boolean = false,
        replayed: Boolean,
        type: String = "ACCESS_BLOCKED",
        severity: String = "BLOCKING",
        resolution: String? = null,
        outcome: String? = null
    ) = """{"deliveryId":"$DELIVERY_ID","deliveryVersion":$version,"exception":{"id":"$EXCEPTION_ID","sourceKind":"DRIVER_INCIDENT","sourceIncidentId":"$INCIDENT_ID","affectedObjectType":"DELIVERY","affectedObjectId":"$DELIVERY_ID","type":"$type","severity":"$severity","status":"$status","reason":"Road closure","description":"The delivery cannot reach its destination.","place":"North entrance","resolution":${resolution?.let(::jsonString) ?: "null"},"outcome":${outcome?.let(::jsonString) ?: "null"},"reportedByMembershipId":"$MEMBERSHIP_ID","occurredAt":"2026-10-01T16:55:00Z","reportedAt":"2026-10-01T16:58:00Z","responsibleMembershipId":"$MEMBERSHIP_ID","claimedAt":"2026-10-01T17:01:00Z","underReviewByMembershipId":${if (reviewed) "\"$MEMBERSHIP_ID\"" else "null"},"underReviewAt":${if (reviewed) "\"2026-10-01T17:02:00Z\"" else "null"},"evidenceObjectIds":[]},"replayed":$replayed}"""

    private fun jsonString(value: String) = JsonPrimitive(value).toString()

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
        const val EXCEPTION_ID = "33333333-3333-4333-8333-333333333333"
        const val INCIDENT_ID = "44444444-4444-4444-8444-444444444444"
        const val SECOND_EXCEPTION_ID = "66666666-6666-4666-8666-666666666666"
        const val SECOND_INCIDENT_ID = "77777777-7777-4777-8777-777777777777"
        const val EVIDENCE_ID = "99999999-9999-4999-8999-999999999999"
        const val MEMBERSHIP_ID = "55555555-5555-4555-8555-555555555555"
        const val OTHER_MEMBERSHIP_ID = "88888888-8888-4888-8888-888888888888"
    }
}
