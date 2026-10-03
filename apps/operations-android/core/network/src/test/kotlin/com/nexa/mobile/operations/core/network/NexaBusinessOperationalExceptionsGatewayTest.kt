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

class NexaBusinessOperationalExceptionsGatewayTest {
    @Test
    fun readsCurrentListAndEligibleAssigneesFromCurrentProtectedRoutes() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(200).setBody(currentResponse()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(assigneesResponse()))
            val gateway = gateway(server)

            val current = gateway.current() as BusinessOperationalExceptionsNetworkResult.Current
            assertEquals("2026-10-01T17:00:00Z", current.value.asOf)
            assertEquals(1, current.value.exceptions.size)
            assertEquals(7L, current.value.exceptions.single().deliveryVersion)
            assertEquals("CRITICAL", current.value.exceptions.single().severity)
            assertEquals("THERMAL_EXCURSION", current.value.exceptions.single().type)
            val listRequest = server.takeRequest()
            assertEquals("GET", listRequest.method)
            assertEquals("/api/v1/operational-exceptions", listRequest.path)

            val eligible = gateway.assignees(
                EXCEPTION_ID
            ) as BusinessOperationalExceptionsNetworkResult.Assignees
            assertEquals(MEMBER_ID, eligible.values.single().membershipId)
            assertTrue(eligible.values.single().coordinator)
            val assigneeRequest = server.takeRequest()
            assertEquals("GET", assigneeRequest.method)
            assertEquals(
                "/api/v1/operational-exceptions/$EXCEPTION_ID/assignees",
                assigneeRequest.path
            )
        }
    }

    @Test
    fun eachCoordinationMutationUsesExactRouteFrozenBodyAndStrongDeliveryVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            val actions = listOf(
                Triple(
                    BusinessOperationalExceptionActionTransport.CLAIM,
                    "claims",
                    "{\"reason\":\"triage\"}"
                ),
                Triple(
                    BusinessOperationalExceptionActionTransport.REASSIGN,
                    "assignments",
                    "{\"responsibleMembershipId\":\"$MEMBER_ID\",\"reason\":\"handoff\"}"
                ),
                Triple(
                    BusinessOperationalExceptionActionTransport.FOLLOW_UP,
                    "follow-ups",
                    "{\"reason\":\"check\",\"note\":\"call warehouse\"}"
                ),
                Triple(
                    BusinessOperationalExceptionActionTransport.RESOLVE,
                    "resolutions",
                    "{\"reason\":\"warning condition addressed\"}"
                ),
                Triple(
                    BusinessOperationalExceptionActionTransport.CLOSE,
                    "closures",
                    "{\"reason\":\"verified complete\"}"
                )
            )
            val statuses = listOf("CLAIMED", "CLAIMED", "UNDER_REVIEW", "RESOLVED", "CLOSED")
            actions.forEachIndexed { index, _ ->
                val version = index + 8
                server.enqueue(
                    MockResponse().setResponseCode(201).setHeader("ETag", "\"$version\"")
                        .setBody(mutationResponse(version.toLong(), statuses[index]))
                )
            }
            val gateway = gateway(server)

            actions.forEachIndexed { index, (action, path, body) ->
                val beforeVersion = index + 7L
                val result = gateway.mutate(
                    EXCEPTION_ID,
                    action,
                    beforeVersion,
                    "command-$index",
                    body
                )
                assertTrue(result is BusinessOperationalExceptionsNetworkResult.Changed)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/v1/operational-exceptions/$EXCEPTION_ID/$path", request.path)
                assertEquals("\"$beforeVersion\"", request.getHeader("If-Match"))
                assertEquals("command-$index", request.getHeader("Idempotency-Key"))
                assertEquals(body, request.body.readUtf8())
            }
        }
    }

    @Test
    fun malformedMutationBodyNeverReachesTransport() = runTest {
        MockWebServer().use { server ->
            server.start()
            val result = gateway(server).mutate(
                EXCEPTION_ID,
                BusinessOperationalExceptionActionTransport.CLAIM,
                7,
                "key",
                "{}"
            )
            assertEquals(
                BusinessOperationalExceptionsNetworkResult.Failed("INVALID_COMMAND"),
                result
            )
            assertEquals(0, server.requestCount)
        }
    }

    private fun gateway(server: MockWebServer) = NexaBusinessOperationalExceptionsGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeTokens()
        )
    )

    private fun currentResponse() =
        """{"asOf":"2026-10-01T17:00:00Z","exceptions":[${exceptionJson(7)}]}"""
    private fun assigneesResponse() =
        """[{"membershipId":"$MEMBER_ID","displayName":"Current coordinator","coordinator":true,"driverReporter":false}]"""
    private fun mutationResponse(version: Long, status: String) =
        """{"deliveryId":"$DELIVERY_ID","deliveryVersion":$version,"exception":${exceptionJson(
            version,
            type = "DELAY",
            severity = "WARNING",
            status = status,
            resolution = if (status in setOf("RESOLVED", "CLOSED")) "condition addressed" else null,
            outcome = if (status in setOf(
                    "RESOLVED",
                    "CLOSED"
                )
            ) {
                "WARNING_CONDITION_ADDRESSED"
            } else {
                null
            }
        )},"replayed":false}"""

    private fun exceptionJson(
        version: Long,
        type: String = "THERMAL_EXCURSION",
        severity: String = "CRITICAL",
        status: String = "OPEN",
        resolution: String? = null,
        outcome: String? = null
    ) =
        """{"id":"$EXCEPTION_ID","deliveryId":"$DELIVERY_ID","deliveryVersion":$version,"sourceKind":"DRIVER_INCIDENT","sourceIncidentId":"$INCIDENT_ID","affectedObjectType":"DELIVERY","affectedObjectId":"$DELIVERY_ID","type":"$type","severity":"$severity","status":"$status","reason":"temperature excursion","description":"Manual temperature evidence is out of range.","place":null,"resolution":${resolution?.let {
            "\"$it\""
        } ?: "null"},"outcome":${outcome?.let {
            "\"$it\""
        } ?: "null"},"reportedByMembershipId":"$MEMBER_ID","occurredAt":"2026-10-01T16:55:00Z","reportedAt":"2026-10-01T16:58:00Z","responsibleMembershipId":${if (status == "OPEN") "null" else "\"$MEMBER_ID\""},"claimedAt":${if (status == "OPEN") "null" else "\"2026-10-01T17:01:00Z\""},"underReviewByMembershipId":${if (status in setOf(
                "UNDER_REVIEW",
                "RESOLVED",
                "CLOSED"
            )
        ) {
            "\"$MEMBER_ID\""
        } else {
            "null"
        }},"underReviewAt":${if (status in setOf(
                "UNDER_REVIEW",
                "RESOLVED",
                "CLOSED"
            )
        ) {
            "\"2026-10-01T17:02:00Z\""
        } else {
            "null"
        }},"coordinationOwnerMembershipId":"$MEMBER_ID","coordinationClaimedAt":"2026-10-01T17:00:00Z","evidenceObjectIds":[]}"""

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
        const val EXCEPTION_ID = "33333333-3333-4333-8333-333333333333"
        const val INCIDENT_ID = "44444444-4444-4444-8444-444444444444"
        const val MEMBER_ID = "55555555-5555-4555-8555-555555555555"
    }
}
