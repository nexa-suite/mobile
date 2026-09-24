package com.nexa.mobile.operations.core.network

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProblemMappingTest {
    @Test
    fun httpStatusWinsOverInconsistentProblemCategory() {
        val problem =
            """{"type":"about:blank","title":"Unauthorized","status":401,
               "detail":"sensitive detail","instance":"/internal","code":"UNAUTHORIZED",
               "category":"CLIENT_ERROR","retryable":false,"correlationId":"body-id"}"""
        val mapped = ProblemMapping.fromHttp(
            401,
            Headers.headersOf("X-Correlation-ID", "server-id"),
            "application/problem+json",
            problem,
            false
        )
        assertEquals(FailureKind.AuthenticationRequired, mapped.kind)
        assertEquals("UNAUTHORIZED", mapped.problemCode)
        assertEquals("CLIENT_ERROR", mapped.problemCategory)
        assertEquals(false, mapped.retryable)
        assertEquals("server-id", mapped.serverCorrelationId)
        assertEquals("about:blank", mapped.problem?.type)
        assertEquals("Unauthorized", mapped.problem?.title)
        assertEquals(401, mapped.problem?.status)
        assertEquals("sensitive detail", mapped.problem?.detail)
        assertEquals("/internal", mapped.problem?.instance)
        assertEquals("body-id", mapped.problem?.correlationId)
        assertFalse(mapped.toString().contains("sensitive detail"))
        assertFalse(mapped.problem.toString().contains("sensitive detail"))
    }

    @Test
    fun requiredErrorTaxonomyMapsWithoutTrustingProblemBody() {
        val expected = mapOf(
            400 to FailureKind.ValidationFailure,
            401 to FailureKind.AuthenticationRequired,
            403 to FailureKind.AuthorizationFailure,
            404 to FailureKind.ResourceUnavailable,
            409 to FailureKind.BusinessConflict,
            412 to FailureKind.StaleState,
            428 to FailureKind.PreconditionRequired,
            429 to FailureKind.Throttled,
            503 to FailureKind.RetryableServerFailure
        )
        expected.forEach { (status, kind) ->
            assertEquals(
                kind,
                ProblemMapping.fromHttp(
                    status,
                    Headers.Builder().build(),
                    "application/problem+json",
                    "not-json",
                    false
                ).kind
            )
        }
        assertEquals(
            FailureKind.UnknownOutcome,
            ProblemMapping.fromHttp(
                503,
                Headers.Builder().build(),
                "application/problem+json",
                "{}",
                true
            ).kind
        )
        assertEquals(
            FailureKind.AuthenticationRequired,
            ProblemMapping.fromHttp(
                401,
                Headers.Builder().build(),
                "text/html",
                "unauthorized",
                false
            ).kind
        )
        assertNull(
            ProblemMapping.fromHttp(
                400,
                Headers.Builder().build(),
                "application/problem+json",
                "{",
                false
            ).problem
        )
    }
}
