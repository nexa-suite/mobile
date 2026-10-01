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

class NexaDriverDeliveryGatewayTest {
    @Test
    fun assignedListAndDetailUseOnlyCurrentDriverRoutesAndValidateEtag() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, "[${deliveryJson(version = 9)}]"))
            server.enqueue(jsonResponse(200, deliveryJson(version = 9)).setHeader("ETag", "\"9\""))
            val gateway = gateway(server)

            val list = gateway.assignedDeliveries() as DriverDeliveryNetworkOutcome.Assigned
            val detail = gateway.delivery(DELIVERY_ID) as DriverDeliveryNetworkOutcome.Detail

            assertEquals(listOf(DELIVERY_ID), list.items.map { it.id })
            assertEquals("DISPATCHED", detail.item.status)
            assertEquals("Av. Central 100", detail.item.destinationSnapshot)
            assertEquals("/api/v1/driver/deliveries", server.takeRequest().path)
            assertEquals("/api/v1/driver/deliveries/$DELIVERY_ID", server.takeRequest().path)
        }
    }

    @Test
    fun startUsesCallerKeyAndVersionAndAcceptsOnlyMatchingServerAttempt() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, startJson(version = 10)).setHeader("ETag", "\"10\""))
            val gateway = gateway(server)

            val result = gateway.startAttempt(DELIVERY_ID, 9, "delivery-start-1")

            assertTrue(result is DriverDeliveryNetworkOutcome.Started)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/driver/deliveries/$DELIVERY_ID/attempts", request.path)
            assertEquals("\"9\"", request.getHeader("If-Match"))
            assertEquals("delivery-start-1", request.getHeader("Idempotency-Key"))
            assertEquals("{}", request.body.readUtf8())
        }
    }

    @Test
    fun malformedProjectionNeverBecomesAnAssignmentOrSuccessfulAttempt() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, "[${deliveryJson(id = "../other", version = 9)}]"))
            server.enqueue(
                jsonResponse(
                    201,
                    """{"delivery":${deliveryJson(
                        version = 10
                    )},"attempt":{"id":"$ATTEMPT_ID","attemptNumber":1,"status":"FINAL"},"replayed":false}"""
                )
                    .setHeader("ETag", "\"10\"")
            )
            val gateway = gateway(server)

            assertEquals(
                DriverDeliveryNetworkOutcome.ServiceUnavailable,
                gateway.assignedDeliveries()
            )
            assertEquals(
                DriverDeliveryNetworkOutcome.UnknownOutcome,
                gateway.startAttempt(DELIVERY_ID, 9, "delivery-start-malformed")
            )
        }
    }

    @Test
    fun uncertainNetworkStartIsNotAutomaticallyRetried() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

            assertEquals(
                DriverDeliveryNetworkOutcome.UnknownOutcome,
                gateway(server).startAttempt(DELIVERY_ID, 9, "delivery-start-uncertain")
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun eligibleUnauthorizedReplayKeepsTheSameStartIdentityAndBody() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(401, "TOKEN_EXPIRED"))
            server.enqueue(jsonResponse(201, startJson(version = 10)).setHeader("ETag", "\"10\""))
            val tokens = FakeAccessTokenSource()

            val result = gateway(server, tokens).startAttempt(DELIVERY_ID, 9, "delivery-start-401")

            assertTrue(result is DriverDeliveryNetworkOutcome.Started)
            assertEquals(1, tokens.recoverCount)
            val first = server.takeRequest()
            val replay = server.takeRequest()
            assertEquals("access-1", first.getHeader("Authorization")?.removePrefix("Bearer "))
            assertEquals("access-2", replay.getHeader("Authorization")?.removePrefix("Bearer "))
            listOf(first, replay).forEach { request ->
                assertEquals("delivery-start-401", request.getHeader("Idempotency-Key"))
                assertEquals("\"9\"", request.getHeader("If-Match"))
                assertEquals("{}", request.body.readUtf8())
            }
        }
    }

    private fun gateway(
        server: MockWebServer,
        source: FakeAccessTokenSource = FakeAccessTokenSource()
    ): NexaDriverDeliveryGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaDriverDeliveryGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), source)
        )
    }

    private fun deliveryJson(id: String = DELIVERY_ID, version: Long) =
        """{"id":"$id","fulfillmentId":"$FULFILLMENT_ID","salesOrderId":"$ORDER_ID","status":"DISPATCHED","destinationSnapshot":"Av. Central 100","scheduledAt":null,"dispatchedAt":"2026-09-30T15:00:00Z","deliveredAt":null,"updatedAt":"2026-09-30T15:00:00Z","version":$version,"activeAttempt":null}"""

    private fun startJson(version: Long) = """{"delivery":${deliveryJson(
        version = version
    ).replace(
        "\"activeAttempt\":null",
        "\"activeAttempt\":$activeAttemptJson"
    )},"attempt":$activeAttemptJson,"replayed":false}"""

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
        const val DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413102"
        const val ORDER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413103"
        const val ATTEMPT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413104"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413105"
        val activeAttemptJson =
            """{"id":"$ATTEMPT_ID","attemptNumber":1,"status":"ACTIVE","startedByMembershipId":"$MEMBERSHIP_ID","startedAt":"2026-09-30T15:01:00Z"}"""
    }
}
