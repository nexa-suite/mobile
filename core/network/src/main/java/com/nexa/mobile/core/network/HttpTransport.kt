package com.nexa.mobile.core.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
)

fun interface HttpRequestExecutor {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** Small platform transport; authentication policy stays in [NativeAccessClient]. */
class UrlConnectionHttpRequestExecutor(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
) : HttpRequestExecutor {
    init {
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive" }
        require(readTimeoutMillis > 0) { "Read timeout must be positive" }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            useCaches = false
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val bodyBytes = request.body?.toByteArray(Charsets.UTF_8)
            if (bodyBytes != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bodyBytes.size)
                connection.outputStream.use { it.write(bodyBytes) }
            }

            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { it.value.orEmpty() }
            HttpResponse(status = status, headers = headers, body = body)
        } catch (exception: IOException) {
            throw exception
        } finally {
            connection.disconnect()
        }
    }
}
