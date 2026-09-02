package com.nexa.mobile.core.network

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

enum class ApiErrorCategory {
    VALIDATION,
    UNAUTHORIZED,
    FORBIDDEN,
    CONFLICT,
    STALE,
    PRECONDITION_REQUIRED,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    UNKNOWN,
}

data class ApiError(
    val category: ApiErrorCategory,
    val status: Int? = null,
    val code: String? = null,
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null,
)
