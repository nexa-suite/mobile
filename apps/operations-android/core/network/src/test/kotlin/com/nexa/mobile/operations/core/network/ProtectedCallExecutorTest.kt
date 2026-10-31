package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedCallExecutorTest {
    @Test
    fun getRetriesOneIoFailureWithSamePathOriginAndLease() = runBlocking {
        val tokens = FakeAccessTokens()
        val requests = mutableListOf<Request>()
        val executor = executor(tokens) { chain ->
            requests += chain.request()
            if (requests.size == 1) throw IOException("synthetic network failure")
            response(chain.request(), 200)
        }

        val result = executor.execute(ProtectedRequest(ProtectedMethod.GET, PATH))

        assertEquals(200, (result as ProtectedResult.Success).status)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.encodedPath == PATH })
        assertTrue(requests.all { it.url.scheme == "http" && it.url.host == "127.0.0.1" })
        assertTrue(requests.all { it.header("Authorization") != null })
        assertTrue(requests.map { it.header("Authorization") }.distinct().size == 1)
        assertEquals(2, tokens.epochChecks)
        assertEquals(0, tokens.recoverCalls)
    }

    @Test
    fun mutationNetworkFailureRemainsUnknownAndNeverRetries() = runBlocking {
        for (method in listOf(
            ProtectedMethod.POST,
            ProtectedMethod.PUT,
            ProtectedMethod.PATCH,
            ProtectedMethod.DELETE
        )) {
            val tokens = FakeAccessTokens()
            val requests = mutableListOf<Request>()
            val executor = executor(tokens) { chain ->
                requests += chain.request()
                throw IOException("synthetic mutation failure")
            }

            val result = executor.execute(
                ProtectedRequest(method, PATH, payload = "{}", idempotencyKey = "test-command")
            )

            assertEquals(FailureKind.UnknownOutcome, failureKind(result))
            assertEquals(1, requests.size)
            assertEquals(0, tokens.recoverCalls)
        }
    }

    @Test
    fun changedEpochPreventsGetRetryAndFailsClosed() = runBlocking {
        val tokens = FakeAccessTokens()
        val requests = mutableListOf<Request>()
        val executor = executor(tokens) { chain ->
            requests += chain.request()
            tokens.currentEpoch = tokens.initialLease.epoch + 1
            throw IOException("synthetic failure after scope change")
        }

        val result = executor.execute(ProtectedRequest(ProtectedMethod.GET, PATH))

        assertEquals(FailureKind.AuthenticationRequired, failureKind(result))
        assertEquals(1, requests.size)
        assertEquals(0, tokens.recoverCalls)
    }

    @Test
    fun socketTimeoutDoesNotRetryGet() = runBlocking {
        val tokens = FakeAccessTokens()
        val requests = mutableListOf<Request>()
        val executor = executor(tokens) { chain ->
            requests += chain.request()
            throw SocketTimeoutException("synthetic timeout")
        }

        val result = executor.execute(ProtectedRequest(ProtectedMethod.GET, PATH))

        assertEquals(FailureKind.Timeout, failureKind(result))
        assertEquals(1, requests.size)
        assertEquals(1, tokens.epochChecks)
    }

    @Test
    fun httpFailureDoesNotRetryGet() = runBlocking {
        val tokens = FakeAccessTokens()
        val requests = mutableListOf<Request>()
        val executor = executor(tokens) { chain ->
            requests += chain.request()
            response(chain.request(), 503)
        }

        val result = executor.execute(ProtectedRequest(ProtectedMethod.GET, PATH))

        assertEquals(FailureKind.RetryableServerFailure, failureKind(result))
        assertEquals(1, requests.size)
        assertEquals(0, tokens.recoverCalls)
    }

    @Test
    fun unauthorizedGetKeepsExistingRefreshAndReplayFlow() = runBlocking {
        val tokens = FakeAccessTokens().apply {
            replacementLease = AccessTokenLease("replacement-test-lease", 2, initialLease.epoch)
        }
        val requests = mutableListOf<Request>()
        val executor = executor(tokens) { chain ->
            requests += chain.request()
            if (requests.size == 1) {
                response(chain.request(), 401)
            } else {
                response(chain.request(), 200)
            }
        }

        val result = executor.execute(ProtectedRequest(ProtectedMethod.GET, PATH))

        assertEquals(200, (result as ProtectedResult.Success).status)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.encodedPath == PATH })
        assertTrue(requests[0].header("Authorization") != requests[1].header("Authorization"))
        assertEquals(1, tokens.recoverCalls)
    }

    private fun executor(tokens: FakeAccessTokens, interceptor: Interceptor) =
        ProtectedCallExecutor(
            ApiEndpoint("http://127.0.0.1/"),
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(interceptor)
                .build(),
            tokens
        )

    private fun response(request: Request, status: Int): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("synthetic response")
        .body("{}".toResponseBody("application/json".toMediaType()))
        .build()

    private fun failureKind(result: ProtectedResult): FailureKind =
        (result as ProtectedResult.Failure).error.kind

    private class FakeAccessTokens : AccessTokenSource {
        val initialLease = AccessTokenLease("synthetic-test-lease", 1, 4)
        var replacementLease: AccessTokenLease? = null
        var currentEpoch = initialLease.epoch
        var recoverCalls = 0
        var epochChecks = 0

        override val sessionState: StateFlow<SessionState> =
            MutableStateFlow(SessionState.Active)

        override suspend fun currentAccess(): AccessTokenLease = initialLease

        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? {
            recoverCalls++
            return replacementLease
        }

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit

        override suspend fun isEpochCurrent(epoch: Long): Boolean {
            epochChecks++
            return epoch == currentEpoch
        }
    }

    private companion object {
        const val PATH = "/api/v1/runtime-check"
    }
}
