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

class NexaDispatchHandoffIdentityGatewayTest {
    @Test
    fun issueUsesDispatchPurposeAndStableKeyAndReturnsOnlyExactDeliveryAssignment() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, wire(token = "dsp_opaque_token")))

            val result = gateway(server).issue(
                DELIVERY_ID,
                ASSIGNMENT_ID,
                "issue-key-1",
                issueBody()
            ) as DispatchHandoffIdentityNetworkOutcome.Identity
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/deliveries/$DELIVERY_ID/handoff-tokens",
                request.requestUrl?.encodedPath
            )
            assertEquals("issue-key-1", request.getHeader("Idempotency-Key"))
            assertEquals(
                """{"purpose":"DISPATCH_HANDOFF","assignmentId":"$ASSIGNMENT_ID"}""",
                request.body.readUtf8()
            )
            assertEquals(ASSIGNMENT_ID, result.value.assignmentId)
            assertEquals(DELIVERY_ID, result.value.deliveryId)
            assertEquals("dsp_opaque_token", result.value.token)
            assertFalse(result.value.toString().contains("dsp_opaque_token"))
        }
    }

    @Test
    fun validationIsExplicitIdentityOnlyPostAndRejectsAResponseForAnotherTuple() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, wire(token = null)))
            server.enqueue(jsonResponse(200, wire(deliveryId = OTHER_DELIVERY_ID, token = null)))
            val gateway = gateway(server)

            val result = gateway.validate(DELIVERY_ID, ASSIGNMENT_ID, "dsp_operator_code")
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/delivery-handoff/validations", request.requestUrl?.encodedPath)
            assertEquals(
                """{"purpose":"DISPATCH_HANDOFF","token":"dsp_operator_code","deliveryId":"$DELIVERY_ID","assignmentId":"$ASSIGNMENT_ID"}""",
                request.body.readUtf8()
            )
            assertTrue(request.getHeader("Idempotency-Key").isNullOrBlank())
            assertEquals(
                DispatchHandoffIdentityNetworkOutcome.Identity::class,
                (result as DispatchHandoffIdentityNetworkOutcome.Identity)::class
            )
            assertEquals(null, result.value.token)
            assertEquals(
                DispatchHandoffIdentityNetworkOutcome.UnknownOutcome,
                gateway.validate(DELIVERY_ID, ASSIGNMENT_ID, "dsp_operator_code")
            )
        }
    }

    @Test
    fun exactKeyReplayWithoutTokenAndRejectedValidationStayDistinct() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, wire(token = null)))
            server.enqueue(problemResponse(422, "HANDOFF_TOKEN_EXPIRED"))
            val gateway = gateway(server)

            val replay = gateway.issue(DELIVERY_ID, ASSIGNMENT_ID, "same-key", issueBody())
                as DispatchHandoffIdentityNetworkOutcome.Identity
            assertEquals(null, replay.value.token)
            assertEquals(
                DispatchHandoffIdentityNetworkOutcome.Rejected("HANDOFF_TOKEN_EXPIRED"),
                gateway.validate(DELIVERY_ID, ASSIGNMENT_ID, "dsp_expired")
            )
        }
    }

    private fun gateway(server: MockWebServer) = NexaDispatchHandoffIdentityGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeAccessTokenSource()
        )
    )

    private fun issueBody() = """{"purpose":"DISPATCH_HANDOFF","assignmentId":"$ASSIGNMENT_ID"}"""

    private fun wire(deliveryId: String = DELIVERY_ID, token: String?) =
        """{"purpose":"DISPATCH_HANDOFF","handoffId":"$HANDOFF_ID","deliveryId":"$deliveryId","assignmentId":"$ASSIGNMENT_ID","deliveryVersion":16,"expiresAt":"2030-10-01T12:00:00Z","status":"ACTIVE","token":${token?.let {
            "\"$it\""
        } ?: "null"}}"""

    private fun jsonResponse(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun problemResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody("""{"status":$status,"code":"$code","category":"CLIENT_ERROR"}""")

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
        const val DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val OTHER_DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413002"
        const val ASSIGNMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413003"
        const val HANDOFF_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413004"
    }
}
