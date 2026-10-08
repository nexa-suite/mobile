package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaExecutionTemperatureGatewayTest {
    @Test
    fun excursionPostUsesFrozenSourceEvidenceAndStrongDeliveryPrecondition() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"8\"").setBody(readingResponse())
            )
            val body = readingBody()
            val command = ExecutionTemperatureReadingTransportCommand(
                LINE_ID,
                SKU_ID,
                BigDecimal.ONE,
                BigDecimal("-5"),
                CAPTURED_AT,
                INCIDENT_ID,
                EVIDENCE_ID
            )

            val result = gateway(server).record(DELIVERY_ID, 7, "reading-key", body, command)

            assertTrue(result is ExecutionTemperatureNetworkOutcome.ReadingRecorded)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/driver/deliveries/$DELIVERY_ID/execution-temperature-readings",
                request.path
            )
            assertEquals("\"7\"", request.getHeader("If-Match"))
            assertEquals("reading-key", request.getHeader("Idempotency-Key"))
            assertEquals(body, request.body.readUtf8())
        }
    }

    @Test
    fun weakOrWrongSnapshotEtagIsRejectedBeforeAuthorityProjectionIsExposed() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(
                    200
                ).setHeader("ETag", "W/\"7\"").setBody(snapshotResponse())
            )
            val result = gateway(server).current(DELIVERY_ID, driverMode = true)
            assertEquals(ExecutionTemperatureNetworkOutcome.ServiceUnavailable, result)
            assertEquals(
                "/api/v1/driver/deliveries/$DELIVERY_ID/execution-temperature-readings",
                server.takeRequest().path
            )
        }
    }

    @Test
    fun internalDispositionUsesExplicitHoldRouteAndCurrentDeliveryVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"9\"").setBody(dispositionResponse())
            )
            val body = """{"disposition":"RELEASE","reason":"temperature verified"}"""
            val result = gateway(
                server
            ).dispose(DELIVERY_ID, HOLD_ID, 8, "dispose-key", body, "RELEASE")
            assertTrue(result is ExecutionTemperatureNetworkOutcome.Disposed)
            val request = server.takeRequest()
            assertEquals(
                "/api/v1/deliveries/$DELIVERY_ID/execution-holds/$HOLD_ID/dispositions",
                request.path
            )
            assertEquals("\"8\"", request.getHeader("If-Match"))
            assertEquals("dispose-key", request.getHeader("Idempotency-Key"))
            assertEquals(body, request.body.readUtf8())
        }
    }

    private fun gateway(server: MockWebServer): NexaExecutionTemperatureGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaExecutionTemperatureGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), FakeTokens())
        )
    }

    private fun snapshotResponse() = """{"deliveryId":"$DELIVERY_ID","deliveryVersion":7,
        "deliveryStatus":"IN_TRANSIT","attemptId":"$ATTEMPT_ID","originWarehouseId":"$WAREHOUSE_ID",
        "lines":[{"fulfillmentLineId":"$LINE_ID","skuId":"$SKU_ID","unit":"EA","remainingQuantity":5,
        "coldChainRequired":true,"minimumCelsius":2,"maximumCelsius":8}],"holds":[]}"""

    private fun readingBody() =
        """{"fulfillmentLineId":"$LINE_ID","skuId":"$SKU_ID","affectedQuantity":1,"value":-5,"unit":"CELSIUS","occurredAt":"$CAPTURED_AT","sourceIncidentId":"$INCIDENT_ID","evidenceObjectId":"$EVIDENCE_ID"}"""

    private fun readingResponse() =
        """{"id":"$READING_ID","deliveryId":"$DELIVERY_ID","attemptId":"$ATTEMPT_ID",
        "fulfillmentLineId":"$LINE_ID","skuId":"$SKU_ID","affectedQuantity":1,"quantityUnit":"EA",
        "valueCelsius":-5,"temperatureUnit":"CELSIUS","minimumCelsius":2,"maximumCelsius":8,
        "status":"OUT_OF_RANGE","actorMembershipId":"$MEMBERSHIP_ID","occurredAt":"$CAPTURED_AT",
        "recordedAt":"$RECORDED_AT","evidenceObjectId":"$EVIDENCE_ID","sourceIncidentId":"$INCIDENT_ID",
        "hold":{"id":"$HOLD_ID","readingId":"$READING_ID","exceptionId":"$EXCEPTION_ID",
        "fulfillmentLineId":"$LINE_ID","skuId":"$SKU_ID","affectedQuantity":1,"quantityUnit":"EA",
        "status":"HELD","reportedByMembershipId":"$MEMBERSHIP_ID","reportedAt":"$RECORDED_AT",
        "disposition":null,"authorizedByMembershipId":null,"disposedAt":null,"reason":null},
        "deliveryVersion":8,"replayed":false}"""

    private fun dispositionResponse() = """{"hold":{"id":"$HOLD_ID","readingId":"$READING_ID",
        "exceptionId":"$EXCEPTION_ID","fulfillmentLineId":"$LINE_ID","skuId":"$SKU_ID",
        "affectedQuantity":1,"quantityUnit":"EA","status":"RELEASED","reportedByMembershipId":"$MEMBERSHIP_ID",
        "reportedAt":"$RECORDED_AT","disposition":"RELEASE","authorizedByMembershipId":"$MEMBERSHIP_ID",
        "disposedAt":"$RECORDED_AT","reason":"temperature verified"},"deliveryVersion":9,"replayed":false}"""

    private class FakeTokens : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("execution-temperature-test", 1, 1)
        override suspend fun currentAccess() = lease
        override suspend fun isEpochCurrent(epoch: Long) = lease.epoch == epoch
        override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease) = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
    }

    private companion object {
        const val DELIVERY_ID = "11111111-1111-4111-8111-111111111111"
        const val ATTEMPT_ID = "22222222-2222-4222-8222-222222222222"
        const val WAREHOUSE_ID = "33333333-3333-4333-8333-333333333333"
        const val LINE_ID = "44444444-4444-4444-8444-444444444444"
        const val SKU_ID = "55555555-5555-4555-8555-555555555555"
        const val MEMBERSHIP_ID = "66666666-6666-4666-8666-666666666666"
        const val INCIDENT_ID = "77777777-7777-4777-8777-777777777777"
        const val EVIDENCE_ID = "88888888-8888-4888-8888-888888888888"
        const val HOLD_ID = "99999999-9999-4999-8999-999999999999"
        const val EXCEPTION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val READING_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val CAPTURED_AT = Instant.parse("2026-10-01T17:00:00Z")
        val RECORDED_AT = Instant.parse("2026-10-01T17:01:00Z")
    }
}
