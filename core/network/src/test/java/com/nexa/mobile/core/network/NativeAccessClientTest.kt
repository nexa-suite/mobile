package com.nexa.mobile.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAccessClientTest {
    @Test
    fun workspacePreviewUsesPublicContractWithoutAuthOrSurfaceHeaders() = runBlocking {
        val executor = RecordingExecutor(
            HttpResponse(
                status = 200,
                body = """
                    {
                      "recognized":true,
                      "displayName":"ICISA",
                      "workspaceUrl":"https://icisa.example.test",
                      "logoUrl":null,
                      "loginAvailable":true
                    }
                """.trimIndent(),
            ),
        )

        val result = NativeAccessClient("https://api.example.test/", executor)
            .workspacePreview(" icisa-test ")

        val preview = (result as ApiResult.Success).value
        assertTrue(preview.recognized)
        assertEquals("ICISA", preview.displayName)
        assertEquals("https://icisa.example.test", preview.workspaceUrl)
        assertEquals("https://api.example.test/api/v1/auth/workspace-previews", executor.request.url)
        assertEquals("POST", executor.request.method)
        assertEquals("application/json", executor.request.headers["Content-Type"])
        assertFalse(executor.request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertFalse(executor.request.headers.containsKey("X-Nexa-Client"))
        assertFalse(executor.request.headers.containsKey("X-Nexa-Surface"))
        assertEquals("{\"workspaceSlug\":\"icisa-test\"}", executor.request.body)
    }

    @Test
    fun unknownWorkspaceRemainsServerAuthoritative() = runBlocking {
        val executor = RecordingExecutor(
            HttpResponse(
                status = 200,
                body = """
                    {
                      "recognized":false,
                      "displayName":null,
                      "workspaceUrl":null,
                      "logoUrl":null,
                      "loginAvailable":false
                    }
                """.trimIndent(),
            ),
        )

        val result = NativeAccessClient("https://api.example.test", executor)
            .workspacePreview("does-not-exist")

        val preview = (result as ApiResult.Success).value
        assertFalse(preview.recognized)
        assertFalse(preview.loginAvailable)
        assertNull(preview.displayName)
        assertNull(preview.workspaceUrl)
    }

    @Test
    fun blankWorkspaceSlugFailsBeforeTransport() = runBlocking {
        val executor = RecordingExecutor(HttpResponse(status = 500))

        val result = NativeAccessClient("https://api.example.test", executor)
            .workspacePreview("  ")

        assertEquals("WORKSPACE_SLUG_REQUIRED", (result as ApiResult.Failure).error.code)
        assertEquals(ApiErrorCategory.VALIDATION, result.error.category)
        assertEquals(0, executor.calls)
    }

    @Test
    fun signInUsesNativeTransportAndReturnsOpaqueRefreshHeader() = runBlocking {
        val executor = RecordingExecutor(
            HttpResponse(
                status = 200,
                headers = mapOf("X-Nexa-Refresh-Token" to listOf("refresh-2")),
                body = authenticationJson("PLATFORM"),
            ),
        )

        val result = NativeAccessClient("https://api.example.test", executor).signIn(
            identifier = "operator@example.test",
            password = "secret-not-persisted",
            workspaceSlug = "cold-chain",
            surface = ApiClientSurface.PLATFORM,
        )

        val authentication = (result as ApiResult.Success).value
        assertEquals("refresh-2", authentication.refreshToken)
        assertEquals("https://api.example.test/api/v1/authentication/sign-in", executor.request.url)
        assertEquals("NATIVE", executor.request.headers["X-Nexa-Client"])
        assertTrue(executor.request.body!!.contains("\"workspaceSlug\":\"cold-chain\""))
        assertTrue(executor.request.body!!.contains("\"surface\":\"PLATFORM\""))
        assertTrue(executor.request.body!!.contains("\"password\":\"secret-not-persisted\""))
    }

    @Test
    fun refreshUsesRotatedHeaderAndDoesNotSendCookiesOrBody() = runBlocking {
        val executor = RecordingExecutor(
            HttpResponse(
                status = 200,
                headers = mapOf("x-nexa-refresh-token" to listOf("refresh-3")),
                body = authenticationJson("PORTAL"),
            ),
        )

        val result = NativeAccessClient("https://api.example.test", executor).refresh(
            NativeRefreshCredentials("access-2", "refresh-2", ApiClientSurface.PORTAL),
        )

        assertEquals("refresh-3", (result as ApiResult.Success).value.refreshToken)
        assertEquals("POST", executor.request.method)
        assertEquals("NATIVE", executor.request.headers["X-Nexa-Client"])
        assertEquals("PORTAL", executor.request.headers["X-Nexa-Surface"])
        assertEquals("refresh-2", executor.request.headers["X-Nexa-Refresh-Token"])
        assertNull(executor.request.body)
        assertFalse(executor.request.headers.keys.any { it.equals("Cookie", ignoreCase = true) })
    }

    @Test
    fun currentSessionUsesBearerAndMapsProblemDetailsSafely() = runBlocking {
        val executor = RecordingExecutor(
            HttpResponse(
                status = 401,
                headers = mapOf("Content-Type" to listOf("application/problem+json")),
                body = "{\"status\":401,\"code\":\"ACCESS_TOKEN_INVALID\",\"retryable\":false}",
            ),
        )

        val result = NativeAccessClient("https://api.example.test", executor).currentSession("access-2")

        val failure = (result as ApiResult.Failure).error
        assertEquals(ApiErrorCategory.UNAUTHORIZED, failure.category)
        assertEquals("ACCESS_TOKEN_INVALID", failure.code)
        assertEquals("Bearer access-2", executor.request.headers["Authorization"])
    }

    @Test
    fun signOutUsesNativeBearerTransportAndAcceptsNoContent() = runBlocking {
        val executor = RecordingExecutor(HttpResponse(status = 204))

        val result = NativeAccessClient("https://api.example.test", executor).signOut(
            NativeRefreshCredentials("access-2", "refresh-2", ApiClientSurface.PLATFORM),
        )

        assertTrue(result is ApiResult.Success)
        assertEquals("NATIVE", executor.request.headers["X-Nexa-Client"])
        assertEquals("Bearer access-2", executor.request.headers["Authorization"])
        assertNull(executor.request.body)
    }

    @Test
    fun nativeSurfaceIsNotInvented() {
        assertEquals(ApiClientSurface.PLATFORM, ApiClientSurface.parse("platform"))
        assertEquals(ApiClientSurface.PORTAL, ApiClientSurface.parse("PORTAL"))
        assertNull(ApiClientSurface.parse("NATIVE"))
        assertNull(ApiClientSurface.parse("OPERATIONS"))
        assertNull(ApiClientSurface.parse("MOBILE"))
    }

    private fun authenticationJson(surface: String): String = """
        {
          "accessToken":"access-2",
          "tokenType":"Bearer",
          "expiresIn":900,
          "session":{
            "userId":"user-1",
            "displayName":"Operator",
            "email":"operator@example.test",
            "preferredLanguage":"en-US",
            "tenantId":"tenant-1",
            "tenantSlug":"tenant",
            "workspaceId":"workspace-1",
            "workspaceSlug":"cold-chain",
            "membershipId":"membership-1",
            "roles":["OPERATOR"],
            "permissions":["catalog:read"],
            "roleDefinitionIds":[],
            "authorizationVersion":2,
            "surface":"$surface"
          }
        }
    """.trimIndent()

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
