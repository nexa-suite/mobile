package com.nexa.mobile.feature.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.core.network.ApiClientSurface
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.network.NativeAuthentication
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import kotlin.coroutines.cancellation.CancellationException
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

/** Owns ephemeral form input and stores credentials only after server success. */
class SignInViewModel(
    private val gateway: SignInGateway?,
    private val surface: ApiClientSurface?,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _formState = MutableStateFlow(
        SignInFormState(
            status = if (gateway == null || surface == null) SignInStatus.Blocked else SignInStatus.Idle,
        ),
    )
    val formState: StateFlow<SignInFormState> = _formState.asStateFlow()

    fun onIdentifierChanged(value: String) {
        _formState.update { it.copy(identifier = value, status = editableStatus(it.status)) }
    }

    fun onWorkspaceChanged(value: String) {
        _formState.update { it.copy(workspaceSlug = value, status = editableStatus(it.status)) }
    }

    fun onPasswordChanged(value: String) {
        _formState.update { it.copy(password = value, status = editableStatus(it.status)) }
    }

    fun submit() {
        val current = _formState.value
        if (current.status is SignInStatus.Blocked || current.status is SignInStatus.Loading) return

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
            val result = try {
                configuredGateway.signIn(
                    identifier = current.identifier.trim(),
                    password = current.password,
                    workspaceSlug = current.workspaceSlug.trim(),
                    surface = configuredSurface,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
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
        try {
            sessionStore.write(material)
            _formState.update { it.copy(status = SignInStatus.Authenticated) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            try {
                sessionStore.clear()
            } catch (clearCancellation: CancellationException) {
                throw clearCancellation
            } catch (_: Throwable) {
                // The state remains failed closed even if cleanup also fails.
            }
            _formState.update { it.copy(status = SignInStatus.Failed(SignInFailure.STORAGE)) }
        }
    }

    class Factory(
        private val gateway: SignInGateway?,
        private val surface: ApiClientSurface?,
        private val sessionStore: SessionStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SignInViewModel::class.java)) {
                "Unsupported ViewModel: ${modelClass.name}"
            }
            return SignInViewModel(gateway, surface, sessionStore) as T
        }
    }
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

private fun editableStatus(status: SignInStatus): SignInStatus = when (status) {
    SignInStatus.Blocked -> status
    SignInStatus.Loading -> status
    SignInStatus.Authenticated,
    is SignInStatus.Failed -> SignInStatus.Idle
    SignInStatus.Idle -> status
}
