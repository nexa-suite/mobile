package com.nexa.mobile.feature.access

import com.nexa.mobile.core.network.WorkspacePreview

data class SignInFormState(
    val identifier: String = "",
    val workspaceSlug: String = "",
    val password: String = "",
    val status: SignInStatus = SignInStatus.Idle,
    val workspacePreview: WorkspacePreviewState = WorkspacePreviewState.NotConfigured,
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

sealed interface WorkspacePreviewState {
    data object NotConfigured : WorkspacePreviewState

    data object Idle : WorkspacePreviewState

    data object Loading : WorkspacePreviewState

    data class Ready(
        val workspaceSlug: String,
        val preview: WorkspacePreview,
    ) : WorkspacePreviewState

    data class Failed(val reason: WorkspacePreviewFailure) : WorkspacePreviewState
}

enum class WorkspacePreviewFailure {
    VALIDATION,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    UNKNOWN,
}
