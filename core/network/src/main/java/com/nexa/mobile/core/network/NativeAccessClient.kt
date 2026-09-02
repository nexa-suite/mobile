package com.nexa.mobile.core.network

import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun normalizeNativeApiBaseUrl(value: String): String {
    val normalized = value.trim().trimEnd('/')
    val uri = runCatching { URI(normalized) }.getOrNull()
    require(
        uri != null && (uri.scheme == "https" || uri.scheme == "http") &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null,
    ) { "Native API base URL must be an absolute HTTP(S) URL without credentials, query or fragment" }
    return normalized
}

enum class ApiClientSurface(val wireValue: String) {
    PLATFORM("PLATFORM"),
    PORTAL("PORTAL");

    companion object {
        fun parse(value: String): ApiClientSurface? = entries.firstOrNull {
            it.wireValue == value.trim().uppercase(Locale.ROOT)
        }
    }
}

data class NativeAuthentication(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val surface: ApiClientSurface,
    val session: AuthenticationSession,
)

data class NativeRefreshCredentials(
    val accessToken: String,
    val refreshToken: String,
    val surface: ApiClientSurface,
)

@Serializable
data class WorkspacePreview(
    val recognized: Boolean,
    val displayName: String? = null,
    val workspaceUrl: String? = null,
    val logoUrl: String? = null,
    val loginAvailable: Boolean,
)

@Serializable
data class AuthenticationSession(
    val userId: String,
    val displayName: String,
    val email: String,
    val preferredLanguage: String,
    val tenantId: String,
    val tenantSlug: String,
    val workspaceId: String,
    val workspaceSlug: String,
    val membershipId: String,
    val roles: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
    val roleDefinitionIds: Set<String> = emptySet(),
    val authorizationVersion: Long = 0,
    val surface: String,
)

@Serializable
data class CurrentSession(
    val user: SessionUser,
    val tenant: SessionTenant,
    val workspace: SessionWorkspace,
    val membership: SessionMembership,
    val surface: String,
)

@Serializable
data class SessionUser(
    val userId: String,
    val displayName: String,
    val email: String,
    val preferredLanguage: String,
)

@Serializable
data class SessionTenant(val tenantId: String, val tenantSlug: String)

@Serializable
data class SessionWorkspace(val workspaceId: String, val workspaceSlug: String)

@Serializable
data class SessionMembership(
    val membershipId: String,
    val roles: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
    val roleDefinitionIds: Set<String> = emptySet(),
    val authorizationVersion: Long = 0,
)

/**
 * Typed client for the native transport evidenced by API v0.17.0.
 * No Operations-to-ClientSurface mapping is selected here; callers must pass
 * an explicitly approved [ApiClientSurface].
 */
