package com.nexa.mobile.core.network

import kotlinx.serialization.Serializable

/** Safe subset of the tagged API Problem Details contract. */
@Serializable
data class ProblemDetail(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val code: String? = null,
    val correlationId: String? = null,
    val category: String? = null,
    val retryable: Boolean? = null,
    val traceId: String? = null,
    val errors: List<FieldError> = emptyList(),
) {
    @Serializable
    data class FieldError(val field: String, val message: String)
}
