package com.nexa.mobile.feature.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.core.network.ApiClientSurface
import com.nexa.mobile.core.network.ApiErrorMapper
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.network.NativeAuthentication
import com.nexa.mobile.core.network.WorkspacePreview
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun interface SignInGateway {
    suspend fun signIn(
        identifier: String,
        password: String,
        workspaceSlug: String,
        surface: ApiClientSurface,
    ): ApiResult<NativeAuthentication>
}

fun interface WorkspacePreviewGateway {
    suspend fun preview(workspaceSlug: String): ApiResult<WorkspacePreview>
}

/** Owns ephemeral form input and stores credentials only after server success. */
class SignInViewModel(
    private val gateway: SignInGateway?,
    private val surface: ApiClientSurface?,
    private val sessionStore: SessionStore,
    private val workspacePreviewGateway: WorkspacePreviewGateway? = null,
) : ViewModel() {
    private val _formState = MutableStateFlow(
        SignInFormState(
            status = if (workspacePreviewGateway == null && (gateway == null || surface == null)) {
                SignInStatus.Blocked
            } else {
                SignInStatus.Idle
            },
            workspacePreview = if (workspacePreviewGateway == null) {
                WorkspacePreviewState.NotConfigured
            } else {
                WorkspacePreviewState.Idle
            },
        ),
    )
    val formState: StateFlow<SignInFormState> = _formState.asStateFlow()

    fun onIdentifierChanged(value: String) {
        _formState.update { it.copy(identifier = value, status = editableStatus(it.status)) }
    }

    fun onWorkspaceChanged(value: String) {
        _formState.update { current ->
            val normalized = value.trim()
            val preview = when (val currentPreview = current.workspacePreview) {
                WorkspacePreviewState.NotConfigured -> currentPreview
                is WorkspacePreviewState.Ready -> if (currentPreview.workspaceSlug == normalized) {
                    currentPreview
                } else {
                    WorkspacePreviewState.Idle
                }
                else -> WorkspacePreviewState.Idle
            }
            current.copy(
                workspaceSlug = value,
                status = editableStatus(current.status),
                workspacePreview = preview,
            )
        }
    }

    fun onPasswordChanged(value: String) {
        _formState.update { it.copy(password = value, status = editableStatus(it.status)) }
    }

    fun submit() {
        val current = _formState.value
        if (current.status is SignInStatus.Blocked || current.status is SignInStatus.Loading) return

        val configuredPreviewGateway = workspacePreviewGateway
        val normalizedWorkspaceSlug = current.workspaceSlug.trim()
        if (configuredPreviewGateway != null && !current.canSignIn(normalizedWorkspaceSlug)) {
            requestWorkspacePreview(configuredPreviewGateway, normalizedWorkspaceSlug)
            return
        }

        val configuredGateway = gateway
        val configuredSurface = surface
        if (configuredGateway == null || configuredSurface == null) {
            _formState.update { it.copy(password = "", status = SignInStatus.Blocked) }
            return
        }
        if (current.identifier.isBlank() || current.workspaceSlug.isBlank() || current.password.isBlank()) {
            _formState.update { it.copy(password = "", status = SignInStatus.Failed(SignInFailure.VALIDATION)) }
            return
        }

        _formState.update { it.copy(password = "", status = SignInStatus.Loading) }
        viewModelScope.launch {
            val result = runCatching {
                configuredGateway.signIn(
                    identifier = current.identifier.trim(),
                    password = current.password,
                    workspaceSlug = current.workspaceSlug.trim(),
                    surface = configuredSurface,
                )
            }.getOrElse {
                ApiResult.Failure(com.nexa.mobile.core.network.ApiErrorMapper.network())
            }
            when (result) {
                is ApiResult.Success -> persist(result.value, configuredSurface)
                is ApiResult.Failure -> _formState.update {
                    it.copy(status = SignInStatus.Failed(result.error.toSignInFailure()))
                }
            }
        }
    }

    fun consumeAuthenticated() {
        _formState.update { it.copy(status = SignInStatus.Idle) }
    }

    private suspend fun persist(authentication: NativeAuthentication, surface: ApiClientSurface) {
        val material = SessionMaterial(
            accessToken = authentication.accessToken,
            refreshToken = authentication.refreshToken,
            accessTokenExpiresAtEpochSeconds =
                (System.currentTimeMillis() / 1_000L) + authentication.expiresInSeconds,
            surface = surface.wireValue,
        )
        runCatching { sessionStore.write(material) }
            .onSuccess { _formState.update { it.copy(status = SignInStatus.Authenticated) } }
            .onFailure {
                runCatching { sessionStore.clear() }
                _formState.update { it.copy(status = SignInStatus.Failed(SignInFailure.STORAGE)) }
            }
    }

    class Factory(
        private val gateway: SignInGateway?,
        private val surface: ApiClientSurface?,
        private val sessionStore: SessionStore,
        private val workspacePreviewGateway: WorkspacePreviewGateway? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SignInViewModel::class.java)) {
                "Unsupported ViewModel: ${modelClass.name}"
            }
            return SignInViewModel(gateway, surface, sessionStore, workspacePreviewGateway) as T
        }
    }

    private fun requestWorkspacePreview(
        gateway: WorkspacePreviewGateway,
        workspaceSlug: String,
    ) {
        if (workspaceSlug.isBlank()) {
            _formState.update {
                it.copy(
                    password = "",
                    status = SignInStatus.Idle,
                    workspacePreview = WorkspacePreviewState.Failed(WorkspacePreviewFailure.VALIDATION),
                )
            }
            return
        }

        _formState.update {
            it.copy(
                password = "",
                status = SignInStatus.Loading,
                workspacePreview = WorkspacePreviewState.Loading,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                gateway.preview(workspaceSlug)
            }.getOrElse {
                ApiResult.Failure(ApiErrorMapper.network())
            }
            if (_formState.value.workspaceSlug.trim() != workspaceSlug) {
                _formState.update {
                    it.copy(
                        status = SignInStatus.Idle,
                        workspacePreview = WorkspacePreviewState.Idle,
                    )
                }
                return@launch
            }
            when (result) {
                is ApiResult.Success -> _formState.update {
                    it.copy(
                        status = SignInStatus.Idle,
                        workspacePreview = WorkspacePreviewState.Ready(workspaceSlug, result.value),
                    )
                }
                is ApiResult.Failure -> _formState.update {
                    it.copy(
                        status = SignInStatus.Idle,
                        workspacePreview = WorkspacePreviewState.Failed(result.error.toWorkspacePreviewFailure()),
                    )
                }
            }
        }
    }
}

