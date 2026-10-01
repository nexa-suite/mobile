package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaStockTransferGatewayTest {
    @Test
    fun postsFrozenBodyWithStableKeyAndExpectedSourceVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            val reason = "move \"fragile\" stock"
            val body = commandBody(reason = reason)
            server.enqueue(jsonResponse(transferJson(reason = reason)))

            val result = gateway(server).createTransfer(body, 17, "transfer-key-1")
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("/api/v1/inventory/transfers", request.requestUrl?.encodedPath)
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertEquals("transfer-key-1", request.getHeader("Idempotency-Key"))
            assertEquals("\"17\"", request.getHeader("If-Match"))
            assertEquals(body, request.body.readUtf8())
            val confirmed = (result as StockTransferNetworkOutcome.Confirmed).transfer
            assertEquals("REQUESTED", confirmed.status)
            assertEquals("1.2300", confirmed.requestedQuantity.toPlainString())
            assertEquals("0", confirmed.transferredQuantity.toPlainString())
            assertEquals(17L, confirmed.sourceVersionBefore)
            assertEquals(null, confirmed.receivedAt)
            assertTrue(!confirmed.toString().contains("1.2300"))
        }
    }

    @Test
    fun mismatchOrTransportFailureNeverLooksLikeTransferConfirmation() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(transferJson(status = "IN_TRANSIT")))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val gateway = gateway(server)

            assertEquals(
                StockTransferNetworkOutcome.UnknownOutcome,
                gateway.createTransfer(commandBody(), 17, "transfer-key-1")
            )
            assertEquals(
                StockTransferNetworkOutcome.UnknownOutcome,
                gateway.createTransfer(commandBody(), 17, "transfer-key-1")
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun staleVersionAndConflictStayExplicitAndInvalidBodyDoesNotCallServer() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(412, "CONCURRENCY_CONFLICT"))
            server.enqueue(problemResponse(409, "IDEMPOTENCY_PAYLOAD_CONFLICT"))
            val gateway = gateway(server)

            assertEquals(
                StockTransferNetworkOutcome.PreconditionFailed,
                gateway.createTransfer(commandBody(), 17, "transfer-key-1")
            )
            assertEquals(
                StockTransferNetworkOutcome.Conflict,
                gateway.createTransfer(commandBody(), 17, "transfer-key-1")
            )
            assertEquals(
                StockTransferNetworkOutcome.Rejected("INVALID_REQUEST"),
                gateway.createTransfer("{}", 17, "transfer-key-1")
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun loadsDestinationTransfersAndPostsOnlyExpectedReceiptWithVersionAndStableKey() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(
                """{"items":[${inTransitTransferJson()}],"page":0,"size":25,"total":1}""",
                status = 200
            ))
            server.enqueue(jsonResponse(receivedTransferJson(), status = 200))
            val gateway = gateway(server)

            val page = gateway.transfersForDestination(DESTINATION_WAREHOUSE_ID)
            val listRequest = server.takeRequest()
            assertEquals("GET", listRequest.method)
            assertEquals(
                "/api/v1/inventory/transfers?destinationWarehouseId=$DESTINATION_WAREHOUSE_ID&page=0&size=25",
                listRequest.path
            )
            val expected = (page as StockTransferLookupNetworkOutcome.Page).items.single()
            assertEquals("1.2300", expected.transferredQuantity.toPlainString())
            assertEquals("IN_TRANSIT", expected.status)

            val result = gateway.receiveTransfer(expected, "receipt-key-1")
            val receiptRequest = server.takeRequest()
            assertEquals("POST", receiptRequest.method)
            assertEquals("/api/v1/inventory/transfers/$TRANSFER_ID/receipts", receiptRequest.requestUrl?.encodedPath)
            assertEquals("receipt-key-1", receiptRequest.getHeader("Idempotency-Key"))
            assertEquals("\"2\"", receiptRequest.getHeader("If-Match"))
            assertEquals("", receiptRequest.body.readUtf8())
            assertEquals("RECEIVED", (result as StockTransferNetworkOutcome.Confirmed).transfer.status)
        }
    }

    private fun gateway(server: MockWebServer): NexaStockTransferGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaStockTransferGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), FakeAccessTokenSource())
        )
    }

    private fun commandBody(reason: String = "relocate stock") =
        """{"sourceLotId":"$SOURCE_LOT_ID","sourceWarehouseId":"$SOURCE_WAREHOUSE_ID","sourceZoneId":"$SOURCE_ZONE_ID","destinationWarehouseId":"$DESTINATION_WAREHOUSE_ID","destinationZoneId":"$DESTINATION_ZONE_ID","skuId":"$SKU_ID","catalogItemId":"CAT-42","quantity":1.2300,"unit":"EA","reason":"${reason.jsonEscaped()}"}"""

    private fun transferJson(
        reason: String = "relocate stock",
        status: String = "REQUESTED"
    ) =
        """{"id":"$TRANSFER_ID","sourceWarehouseId":"$SOURCE_WAREHOUSE_ID","sourceZoneId":"$SOURCE_ZONE_ID","sourceLotId":"$SOURCE_LOT_ID","destinationWarehouseId":"$DESTINATION_WAREHOUSE_ID","destinationZoneId":"$DESTINATION_ZONE_ID","destinationLotId":null,"skuId":"$SKU_ID","catalogItemId":"CAT-42","batchNumber":"LOT-17","expirationDate":"2027-02-15","requestedQuantity":1.2300,"transferredQuantity":0,"unit":"EA","mode":"PARTIAL","status":"$status","reason":"${reason.jsonEscaped()}","sourceVersionBefore":17,"sourceVersionAfter":null,"destinationVersionAfter":null,"version":0,"dispatchedAt":null,"receivedAt":null} """.trim()

    private fun inTransitTransferJson() = transferJson()
        .replace("\"status\":\"REQUESTED\"", "\"status\":\"IN_TRANSIT\"")
        .replace("\"transferredQuantity\":0", "\"transferredQuantity\":1.2300")
        .replace("\"sourceVersionAfter\":null", "\"sourceVersionAfter\":18")
        .replace("\"version\":0", "\"version\":2")
        .replace("\"dispatchedAt\":null", "\"dispatchedAt\":\"2026-09-30T10:00:00Z\"")

    private fun receivedTransferJson() = inTransitTransferJson()
        .replace("\"status\":\"IN_TRANSIT\"", "\"status\":\"RECEIVED\"")
        .replace("\"destinationLotId\":null", "\"destinationLotId\":\"d8c24a46-57d9-4f64-8fa7-6a641b413401\"")
        .replace("\"destinationVersionAfter\":null", "\"destinationVersionAfter\":7")
        .replace("\"version\":2", "\"version\":3")
        .replace("\"receivedAt\":null", "\"receivedAt\":\"2026-09-30T10:30:00Z\"")

    private fun String.jsonEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun jsonResponse(body: String, status: Int = 201) = MockResponse()
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
        override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }

    private companion object {
        const val SOURCE_LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val SOURCE_WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val SOURCE_ZONE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val DESTINATION_WAREHOUSE_ID = "a8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val DESTINATION_ZONE_ID = "a8c24a46-57d9-4f64-8fa7-6a641b413202"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413301"
        const val TRANSFER_ID = "c8c24a46-57d9-4f64-8fa7-6a641b413401"
    }
}
