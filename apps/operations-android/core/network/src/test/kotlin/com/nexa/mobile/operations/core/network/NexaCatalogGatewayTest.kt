package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import java.util.concurrent.TimeUnit
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

class NexaCatalogGatewayTest {
    @Test
    fun searchTrimsAndEncodesUnicodeQueryAndMapsCandidateProjection() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    200,
                    """{"items":[{"catalogItemId":"CAT-0001","itemName":"Grana Padano","presentation":"150G","skuCode":"PROD-0001","brandName":"Agriform","productVariantName":"Gold","productFamilyName":"Cheese","image":{"url":"/catalog-items/agriform-queso-grana-padano-dop-150g.png","fileName":"agriform-queso-grana-padano-dop-150g.png"}}],"page":0,"size":20,"totalItems":1,"totalPages":1}"""
                )
            )

            val gateway = gateway(server)
            val outcome = gateway.search("  café 漢字 &+%  ", null)
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/api/v1/catalog-items", request.requestUrl?.encodedPath)
            assertEquals("café 漢字 &+%", request.requestUrl?.queryParameter("q"))
            assertEquals("0", request.requestUrl?.queryParameter("page"))
            assertEquals("20", request.requestUrl?.queryParameter("size"))
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Nexa-Client"))
            assertNull(request.getHeader("X-Nexa-Surface"))
            assertTrue(outcome is CatalogSearchOutcome.Page)
            val page = (outcome as CatalogSearchOutcome.Page).value
            val candidate = page.candidates.single()
            assertEquals("CAT-0001", candidate.catalogItemId)
            assertEquals("Grana Padano", candidate.itemName)
            assertEquals("150G", candidate.presentation)
            assertEquals("PROD-0001", candidate.skuCode)
            assertEquals("Agriform", candidate.brandName)
            assertEquals("Gold", candidate.productVariantName)
            assertEquals("Cheese", candidate.productFamilyName)
            assertEquals("agriform-queso-grana-padano-dop-150g.png", candidate.imageFileName)
            assertNull(page.nextPageKey)
            assertFalse(candidate.toString().contains("CAT-0001"))
            assertFalse(candidate.toString().contains("PROD-0001"))
        }
    }

    @Test
    fun pageMappingHandlesZeroOneAndManyCandidatesAndStableNumericTokens() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, pageJson(page = 0, totalPages = 0, items = "")))
            server.enqueue(
                jsonResponse(
                    200,
                    pageJson(page = 0, totalPages = 2, items = candidateJson("CAT-0001"))
                )
            )
            server.enqueue(
                jsonResponse(
                    200,
                    pageJson(
                        page = 1,
                        totalPages = 3,
                        items = candidateJson("CAT-0002") + "," + candidateJson("CAT-0003") + "," +
                            candidateJson("CAT-0004")
                    )
                )
            )
            server.enqueue(jsonResponse(200, pageJson(page = 2, totalPages = 3, items = "")))
            val gateway = gateway(server)

            val zero = gateway.search("cheese", null) as CatalogSearchOutcome.Page
            assertTrue(zero.value.candidates.isEmpty())
            assertNull(zero.value.nextPageKey)

            val one = gateway.search("cheese", "0") as CatalogSearchOutcome.Page
            assertEquals(1, one.value.candidates.size)
            assertEquals("1", one.value.nextPageKey)

            val many = gateway.search("cheese", one.value.nextPageKey) as CatalogSearchOutcome.Page
            assertEquals(3, many.value.candidates.size)
            assertEquals("2", many.value.nextPageKey)

            val finalPage = gateway.search(
                "cheese",
                many.value.nextPageKey
            ) as CatalogSearchOutcome.Page
            assertTrue(finalPage.value.candidates.isEmpty())
            assertNull(finalPage.value.nextPageKey)

            assertEquals("0", server.takeRequest().requestUrl?.queryParameter("page"))
            assertEquals("0", server.takeRequest().requestUrl?.queryParameter("page"))
            assertEquals("1", server.takeRequest().requestUrl?.queryParameter("page"))
            assertEquals("2", server.takeRequest().requestUrl?.queryParameter("page"))
        }
    }

    @Test
    fun malformedPageKeysAndInvalidQueriesDoNotDispatchRequests() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = gateway(server)
            val invalidInputs = listOf(
                "" to null,
                "   " to null,
                "a".repeat(121) to null,
                "cheese" to "-1",
                "cheese" to "01",
                "cheese" to "1x",
                "cheese" to "99999999999999999999"
            )

            invalidInputs.forEach { (query, pageKey) ->
                assertEquals(CatalogSearchOutcome.InvalidQuery, gateway.search(query, pageKey))
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun detailUsesSelectedItemRouteAndFieldsReturnedByDetailResponse() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    200,
                    """{"catalogItemId":"CAT-0001","itemName":"Detail name","presentation":"Detail pack","skuCode":"DETAIL-SKU","brandName":"Brand","productVariantName":"Variant","productFamilyName":"Family","unitOfMeasure":"KG","packagingType":"Vacuum","coldChainRequirement":"REFRIGERATED","image":{"url":"/catalog-items/cavour-salame-milano-100g.jpeg","fileName":"cavour-salame-milano-100g.jpeg"}}"""
                )
            )
            val outcome = gateway(server).loadDetail("CAT-0001")

            assertTrue(outcome is CatalogDetailOutcome.Found)
            val detail = (outcome as CatalogDetailOutcome.Found).value
            assertEquals("Detail name", detail.itemName)
            assertEquals("Detail pack", detail.presentation)
            assertEquals("DETAIL-SKU", detail.skuCode)
            assertEquals("Brand", detail.brandName)
            assertEquals("Variant", detail.productVariantName)
            assertEquals("Family", detail.productFamilyName)
            assertEquals("KG", detail.unitOfMeasure)
            assertEquals("Vacuum", detail.packagingType)
            assertEquals("REFRIGERATED", detail.coldChainRequirement)
            assertEquals("cavour-salame-milano-100g.jpeg", detail.imageFileName)
            assertEquals("/api/v1/catalog-items/CAT-0001", server.takeRequest().path)
        }
    }

    @Test
    fun detailOptionalFieldsRemainAbsentAndMalformedIdsDoNotDispatch() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    200,
                    """{"catalogItemId":"CAT-0002","itemName":"Item","presentation":"Box","skuCode":"SKU-2"}"""
                )
            )
            val gateway = gateway(server)

            assertEquals(
                CatalogDetailOutcome.CandidateUnavailable,
                gateway.loadDetail("../CAT-0002")
            )
            val outcome = gateway.loadDetail("CAT-0002") as CatalogDetailOutcome.Found
            assertNull(outcome.value.brandName)
            assertNull(outcome.value.productVariantName)
            assertNull(outcome.value.productFamilyName)
            assertNull(outcome.value.unitOfMeasure)
            assertNull(outcome.value.packagingType)
            assertNull(outcome.value.coldChainRequirement)
            assertNull(outcome.value.imageFileName)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun malformedSuccessfulBodiesBecomeServiceUnavailable() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, "not-json"))
            server.enqueue(jsonResponse(200, pageJson(page = 0, totalPages = 1, items = "{}")))
            server.enqueue(jsonResponse(200, pageJson(page = 1, totalPages = 1, items = "")))
            server.enqueue(
                jsonResponse(
                    200,
                    """{"catalogItemId":"CAT-OTHER","itemName":"Item","presentation":"Box","skuCode":"SKU"}"""
                )
            )
            val gateway = gateway(server)

            assertEquals(CatalogSearchOutcome.ServiceUnavailable, gateway.search("cheese", null))
            assertEquals(CatalogSearchOutcome.ServiceUnavailable, gateway.search("cheese", null))
            assertEquals(CatalogSearchOutcome.ServiceUnavailable, gateway.search("cheese", null))
            assertEquals(CatalogDetailOutcome.ServiceUnavailable, gateway.loadDetail("CAT-0001"))
        }
    }

    @Test
    fun protectedErrorsMapToSafeCatalogOutcomes() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(400, "INVALID_CATALOG_QUERY"))
            server.enqueue(problemResponse(403, "PERMISSION_DENIED"))
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))
            server.enqueue(problemResponse(500, "INTERNAL_ERROR"))
            server.enqueue(MockResponse().setResponseCode(404))
            val gateway = gateway(server)

            assertEquals(CatalogSearchOutcome.InvalidQuery, gateway.search("bad", null))
            assertEquals(CatalogSearchOutcome.PermissionDenied, gateway.search("cheese", null))
            assertEquals(CatalogSearchOutcome.ContextInvalidated, gateway.search("cheese", null))
            assertEquals(CatalogSearchOutcome.ServiceUnavailable, gateway.search("cheese", null))
            assertEquals(CatalogDetailOutcome.CandidateUnavailable, gateway.loadDetail("CAT-0001"))
        }
    }

    @Test
    fun unauthorizedReadUsesExecutorRefreshAndOneReplay() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(401, "ACCESS_TOKEN_INVALID"))
            server.enqueue(
                jsonResponse(
                    200,
                    pageJson(page = 0, totalPages = 0, items = "")
                )
            )
            val source = FakeAccessTokenSource()
            val outcome = gateway(server, source).search("cheese", null)

            assertTrue(outcome is CatalogSearchOutcome.Page)
            assertEquals(1, source.recoverCount)
            assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun unauthorizedReadWithoutRecoverableSessionMapsSessionExpired() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(401, "ACCESS_TOKEN_INVALID"))
            val source = FakeAccessTokenSource(recoverable = false)
            val outcome = gateway(server, source).search("cheese", null)

            assertEquals(CatalogSearchOutcome.SessionExpired, outcome)
            assertEquals(1, source.recoverCount)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun networkFailureMapsUnavailableAndDoesNotRetry() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val source = FakeAccessTokenSource()
            val outcome = gateway(server, source).search("cheese", null)

            assertEquals(CatalogSearchOutcome.NetworkUnavailable, outcome)
            assertEquals(0, source.recoverCount)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun readTimeoutMapsToNetworkUnavailable() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(200, pageJson(page = 0, totalPages = 0, items = ""))
                    .setBodyDelay(200, TimeUnit.MILLISECONDS)
            )
            val endpoint = ApiEndpoint(server.url("/").toString())
            val client = ApiHttpClient.create(endpoint)
                .newBuilder()
                .readTimeout(20, TimeUnit.MILLISECONDS)
                .build()
            val gateway = NexaCatalogGateway(
                ProtectedCallExecutor(endpoint, client, FakeAccessTokenSource())
            )

            assertEquals(CatalogSearchOutcome.NetworkUnavailable, gateway.search("cheese", null))
        }
    }

    private fun gateway(
        server: MockWebServer,
        source: FakeAccessTokenSource = FakeAccessTokenSource()
    ): NexaCatalogGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaCatalogGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), source)
        )
    }

    private fun jsonResponse(status: Int, body: String) = MockResponse().setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun problemResponse(status: Int, code: String) = MockResponse().setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody(
            """{"status":$status,"code":"$code","category":"CLIENT_ERROR","detail":"private detail"}"""
        )

    private fun pageJson(page: Int, totalPages: Int, items: String) =
        """{"items":[$items],"page":$page,"size":20,"totalItems":${if (items.isBlank()) 0 else 1},"totalPages":$totalPages}"""

    private fun candidateJson(id: String) =
        """{"catalogItemId":"$id","itemName":"Item $id","presentation":"Pack","skuCode":"SKU-$id"}"""

    private class FakeAccessTokenSource(private val recoverable: Boolean = true) :
        AccessTokenSource {
        private val state = MutableStateFlow<SessionState>(SessionState.Active)
        override val sessionState: StateFlow<SessionState> = state
        private var lease = AccessTokenLease("access-1", generation = 1, epoch = 1)
        var recoverCount = 0

        override suspend fun currentAccess(): AccessTokenLease = lease

        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? {
            recoverCount++
            if (!recoverable) return null
            lease = AccessTokenLease("access-2", generation = 2, epoch = observed.epoch)
            return lease
        }

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit

        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }
}
