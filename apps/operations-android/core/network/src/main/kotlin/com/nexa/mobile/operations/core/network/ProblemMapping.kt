package com.nexa.mobile.operations.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers

enum class FailureKind {
    ValidationFailure,
    AuthenticationRequired,
    AuthorizationFailure,
    ResourceUnavailable,
    BusinessConflict,
    StaleState,
    PreconditionRequired,
    Throttled,
    NetworkUnavailable,
    Timeout,
    RetryableServerFailure,
    UnknownOutcome,
    ProtocolFailure
}

/** Safe to pass across app boundaries; raw server detail remains inside the network module. */
class ClientFailure internal constructor(
    val kind: FailureKind,
    val httpStatus: Int? = null,
    val problemCode: String? = null,
    val problemCategory: String? = null,
    val retryable: Boolean? = null,
    val serverCorrelationId: String? = null,
    internal val problem: ProblemDetails? = null
) {
    override fun toString(): String =
        "ClientFailure(kind=$kind, httpStatus=$httpStatus, problemCode=$problemCode, serverCorrelationId=$serverCorrelationId)"
}

@Serializable
internal data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val code: String? = null,
    val category: String? = null,
    val retryable: Boolean? = null,
    val correlationId: String? = null
) {
    override fun toString(): String = "ProblemDetails(status=$status, code=$code, detail=REDACTED)"
}

internal object ProblemMapping {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromHttp(
        status: Int,
        headers: Headers,
        contentType: String?,
        body: String?,
        mutation: Boolean
    ): ClientFailure {
        val problem = if (contentType?.substringBefore(
                ';'
            )?.trim()?.equals("application/problem+json", true) ==
            true
        ) {
            try {
                body?.let { json.decodeFromString<ProblemDetails>(it) }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val serverKind =
            if (mutation) FailureKind.UnknownOutcome else FailureKind.RetryableServerFailure
        val kind = when (status) {
            400, 422 -> FailureKind.ValidationFailure
            401 -> FailureKind.AuthenticationRequired
            403 -> FailureKind.AuthorizationFailure
            404 -> FailureKind.ResourceUnavailable
            409 -> FailureKind.BusinessConflict
            412 -> FailureKind.StaleState
            428 -> FailureKind.PreconditionRequired
            429 -> FailureKind.Throttled
            in 500..599 -> serverKind
            else -> FailureKind.ProtocolFailure
        }
        return ClientFailure(
            kind = kind,
            httpStatus = status,
            problemCode = problem?.code,
            problemCategory = problem?.category,
            retryable = problem?.retryable,
            serverCorrelationId = headers["X-Correlation-ID"] ?: problem?.correlationId,
            problem = problem
        )
    }

    fun local(kind: FailureKind): ClientFailure = ClientFailure(kind)
}
