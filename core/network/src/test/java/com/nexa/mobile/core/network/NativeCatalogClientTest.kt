package com.nexa.mobile.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeCatalogClientTest {
    @Test
    fun resolveUsesBearerAndEncodesIdentifierWithoutInventingClientSurface() = runBlocking {
        val executor = RecordingExecutor(
            HttpResponse(
                status = 200,
                body = """
                    {
                      "outcome":"RESOLVED",
                      "identifierType":"SKU_CODE",
                      "normalizedIdentifier":"SKU/001",
                      "candidateCount":1,
                      "skuId":"sku-1",
                      "skuCode":"SKU/001",
                      "gtin":null,
                      "presentation":"Box",
                      "unitOfMeasure":"UNIT",
                      "status":"ACTIVE"
                    }
                """.trimIndent(),
            ),
        )

        val result = NativeCatalogClient("https://api.example.test/", executor).resolveSku(
            identifier = " SKU/001 ",
            accessToken = "access-2",
        )

        val resolution = (result as ApiResult.Success).value
        assertEquals("RESOLVED", resolution.outcome)
        assertEquals("sku-1", resolution.skuId)
        assertEquals(
            "https://api.example.test/api/v1/skus/resolve?identifier=SKU%2F001",
            executor.request.url,
        )
        assertEquals("Bearer access-2", executor.request.headers["Authorization"])
        assertFalse(executor.request.headers.containsKey("X-Nexa-Client"))
    }

    @Test
    fun blankIdentifierAndTokenFailBeforeTransport() = runBlocking {
        val executor = RecordingExecutor(HttpResponse(status = 500))
        val client = NativeCatalogClient("https://api.example.test", executor)

        val blankIdentifier = client.resolveSku("  ", "access-2")
        val blankToken = client.resolveSku("SKU-001", "  ")

        assertEquals("IDENTIFIER_REQUIRED", (blankIdentifier as ApiResult.Failure).error.code)
        assertEquals(ApiErrorCategory.UNAUTHORIZED, (blankToken as ApiResult.Failure).error.category)
        assertEquals(0, executor.calls)
    }

    @Test
    fun notFoundResponseRemainsAnAuthoritativeResolutionOutcome() = runBlocking {
        val executor = RecordingExecutor(
            HttpResponse(
                status = 200,
                body = """
                    {
                      "outcome":"NOT_FOUND",
                      "identifierType":"UNKNOWN",
                      "normalizedIdentifier":"UNKNOWN-001",
                      "candidateCount":0
                    }
                """.trimIndent(),
            ),
        )

        val result = NativeCatalogClient("https://api.example.test", executor)
            .resolveSku("UNKNOWN-001", "access-2")

        val resolution = (result as ApiResult.Success).value
        assertEquals("NOT_FOUND", resolution.outcome)
        assertEquals(0, resolution.candidateCount)
        assertEquals(null, resolution.skuId)
    }

    private class RecordingExecutor(private val response: HttpResponse) : HttpRequestExecutor {
        var calls = 0
        lateinit var request: HttpRequest

        override suspend fun execute(request: HttpRequest): HttpResponse {
            calls += 1
            this.request = request
            return response
        }
    }
}
