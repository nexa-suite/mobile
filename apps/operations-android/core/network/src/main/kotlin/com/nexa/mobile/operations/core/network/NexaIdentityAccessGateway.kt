package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.IssuedNativeSession
import java.io.IOException
import java.time.Clock
import java.time.Instant
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

private const val NATIVE_CLIENT = "NATIVE"
private const val PLATFORM_SURFACE = "PLATFORM"
private const val ACCESS_CONTEXT_TICKET_HEADER = "X-Nexa-Context-Ticket"
private val identityAccessJson = Json { ignoreUnknownKeys = true }

internal interface NativeIdentityAccessService {
    @POST("api/v1/authentication/identity-sign-in")
    suspend fun identitySignIn(
        @Header("X-Nexa-Client") client: String = NATIVE_CLIENT,
        @Body body: IdentitySignInRequest
    ): Response<IdentitySignInResponse>

    @GET("api/v1/me/access-contexts")
    suspend fun accessContexts(
        @Header("X-Nexa-Client") client: String = NATIVE_CLIENT,
        @Header("X-Nexa-Surface") surface: String = PLATFORM_SURFACE,
        @Header(ACCESS_CONTEXT_TICKET_HEADER) ticket: String? = null,
        @Header("Authorization") authorization: String? = null
    ): Response<AccessContextsResponse>

    @POST("api/v1/me/access-context-selections")
    suspend fun selectAccessContext(
        @Header("X-Nexa-Client") client: String = NATIVE_CLIENT,
        @Header("X-Nexa-Surface") surface: String = PLATFORM_SURFACE,
        @Header(ACCESS_CONTEXT_TICKET_HEADER) ticket: String? = null,
        @Header("Authorization") authorization: String? = null,
        @Body body: AccessContextSelectionRequest
    ): Response<IdentityAuthenticationBody>
}

@Serializable
internal data class IdentitySignInRequest(
    val identifier: String,
    val password: String,
    val surface: String
) {
    override fun toString(): String =
        "IdentitySignInRequest(identifier=REDACTED, password=REDACTED, surface=$surface)"
}

@Serializable
internal data class IdentitySignInResponse(
    val outcome: String? = null,
    val session: IdentityAuthenticationBody? = null,
    val ticketExpiresAt: String? = null
) {
    override fun toString(): String =
        "IdentitySignInResponse(outcome=$outcome, session=${session != null}, " +
            "ticketExpiresAt=$ticketExpiresAt)"
}

@Serializable
internal data class IdentityAuthenticationBody(
    val accessToken: String? = null,
    val tokenType: String? = null,
    val expiresIn: Long? = null,
    val session: NativeAuthenticationSession? = null
) {
    override fun toString(): String =
        "IdentityAuthenticationBody(accessToken=REDACTED, refreshCredential=REDACTED, " +
            "session=${session != null})"
}

@Serializable
data class NativeAuthenticationSession(
    val userId: String? = null,
    val tenantId: String? = null,
    val tenantSlug: String? = null,
    val workspaceId: String? = null,
    val workspaceSlug: String? = null,
    val membershipId: String? = null,
    val permissions: Set<String> = emptySet(),
    val surface: String? = null
) {
    override fun toString(): String =
        "NativeAuthenticationSession(surface=$surface, membershipId=REDACTED)"
}

@Serializable
internal data class AccessContextsResponse(
    val accessContexts: List<AccessContextBody> = emptyList()
) {
    override fun toString(): String =
        "AccessContextsResponse(accessContexts=${accessContexts.size})"
}

@Serializable
internal data class AccessContextBody(
    val membershipId: String? = null,
    val tenantId: String? = null,
    val tenantName: String? = null,
    val tenantSlug: String? = null,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val workspaceSlug: String? = null
) {
    override fun toString(): String = "AccessContextBody(membershipId=REDACTED)"
}

@Serializable
internal data class AccessContextSelectionRequest(val membershipId: String) {
    override fun toString(): String = "AccessContextSelectionRequest(membershipId=REDACTED)"
}

data class NativeAccessContext(
    val membershipId: String,
    val tenantId: String,
    val tenantName: String,
    val tenantSlug: String,
    val workspaceId: String,
    val workspaceName: String,
    val workspaceSlug: String
) {
    override fun toString(): String = "NativeAccessContext(membershipId=REDACTED)"
}

sealed interface IdentitySignInOutcome {
    data class Authenticated(
        val issuedSession: IssuedNativeSession,
        val sessionContext: NativeAuthenticationSession
    ) : IdentitySignInOutcome

