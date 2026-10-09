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
import org.junit.Test

class NexaFulfillmentDispatchGatewayTest {
    @Test
    fun currentHandoffEvidenceIsReadFromTypedCurrentRouteAndValidatesScopeAndVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                evidenceResponse("\"warehouseActorMembershipId\":\"$WAREHOUSE_MEMBERSHIP_ID\"")
            )
            val gateway = gateway(server)

            val result = gateway.currentHandoffEvidence(FULFILLMENT_ID) as
                FulfillmentHandoffEvidenceNetworkOutcome.Evidence
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/handoff-evidence/current",
                request.requestUrl?.encodedPath
            )
            assertEquals(EVIDENCE_ID, result.value.id)
            assertEquals(WAREHOUSE_MEMBERSHIP_ID, result.value.warehouseActorMembershipId)
            assertEquals(null, result.value.dispatchActorMembershipId)
            assertEquals(DRIVER_MEMBERSHIP_ID, result.value.driverMembershipId)
            assertEquals(CHECK_ID, result.value.outgoingGoodsCheckId)
            assertEquals(true, result.value.current)
        }
    }

    @Test
    fun currentHandoffEvidenceAcceptsNewDispatchActorWithoutWarehouseEvidence() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                evidenceResponse(
                    "\"warehouseActorMembershipId\":null,\"dispatchActorMembershipId\":\"$DISPATCH_MEMBERSHIP_ID\""
                )
            )

            val result = gateway(server).currentHandoffEvidence(FULFILLMENT_ID) as
                FulfillmentHandoffEvidenceNetworkOutcome.Evidence

            assertEquals(null, result.value.warehouseActorMembershipId)
            assertEquals(DISPATCH_MEMBERSHIP_ID, result.value.dispatchActorMembershipId)
            assertEquals(DRIVER_MEMBERSHIP_ID, result.value.driverMembershipId)
            assertEquals(CHECK_ID, result.value.outgoingGoodsCheckId)
        }
    }

    @Test
    fun currentHandoffEvidenceRejectsMissingOrMalformedActorIdentity() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                evidenceResponse(
                    "\"warehouseActorMembershipId\":null,\"dispatchActorMembershipId\":null"
                )
            )
            server.enqueue(
                evidenceResponse(
                    "\"warehouseActorMembershipId\":\"not-a-membership-id\"," +
                        "\"dispatchActorMembershipId\":null"
                )
            )

            val gateway = gateway(server)

            assertEquals(
                FulfillmentHandoffEvidenceNetworkOutcome.ServiceUnavailable,
                gateway.currentHandoffEvidence(FULFILLMENT_ID)
            )
            assertEquals(
                FulfillmentHandoffEvidenceNetworkOutcome.ServiceUnavailable,
                gateway.currentHandoffEvidence(FULFILLMENT_ID)
            )
        }
    }

    @Test
    fun dispatchUsesPublishedPreparedFulfillmentTransitionAndValidatesRealDelivery() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("ETag", "\"13\"")
                    .setBody(
                        """{"id":"$FULFILLMENT_ID","status":"HANDED_OVER","version":13,"updatedAt":"2026-09-30T10:15:30Z","deliveryId":"$DELIVERY_ID","deliveryStatus":"READY","deliveryVersion":0}"""
                    )
            )
            val endpoint = ApiEndpoint(server.url("/").toString())
            val gateway = NexaFulfillmentDispatchGateway(
                ProtectedCallExecutor(
                    endpoint,
                    ApiHttpClient.create(endpoint),
                    FakeAccessTokenSource()
                )
            )

            val exactBody = listOf(
                """{"physicalAllocationId":"$ALLOCATION_ID","physicalAllocationVersion":7,""",
                """"driverAssignmentId":"$ASSIGNMENT_ID","driverAssignmentVersion":12,""",
                """"outgoingGoodsCheckId":"$CHECK_ID"}"""
            ).joinToString(separator = "")
            val outcome = gateway.dispatch(FULFILLMENT_ID, 12, "dispatch-key", exactBody) as
                FulfillmentDispatchNetworkOutcome.Dispatched
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/dispatches",
                request.requestUrl?.encodedPath
            )
            assertEquals("\"12\"", request.getHeader("If-Match"))
            assertEquals("dispatch-key", request.getHeader("Idempotency-Key"))
            assertEquals(exactBody, request.body.readUtf8())
            assertEquals(FULFILLMENT_ID, outcome.fulfillment.fulfillmentId)
            assertEquals(DELIVERY_ID, outcome.fulfillment.deliveryId)
            assertEquals("HANDED_OVER", outcome.fulfillment.status)
        }
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

    private fun gateway(server: MockWebServer): NexaFulfillmentDispatchGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaFulfillmentDispatchGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), FakeAccessTokenSource())
        )
    }

    private fun evidenceResponse(actorFields: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .addHeader("ETag", "\"13\"")
        .setBody(
            """{"id":"$EVIDENCE_ID","fulfillmentId":"$FULFILLMENT_ID","fulfillmentVersion":13,"deliveryId":"$DELIVERY_ID",$actorFields,"driverAssignmentId":"$ASSIGNMENT_ID","driverMembershipId":"$DRIVER_MEMBERSHIP_ID","physicalAllocationId":"$ALLOCATION_ID","physicalAllocationVersion":7,"outgoingGoodsCheckId":"$CHECK_ID","occurredAt":"2026-09-30T10:15:30Z","current":true}"""
        )

    private companion object {
        const val FULFILLMENT_ID = "11111111-1111-4111-8111-111111111111"
        const val DELIVERY_ID = "88888888-8888-4888-8888-888888888888"
        const val EVIDENCE_ID = "99999999-9999-4999-8999-999999999999"
        const val WAREHOUSE_MEMBERSHIP_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val DISPATCH_MEMBERSHIP_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val DRIVER_MEMBERSHIP_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val ALLOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val ASSIGNMENT_ID = "33333333-3333-4333-8333-333333333333"
        const val CHECK_ID = "44444444-4444-4444-8444-444444444444"
    }
}
