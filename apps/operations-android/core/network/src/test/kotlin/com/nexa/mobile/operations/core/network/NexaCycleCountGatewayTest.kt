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

class NexaCycleCountGatewayTest {
    @Test
    fun countReplaysLiteralFrozenBodyAndVersionedCorrectionHasNoReconstructedPayload() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(countResponse()))
            server.enqueue(jsonResponse(correctionResponse(), 200))
            val gateway = gateway(server)
            val body = "{\"observedQuantity\":4.250,\"unit\":\"EA\"}"

            val count = gateway.record(
                CycleCountCountCommand(
                    idempotencyKey = COUNT_KEY,
                    lotId = LOT_ID,
                    warehouseId = WAREHOUSE_ID,
                    zoneId = ZONE_ID,
                    lotVersion = 7,
                    expectedQuantityText = "5.000",
                    observedQuantityText = "4.250",
                    unit = "EA",
                    membershipId = MEMBERSHIP_ID,
                    frozenBody = body
                )
            )
            val countRequest = server.takeRequest()

            assertEquals("POST", countRequest.method)
            assertEquals("/api/v1/inventory/lots/$LOT_ID/cycle-counts", countRequest.requestUrl?.encodedPath)
            assertEquals(body, countRequest.body.readUtf8())
            assertEquals(COUNT_KEY, countRequest.getHeader("Idempotency-Key"))
            assertEquals("\"7\"", countRequest.getHeader("If-Match"))
            assertTrue(count is CycleCountNetworkOutcome.Recorded)

            val correction = gateway.applyCorrection(
                CycleCountCorrectionCommand(
                    idempotencyKey = CORRECTION_KEY,
                    countId = COUNT_ID,
                    lotId = LOT_ID,
                    warehouseId = WAREHOUSE_ID,
                    zoneId = ZONE_ID,
                    expectedLotVersion = 7,
                    expectedQuantityText = "5.000",
                    observedQuantityText = "4.250",
                    unit = "EA",
                    membershipId = MEMBERSHIP_ID
                )
            )
            val correctionRequest = server.takeRequest()
            assertEquals("POST", correctionRequest.method)
            assertEquals("/api/v1/inventory/cycle-counts/$COUNT_ID/corrections", correctionRequest.requestUrl?.encodedPath)
            assertEquals("", correctionRequest.body.readUtf8())
            assertEquals(CORRECTION_KEY, correctionRequest.getHeader("Idempotency-Key"))
            assertEquals("\"7\"", correctionRequest.getHeader("If-Match"))
            assertTrue(correction is CycleCountNetworkOutcome.Applied)
        }
    }

    @Test
    fun countRejectsChangedFrozenBodyBeforeSending() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = gateway(server)
            val result = gateway.record(
                CycleCountCountCommand(
                    idempotencyKey = COUNT_KEY,
                    lotId = LOT_ID,
                    warehouseId = WAREHOUSE_ID,
                    zoneId = ZONE_ID,
                    lotVersion = 7,
                    expectedQuantityText = "5",
                    observedQuantityText = "4.25",
                    unit = "EA",
                    membershipId = MEMBERSHIP_ID,
                    frozenBody = "{\"observedQuantity\":4.5,\"unit\":\"EA\"}"
                )
            )

            assertEquals(CycleCountNetworkOutcome.Rejected("INVALID_REQUEST"), result)
            assertEquals(0, server.requestCount)
        }
    }

    private fun gateway(server: MockWebServer): NexaCycleCountGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaCycleCountGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), FakeAccessTokenSource())
        )
    }

    private fun jsonResponse(body: String, status: Int = 201) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun countResponse() = """{"id":"$COUNT_ID","lotId":"$LOT_ID","warehouseId":"$WAREHOUSE_ID","zoneId":"$ZONE_ID","lotVersion":7,"expectedQuantity":5.000,"observedQuantity":4.250,"unit":"EA","status":"REQUESTED","actorMembershipId":"$MEMBERSHIP_ID","recordedAt":"2026-09-30T10:00:00Z"}"""

    private fun correctionResponse() = """{"id":"$CORRECTION_ID","cycleCountId":"$COUNT_ID","lotId":"$LOT_ID","warehouseId":"$WAREHOUSE_ID","zoneId":"$ZONE_ID","lotVersionBefore":7,"lotVersionAfter":8,"quantityBefore":5.000,"quantityAfter":4.250,"quantityDelta":-0.750,"unit":"EA","actorMembershipId":"$MEMBERSHIP_ID","recordedAt":"2026-09-30T10:00:00Z"}"""

    private class FakeAccessTokenSource : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("access-1", generation = 1, epoch = 1)
        override suspend fun currentAccess(): AccessTokenLease = lease
        override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }

    private companion object {
        const val COUNT_KEY = "count-key-1"
        const val CORRECTION_KEY = "correction-key-1"
        const val MEMBERSHIP_ID = "00000000-0000-4000-8000-000000000001"
        const val WAREHOUSE_ID = "00000000-0000-4000-8000-000000000002"
        const val ZONE_ID = "00000000-0000-4000-8000-000000000003"
        const val LOT_ID = "00000000-0000-4000-8000-000000000004"
        const val COUNT_ID = "00000000-0000-4000-8000-000000000005"
        const val CORRECTION_ID = "00000000-0000-4000-8000-000000000006"
    }
}
