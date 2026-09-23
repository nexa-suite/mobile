package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

enum class ProtectedMethod { GET, POST, PUT, PATCH, DELETE }

/** Caller-owned logical command identity remains stable through an eligible 401 replay. */
class ProtectedRequest(
    val method: ProtectedMethod,
    val path: String,
    val payload: String? = null,
    val idempotencyKey: String? = null,
    val ifMatch: String? = null
) {
    init {
        require(path.startsWith("/api/v1/") && "//" !in path && "://" !in path)
        require(
            idempotencyKey == null || (idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        )
        require(ifMatch == null || ifMatch.isNotBlank())
        require(method != ProtectedMethod.GET || payload == null)
    }

    val isMutation: Boolean get() = method != ProtectedMethod.GET
    val replayEligible: Boolean get() = !isMutation || idempotencyKey != null

    override fun toString(): String =
        "ProtectedRequest(method=$method, path=REDACTED, payload=REDACTED)"

    companion object {
        fun newCommandKey(): String = UUID.randomUUID().toString()
    }
}

sealed interface ProtectedResult {
    class Success(
        val status: Int,
        val body: String?,
        val etag: String?,
        val serverCorrelationId: String?
    ) : ProtectedResult {
        override fun toString(): String = "Success(status=$status, body=REDACTED, etag=REDACTED)"
    }

    data class Failure(val error: ClientFailure) : ProtectedResult
}

class ProtectedCallExecutor(
    private val endpoint: ApiEndpoint,
    private val client: OkHttpClient,
    private val tokens: AccessTokenSource
) {
    suspend fun execute(command: ProtectedRequest): ProtectedResult {
        val access = tokens.currentAccess()
            ?: return ProtectedResult.Failure(
                ProblemMapping.local(FailureKind.AuthenticationRequired)
            )
        val url = endpoint.url.resolve(command.path)
            ?: return ProtectedResult.Failure(ProblemMapping.local(FailureKind.ProtocolFailure))
        if (!endpoint.isTrusted(url) || !url.encodedPath.startsWith("/api/v1/")) {
            return ProtectedResult.Failure(ProblemMapping.local(FailureKind.ProtocolFailure))
        }

        val first = exchange(command, access, url.toString())
        if (!tokens.isEpochCurrent(access.epoch)) {
            return ProtectedResult.Failure(ProblemMapping.local(FailureKind.AuthenticationRequired))
        }
        if (first is Exchange.NetworkFailure) return networkFailure(command, first.cause)
        first as Exchange.Http
        if (first.status != 401 || !command.replayEligible) return mapResponse(command, first)

        val replacement = tokens.recoverAfterUnauthorized(access)
            ?: return ProtectedResult.Failure(
                ProblemMapping.local(FailureKind.AuthenticationRequired)
            )
        if (!tokens.isEpochCurrent(access.epoch)) {
            return ProtectedResult.Failure(ProblemMapping.local(FailureKind.AuthenticationRequired))
        }
        val replay = exchange(command, replacement, url.toString())
        if (!tokens.isEpochCurrent(access.epoch)) {
            return ProtectedResult.Failure(ProblemMapping.local(FailureKind.AuthenticationRequired))
        }
        if (replay is Exchange.NetworkFailure) return networkFailure(command, replay.cause)
        replay as Exchange.Http
        if (replay.status == 401) tokens.rejectCurrentAccess(replacement)
        return mapResponse(command, replay)
    }

    private fun mapResponse(command: ProtectedRequest, response: Exchange.Http): ProtectedResult =
        if (response.status in 200..299) {
            ProtectedResult.Success(
                response.status,
                response.body,
                response.etag,
                response.correlationId
            )
        } else {
            ProtectedResult.Failure(
                ProblemMapping.fromHttp(
                    response.status,
                    response.headers,
                    response.contentType,
                    response.body,
                    command.isMutation
                )
            )
        }

    private fun networkFailure(
        command: ProtectedRequest,
        error: IOException
    ): ProtectedResult.Failure {
        val kind = when {
            command.isMutation -> FailureKind.UnknownOutcome
            error is SocketTimeoutException -> FailureKind.Timeout
            else -> FailureKind.NetworkUnavailable
        }
        return ProtectedResult.Failure(ProblemMapping.local(kind))
    }

    private suspend fun exchange(
        command: ProtectedRequest,
        access: AccessTokenLease,
        url: String
    ): Exchange {
        val body = if (command.isMutation) {
            (command.payload ?: "").toRequestBody("application/json".toMediaType())
        } else {
            null
        }
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${access.value}")
            .apply {
                command.idempotencyKey?.let { header("Idempotency-Key", it) }
                command.ifMatch?.let { header("If-Match", it) }
            }
            .method(command.method.name, body)
            .build()
        return try {
            val response = client.newCall(request).await()
            response.use {
                Exchange.Http(
                    status = it.code,
                    headers = it.headers,
                    body = it.body?.string(),
                    contentType = it.body?.contentType()?.toString(),
                    etag = it.header("ETag"),
                    correlationId = it.header("X-Correlation-ID")
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            Exchange.NetworkFailure(failure)
        }
    }

    private sealed interface Exchange {
        data class Http(
            val status: Int,
            val headers: okhttp3.Headers,
            val body: String?,
            val contentType: String?,
            val etag: String?,
            val correlationId: String?
        ) : Exchange

        data class NetworkFailure(val cause: IOException) : Exchange
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
