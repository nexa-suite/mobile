package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaOperationsCatalogGatewayTest {
    private val productId = "6b9d1f6d-6c6f-4e7d-9d6b-005100000051"

    @Test
    fun searchUsesAuthorizedManagementProjectionAndPreservesInternalPriceAndImage() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    200,
                    """{"items":[${productJson()}],"page":0,"size":20,"total":1}"""
                )
            )

            val outcome = gateway(server).search("  PROD-0051  ", null)
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/api/v1/catalog/products", request.requestUrl?.encodedPath)
            assertEquals("PROD-0051", request.requestUrl?.queryParameter("search"))
            assertEquals("0", request.requestUrl?.queryParameter("page"))
            assertEquals("20", request.requestUrl?.queryParameter("size"))
            assertEquals("ACTIVE", request.requestUrl?.queryParameter("status"))
            assertEquals("Bearer access-1", request.getHeader("Authorization"))

            assertTrue(outcome is OperationsCatalogSearchOutcome.Page)
            val candidate = (outcome as OperationsCatalogSearchOutcome.Page)
                .value.candidates.single()
            assertEquals(productId, candidate.productId)
            assertEquals("CAT-0051", candidate.catalogItemId)
            assertEquals("PROD-0051", candidate.skuCode)
            assertEquals("agriform_queso-grana-padano-dop_corte.png", candidate.imageFileName)
            assertEquals("17.30", candidate.priceAmount)
            assertEquals("PEN", candidate.priceCurrency)
            assertFalse(candidate.toString().contains("PROD-0051"))
            assertFalse(candidate.toString().contains("CAT-0051"))
        }
    }

    @Test
    fun searchMapsManagementPageAndDoesNotDiscardBuyerHiddenInternalProducts() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    200,
                    """{"items":[${productJson(
                        buyerVisible = false
                    )}],"page":1,"size":20,"total":21}"""
                )
            )

            val outcome = gateway(server).search("cheese", "1")
            assertTrue(outcome is OperationsCatalogSearchOutcome.Page)
            assertEquals(
                null,
                (outcome as OperationsCatalogSearchOutcome.Page).value.nextPageKey
            )
            assertEquals(1, outcome.value.candidates.size)
            assertEquals("CAT-0051", outcome.value.candidates.single().catalogItemId)
            assertEquals("1", server.takeRequest().requestUrl?.queryParameter("page"))
        }
    }

    @Test
    fun detailUsesProductUuidAndMapsConfirmedFacts() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, productJson()))

            val outcome = gateway(server).loadDetail(productId)
            assertTrue(outcome is OperationsCatalogDetailOutcome.Found)
            val detail = (outcome as OperationsCatalogDetailOutcome.Found).value
            assertEquals(productId, detail.productId)
            assertEquals("CAT-0051", detail.catalogItemId)
            assertEquals("QUESO GRANA PADANO DOP CORTE", detail.itemName)
            assertEquals("REFRIGERATED", detail.storageTemperature)
            assertEquals("agriform_queso-grana-padano-dop_corte.png", detail.imageFileName)
            assertEquals("17.30", detail.priceAmount)
            assertEquals("PEN", detail.priceCurrency)
            assertEquals("/api/v1/catalog/products/$productId", server.takeRequest().path)
        }
    }

    @Test
    fun malformedIdsAndQueriesDoNotDispatchRequests() = runTest {
        MockWebServer().use { server ->
            server.start()
            val catalog = gateway(server)
            assertEquals(
                OperationsCatalogDetailOutcome.CandidateUnavailable,
                catalog.loadDetail("CAT-0051")
            )
            assertEquals(
                OperationsCatalogSearchOutcome.InvalidQuery,
                catalog.search("cheese", "01")
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun protectedErrorsPreservePermissionAndSessionBoundaries() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(403, "PERMISSION_DENIED"))
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))
            server.enqueue(MockResponse().setResponseCode(404))
            val catalog = gateway(server)

            assertEquals(
                OperationsCatalogSearchOutcome.PermissionDenied,
                catalog.search("cheese", null)
            )
            assertEquals(
                OperationsCatalogSearchOutcome.ContextInvalidated,
                catalog.search("cheese", null)
            )
            assertEquals(
                OperationsCatalogDetailOutcome.CandidateUnavailable,
                catalog.loadDetail(productId)
            )
        }
    }

    private fun gateway(server: MockWebServer): NexaOperationsCatalogGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaOperationsCatalogGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), FakeAccessTokenSource())
        )
    }

    private fun productJson(buyerVisible: Boolean = false): String =
        """{"id":"$productId","catalogItemId":"CAT-0051","productCode":"PROD-0051","name":"QUESO GRANA PADANO DOP CORTE","description":"Provisional catalog reference","brandName":"Agriform","status":"ACTIVE","presentation":"UNSPECIFIED","unitOfMeasure":"UNIT","storageTemperature":"REFRIGERATED","buyerVisible":$buyerVisible,"imagePath":"/catalog-items/agriform_queso-grana-padano-dop_corte.png","currentPrice":{"amount":17.30,"currency":"PEN"}}"""

    private fun jsonResponse(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun problemResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody(
            """{"status":$status,"code":"$code","category":"CLIENT_ERROR","detail":"private detail"}"""
        )

    private class FakeAccessTokenSource : AccessTokenSource {
        private val state = MutableStateFlow<SessionState>(SessionState.Active)
        override val sessionState: StateFlow<SessionState> = state
        private val lease = AccessTokenLease("access-1", generation = 1, epoch = 1)

        override suspend fun currentAccess(): AccessTokenLease = lease

        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? = null

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit

        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == lease.epoch
    }
}