    data object SelectionRequired : IdentitySignInOutcome
    data object NoWorkContext : IdentitySignInOutcome
    data object Rejected : IdentitySignInOutcome
    data object NetworkUnavailable : IdentitySignInOutcome
    data object ServiceUnavailable : IdentitySignInOutcome
    data object UnknownOutcome : IdentitySignInOutcome
}

sealed interface AccessContextsOutcome {
    data class Available(val contexts: List<NativeAccessContext>) : AccessContextsOutcome
    data object TicketExpired : AccessContextsOutcome
    data object SessionExpired : AccessContextsOutcome
    data object Rejected : AccessContextsOutcome
    data object NetworkUnavailable : AccessContextsOutcome
    data object ServiceUnavailable : AccessContextsOutcome
}

sealed interface AccessContextSelectionOutcome {
    data class Authenticated(
        val issuedSession: IssuedNativeSession,
        val sessionContext: NativeAuthenticationSession
    ) : AccessContextSelectionOutcome

    data object Rejected : AccessContextSelectionOutcome
    data object SessionExpired : AccessContextSelectionOutcome
    data object UnknownOutcome : AccessContextSelectionOutcome
}

/** Native Wave 2 transport. Ticket stays memory-only and is consumed before selection dispatch. */
class NexaIdentityAccessGateway private constructor(
    private val service: NativeIdentityAccessService,
    private val clock: Clock
) {
    private val ticketLock = Any()
    private var pendingTicket: PendingTicket? = null

    suspend fun identitySignIn(identifier: String, password: String): IdentitySignInOutcome {
        synchronized(ticketLock) { pendingTicket = null }
        return try {
            val response = service.identitySignIn(
                body = IdentitySignInRequest(
                    identifier = identifier,
                    password = password,
                    surface = PLATFORM_SURFACE
                )
            )
            when {
                response.code() == 401 -> IdentitySignInOutcome.Rejected

                response.code() in 500..599 -> IdentitySignInOutcome.UnknownOutcome

                !response.isSuccessful -> IdentitySignInOutcome.Rejected

                else -> when (response.body()?.outcome) {
                    "NO_WORK_CONTEXT" -> IdentitySignInOutcome.NoWorkContext

                    "SESSION_ESTABLISHED" -> {
                        val auth = response.body()?.session
                            ?: return IdentitySignInOutcome.ServiceUnavailable
                        val issued = auth.issuedSession(response.headers()["X-Nexa-Refresh-Token"])
                            ?: return IdentitySignInOutcome.ServiceUnavailable
                        val sessionContext = auth.session
                            ?: return IdentitySignInOutcome.ServiceUnavailable
                        if (sessionContext.surface != PLATFORM_SURFACE) {
                            IdentitySignInOutcome.ServiceUnavailable
                        } else {
                            IdentitySignInOutcome.Authenticated(issued, sessionContext)
                        }
                    }

                    "CONTEXT_SELECTION_REQUIRED" -> {
                        val body = response.body()
                        val rawTicket = response.headers()[ACCESS_CONTEXT_TICKET_HEADER]
                        val expiry = body?.ticketExpiresAt?.let(::parseInstant)
                        if (rawTicket.isNullOrBlank() || expiry == null ||
                            expiry <= clock.instant() ||
                            body.session != null ||
                            response.headers()["X-Nexa-Refresh-Token"] != null
                        ) {
                            IdentitySignInOutcome.ServiceUnavailable
                        } else {
                            synchronized(ticketLock) {
                                pendingTicket =
                                    PendingTicket(rawTicket, expiry)
                            }
                            IdentitySignInOutcome.SelectionRequired
                        }
                    }

                    else -> IdentitySignInOutcome.ServiceUnavailable
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            IdentitySignInOutcome.UnknownOutcome
        } catch (_: Exception) {
            IdentitySignInOutcome.ServiceUnavailable
        }
    }

    suspend fun accessContexts(accessToken: String? = null): AccessContextsOutcome {
        val ticket = if (accessToken == null) {
            validTicket() ?: return AccessContextsOutcome.TicketExpired
        } else {
            null
        }
        if (accessToken != null && accessToken.isBlank()) return AccessContextsOutcome.Rejected
        return try {
            val response = service.accessContexts(
                ticket = ticket?.value,
                authorization = accessToken?.let { "Bearer $it" }
            )
            when {
                accessToken != null && response.code() == 401 ->
                    AccessContextsOutcome.SessionExpired

                response.code() in setOf(401, 403, 404, 410) -> {
                    clearTicket()
                    AccessContextsOutcome.TicketExpired
                }

                response.code() in 500..599 -> AccessContextsOutcome.ServiceUnavailable

                !response.isSuccessful -> AccessContextsOutcome.Rejected

                else -> {
                    val responseContexts = response.body()?.accessContexts
                        ?: return AccessContextsOutcome.Rejected
                    val contexts = responseContexts.map {
                        it.toClientContext() ?: return AccessContextsOutcome.Rejected
                    }
                    AccessContextsOutcome.Available(contexts)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            AccessContextsOutcome.NetworkUnavailable
        } catch (_: Exception) {
            AccessContextsOutcome.Rejected
        }
    }

    suspend fun selectAccessContext(
        membershipId: String,
        accessToken: String? = null
    ): AccessContextSelectionOutcome {
        val ticket = if (accessToken == null) {
            consumeTicket() ?: return AccessContextSelectionOutcome.Rejected
        } else {
            null
        }
        if (accessToken != null && accessToken.isBlank()) {
            return AccessContextSelectionOutcome.Rejected
        }
        return try {
            val response = service.selectAccessContext(
                ticket = ticket?.value,
                authorization = accessToken?.let { "Bearer $it" },
                body = AccessContextSelectionRequest(membershipId)
            )
            if (!response.isSuccessful) {
                if (accessToken != null && response.code() == 401) {
                    AccessContextSelectionOutcome.SessionExpired
                } else if (response.code() in 500..599) {
                    AccessContextSelectionOutcome.UnknownOutcome
                } else {
                    AccessContextSelectionOutcome.Rejected
                }
            } else {
                val auth = response.body()
                val issued = auth?.issuedSession(response.headers()["X-Nexa-Refresh-Token"])
                val sessionContext = auth?.session
                if (issued == null || sessionContext == null ||
                    sessionContext.surface != PLATFORM_SURFACE ||
                    sessionContext.membershipId != membershipId
                ) {
                    AccessContextSelectionOutcome.UnknownOutcome
                } else {
                    AccessContextSelectionOutcome.Authenticated(issued, sessionContext)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AccessContextSelectionOutcome.UnknownOutcome
        }
    }

    private fun validTicket(): PendingTicket? = synchronized(ticketLock) {
        val current = pendingTicket
        if (current == null || current.expiresAt <= clock.instant()) {
            pendingTicket = null
            null
        } else {
            current
        }
    }

    private fun consumeTicket(): PendingTicket? = synchronized(ticketLock) {
        val current = pendingTicket
        pendingTicket = null
        current?.takeIf { it.expiresAt > clock.instant() }
    }

    private fun clearTicket() = synchronized(ticketLock) { pendingTicket = null }

    private fun parseInstant(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: Exception) {
        null
    }

    private data class PendingTicket(val value: String, val expiresAt: Instant) {
        override fun toString(): String = "PendingTicket(value=REDACTED, expiresAt=$expiresAt)"
    }

    companion object {
        fun create(
            endpoint: ApiEndpoint,
            client: OkHttpClient = ApiHttpClient.create(endpoint),
            clock: Clock = Clock.systemUTC()
        ): NexaIdentityAccessGateway {
            val retrofit = Retrofit.Builder()
                .baseUrl(endpoint.url)
                .client(client)
                .addConverterFactory(
                    identityAccessJson.asConverterFactory("application/json".toMediaType())
                )
                .build()
            return NexaIdentityAccessGateway(
                retrofit.create(NativeIdentityAccessService::class.java),
                clock
            )
        }
    }
}

private fun IdentityAuthenticationBody.issuedSession(
    refreshCredential: String?
): IssuedNativeSession? {
    val access = accessToken?.takeIf(String::isNotBlank) ?: return null
    val refresh = refreshCredential?.takeIf(String::isNotBlank) ?: return null
    return try {
        IssuedNativeSession(access, refresh)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun AccessContextBody.toClientContext(): NativeAccessContext? {
    val membership = membershipId?.takeIf(String::isNotBlank) ?: return null
    val tenant = tenantId?.takeIf(String::isNotBlank) ?: return null
    val tenantDisplay = tenantName?.takeIf(String::isNotBlank) ?: return null
    val tenantCode = tenantSlug?.takeIf(String::isNotBlank) ?: return null
    val workspace = workspaceId?.takeIf(String::isNotBlank) ?: return null
    val workspaceDisplay = workspaceName?.takeIf(String::isNotBlank) ?: return null
    val workspaceCode = workspaceSlug?.takeIf(String::isNotBlank) ?: return null
    return NativeAccessContext(
        membership,
        tenant,
        tenantDisplay,
        tenantCode,
        workspace,
        workspaceDisplay,
        workspaceCode
    )
}
