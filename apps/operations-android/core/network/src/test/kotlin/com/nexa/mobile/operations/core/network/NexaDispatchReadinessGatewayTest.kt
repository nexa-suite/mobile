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

class NexaDispatchReadinessGatewayTest {
    @Test
    fun listUsesProtectedCurrentRouteAndPreservesServerReadinessFacts() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(page(readinessJson())))

            val result = gateway(server).list() as DispatchReadinessNetworkOutcome.ListResult
            val request = server.takeRequest()
            val item = result.items.single()

            assertEquals("GET", request.method)
            assertEquals("/api/v1/dispatch-readiness", request.requestUrl?.encodedPath)
            assertEquals("0", request.requestUrl?.queryParameter("page"))
            assertEquals("100", request.requestUrl?.queryParameter("size"))
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertEquals(FULFILLMENT_ID, item.fulfillmentId)
            assertEquals(12L, item.fulfillmentVersion)
            assertEquals(7L, item.physicalAllocationVersion)
            assertEquals("PREPARED_FULFILLMENT", item.subjectKind)
            assertTrue(item.ready)
            assertTrue(item.allocationComplete)
            assertTrue(item.pickingComplete)
            assertTrue(item.pickingEvidenceComplete)
            assertEquals("3.50", item.lines.single().evidencedPickedQuantity.toPlainString())
            assertEquals("2026-09-30T10:15:30Z", item.asOf.toString())
            assertEquals(item.asOf, result.asOf)
            assertFalse(item.toString().contains("3.50"))
        }
    }

    @Test
    fun listPaginatesAndRejectsAChangedTotalBetweenReads() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(page(readinessJson(), total = 2)))
            server.enqueue(
                jsonResponse(
                    page(readinessJson(OTHER_FULFILLMENT_ID), page = 1, total = 2)
                )
            )
            server.enqueue(jsonResponse(page(readinessJson(), total = 2)))
            server.enqueue(jsonResponse(page(readinessJson(), page = 1, total = 3)))
            val gateway = gateway(server)

            val pageOne = gateway.list() as DispatchReadinessNetworkOutcome.ListResult
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("0", first.requestUrl?.queryParameter("page"))
            assertEquals("1", second.requestUrl?.queryParameter("page"))
            assertEquals(2, pageOne.items.size)
            assertEquals(
                DispatchReadinessNetworkOutcome.ServiceUnavailable,
                gateway.list()
            )
        }
    }

    @Test
    fun detailRequiresTheExactPreparedFulfillmentIdentity() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(readinessJson()))
            server.enqueue(jsonResponse(readinessJson(OTHER_FULFILLMENT_ID)))
            val gateway = gateway(server)

            val detail = gateway.detail(FULFILLMENT_ID) as
                DispatchReadinessNetworkOutcome.Detail
            val request = server.takeRequest()
            assertEquals(
                "/api/v1/dispatch-readiness/$FULFILLMENT_ID",
                request.requestUrl?.encodedPath
            )
            assertEquals(FULFILLMENT_ID, detail.item.fulfillmentId)
            assertEquals(
                DispatchReadinessNetworkOutcome.ServiceUnavailable,
                gateway.detail(FULFILLMENT_ID)
            )
        }
    }

    @Test
    fun authorizationAndCurrentContextFailuresRemainDistinct() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(403, "ACCESS_DENIED"))
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))
            server.enqueue(problemResponse(404, "FULFILLMENT_NOT_FOUND"))
            val gateway = gateway(server)

            assertEquals(DispatchReadinessNetworkOutcome.PermissionDenied, gateway.list())
            assertEquals(DispatchReadinessNetworkOutcome.ContextInvalidated, gateway.list())
            assertEquals(
                DispatchReadinessNetworkOutcome.PermissionDenied,
                gateway.detail(FULFILLMENT_ID)
            )
        }
    }

    private fun gateway(server: MockWebServer): NexaDispatchReadinessGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaDispatchReadinessGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), FakeAccessTokenSource())
        )
    }

    private fun page(item: String, page: Int = 0, total: Int = 1) =
        """{"items":[$item],"page":$page,"size":100,"totalItems":$total,"asOf":"2026-09-30T10:15:30Z"}"""

    private fun readinessJson(id: String = FULFILLMENT_ID) =
        """{"subjectKind":"PREPARED_FULFILLMENT","fulfillmentId":"$id","fulfillmentVersion":12,"fulfillmentStatus":"READY_FOR_DISPATCH","physicalAllocationId":"$ALLOCATION_ID","physicalAllocationStatus":"ALLOCATED","physicalAllocationVersion":7,"deliveryId":null,"deliveryStatus":null,"deliveryVersion":null,"allocationComplete":true,"pickingComplete":true,"pickingEvidenceComplete":true,"ready":true,"reasons":[],"lines":[{"fulfillmentLineId":"$LINE_ID","skuId":"$SKU_ID","catalogItemId":"CAT-42","allocatedQuantity":3.50,"physicallyAllocatedQuantity":3.50,"pickedQuantity":3.50,"evidencedPickedQuantity":3.50,"allocationComplete":true,"pickingComplete":true,"evidenceComplete":true}],"asOf":"2026-09-30T10:15:30Z"}"""

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
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
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val OTHER_FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413002"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val LINE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413301"
    }
}
