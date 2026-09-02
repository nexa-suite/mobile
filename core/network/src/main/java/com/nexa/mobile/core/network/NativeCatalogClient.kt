package com.nexa.mobile.core.network

import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Transport DTO for the server-owned BC-03 identifier resolution response. */
@Serializable
data class SkuResolution(
    val outcome: String,
    val identifierType: String,
    val normalizedIdentifier: String,
    val candidateCount: Int,
    val skuId: String? = null,
    val skuCode: String? = null,
    val gtin: String? = null,
    val presentation: String? = null,
    val unitOfMeasure: String? = null,
    val status: String? = null,
)

/** Reads the tagged API v0.17.0 SKU resolution contract without owning catalog truth. */
class NativeCatalogClient(
    baseUrl: String,
    private val executor: HttpRequestExecutor = UrlConnectionHttpRequestExecutor(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val baseUrl = normalizeNativeApiBaseUrl(baseUrl)

    suspend fun resolveSku(identifier: String, accessToken: String): ApiResult<SkuResolution> {
        val normalizedIdentifier = identifier.trim()
        if (normalizedIdentifier.isBlank()) {
            return ApiResult.Failure(
                ApiError(category = ApiErrorCategory.VALIDATION, code = "IDENTIFIER_REQUIRED"),
            )
        }
        if (accessToken.isBlank()) {
            return ApiResult.Failure(
                ApiError(category = ApiErrorCategory.UNAUTHORIZED, code = "ACCESS_TOKEN_REQUIRED"),
            )
        }

        val request = HttpRequest(
            method = "GET",
            url = "$baseUrl/api/v1/skus/resolve?identifier=${urlEncode(normalizedIdentifier)}",
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $accessToken",
            ),
        )
        return execute(request) { response ->
            json.decodeFromString(SkuResolution.serializer(), response.body)
        }
    }

    private suspend inline fun <reified T> execute(
        request: HttpRequest,
        crossinline decode: (HttpResponse) -> T,
    ): ApiResult<T> {
        return try {
            val response = executor.execute(request)
            if (response.status !in 200..299) {
                ApiResult.Failure(response.error())
            } else {
                ApiResult.Success(decode(response))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            ApiResult.Failure(ApiErrorMapper.network())
        } catch (_: RuntimeException) {
            ApiResult.Failure(ApiError(category = ApiErrorCategory.UNKNOWN, code = "MALFORMED_RESPONSE"))
        }
    }

    private fun HttpResponse.error(): ApiError {
        val problem = body.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString(ProblemDetail.serializer(), it) }.getOrNull()
        }
        return ApiErrorMapper.fromHttp(status, problem, header("Retry-After")?.toLongOrNull())
    }

    private fun HttpResponse.header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

    private companion object {
        fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
