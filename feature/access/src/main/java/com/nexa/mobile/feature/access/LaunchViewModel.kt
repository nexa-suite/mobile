package com.nexa.mobile.feature.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface SessionConfirmation {
    suspend fun confirm(session: SessionMaterial): ApiResult<ConfirmedSessionContext>
}

fun interface SessionRevocation {
    suspend fun revoke(session: SessionMaterial): ApiResult<Unit>
}

/**
 * Coordinates the non-authoritative local session with server confirmation.
 * Protected content is represented only after [SessionConfirmation] succeeds.
 */
class LaunchViewModel(
    private val sessionStore: SessionStore,
    private val sessionConfirmation: SessionConfirmation? = null,
    private val sessionRevocation: SessionRevocation? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow<LaunchUiState>(LaunchUiState.Initial)
    val uiState: StateFlow<LaunchUiState> = _uiState.asStateFlow()

    fun restore() {
        if (_uiState.value == LaunchUiState.Loading) return

        viewModelScope.launch {
            _uiState.value = LaunchUiState.Loading
            val session = try {
                sessionStore.read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _uiState.value = LaunchUiState.Unavailable(LaunchFailure.STORAGE)
                return@launch
            }

            if (session == null) {
                _uiState.value = LaunchUiState.NoSession
                return@launch
            }

            val confirmationGateway = sessionConfirmation
            if (confirmationGateway == null) {
                _uiState.value = LaunchUiState.Unavailable(LaunchFailure.AUTH_SURFACE_BLOCKED)
                return@launch
            }

            val confirmation = try {
                confirmationGateway.confirm(session)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _uiState.value = LaunchUiState.Unavailable(LaunchFailure.NETWORK)
                return@launch
            }

            _uiState.value = when (confirmation) {
                is ApiResult.Success -> LaunchUiState.Confirmed(confirmation.value)
                is ApiResult.Failure -> confirmation.error.category.toLaunchState()
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            _uiState.value = LaunchUiState.Loading

            val session = try {
                sessionStore.read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            session?.let { material ->
                try {
                    sessionRevocation?.revoke(material)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Local cleanup remains mandatory when remote revocation fails.
                }
            }

            _uiState.value = try {
                sessionStore.clear()
                LaunchUiState.NoSession
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                LaunchUiState.Unavailable(LaunchFailure.STORAGE)
            }
        }
    }

    class Factory(
        private val sessionStore: SessionStore,
        private val sessionConfirmation: SessionConfirmation? = null,
        private val sessionRevocation: SessionRevocation? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LaunchViewModel::class.java)) {
                "Unsupported ViewModel: ${modelClass.name}"
            }
            return LaunchViewModel(sessionStore, sessionConfirmation, sessionRevocation) as T
        }
    }
}

private fun com.nexa.mobile.core.network.ApiErrorCategory.toLaunchState(): LaunchUiState = when (this) {
    com.nexa.mobile.core.network.ApiErrorCategory.UNAUTHORIZED,
    com.nexa.mobile.core.network.ApiErrorCategory.FORBIDDEN -> LaunchUiState.Unauthorized
    else -> LaunchUiState.Unavailable(toLaunchFailure())
}
