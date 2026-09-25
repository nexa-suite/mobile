package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AuthGatewayFailure
import com.nexa.mobile.operations.core.auth.session.AuthRemoteGateway
import com.nexa.mobile.operations.core.auth.session.IssuedNativeSession
import com.nexa.mobile.operations.core.auth.session.NativeSignIn
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

private const val NATIVE = "NATIVE"
private const val PLATFORM = "PLATFORM"

internal interface NativeAuthService {
    @POST("api/v1/authentication/sign-in")
    suspend fun signIn(
        @Header("X-Nexa-Client") client: String = NATIVE,
        @Body body: SignInBody
    ): Response<AuthenticationBody>

    @POST("api/v1/authentication/refresh")
    suspend fun refresh(
        @Header("X-Nexa-Client") client: String = NATIVE,
        @Header("X-Nexa-Surface") surface: String = PLATFORM,
        @Header("X-Nexa-Refresh-Token") credential: String
    ): Response<AuthenticationBody>

    @POST("api/v1/authentication/sign-out")
    suspend fun signOut(
        @Header("Authorization") authorization: String,
        @Header("X-Nexa-Client") client: String = NATIVE
    ): Response<Unit>

    @GET("api/v1/session")
    suspend fun session(@Header("Authorization") authorization: String): Response<SessionBody>
}

@Serializable
internal data class SignInBody(
    val identifier: String,
    val password: String,
    val workspaceSlug: String,
    val surface: String
)

@Serializable
internal data class AuthenticationBody(val accessToken: String)

@Serializable
internal data class SessionBody(
    val user: SessionUserBody? = null,
    val tenant: TenantBody? = null,
    val workspace: WorkspaceBody? = null,
    val membership: MembershipBody? = null,
    val surface: String? = null
)

@Serializable
internal data class SessionUserBody(val userId: String? = null)

@Serializable
internal data class TenantBody(
    val tenantId: String? = null,
    val tenantName: String? = null,
    val tenantSlug: String? = null
)

@Serializable
internal data class WorkspaceBody(
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val workspaceSlug: String? = null
)

@Serializable
internal data class MembershipBody(
    val membershipId: String? = null,
    val permissions: Set<String> = emptySet()
)

class NexaAuthGateway private constructor(private val service: NativeAuthService) :
    AuthRemoteGateway {
    override suspend fun signIn(input: NativeSignIn): IssuedNativeSession =
        runCall(ambiguousOnIo = false) {
            issue(
                service.signIn(
                    body = SignInBody(
                        input.identifier,
                        input.password,
                        input.workspaceSlug,
                        PLATFORM
                    )
                )
            )
        }

    override suspend fun refresh(credential: String): IssuedNativeSession =
        runCall(ambiguousOnIo = true) {
            requireHeaderValue(credential)
            issue(service.refresh(credential = credential), ambiguousOnFailure = true)
        }

    override suspend fun currentSession(accessToken: String): VerifiedSession =
        runCall(ambiguousOnIo = false) {
            val response = service.session(bearer(accessToken))
            if (!response.isSuccessful) throw failure(response.code(), ambiguousOnFailure = false)
            val body = response.body() ?: throw AuthGatewayFailure.ProtocolFailure()
            VerifiedSession(
                body.surface == PLATFORM &&
                    !body.user?.userId.isNullOrBlank() &&
                    !body.tenant?.tenantId.isNullOrBlank() &&
                    !body.workspace?.workspaceId.isNullOrBlank() &&
                    !body.membership?.membershipId.isNullOrBlank(),
                userId = body.user?.userId,
                tenantId = body.tenant?.tenantId,
                tenantName = body.tenant?.tenantName,
                tenantSlug = body.tenant?.tenantSlug,
                workspaceId = body.workspace?.workspaceId,
                workspaceName = body.workspace?.workspaceName,
                workspaceSlug = body.workspace?.workspaceSlug,
                membershipId = body.membership?.membershipId,
                permissions = body.membership?.permissions.orEmpty()
            )
        }

    override suspend fun signOut(accessToken: String): Unit = runCall(ambiguousOnIo = false) {
        val response = service.signOut(bearer(accessToken))
        if (response.code() != 204) throw failure(response.code(), ambiguousOnFailure = false)
    }

    private fun issue(
        response: Response<AuthenticationBody>,
        ambiguousOnFailure: Boolean = false
    ): IssuedNativeSession {
        if (!response.isSuccessful) throw failure(response.code(), ambiguousOnFailure)
        val access = response.body()?.accessToken ?: throw AuthGatewayFailure.ProtocolFailure()
        val refresh =
            response.headers()["X-Nexa-Refresh-Token"] ?: throw AuthGatewayFailure.ProtocolFailure()
        if (access.isBlank() || refresh.isBlank()) throw AuthGatewayFailure.ProtocolFailure()
        requireHeaderValue(access)
        requireHeaderValue(refresh)
        return IssuedNativeSession(access, refresh)
    }

    private fun bearer(token: String): String {
        requireHeaderValue(token)
        return "Bearer $token"
    }

    private fun requireHeaderValue(value: String) {
        if (value.isBlank() || '\r' in value ||
            '\n' in value
        ) {
            throw AuthGatewayFailure.ProtocolFailure()
        }
    }

    private suspend fun <T> runCall(ambiguousOnIo: Boolean, call: suspend () -> T): T = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AuthGatewayFailure) {
        throw failure
    } catch (_: IOException) {
        if (ambiguousOnIo) throw AuthGatewayFailure.AmbiguousOutcome()
        throw AuthGatewayFailure.NetworkUnavailable()
    } catch (_: Exception) {
        throw AuthGatewayFailure.ProtocolFailure()
    }

    companion object {
        fun create(
            endpoint: ApiEndpoint,
            client: OkHttpClient = ApiHttpClient.create(endpoint)
        ): NexaAuthGateway {
            val json = Json { ignoreUnknownKeys = true }
            val retrofit = Retrofit.Builder()
                .baseUrl(endpoint.url)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            return NexaAuthGateway(retrofit.create(NativeAuthService::class.java))
        }
    }
}

private fun failure(status: Int, ambiguousOnFailure: Boolean): AuthGatewayFailure = when {
    status == 401 || status == 403 -> AuthGatewayFailure.DefinitiveRejection()
    ambiguousOnFailure && status >= 500 -> AuthGatewayFailure.AmbiguousOutcome()
    status >= 500 -> AuthGatewayFailure.NetworkUnavailable()
    else -> AuthGatewayFailure.ProtocolFailure()
}
