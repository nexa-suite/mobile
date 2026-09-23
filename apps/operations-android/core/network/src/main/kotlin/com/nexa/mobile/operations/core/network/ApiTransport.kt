package com.nexa.mobile.operations.core.network

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Configuration is an origin, not a credential or a path supplied by a caller. */
class ApiEndpoint(value: String) {
    val url: HttpUrl = value.toHttpUrl().also {
        require(it.encodedPath == "/" && it.query == null && it.fragment == null)
        require(it.username.isEmpty() && it.password.isEmpty())
        require(it.scheme == "https" || (it.scheme == "http" && it.host in LOCAL_DEBUG_HOSTS))
    }

    fun isTrusted(candidate: HttpUrl): Boolean =
        candidate.scheme == url.scheme && candidate.host == url.host && candidate.port == url.port

    companion object {
        private val LOCAL_DEBUG_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")
    }
}

/** Rejects untrusted destinations before any request, including a mistakenly added @Url call. */
class OriginGuardInterceptor(private val endpoint: ApiEndpoint) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!endpoint.isTrusted(chain.request().url)) throw IOException("Untrusted API origin")
        return chain.proceed(chain.request())
    }
}

class CorrelationInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder()
            .header("X-Correlation-ID", UUID.randomUUID().toString())
            .build()
    )
}

object ApiHttpClient {
    fun create(endpoint: ApiEndpoint): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(OriginGuardInterceptor(endpoint))
        .addInterceptor(CorrelationInterceptor())
        .build()
}
