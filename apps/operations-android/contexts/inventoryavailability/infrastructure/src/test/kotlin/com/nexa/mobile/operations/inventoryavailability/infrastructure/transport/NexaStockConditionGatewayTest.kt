package com.nexa.mobile.operations.inventoryavailability.infrastructure.transport

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
import org.junit.Test

class NexaStockConditionGatewayTest {
    @Test fun lotListUsesAuthorizedLotRouteAndKeepsPhysicalQuantityNamesDistinct() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(page(lotJson())))
            val result = gateway(server).lots()
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/api/v1/inventory/lots", request.requestUrl?.encodedPath)
            assertEquals("0", request.requestUrl?.queryParameter("page"))
            assertEquals("100", request.requestUrl?.queryParameter("size"))
            assertEquals("expirationDate,asc", request.requestUrl?.queryParameter("sort"))
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            val item = (result as StockConditionNetworkOutcome.Lots).items.single()
            assertEquals("LOT-1", item.batchNumber)
            assertEquals("12.50", item.onHand.toPlainString())
            assertEquals("2.25", item.reserved.toPlainString())
            assertEquals("10.25", item.physicalRemaining.toPlainString())
            assertEquals("QUARANTINED", item.status)
            assertFalse(item.toString().contains("12.50"))
            assertFalse(request.path.orEmpty().contains("inventory-availability"))
        }
    }

    @Test fun detailRequiresExactRequestedLotIdentity() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(lotJson()))
            server.enqueue(jsonResponse(lotJson().replace(LOT_ID, OTHER_LOT_ID)))
            val gateway = gateway(server)

            val matching = gateway.lot(LOT_ID) as StockConditionNetworkOutcome.Lot
            val request = server.takeRequest()
            assertEquals("/api/v1/inventory/lots/$LOT_ID", request.requestUrl?.encodedPath)
            assertEquals(LOT_ID, matching.item.id)
            assertEquals(
                StockConditionNetworkOutcome.ServiceUnavailable,
                gateway.lot(LOT_ID)
            )
        }
    }

    @Test fun sellableProjectionUsesCurrentWarehouseEndpointAndExactCatalogId() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    """[{"catalogItemId":"CAT-42","status":"AVAILABLE","asOf":"2026-09-30T10:15:30Z","physicalQuantity":24.50,"safetyStock":4.00,"sellableQuantity":20.50}]"""
                )
            )
            val result = gateway(server).availability(WAREHOUSE_ID, "CAT-42")
            val request = server.takeRequest()

            assertEquals(
                "/api/v1/warehouses/$WAREHOUSE_ID/inventory-availability",
                request.requestUrl?.encodedPath
            )
            assertEquals("CAT-42", request.requestUrl?.queryParameter("catalogItemId"))
            assertFalse(request.path.orEmpty().startsWith("/api/v1/inventory-availability"))
            val item = (result as StockConditionNetworkOutcome.Availability).item
                ?: error("scoped availability projection expected")
            assertEquals("CAT-42", item.catalogItemId)
            assertEquals("20.50", item.sellableQuantity?.toPlainString())
            assertEquals("2026-09-30T10:15:30Z", item.asOf.toString())
        }
    }

    @Test fun foreignCatalogProjectionAndIncompleteLotPagesFailClosed() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    """[{"catalogItemId":"CAT-OTHER","status":"AVAILABLE","asOf":"2026-09-30T10:15:30Z","sellableQuantity":2}]"""
                )
            )
            server.enqueue(jsonResponse(page(lotJson(), page = 1)))
            val gateway = gateway(server)

            assertEquals(
                StockConditionNetworkOutcome.ServiceUnavailable,
                gateway.availability(WAREHOUSE_ID, "CAT-42")
            )
            assertEquals(StockConditionNetworkOutcome.ServiceUnavailable, gateway.lots())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun accessDenialAndContextInvalidationRemainSeparate() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(403, "ACCESS_DENIED"))
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))
            server.enqueue(problemResponse(404, "RESOURCE_NOT_FOUND"))
            val gateway = gateway(server)

            assertEquals(StockConditionNetworkOutcome.PermissionDenied, gateway.lots())
            assertEquals(StockConditionNetworkOutcome.ContextInvalidated, gateway.lots())
            assertEquals(
                StockConditionNetworkOutcome.PermissionDenied,
                gateway.lot(LOT_ID)
            )
        }
    }

    private fun gateway(server: MockWebServer) = NexaStockConditionGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeAccessTokenSource()
        )
    )

    private fun page(item: String, page: Int = 0) =
        """{"items":[$item],"page":$page,"size":100,"total":1}"""

    private fun lotJson() =
        """{"id":"$LOT_ID","warehouseId":"$WAREHOUSE_ID","zoneId":"$ZONE_ID","catalogItemId":"CAT-42","skuId":"$SKU_ID","batchNumber":"LOT-1","expirationDate":"2027-02-15","receivedAt":"2026-09-29T18:00:00Z","onHand":12.50,"reserved":2.25,"available":10.25,"unit":"EA","status":"QUARANTINED","version":4}"""

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
        const val WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val ZONE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413301"
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val OTHER_LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413402"
    }
}
