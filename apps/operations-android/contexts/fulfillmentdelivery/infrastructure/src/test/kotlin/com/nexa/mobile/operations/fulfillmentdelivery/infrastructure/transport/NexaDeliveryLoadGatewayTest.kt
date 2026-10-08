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
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaDeliveryLoadGatewayTest {
    @Test fun createHasNoInventedLoadVersionAndRetainsFrozenIdentity() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201).setHeader("ETag", "\"0\"").setBody(load())
            )
            val body = listOf(
                """{"fulfillmentIds":["$FIRST","$SECOND"],""",
                """"stopOrder":["$FIRST","$SECOND"],""",
                """"expectedFulfillmentVersions":{"$FIRST":7,"$SECOND":8},""",
                """"reason":"Compatible deliveries",""",
                """"compatibilityAttestation":{"capacitySufficient":true,"handlingCompatible":true,"zoneReasonable":true,"noExclusiveTransportRestriction":true}}"""
            ).joinToString(separator = "")
            assertTrue(
                gateway(
                    server
                ).mutate(
                    false,
                    null,
                    DeliveryLoadNetworkAction.CREATE,
                    null,
                    "create-key",
                    body
                ) is DeliveryLoadNetworkResult.Current
            )
            val request = server.takeRequest()
            assertEquals("/api/v1/dispatch/loads", request.path)
            assertEquals(null, request.getHeader("If-Match"))
            assertEquals("create-key", request.getHeader("Idempotency-Key"))
            assertEquals(body, request.body.readUtf8())
        }
    }

    @Test fun driverAcceptanceIsWholeLoadBodylessAndRequiresCurrentVersion() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(
                    200
                ).setHeader("ETag", "\"4\"").setBody(load(4, "DRIVER_ACCEPTED"))
            )
            val result = gateway(
                server
            ).mutate(true, LOAD, DeliveryLoadNetworkAction.ACCEPT, 3, "accept-key", null)
            assertTrue(result is DeliveryLoadNetworkResult.Current)
            val request = server.takeRequest()
            assertEquals("/api/v1/driver/loads/$LOAD/acceptances", request.path)
            assertEquals("\"3\"", request.getHeader("If-Match"))
            assertEquals(0L, request.body.size)
            assertTrue(
                gateway(
                    server
                ).mutate(
                    false,
                    LOAD,
                    DeliveryLoadNetworkAction.ACCEPT,
                    3,
                    "key",
                    null
                ) is DeliveryLoadNetworkResult.Failed
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unknownMutationHasNoAutomaticRetryAndInvalidBilateralFactFailsClosed() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val failed = gateway(
                server
            ).mutate(
                false,
                LOAD,
                DeliveryLoadNetworkAction.OFFER,
                2,
                "offer-key",
                null
            ) as DeliveryLoadNetworkResult.Failed
            assertTrue(failed.unknownOutcome)
            assertEquals(1, server.requestCount)
            server.enqueue(
                MockResponse().setResponseCode(
                    200
                ).setHeader("ETag", "\"5\"").setBody(load(5, "RESPONSIBILITY_TRANSFERRED"))
            )
            val invalid = gateway(
                server
            ).mutate(
                false,
                LOAD,
                DeliveryLoadNetworkAction.CONFIRM_HANDOFF,
                4,
                "confirm-key",
                null
            ) as DeliveryLoadNetworkResult.Failed
            assertTrue(invalid.unknownOutcome)
        }
    }
    private fun gateway(server: MockWebServer) = NexaDeliveryLoadGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeTokens()
        )
    )
    private fun load(version: Int = 0, status: String = "DRAFT") =
        """{"id":"$LOAD","version":$version,"status":"$status","originWarehouseId":"$WAREHOUSE","stops":[{"fulfillmentId":"$FIRST","deliveryId":"$DELIVERY1","position":1,"deliveryVersion":0},{"fulfillmentId":"$SECOND","deliveryId":"$DELIVERY2","position":2,"deliveryVersion":0}],"compatibilityAttestation":{"capacitySufficient":true,"handlingCompatible":true,"zoneReasonable":true,"noExclusiveTransportRestriction":true,"attestedByMembershipId":"$MEMBER","attestedAt":"2026-10-01T18:00:00Z"}}"""
    private class FakeTokens : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("session-1", generation = 1, epoch = 1)
        override suspend fun currentAccess(): AccessTokenLease = lease
        override suspend fun isEpochCurrent(epoch: Long): Boolean = lease.epoch == epoch
        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
    }

    private companion object {
        const val LOAD = "11111111-1111-4111-8111-111111111111"
        const val WAREHOUSE = "22222222-2222-4222-8222-222222222222"
        const val FIRST = "33333333-3333-4333-8333-333333333333"
        const val SECOND = "44444444-4444-4444-8444-444444444444"
        const val DELIVERY1 = "55555555-5555-4555-8555-555555555555"
        const val DELIVERY2 = "66666666-6666-4666-8666-666666666666"
        const val MEMBER = "77777777-7777-4777-8777-777777777777"
    }
}
