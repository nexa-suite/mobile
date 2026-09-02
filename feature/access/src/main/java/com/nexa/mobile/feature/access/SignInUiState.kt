package com.nexa.mobile.feature.access

data class SignInFormState(
    val identifier: String = "",
    val workspaceSlug: String = "",
    val password: String = "",
    val status: SignInStatus = SignInStatus.Idle,
)

sealed interface SignInStatus {
    data object Idle : SignInStatus

    data object Loading : SignInStatus

    data object Blocked : SignInStatus

    data object Authenticated : SignInStatus

    data class Failed(val reason: SignInFailure) : SignInStatus
}

enum class SignInFailure {
    VALIDATION,
    UNAUTHORIZED,
    FORBIDDEN,
    CONFLICT,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    STORAGE,
    UNKNOWN,
}
