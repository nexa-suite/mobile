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
import org.junit.Test

class NexaDispatchDeliveryPodSealGatewayTest {
    @Test
    fun sealUsesProtectedDeliveryTransitionAndPreservesConcurrencyHeaders() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("ETag", "\"18\"")
                    .setBody(sealedPodJson())
            )

            val result = gateway(server).sealPod(
                deliveryId = DELIVERY_ID,
                deliveryEtag = "\"17\"",
                idempotencyKey = "stable-seal-key"
            ) as DispatchDeliveryPodNetworkOutcome.Sealed
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/deliveries/$DELIVERY_ID/pod/seals",
                request.requestUrl?.encodedPath
            )
            assertEquals("{}", request.body.readUtf8())
            assertEquals("\"17\"", request.getHeader("If-Match"))
            assertEquals("stable-seal-key", request.getHeader("Idempotency-Key"))
            assertEquals("SEALED", result.value.status)
            assertEquals(18L, result.value.deliveryVersion)
            assertEquals("\"18\"", result.value.deliveryEtag)
        }
    }

    private fun gateway(server: MockWebServer): NexaDispatchDeliveryPodSealGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        val executor = ProtectedCallExecutor(
            endpoint,
            ApiHttpClient.create(endpoint),
            FakeAccessTokenSource()
        )
        return NexaDispatchDeliveryPodSealGateway(executor)
    }

    private fun sealedPodJson() =
        """{"id":"$POD_ID","deliveryId":"$DELIVERY_ID","status":"SEALED","receiverName":"Receiver","capturedAt":"2026-09-30T10:15:30Z","sealedAt":"2026-09-30T10:16:30Z","deliveryVersion":18}"""

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
        const val DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
        const val POD_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413502"
    }
}
