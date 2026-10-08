package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaPickingGatewayTest {
    @Test
    fun loadsFulfillmentAndExactAllocationProjectionWithServerVersions() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, fulfillmentJson(12)).setHeader("ETag", "\"12\""))
            server.enqueue(jsonResponse(200, allocationJson()).setHeader("ETag", "\"8\""))
            val gateway = gateway(server)

            val fulfillment = gateway.fulfillment(
                FULFILLMENT_ID
            ) as PickingNetworkOutcome.Fulfillment
            val allocation = gateway.allocation(FULFILLMENT_ID) as PickingNetworkOutcome.Allocation

            assertEquals(12L, fulfillment.value.version)
            assertEquals("PICKING", fulfillment.value.status)
            assertEquals(BigDecimal("3.500"), fulfillment.value.lines.single().remainingQuantity)
            assertEquals(8L, allocation.value.version)
            assertEquals("ALLOCATED", allocation.value.status)
            assertEquals("0.500", allocation.value.lines.single().remainingQuantity.toPlainString())
            assertEquals("2027-06-30", allocation.value.lines.single().expirationDate.toString())
            assertEquals("/api/v1/fulfillments/$FULFILLMENT_ID", server.takeRequest().path)
            val allocationRequest = server.takeRequest()
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/physical-allocation",
                allocationRequest.path
            )
            assertEquals("Bearer access-1", allocationRequest.getHeader("Authorization"))
            assertNull(allocationRequest.getHeader("Idempotency-Key"))
            assertNull(allocationRequest.getHeader("X-Nexa-Client"))
            assertNull(allocationRequest.getHeader("X-Nexa-Surface"))
            assertNull(allocationRequest.getHeader("X-Nexa-Refresh-Token"))
        }
    }

    @Test
    fun confirmationSendsCurrentVersionsExactLotRefsAndUnroundedNumericQuantity() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, fulfillmentJson(13)).setHeader("ETag", "\"13\""))
            val request = command(quantity = "0.0100")

            val result = gateway(server).confirmPicking(request, "pick-intent-13")
            assertTrue(result is PickingNetworkOutcome.Updated)
            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/picking-confirmations",
                sent.path
            )
            assertEquals("\"12\"", sent.getHeader("If-Match"))
            assertEquals("pick-intent-13", sent.getHeader("Idempotency-Key"))
            val body = sent.body.readUtf8()
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("8", json.getValue("allocationVersion").jsonPrimitive.content)
            val line = json.getValue("lines").let { it as kotlinx.serialization.json.JsonArray }
                .single().jsonObject
            assertEquals(
                FULFILLMENT_LINE_ID,
                line.getValue("fulfillmentLineId").jsonPrimitive.content
            )
            assertEquals(LOT_ID, line.getValue("lotId").jsonPrimitive.content)
            assertEquals(WAREHOUSE_ID, line.getValue("warehouseId").jsonPrimitive.content)
            assertEquals(
                PHYSICAL_ALLOCATION_LINE_ID,
                line.getValue("physicalAllocationLineId").jsonPrimitive.content
            )
            assertFalse(line.getValue("quantity").jsonPrimitive.isString)
            assertEquals("0.0100", line.getValue("quantity").jsonPrimitive.content)
            assertFalse(body.contains("fefoOverride"))
            assertFalse(body.contains("fefoOverrideReason"))
        }
    }

    @Test
    fun sameKeyUnauthorizedReplayUsesByteIdenticalPayloadAndIfMatch() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(401, "ACCESS_TOKEN_INVALID"))
            server.enqueue(jsonResponse(200, fulfillmentJson(13)).setHeader("ETag", "\"13\""))
            val source = FakeAccessTokenSource()
            val request = command(quantity = "1.2500")

            assertTrue(
                gateway(
                    server,
                    source
                ).confirmPicking(request, "pick-same-identity") is PickingNetworkOutcome.Updated
            )
            val first = server.takeRequest()
            val replay = server.takeRequest()
            assertEquals(1, source.recoverCount)
            assertEquals("pick-same-identity", first.getHeader("Idempotency-Key"))
            assertEquals(first.getHeader("Idempotency-Key"), replay.getHeader("Idempotency-Key"))
            assertEquals("\"12\"", first.getHeader("If-Match"))
            assertEquals(first.getHeader("If-Match"), replay.getHeader("If-Match"))
            assertEquals(first.body.readUtf8(), replay.body.readUtf8())
            assertEquals("Bearer access-1", first.getHeader("Authorization"))
            assertEquals("Bearer access-2", replay.getHeader("Authorization"))
        }
    }

    @Test
    fun changedPayloadConflictAndStaleVersionRemainKnownOutcomes() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(409, "IDEMPOTENCY_PAYLOAD_CONFLICT"))
            server.enqueue(problemResponse(412, "FULFILLMENT_VERSION_STALE"))
            val gateway = gateway(server)

            assertEquals(
                PickingNetworkOutcome.Rejected("IDEMPOTENCY_PAYLOAD_CONFLICT"),
                gateway.confirmPicking(command(quantity = "0.0200"), "same-key")
            )
            assertEquals(
                PickingNetworkOutcome.StaleVersion,
                gateway.confirmPicking(command(quantity = "0.0100"), "new-decision-key")
            )
            val changed = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val changedLine = changed.getValue("lines").let {
                it as kotlinx.serialization.json.JsonArray
            }.single().jsonObject
            assertFalse(changedLine.getValue("quantity").jsonPrimitive.isString)
            assertEquals("0.0200", changedLine.getValue("quantity").jsonPrimitive.content)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun startPickingUsesItsCallerOwnedIdentityAndExpectedFulfillmentVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, fulfillmentJson(13)).setHeader("ETag", "\"13\""))

            assertTrue(
                gateway(server).startPicking(FULFILLMENT_ID, 12, "start-picking-1") is
                    PickingNetworkOutcome.Updated
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/fulfillments/$FULFILLMENT_ID/picking-starts", request.path)
            assertEquals("\"12\"", request.getHeader("If-Match"))
            assertEquals("start-picking-1", request.getHeader("Idempotency-Key"))
            assertEquals("", request.body.readUtf8())
        }
    }

    @Test
    fun malformedOrInconsistentAllocationProjectionNeverBecomesAnOffer() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(200, allocationJson(remaining = "99.000")).setHeader("ETag", "\"8\"")
            )

            assertEquals(
                PickingNetworkOutcome.ServiceUnavailable,
                gateway(server).allocation(FULFILLMENT_ID)
            )
        }
    }

    private fun gateway(
        server: MockWebServer,
        source: FakeAccessTokenSource = FakeAccessTokenSource()
    ): NexaPickingGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaPickingGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), source)
        )
    }

    private fun command(quantity: String) = PickingConfirmationRequest(
        fulfillmentId = FULFILLMENT_ID,
        expectedFulfillmentVersion = 12,
        allocationVersion = 8,
        fulfillmentLineId = FULFILLMENT_LINE_ID,
        skuId = SKU_ID,
        physicalAllocationLineId = PHYSICAL_ALLOCATION_LINE_ID,
        lotId = LOT_ID,
        warehouseId = WAREHOUSE_ID,
        quantity = BigDecimal(quantity),
        unit = "KG"
    )

    private fun fulfillmentJson(version: Long) =
        """{"id":"$FULFILLMENT_ID","status":"PICKING","version":$version,"lines":[{"id":"$FULFILLMENT_LINE_ID","skuId":"$SKU_ID","catalogItemId":"CAT-0017","allocatedQuantity":4.000,"pickedQuantity":0.500,"remainingQuantity":3.500,"unit":"KG"}]}"""

    private fun allocationJson(remaining: String = "0.500") =
        """{"allocationId":"$ALLOCATION_ID","status":"ALLOCATED","version":8,"asOf":"2026-09-30T15:00:00Z","lines":[{"physicalAllocationLineId":"$PHYSICAL_ALLOCATION_LINE_ID","skuId":"$SKU_ID","catalogItemId":"CAT-0017","warehouseId":"$WAREHOUSE_ID","zoneId":null,"lotId":"$LOT_ID","quantity":1.000,"releasedQuantity":0.250,"consumedQuantity":0.250,"remainingQuantity":$remaining,"unit":"KG","expirationDate":"2027-06-30"}]}"""

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
        private var lease = AccessTokenLease("access-1", generation = 1, epoch = 1)
        var recoverCount = 0

        override suspend fun currentAccess(): AccessTokenLease = lease

        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease {
            recoverCount++
            lease = AccessTokenLease("access-2", generation = 2, epoch = observed.epoch)
            return lease
        }

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit

        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }

    private companion object {
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val FULFILLMENT_LINE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413002"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413003"
        const val PHYSICAL_ALLOCATION_LINE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413004"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413005"
        const val WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413006"
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413007"
    }
}
