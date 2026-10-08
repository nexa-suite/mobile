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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaOutgoingGoodsCheckGatewayTest {
    @Test
    fun recordPostsFrozenBodyAndExactAuthorityHeaders() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    """{"id":"$CHECK_ID","fulfillmentId":"$FULFILLMENT_ID","fulfillmentVersion":12,"physicalAllocationId":"$ALLOCATION_ID","physicalAllocationVersion":7,"matches":true,"current":true,"openDiscrepancy":false,"checkedByMembershipId":"$MEMBERSHIP_ID","checkedAt":"2026-09-30T10:15:30Z","lines":[{"physicalAllocationLineId":"$LINE_ID","skuId":"$SKU_ID","expectedLotId":"$LOT_ID","observedLotId":"$LOT_ID","expectedQuantity":2.5,"observedQuantity":2.50,"unit":"each","matches":true}],"replayed":false}"""
                ).addHeader("ETag", "\"12\"")
            )
            val gateway = gateway(server)
            val body = listOf(
                """{"physicalAllocationId":"$ALLOCATION_ID","physicalAllocationVersion":7,""",
                """"observations":[{"physicalAllocationLineId":"$LINE_ID",""",
                """"observedLotId":"$LOT_ID","observedQuantity":2.50}]}"""
            ).joinToString(separator = "")

            val outcome = gateway.record(
                OutgoingGoodsCheckCommand(
                    fulfillmentId = FULFILLMENT_ID,
                    expectedFulfillmentVersion = 12,
                    physicalAllocationId = ALLOCATION_ID,
                    physicalAllocationVersion = 7,
                    observations = listOf(
                        OutgoingGoodsObservation(LINE_ID, LOT_ID, BigDecimal("2.50"))
                    ),
                    idempotencyKey = "outgoing-check-key",
                    exactRequestBody = body
                )
            ) as OutgoingGoodsCheckNetworkOutcome.Recorded

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/outgoing-checks",
                request.requestUrl?.encodedPath
            )
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertEquals("\"12\"", request.getHeader("If-Match"))
            assertEquals("outgoing-check-key", request.getHeader("Idempotency-Key"))
            assertEquals(body, request.body.readUtf8())
            assertEquals(CHECK_ID, outcome.check.id)
            assertTrue(outcome.check.current)
        }
    }

    @Test
    fun inconsistentFrozenBodyIsRejectedBeforeNetworkCall() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = gateway(server)
            val result = gateway.record(
                OutgoingGoodsCheckCommand(
                    fulfillmentId = FULFILLMENT_ID,
                    expectedFulfillmentVersion = 12,
                    physicalAllocationId = ALLOCATION_ID,
                    physicalAllocationVersion = 7,
                    observations = listOf(
                        OutgoingGoodsObservation(LINE_ID, LOT_ID, BigDecimal("2.5"))
                    ),
                    idempotencyKey = "outgoing-check-key",
                    exactRequestBody = "{}"
                )
            )
            assertEquals(OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable, result)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun resolvesOnlyWithFrozenReasonAndBothCheckIdentities() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    """{"id":"$RESOLUTION_ID","fulfillmentId":"$FULFILLMENT_ID","fulfillmentVersion":12,"physicalAllocationId":"$ALLOCATION_ID","physicalAllocationVersion":7,"discrepancyCheckId":"$DISCREPANCY_ID","matchingCheckId":"$CHECK_ID","actorMembershipId":"$MEMBERSHIP_ID","reason":"Recount confirmed the allocated goods.","resolvedAt":"2026-09-30T10:16:30Z","current":true,"replayed":false}"""
                ).addHeader("ETag", "\"12\"")
            )
            val body = listOf(
                """{"physicalAllocationId":"$ALLOCATION_ID","physicalAllocationVersion":7,""",
                """"discrepancyCheckId":"$DISCREPANCY_ID","matchingCheckId":"$CHECK_ID",""",
                """"reason":"Recount confirmed the allocated goods."}"""
            ).joinToString(separator = "")

            val result = gateway(server).resolve(
                OutgoingGoodsDiscrepancyResolutionCommand(
                    fulfillmentId = FULFILLMENT_ID,
                    expectedFulfillmentVersion = 12,
                    physicalAllocationId = ALLOCATION_ID,
                    physicalAllocationVersion = 7,
                    discrepancyCheckId = DISCREPANCY_ID,
                    matchingCheckId = CHECK_ID,
                    reason = "Recount confirmed the allocated goods.",
                    idempotencyKey = "outgoing-resolution-key",
                    exactRequestBody = body
                )
            ) as OutgoingGoodsCheckNetworkOutcome.Resolved

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/fulfillments/$FULFILLMENT_ID/outgoing-discrepancy-resolutions",
                request.requestUrl?.encodedPath
            )
            assertEquals("\"12\"", request.getHeader("If-Match"))
            assertEquals("outgoing-resolution-key", request.getHeader("Idempotency-Key"))
            assertEquals(body, request.body.readUtf8())
            assertEquals(DISCREPANCY_ID, result.resolution.discrepancyCheckId)
            assertTrue(result.resolution.current)
        }
    }

    private fun gateway(server: MockWebServer) = NexaOutgoingGoodsCheckGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeAccessTokenSource()
        )
    )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(201)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

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
        const val FULFILLMENT_ID = "11111111-1111-4111-8111-111111111111"
        const val ALLOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val LINE_ID = "33333333-3333-4333-8333-333333333333"
        const val SKU_ID = "44444444-4444-4444-8444-444444444444"
        const val LOT_ID = "55555555-5555-4555-8555-555555555555"
        const val MEMBERSHIP_ID = "66666666-6666-4666-8666-666666666666"
        const val CHECK_ID = "77777777-7777-4777-8777-777777777777"
        const val DISCREPANCY_ID = "88888888-8888-4888-8888-888888888888"
        const val RESOLUTION_ID = "99999999-9999-4999-8999-999999999999"
    }
}
