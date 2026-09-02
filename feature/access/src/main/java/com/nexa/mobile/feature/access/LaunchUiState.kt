package com.nexa.mobile.feature.access

import com.nexa.mobile.core.network.ApiErrorCategory

sealed interface LaunchUiState {
    data object Initial : LaunchUiState

    data object Loading : LaunchUiState

    data object NoSession : LaunchUiState

    data object Confirmed : LaunchUiState

    data class Unavailable(val failure: LaunchFailure) : LaunchUiState

    data object Unauthorized : LaunchUiState
}

enum class LaunchFailure {
    AUTH_SURFACE_BLOCKED,
    VALIDATION,
    CONFLICT,
    STALE,
    PRECONDITION_REQUIRED,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    STORAGE,
    UNKNOWN,
}

fun ApiErrorCategory.toLaunchFailure(): LaunchFailure = when (this) {
    ApiErrorCategory.VALIDATION -> LaunchFailure.VALIDATION
    ApiErrorCategory.CONFLICT -> LaunchFailure.CONFLICT
    ApiErrorCategory.STALE -> LaunchFailure.STALE
    ApiErrorCategory.PRECONDITION_REQUIRED -> LaunchFailure.PRECONDITION_REQUIRED
    ApiErrorCategory.RATE_LIMITED -> LaunchFailure.RATE_LIMITED
    ApiErrorCategory.NETWORK -> LaunchFailure.NETWORK
    ApiErrorCategory.SERVER -> LaunchFailure.SERVER
    ApiErrorCategory.UNKNOWN -> LaunchFailure.UNKNOWN
    ApiErrorCategory.UNAUTHORIZED,
    ApiErrorCategory.FORBIDDEN -> error("Unauthorized categories require the fail-closed state")
}
