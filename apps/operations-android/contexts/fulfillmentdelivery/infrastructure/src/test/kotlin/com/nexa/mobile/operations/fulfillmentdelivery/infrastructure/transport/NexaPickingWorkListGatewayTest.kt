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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaPickingWorkListGatewayTest {
    @Test
    fun listsOnlyTypedCurrentFulfillmentReferencesThroughProtectedRoute() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, listJson()))
            val outcome = gateway(server).workList(page = 1, size = 25)

            assertTrue(outcome is PickingWorkListNetworkOutcome.Loaded)
            val value = (outcome as PickingWorkListNetworkOutcome.Loaded).value
            assertEquals(1, value.page)
            assertEquals(25, value.size)
            assertEquals(26L, value.totalItems)
            assertEquals(FULFILLMENT_ID, value.items.single().fulfillmentId)
            assertEquals("PICKING", value.items.single().status)
            assertEquals(8L, value.items.single().allocationVersion)
            val request = server.takeRequest()
            assertEquals("/api/v1/fulfillments?page=1&size=25", request.path)
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertNull(request.getHeader("Idempotency-Key"))
            assertNull(request.getHeader("X-Nexa-Refresh-Token"))
        }
    }

    @Test
    fun malformedRowsAndUnexpectedPageAreRejected() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, listJson(fulfillmentId = "not-a-uuid")))
            server.enqueue(jsonResponse(200, listJson(page = 0)))
            val gateway = gateway(server)

            assertEquals(
                PickingWorkListNetworkOutcome.ServiceUnavailable,
                gateway.workList(page = 1, size = 25)
            )
            assertEquals(
                PickingWorkListNetworkOutcome.ServiceUnavailable,
                gateway.workList(page = 1, size = 25)
            )
        }
    }

    private fun gateway(server: MockWebServer): NexaPickingGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaPickingGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), TestAccessTokenSource())
        )
    }

    private fun listJson(page: Int = 1, fulfillmentId: String = FULFILLMENT_ID) =
        """{"items":[{"fulfillmentId":"$fulfillmentId","salesOrderId":"$SALES_ORDER_ID","status":"PICKING","version":12,"physicalAllocationId":"$ALLOCATION_ID","allocationVersion":8,"lineCount":2}],"page":$page,"size":25,"totalItems":26,"asOf":"2026-09-30T12:00:00Z"}"""

    private fun jsonResponse(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private class TestAccessTokenSource : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)

        override suspend fun currentAccess() =
            AccessTokenLease("access-1", generation = 1, epoch = 1)

        override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease) = observed

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit

        override suspend fun isEpochCurrent(epoch: Long) = epoch == 1L
    }

    private companion object {
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val SALES_ORDER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413002"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413003"
    }
}