class NativeAccessClient(
    baseUrl: String,
    private val executor: HttpRequestExecutor = UrlConnectionHttpRequestExecutor(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val baseUrl = normalizeNativeApiBaseUrl(baseUrl)

    suspend fun workspacePreview(workspaceSlug: String): ApiResult<WorkspacePreview> {
        val normalizedWorkspaceSlug = workspaceSlug.trim()
        if (normalizedWorkspaceSlug.isBlank()) {
            return ApiResult.Failure(
                ApiError(category = ApiErrorCategory.VALIDATION, code = "WORKSPACE_SLUG_REQUIRED"),
            )
        }

        val request = HttpRequest(
            method = "POST",
            url = endpoint("/api/v1/auth/workspace-previews"),
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ),
            body = json.encodeToString(WorkspacePreviewRequest.serializer(), WorkspacePreviewRequest(normalizedWorkspaceSlug)),
        )
        return execute(request) { response ->
            json.decodeFromString(WorkspacePreview.serializer(), response.body)
        }
    }

    suspend fun signIn(
        identifier: String,
        password: String,
        workspaceSlug: String,
        surface: ApiClientSurface,
    ): ApiResult<NativeAuthentication> {
        val request = HttpRequest(
            method = "POST",
            url = endpoint("/api/v1/authentication/sign-in"),
            headers = nativeJsonHeaders(),
            body = json.encodeToString(
                SignInRequest.serializer(),
                SignInRequest(identifier, password, workspaceSlug, surface.wireValue),
            ),
        )
        return execute(request) { response ->
            val refreshToken = response.header("X-Nexa-Refresh-Token")
                ?: return@execute malformed(response.status, "NATIVE_REFRESH_TOKEN_MISSING")
            val payload = json.decodeFromString(AuthenticationResponse.serializer(), response.body)
            if (!payload.tokenType.equals("Bearer", ignoreCase = true)) {
                return@execute malformed(response.status, "UNSUPPORTED_TOKEN_TYPE")
            }
            if (payload.session.surface != surface.wireValue) {
                return@execute malformed(response.status, "AUTH_SURFACE_RESPONSE_MISMATCH")
            }
            NativeAuthentication(
                accessToken = payload.accessToken,
                refreshToken = refreshToken,
                expiresInSeconds = payload.expiresIn,
                surface = surface,
                session = payload.session,
            )
        }
    }

    suspend fun refresh(credentials: NativeRefreshCredentials): ApiResult<NativeAuthentication> {
        val request = HttpRequest(
            method = "POST",
            url = endpoint("/api/v1/authentication/refresh"),
            headers = nativeJsonHeaders() + mapOf(
                "X-Nexa-Surface" to credentials.surface.wireValue,
                "X-Nexa-Refresh-Token" to credentials.refreshToken,
            ),
        )
        return execute(request) { response ->
            val refreshToken = response.header("X-Nexa-Refresh-Token")
                ?: return@execute malformed(response.status, "NATIVE_REFRESH_TOKEN_MISSING")
            val payload = json.decodeFromString(AuthenticationResponse.serializer(), response.body)
            if (!payload.tokenType.equals("Bearer", ignoreCase = true)) {
                return@execute malformed(response.status, "UNSUPPORTED_TOKEN_TYPE")
            }
            if (payload.session.surface != credentials.surface.wireValue) {
                return@execute malformed(response.status, "AUTH_SURFACE_RESPONSE_MISMATCH")
            }
            NativeAuthentication(
                accessToken = payload.accessToken,
                refreshToken = refreshToken,
                expiresInSeconds = payload.expiresIn,
                surface = credentials.surface,
                session = payload.session,
            )
        }
    }

    suspend fun currentSession(accessToken: String): ApiResult<CurrentSession> {
        val request = HttpRequest(
            method = "GET",
            url = endpoint("/api/v1/session"),
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $accessToken",
            ),
        )
        return execute(request) { response ->
            json.decodeFromString(CurrentSession.serializer(), response.body)
        }
    }

    suspend fun signOut(credentials: NativeRefreshCredentials): ApiResult<Unit> {
        val request = HttpRequest(
            method = "POST",
            url = endpoint("/api/v1/authentication/sign-out"),
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer ${credentials.accessToken}",
                "X-Nexa-Client" to "NATIVE",
                "X-Nexa-Surface" to credentials.surface.wireValue,
            ),
        )
        return executeWithoutBody(request)
    }

    suspend fun confirmSession(accessToken: String): ApiResult<Unit> = when (val result = currentSession(accessToken)) {
        is ApiResult.Success -> ApiResult.Success(Unit)
        is ApiResult.Failure -> result
    }

    private fun endpoint(path: String): String = "$baseUrl$path"

    private fun nativeJsonHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "X-Nexa-Client" to "NATIVE",
    )

    @Serializable
    private data class WorkspacePreviewRequest(val workspaceSlug: String)

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
        } catch (_: IOException) {
            ApiResult.Failure(ApiErrorMapper.network())
        } catch (_: RuntimeException) {
            ApiResult.Failure(ApiError(category = ApiErrorCategory.UNKNOWN, code = "MALFORMED_RESPONSE"))
        }
    }

    private suspend fun executeWithoutBody(request: HttpRequest): ApiResult<Unit> = try {
        val response = executor.execute(request)
        if (response.status !in 200..299) ApiResult.Failure(response.error()) else ApiResult.Success(Unit)
    } catch (_: IOException) {
        ApiResult.Failure(ApiErrorMapper.network())
    }

    private fun HttpResponse.error(): ApiError {
        val problem = body.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString(ProblemDetail.serializer(), it) }.getOrNull()
        }
        return ApiErrorMapper.fromHttp(status, problem, header("Retry-After")?.toLongOrNull())
    }

    private fun malformed(status: Int, code: String): Nothing =
        throw MalformedNativeResponse(status, code)

    private fun HttpResponse.header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

    @Serializable
    private data class SignInRequest(
        val identifier: String,
        val password: String,
        val workspaceSlug: String,
        val surface: String,
    )

    @Serializable
    private data class AuthenticationResponse(
        val accessToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val session: AuthenticationSession,
    )

    private class MalformedNativeResponse(val status: Int, val code: String) : RuntimeException()

}
