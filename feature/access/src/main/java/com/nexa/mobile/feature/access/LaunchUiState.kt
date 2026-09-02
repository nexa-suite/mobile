package com.nexa.mobile.feature.access

import com.nexa.mobile.core.network.ApiErrorCategory

/** Server-confirmed access context safe for presentation; opaque IDs excluded. */
data class ConfirmedSessionContext(
    val user: User,
    val tenant: Tenant,
    val workspace: Workspace,
    val roles: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
) {
    data class User(
        val displayName: String,
        val email: String,
        val preferredLanguage: String,
    )

    data class Tenant(val tenantSlug: String)

    data class Workspace(val workspaceSlug: String)
}

sealed interface LaunchUiState {
    data object Initial : LaunchUiState

    data object Loading : LaunchUiState

    data object NoSession : LaunchUiState

    data class Confirmed(val context: ConfirmedSessionContext) : LaunchUiState

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
