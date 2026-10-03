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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaSkuIdentifierGatewayTest {
    @Test fun resolvedSkuUsesOnlyActualServerProjectionAndEncodedIdentifier() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(resolvedJson("SKU-4 &+%")))
            val gateway = gateway(server)

            val result = gateway.resolve("  SKU-4 &+%  ")
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/api/v1/skus/resolve", request.requestUrl?.encodedPath)
            assertEquals("SKU-4 &+%", request.requestUrl?.queryParameter("identifier"))
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            val resolved = result as SkuIdentifierResolutionOutcome.Resolved
            assertEquals("c2e78931-f127-433d-b84d-f98b381d2378", resolved.sku.skuId.toString())
            assertEquals("SKU-4", resolved.sku.skuCode)
            assertEquals("12345678", resolved.sku.gtin)
            assertEquals("Pack", resolved.sku.presentation)
            assertEquals("EA", resolved.sku.unitOfMeasure)
            assertEquals(SkuIdentifierType.SKU_CODE, resolved.sku.identifierType)
            assertFalse(resolved.sku.toString().contains("SKU-4"))
            assertFalse(resolved.sku.toString().contains("c2e78931"))
        }
    }

    @Test fun unknownAndAmbiguousResultsNeverExposeCandidateProjection() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    """{"outcome":"NOT_FOUND","identifierType":"SKU_CODE","normalizedIdentifier":"missing","candidateCount":0,"skuId":null,"skuCode":null,"gtin":null,"presentation":null,"unitOfMeasure":null,"status":null}"""
                )
            )
            server.enqueue(
                jsonResponse(
                    """{"outcome":"AMBIGUOUS","identifierType":"GTIN","normalizedIdentifier":"12345678","candidateCount":2,"skuId":null,"skuCode":null,"gtin":null,"presentation":null,"unitOfMeasure":null,"status":null}"""
                )
            )
            val gateway = gateway(server)

            assertTrue(gateway.resolve("missing") is SkuIdentifierResolutionOutcome.NotFound)
            assertEquals(
                SkuIdentifierResolutionOutcome.Ambiguous(2, SkuIdentifierType.GTIN),
                gateway.resolve("12345678")
            )
        }
    }

    @Test fun malformedResponsesFailClosedAndInputsAreBounded() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    resolvedJson(
                        "requested"
                    ).replace(
                        "\"skuId\":\"c2e78931-f127-433d-b84d-f98b381d2378\"",
                        "\"skuId\":\"not-a-uuid\""
                    )
                )
            )
            server.enqueue(jsonResponse(resolvedJson("different")))
            server.enqueue(jsonResponse("not-json"))
            val gateway = gateway(server)

            assertEquals(
                SkuIdentifierResolutionOutcome.ServiceUnavailable,
                gateway.resolve("requested")
            )
            assertEquals(
                SkuIdentifierResolutionOutcome.ServiceUnavailable,
                gateway.resolve("requested")
            )
            assertEquals(
                SkuIdentifierResolutionOutcome.ServiceUnavailable,
                gateway.resolve("requested")
            )
            assertEquals(
                SkuIdentifierResolutionOutcome.InvalidIdentifier,
                gateway.resolve(" ")
            )
            assertEquals(
                SkuIdentifierResolutionOutcome.InvalidIdentifier,
                gateway.resolve("x".repeat(161))
            )
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun permissionAndScopeErrorsAreDistinct() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))
            server.enqueue(problemResponse(403, "ACCESS_DENIED"))
            val gateway = gateway(server)

            assertEquals(
                SkuIdentifierResolutionOutcome.ContextInvalidated,
                gateway.resolve("SKU-4")
            )
            assertEquals(
                SkuIdentifierResolutionOutcome.PermissionDenied,
                gateway.resolve("SKU-4")
            )
        }
    }

    @Test fun unauthorizedAndOfflineResolverCallsFailWithoutIdentifierFallback() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(401, "ACCESS_TOKEN_INVALID"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val source = FakeAccessTokenSource(recoverable = false)
            val gateway = gateway(server, source)

            assertEquals(
                SkuIdentifierResolutionOutcome.SessionExpired,
                gateway.resolve("SKU-4")
            )
            assertEquals(
                SkuIdentifierResolutionOutcome.NetworkUnavailable,
                gateway.resolve("SKU-4")
            )
            assertEquals(2, server.requestCount)
            assertFalse(server.takeRequest().path.orEmpty().contains("CAT-"))
        }
    }

    private fun gateway(
        server: MockWebServer,
        source: FakeAccessTokenSource = FakeAccessTokenSource()
    ): NexaSkuIdentifierGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaSkuIdentifierGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), source)
        )
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun problemResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody(
            """{"status":$status,"code":"$code","category":"CLIENT_ERROR","detail":"private"}"""
        )

    private fun resolvedJson(identifier: String) =
        """{"outcome":"RESOLVED","identifierType":"SKU_CODE","normalizedIdentifier":"$identifier","candidateCount":1,"skuId":"c2e78931-f127-433d-b84d-f98b381d2378","skuCode":"SKU-4","gtin":"12345678","presentation":"Pack","unitOfMeasure":"EA","status":"ACTIVE"}"""

    private class FakeAccessTokenSource(private val recoverable: Boolean = true) :
        AccessTokenSource {
        private val state = MutableStateFlow<SessionState>(SessionState.Active)
        override val sessionState: StateFlow<SessionState> = state
        private var lease = AccessTokenLease("access-1", generation = 1, epoch = 1)

        override suspend fun currentAccess(): AccessTokenLease = lease

        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? = if (recoverable) {
            lease = AccessTokenLease("access-2", generation = 2, epoch = observed.epoch)
            lease
        } else {
            null
        }

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit

        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }
}