private fun SignInFormState.canSignIn(normalizedWorkspaceSlug: String): Boolean {
    val preview = workspacePreview as? WorkspacePreviewState.Ready ?: return workspacePreview is WorkspacePreviewState.NotConfigured
    return preview.workspaceSlug == normalizedWorkspaceSlug && preview.preview.recognized && preview.preview.loginAvailable
}

private fun com.nexa.mobile.core.network.ApiError.toSignInFailure(): SignInFailure = when (category) {
    ApiErrorCategory.VALIDATION -> SignInFailure.VALIDATION
    ApiErrorCategory.UNAUTHORIZED -> SignInFailure.UNAUTHORIZED
    ApiErrorCategory.FORBIDDEN -> SignInFailure.FORBIDDEN
    ApiErrorCategory.CONFLICT -> SignInFailure.CONFLICT
    ApiErrorCategory.RATE_LIMITED -> SignInFailure.RATE_LIMITED
    ApiErrorCategory.NETWORK -> SignInFailure.NETWORK
    ApiErrorCategory.SERVER -> SignInFailure.SERVER
    ApiErrorCategory.STALE,
    ApiErrorCategory.PRECONDITION_REQUIRED,
    ApiErrorCategory.UNKNOWN -> SignInFailure.UNKNOWN
}

private fun com.nexa.mobile.core.network.ApiError.toWorkspacePreviewFailure(): WorkspacePreviewFailure = when (category) {
    ApiErrorCategory.VALIDATION -> WorkspacePreviewFailure.VALIDATION
    ApiErrorCategory.RATE_LIMITED -> WorkspacePreviewFailure.RATE_LIMITED
    ApiErrorCategory.NETWORK -> WorkspacePreviewFailure.NETWORK
    ApiErrorCategory.SERVER -> WorkspacePreviewFailure.SERVER
    ApiErrorCategory.UNAUTHORIZED,
    ApiErrorCategory.FORBIDDEN,
    ApiErrorCategory.CONFLICT,
    ApiErrorCategory.STALE,
    ApiErrorCategory.PRECONDITION_REQUIRED,
    ApiErrorCategory.UNKNOWN -> WorkspacePreviewFailure.UNKNOWN
}

private fun editableStatus(status: SignInStatus): SignInStatus = when (status) {
    SignInStatus.Blocked -> status
    SignInStatus.Loading -> status
    SignInStatus.Authenticated,
    is SignInStatus.Failed -> SignInStatus.Idle
    SignInStatus.Idle -> status
}
