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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaDispatchAssignmentGatewayTest {
    @Test
    fun candidatesAndCurrentAssignmentUsePublishedCurrentRoutes() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    """[{"id":"$MEMBERSHIP_ID","email":"driver@example.test","displayName":"Driver One"}]"""
                )
            )
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                jsonResponse(assignmentJson())
                    .addHeader("ETag", "\"13\"")
            )
            val gateway = gateway(server)

            val candidates = gateway.candidates() as DispatchAssignmentNetworkOutcome.Candidates
            val candidateRequest = server.takeRequest()
            assertEquals("GET", candidateRequest.method)
            assertEquals("/api/v1/dispatch-assignees", candidateRequest.requestUrl?.encodedPath)
            assertEquals(MEMBERSHIP_ID, candidates.items.single().membershipId)
            assertEquals("Driver One", candidates.items.single().displayName)

            val empty = gateway.current(FULFILLMENT_ID) as DispatchAssignmentNetworkOutcome.Current
            val emptyRequest = server.takeRequest()
            assertEquals("GET", emptyRequest.method)
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/driver-assignments",
                emptyRequest.requestUrl?.encodedPath
            )
            assertEquals(null, empty.item)

            val current = gateway.current(
                FULFILLMENT_ID
            ) as DispatchAssignmentNetworkOutcome.Current
            val currentRequest = server.takeRequest()
            assertEquals("GET", currentRequest.method)
            assertEquals(ASSIGNMENT_ID, current.item?.id)
            assertEquals(FULFILLMENT_ID, current.item?.fulfillmentId)
            assertEquals(null, current.item?.deliveryId)
        }
    }

    @Test
    fun assignmentUsesExactVersionsAndStableCommandHeaders() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(assignmentJson()).addHeader("ETag", "\"13\""))
            val gateway = gateway(server)

            val result = gateway.assign(
                DispatchDriverAssignmentRequest(
                    fulfillmentId = FULFILLMENT_ID,
                    expectedFulfillmentVersion = 12,
                    physicalAllocationId = ALLOCATION_ID,
                    physicalAllocationVersion = 7,
                    responsibleMembershipId = MEMBERSHIP_ID
                ),
                "dispatch-assignment-key"
            ) as DispatchAssignmentNetworkOutcome.Assigned
            val request = server.takeRequest()
            val requestBody = request.body.readUtf8()

            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/driver-assignments",
                request.requestUrl?.encodedPath
            )
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertEquals("\"12\"", request.getHeader("If-Match"))
            assertEquals("dispatch-assignment-key", request.getHeader("Idempotency-Key"))
            assertTrue(requestBody.contains("\"physicalAllocationVersion\":7"))
            assertTrue(requestBody.contains(MEMBERSHIP_ID))
            assertEquals(ASSIGNMENT_ID, result.item.id)
            assertEquals(13L, result.item.fulfillmentVersion)
            assertFalse(result.item.toString().contains("Driver One"))
        }
    }

    @Test
    fun malformedOrStaleAssignmentResponsesDoNotBecomeConfirmedFacts() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(assignmentJson(version = 14))
                    .addHeader("ETag", "\"14\"")
            )
            server.enqueue(problemResponse(412, "PRECONDITION_FAILED"))
            val gateway = gateway(server)
            val command = DispatchDriverAssignmentRequest(
                FULFILLMENT_ID,
                12,
                ALLOCATION_ID,
                7,
                MEMBERSHIP_ID
            )

            assertEquals(
                DispatchAssignmentNetworkOutcome.UnknownOutcome,
                gateway.assign(command, "assignment-key")
            )
            assertEquals(
                DispatchAssignmentNetworkOutcome.Stale,
                gateway.assign(command, "assignment-key-2")
            )
            server.takeRequest()
            server.takeRequest()
        }
    }

    private fun gateway(server: MockWebServer) = NexaDispatchAssignmentGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeAccessTokenSource()
        )
    )

    private fun assignmentJson(version: Long = 13) =
        """{"id":"$ASSIGNMENT_ID","fulfillmentId":"$FULFILLMENT_ID","fulfillmentVersion":$version,"physicalAllocationId":"$ALLOCATION_ID","physicalAllocationVersion":7,"responsibleMembershipId":"$MEMBERSHIP_ID","responsibleDisplayName":"Driver One","assignedAt":"2026-09-30T10:15:30Z","deliveryId":null}"""

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun problemResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody("""{"status":$status,"code":"$code","category":"CONCURRENCY"}""")

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
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413104"
        const val ASSIGNMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413105"
    }
}
