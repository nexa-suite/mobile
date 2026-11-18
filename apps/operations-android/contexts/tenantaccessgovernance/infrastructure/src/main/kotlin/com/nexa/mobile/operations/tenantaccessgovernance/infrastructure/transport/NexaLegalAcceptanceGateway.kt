package com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.LegalAcceptanceGateway
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsAcceptResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsAcceptance
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsAcceptanceResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsDocument
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsDocumentResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsSessionLease
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

private const val MY_LEGAL_ACCEPTANCE_PATH = "/api/v1/me/legal-acceptance"
private val legalJson = Json { ignoreUnknownKeys = true }

internal interface PublicMobileTermsService {
    @GET("api/v1/legal/mobile-terms")
    suspend fun getMobileTerms(): Response<MobileTermsWire>
}

@Serializable
internal data class MobileTermsWire(
    val documentId: String,
    val version: String,
    val content: String,
    val contentDigest: String
) {
    fun toDocument(): MobileTermsDocument {
        require(documentId == "mobile-terms" && version.length in 1..80)
        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(contentDigest == expectedDigest)
        return MobileTermsDocument(documentId, version, content, contentDigest)
    }
}

@Serializable
internal data class MobileTermsAcceptanceWire(
    val documentId: String,
    val version: String,
    val content: String,
    val contentDigest: String,
    val accepted: Boolean,
    val acceptedAt: String? = null
) {
    fun toAcceptance(): MobileTermsAcceptance {
        require(accepted == (acceptedAt != null))
        if (acceptedAt != null) Instant.parse(acceptedAt)
        return MobileTermsAcceptance(
            MobileTermsWire(documentId, version, content, contentDigest).toDocument(),
            accepted,
            acceptedAt
        )
    }
}

/** Public terms use the public document endpoint; personal status and consent use authenticated calls. */
class NexaLegalAcceptanceGateway @Inject constructor(
    private val endpoint: ApiEndpoint,
    private val client: OkHttpClient,
    private val sessions: SessionCoordinator,
    private val calls: ProtectedCallExecutor
) : LegalAcceptanceGateway {
    private val publicTerms = Retrofit.Builder()
        .baseUrl(endpoint.url)
        .client(client)
        .addConverterFactory(legalJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PublicMobileTermsService::class.java)

    override suspend fun loadPublicTerms(): MobileTermsDocumentResult = try {
        val response = publicTerms.getMobileTerms()
        if (!response.isSuccessful) return MobileTermsDocumentResult.Unavailable
        val document = response.body()?.toDocument() ?: return MobileTermsDocumentResult.Unavailable
        MobileTermsDocumentResult.Available(document)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MobileTermsDocumentResult.Unavailable
    }

    override suspend fun loadAcceptance(): MobileTermsAcceptanceResult {
        val lease = sessions.currentAccess()
            ?: return MobileTermsAcceptanceResult.SessionExpired
        val leaseIdentity = MobileTermsSessionLease(lease.epoch, lease.generation)
        val result = try {
            calls.execute(
                ProtectedRequest(ProtectedMethod.GET, MY_LEGAL_ACCEPTANCE_PATH, nativeClient = true)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MobileTermsAcceptanceResult.Unavailable(leaseIdentity)
        }
        if (!sessions.isEpochCurrent(lease.epoch)) {
            return MobileTermsAcceptanceResult.SessionExpired
        }
        val currentLease = sessions.currentAccess()
            ?: return MobileTermsAcceptanceResult.SessionExpired
        if (currentLease.epoch != lease.epoch) {
            return MobileTermsAcceptanceResult.SessionExpired
        }
        if (currentLease.generation != lease.generation) {
            return MobileTermsAcceptanceResult.Unavailable(
                MobileTermsSessionLease(currentLease.epoch, currentLease.generation)
            )
        }
        return when (result) {
            is ProtectedResult.Success -> runCatching {
                val wire = legalJson.decodeFromString<MobileTermsAcceptanceWire>(result.body ?: "")
                MobileTermsAcceptanceResult.Available(wire.toAcceptance(), leaseIdentity)
            }.getOrElse { MobileTermsAcceptanceResult.Unavailable(leaseIdentity) }

            is ProtectedResult.Failure -> when (result.error.kind) {
                FailureKind.AuthenticationRequired,
                FailureKind.AuthorizationFailure -> MobileTermsAcceptanceResult.SessionExpired

                FailureKind.NetworkUnavailable,
                FailureKind.Timeout -> MobileTermsAcceptanceResult.Unavailable(
                    leaseIdentity,
                    transportFailure = true
                )

                else -> MobileTermsAcceptanceResult.Unavailable(leaseIdentity)
            }
        }
    }

    override suspend fun accept(document: MobileTermsDocument): MobileTermsAcceptResult {
        val lease = sessions.currentAccess() ?: return MobileTermsAcceptResult.SessionExpired
        val payload = buildJsonObject {
            put("documentId", document.documentId)
            put("version", document.version)
            put("contentDigest", document.contentDigest)
        }.toString()
        val result = try {
            calls.execute(
                ProtectedRequest(
                    ProtectedMethod.POST,
                    MY_LEGAL_ACCEPTANCE_PATH,
                    payload = payload,
                    idempotencyKey = ProtectedRequest.newCommandKey(),
                    nativeClient = true
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return if (sessions.isEpochCurrent(lease.epoch)) {
                MobileTermsAcceptResult.UnknownOutcome
            } else {
                MobileTermsAcceptResult.SessionExpired
            }
        }
        if (!sessions.isEpochCurrent(lease.epoch)) {
            return MobileTermsAcceptResult.SessionExpired
        }
        if (sessions.currentAccess()?.generation != lease.generation) {
            return MobileTermsAcceptResult.UnknownOutcome
        }
        return when (result) {
            is ProtectedResult.Success -> MobileTermsAcceptResult.Accepted

            is ProtectedResult.Failure -> when (result.error.kind) {
                FailureKind.AuthenticationRequired,
                FailureKind.AuthorizationFailure -> MobileTermsAcceptResult.SessionExpired

                FailureKind.BusinessConflict,
                FailureKind.StaleState -> MobileTermsAcceptResult.StaleDocument

                FailureKind.UnknownOutcome,
                FailureKind.NetworkUnavailable,
                FailureKind.Timeout,
                FailureKind.RetryableServerFailure -> MobileTermsAcceptResult.UnknownOutcome

                else -> MobileTermsAcceptResult.Unavailable
            }
        }
    }
}
