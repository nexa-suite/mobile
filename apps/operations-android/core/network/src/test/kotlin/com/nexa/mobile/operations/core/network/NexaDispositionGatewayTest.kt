package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NexaDispositionGatewayTest {
    @Test
    fun currentLotReadKeepsServerDecimalScaleAndVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(lotJson(version = 17)))
            val result = gateway(server).lot(LOT_ID)
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/api/v1/inventory/lots/$LOT_ID", request.requestUrl?.encodedPath)
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            val facts = (result as DispositionNetworkOutcome.Lot).item
            assertEquals("12.500", facts.onHand.toPlainString())
            assertEquals("2.250", facts.reserved.toPlainString())
            assertEquals("10.250", facts.available.toPlainString())
            assertEquals(17L, facts.version)
            assertFalse(facts.toString().contains("12.500"))
        }
    }

    @Test
    fun dispositionUsesTypedRouteExactBodyStableKeyAndCurrentVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(lotJson(version = 18, status = "DEPLETED")))
            val result = gateway(server).dispose(
                lotId = LOT_ID,
                disposition = "WASTE",
                reason = "seal broken",
                expectedVersion = 17,
                idempotencyKey = "stable-intent-123"
            )
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/inventory/lots/$LOT_ID/dispositions",
                request.requestUrl?.encodedPath
            )
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertEquals("stable-intent-123", request.getHeader("Idempotency-Key"))
            assertEquals("\"17\"", request.getHeader("If-Match"))
            assertEquals(
                """{"disposition":"WASTE","reason":"seal broken"}""",
                request.body.readUtf8()
            )
            val confirmed = (result as DispositionNetworkOutcome.Confirmed).item
            assertEquals(18L, confirmed.version)
            assertEquals("DEPLETED", confirmed.status)
            assertEquals("12.500", confirmed.onHand.toPlainString())
        }
    }

    @Test
    fun partialDispositionSendsExactNumericQuantityEvaluationAndCurrentVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(lotJson(version = 22, status = "AVAILABLE")))

            val result = gateway(server).dispose(
                lotId = LOT_ID,
                disposition = "HOLD",
                reason = "temperature excursion",
                expectedVersion = 21,
                idempotencyKey = "partial-intent-21",
                affectedQuantity = BigDecimal("1.2300"),
                temperatureEvaluationId = EVALUATION_ID
            )
            val request = server.takeRequest()

            assertEquals("partial-intent-21", request.getHeader("Idempotency-Key"))
            assertEquals("\"21\"", request.getHeader("If-Match"))
            assertEquals(
                """{"disposition":"HOLD","reason":"temperature excursion","affectedQuantity":1.2300,"temperatureEvaluationId":"$EVALUATION_ID"}""",
                request.body.readUtf8()
            )
            val confirmed = (result as DispositionNetworkOutcome.Confirmed).item
            assertEquals("AVAILABLE", confirmed.status)
            assertEquals(22L, confirmed.version)
        }
    }

    @Test
    fun partialDispositionRejectsIncompleteOrInvalidEvaluationBeforeHttp() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = gateway(server)

            assertEquals(
                DispositionNetworkOutcome.Rejected("INVALID_REQUEST"),
                gateway.dispose(
                    LOT_ID,
                    "HOLD",
                    "temperature excursion",
                    21,
                    "partial-key",
                    affectedQuantity = BigDecimal("1.0")
                )
            )
            assertEquals(
                DispositionNetworkOutcome.Rejected("INVALID_REQUEST"),
                gateway.dispose(
                    LOT_ID,
                    "HOLD",
                    "temperature excursion",
                    21,
                    "partial-key",
                    affectedQuantity = BigDecimal("0.00001"),
                    temperatureEvaluationId = EVALUATION_ID
                )
            )
            assertEquals(
                DispositionNetworkOutcome.Rejected("INVALID_REQUEST"),
                gateway.dispose(
                    LOT_ID,
                    "HOLD",
                    "temperature excursion",
                    21,
                    "partial-key",
                    affectedQuantity = BigDecimal("1.0"),
                    temperatureEvaluationId = "not-a-uuid"
                )
            )
            assertEquals(
                DispositionNetworkOutcome.Rejected("INVALID_REQUEST"),
                gateway.dispose(
                    LOT_ID,
                    "HOLD",
                    "temperature excursion",
                    21,
                    "partial-key",
                    affectedQuantity = BigDecimal("1000000000000000.0000"),
                    temperatureEvaluationId = EVALUATION_ID
                )
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun preconditionConflictAndValidationRemainDistinct() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(412, "CONCURRENCY_CONFLICT"))
            server.enqueue(problemResponse(409, "IDEMPOTENCY_PAYLOAD_CONFLICT"))
            server.enqueue(problemResponse(422, "INVALID_REQUEST"))
            val gateway = gateway(server)

            assertEquals(
                DispositionNetworkOutcome.PreconditionFailed,
                gateway.dispose(command(), "same-key")
            )
            assertEquals(DispositionNetworkOutcome.Conflict, gateway.dispose(command(), "same-key"))
            assertEquals(
                DispositionNetworkOutcome.Rejected("INVALID_REQUEST"),
                gateway.dispose(command(reason = ""), "same-key")
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun ambiguousSocketFailureNeverLooksConfirmed() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val gateway = gateway(server)

            assertEquals(
                DispositionNetworkOutcome.UnknownOutcome,
                gateway.dispose(command(), "same-key")
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun malformedLotProjectionFailsClosed() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse("""{"id":"$LOT_ID","version":19}"""))

            assertEquals(DispositionNetworkOutcome.ServiceUnavailable, gateway(server).lot(LOT_ID))
        }
    }

    @Test
    fun invalidLotPathIsRejectedBeforeHttp() = runTest {
        MockWebServer().use { server ->
            server.start()
            assertEquals(
                DispositionNetworkOutcome.ServiceUnavailable,
                gateway(server).lot("lot/../foreign")
            )
            assertEquals(0, server.requestCount)
        }
    }

    private fun gateway(server: MockWebServer): NexaDispositionGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaDispositionGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), FakeAccessTokenSource())
        )
    }

    private fun command(reason: String = "seal broken") = Triple(LOT_ID, "WASTE", reason)

    private suspend fun NexaDispositionGateway.dispose(
        command: Triple<String, String, String>,
        key: String
    ) = dispose(command.first, command.second, command.third, 17, key)

    private fun lotJson(version: Int, status: String = "HOLD") =
        """{"id":"$LOT_ID","warehouseId":"$WAREHOUSE_ID","zoneId":"$ZONE_ID","catalogItemId":"CAT-42","skuId":"$SKU_ID","batchNumber":"LOT-17","expirationDate":"2027-02-15","receivedAt":"2026-09-29T18:00:00Z","onHand":12.500,"reserved":2.250,"available":10.250,"unit":"EA","status":"$status","version":$version}"""

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
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val ZONE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413301"
        const val EVALUATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
    }
}
