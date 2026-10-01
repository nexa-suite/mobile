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
import java.math.BigDecimal
import java.time.Instant

class NexaFulfillmentTemperatureGatewayTest {
    @Test
    fun currentUsesVersionedSkuPolicyAndKeepsFulfillmentTemperatureOptional() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("ETag", "\"8\"")
                    .setBody(
                        """{"fulfillmentId":"$FULFILLMENT_ID","fulfillmentStatus":"READY_FOR_DISPATCH","fulfillmentVersion":8,"physicalAllocationId":"$ALLOCATION_ID","physicalAllocationVersion":4,"temperatureRequiredForFulfillment":false,"asOf":"2026-10-01T10:00:00Z","lots":[{"skuId":"$SKU_ID","lotId":"$LOT_ID","warehouseId":"$WAREHOUSE_ID","zoneId":"$ZONE_ID","skuColdChainRequired":true,"requiredForFulfillment":false,"minimumCelsius":2.0,"maximumCelsius":8.0,"status":"OPTIONAL_NOT_RECORDED","latestEvidence":null}]}"""
                    )
            )
            val gateway = NexaFulfillmentTemperatureGateway(executor(server))

            val result = gateway.current(FULFILLMENT_ID) as FulfillmentTemperatureNetworkOutcome.Current
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/temperature-evidence/current",
                request.requestUrl?.encodedPath
            )
            assertEquals(8L, result.readiness.fulfillmentVersion)
            assertEquals(false, result.readiness.temperatureRequiredForFulfillment)
            assertEquals(false, result.readiness.lots.single().requiredForFulfillment)
            assertEquals(BigDecimal("2.0"), result.readiness.lots.single().minimumCelsius)
        }
    }

    @Test
    fun recordSendsExactCurrentVersionAndMapsOutsideRangeContractGap() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .addHeader("Content-Type", "application/problem+json")
                    .setBody(
                        """{"status":409,"code":"TEMPERATURE_OUT_OF_RANGE_BACKEND_CONTRACT_GAP","category":"CONFLICT","title":"Not recorded"}"""
                    )
            )
            val gateway = NexaFulfillmentTemperatureGateway(executor(server))
            val occurredAt = Instant.parse("2026-10-01T10:15:30Z")

            val result = gateway.record(
                FULFILLMENT_ID,
                8,
                LOT_ID,
                BigDecimal("9.5"),
                occurredAt,
                "temperature-command-key",
                "{\"lotId\":\"$LOT_ID\",\"value\":9.5,\"unit\":\"CELSIUS\",\"occurredAt\":\"$occurredAt\"}"
            )
            val request = server.takeRequest()

            assertEquals(FulfillmentTemperatureNetworkOutcome.OutsideRangeBackendContractGap, result)
            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/temperature-evidence",
                request.requestUrl?.encodedPath
            )
            assertEquals("\"8\"", request.getHeader("If-Match"))
            assertEquals("temperature-command-key", request.getHeader("Idempotency-Key"))
            assertEquals(
                "{\"lotId\":\"$LOT_ID\",\"value\":9.5,\"unit\":\"CELSIUS\",\"occurredAt\":\"$occurredAt\"}",
                request.body.readUtf8()
            )
        }
    }

    @Test
    fun recordRejectsEvidenceThatDoesNotMatchPreparedFulfillmentVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("ETag", "\"9\"")
                    .setBody(
                        """{"id":"$EVIDENCE_ID","subjectType":"FULFILLMENT","subjectId":"$FULFILLMENT_ID","lotId":"$LOT_ID","warehouseId":"$WAREHOUSE_ID","value":5.0,"unit":"CELSIUS","occurredAt":"2026-10-01T10:15:30Z","actorMembershipId":"$ACTOR_ID","status":"WITHIN_RANGE","source":"MANUAL","fulfillmentVersion":9}"""
                    )
            )
            val gateway = NexaFulfillmentTemperatureGateway(executor(server))

            val result = gateway.record(
                FULFILLMENT_ID,
                8,
                LOT_ID,
                BigDecimal("5.0"),
                Instant.parse("2026-10-01T10:15:30Z"),
                "temperature-command-key",
                "{\"lotId\":\"$LOT_ID\",\"value\":5,\"unit\":\"CELSIUS\",\"occurredAt\":\"2026-10-01T10:15:30Z\"}"
            )

            assertEquals(FulfillmentTemperatureNetworkOutcome.UnknownOutcome, result)
            assertTrue(server.takeRequest().path!!.endsWith("/temperature-evidence"))
        }
    }

    private fun executor(server: MockWebServer) = ProtectedCallExecutor(
        ApiEndpoint(server.url("/").toString()),
        ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
        FakeAccessTokenSource()
    )

    private class FakeAccessTokenSource : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("access-1", generation = 1, epoch = 1)

        override suspend fun currentAccess(): AccessTokenLease = lease
        override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }

    private companion object {
        const val FULFILLMENT_ID = "11111111-1111-4111-8111-111111111111"
        const val ALLOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val SKU_ID = "33333333-3333-4333-8333-333333333333"
        const val LOT_ID = "44444444-4444-4444-8444-444444444444"
        const val WAREHOUSE_ID = "55555555-5555-4555-8555-555555555555"
        const val ZONE_ID = "66666666-6666-4666-8666-666666666666"
        const val EVIDENCE_ID = "77777777-7777-4777-8777-777777777777"
        const val ACTOR_ID = "88888888-8888-4888-8888-888888888888"
    }
}
