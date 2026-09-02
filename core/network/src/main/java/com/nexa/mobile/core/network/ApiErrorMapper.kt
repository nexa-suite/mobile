package com.nexa.mobile.core.network

object ApiErrorMapper {
    private val safeCode = Regex("[A-Z0-9_:-]{1,64}")

    fun fromHttp(status: Int, problem: ProblemDetail? = null): ApiError {
        val category = when (status) {
            400 -> ApiErrorCategory.VALIDATION
            401 -> ApiErrorCategory.UNAUTHORIZED
            403 -> ApiErrorCategory.FORBIDDEN
            409 -> ApiErrorCategory.CONFLICT
            412 -> ApiErrorCategory.STALE
            428 -> ApiErrorCategory.PRECONDITION_REQUIRED
            429 -> ApiErrorCategory.RATE_LIMITED
            in 500..599 -> ApiErrorCategory.SERVER
            else -> ApiErrorCategory.UNKNOWN
        }
        val retryable = problem?.retryable ?: category in setOf(
            ApiErrorCategory.RATE_LIMITED,
            ApiErrorCategory.NETWORK,
            ApiErrorCategory.SERVER,
        )
        return ApiError(
            category = category,
            status = status,
            code = problem?.code?.takeIf(safeCode::matches),
            retryable = retryable,
            retryAfterSeconds = if (category == ApiErrorCategory.RATE_LIMITED) 60 else null,
        )
    }

    fun network(): ApiError = ApiError(
        category = ApiErrorCategory.NETWORK,
        retryable = true,
    )
}
