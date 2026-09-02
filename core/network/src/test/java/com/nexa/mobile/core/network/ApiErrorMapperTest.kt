package com.nexa.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorMapperTest {
    @Test
    fun mapsTaggedProblemDetailsAndPreservesOnlySafeCode() {
        val result = ApiErrorMapper.fromHttp(
            status = 401,
            problem = ProblemDetail(
                status = 401,
                code = "ACCESS_TOKEN_INVALID",
                category = "AUTHENTICATION",
                retryable = false,
                detail = "must not be exposed to UI",
            ),
        )

        assertEquals(ApiErrorCategory.UNAUTHORIZED, result.category)
        assertEquals("ACCESS_TOKEN_INVALID", result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun mapsForbidden() {
        assertEquals(ApiErrorCategory.FORBIDDEN, ApiErrorMapper.fromHttp(403).category)
    }

    @Test
    fun mapsNetworkFailureAsRetryable() {
        val result = ApiErrorMapper.network()

        assertEquals(ApiErrorCategory.NETWORK, result.category)
        assertTrue(result.retryable)
        assertNull(result.status)
    }

    @Test
    fun mapsUnknownNonSuccessWithoutLeakingUnsafeCode() {
        val result = ApiErrorMapper.fromHttp(418, ProblemDetail(code = "token-value"))

        assertEquals(ApiErrorCategory.UNKNOWN, result.category)
        assertNull(result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun mapsRateLimitToObservedRetryWindow() {
        val result = ApiErrorMapper.fromHttp(429)

        assertEquals(ApiErrorCategory.RATE_LIMITED, result.category)
        assertEquals(60L, result.retryAfterSeconds)
        assertTrue(result.retryable)
    }
}
