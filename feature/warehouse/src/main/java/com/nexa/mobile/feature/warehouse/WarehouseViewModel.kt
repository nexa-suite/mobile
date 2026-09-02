package com.nexa.mobile.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.core.network.ApiError
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.network.SkuResolution
import com.nexa.mobile.core.storage.SessionStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun interface SkuIdentifierResolver {
    suspend fun resolve(identifier: String, accessToken: String): ApiResult<SkuResolution>
}

/** Coordinates camera/manual input with the server-owned BC-03 resolver. */
class WarehouseViewModel(
    private val resolver: SkuIdentifierResolver?,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        WarehouseUiState(
            status = if (resolver == null) WarehouseStatus.Blocked else WarehouseStatus.Idle,
        ),
    )
    val uiState: StateFlow<WarehouseUiState> = _uiState.asStateFlow()

    fun onIdentifierChanged(value: String) {
        _uiState.update { state ->
            state.copy(
                identifier = value,
                status = when (state.status) {
                    WarehouseStatus.Blocked,
                    WarehouseStatus.Resolving,
                    WarehouseStatus.Scanning,
                    -> state.status
                    else -> WarehouseStatus.Idle
                },
            )
        }
    }

    fun startScanning() {
        if (resolver == null) {
            _uiState.update { it.copy(status = WarehouseStatus.Blocked) }
            return
        }
        if (_uiState.value.status != WarehouseStatus.Resolving) {
            _uiState.update { it.copy(status = WarehouseStatus.Scanning) }
        }
    }

    fun onCameraPermissionDenied() {
        if (resolver != null && _uiState.value.status != WarehouseStatus.Resolving) {
            _uiState.update { it.copy(status = WarehouseStatus.CameraPermissionDenied) }
        }
    }

    fun onCameraUnavailable() {
        if (resolver != null && _uiState.value.status != WarehouseStatus.Resolving) {
            _uiState.update {
                it.copy(status = WarehouseStatus.Failed(WarehouseFailure.CAMERA_UNAVAILABLE))
            }
        }
    }

    fun onBarcodeDetected(value: String) {
        if (_uiState.value.status == WarehouseStatus.Scanning) {
            resolve(value)
        }
    }

    fun resolveManually() {
        if (_uiState.value.status != WarehouseStatus.Resolving) {
            resolve(_uiState.value.identifier)
        }
    }

    class Factory(
        private val resolver: SkuIdentifierResolver?,
        private val sessionStore: SessionStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WarehouseViewModel::class.java)) {
                "Unsupported ViewModel: ${modelClass.name}"
            }
            return WarehouseViewModel(resolver, sessionStore) as T
        }
    }

    private fun resolve(rawIdentifier: String) {
        val identifier = rawIdentifier.trim()
        if (identifier.isBlank()) {
            _uiState.update { it.copy(status = WarehouseStatus.Failed(WarehouseFailure.VALIDATION)) }
            return
        }

        val configuredResolver = resolver
        if (configuredResolver == null) {
            _uiState.update { it.copy(status = WarehouseStatus.Blocked) }
            return
        }

        _uiState.update { it.copy(identifier = identifier, status = WarehouseStatus.Resolving) }
        viewModelScope.launch {
            val session = try {
                sessionStore.read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _uiState.update { it.copy(status = WarehouseStatus.Failed(WarehouseFailure.STORAGE)) }
                return@launch
            }
            if (session == null) {
                _uiState.update { it.copy(status = WarehouseStatus.Blocked) }
                return@launch
            }

            val result = try {
                configuredResolver.resolve(identifier, session.accessToken)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                ApiResult.Failure(
                    ApiError(category = ApiErrorCategory.NETWORK, retryable = true),
                )
            }
            _uiState.update { it.copy(status = result.toWarehouseStatus(identifier)) }
        }
    }
}

private fun ApiResult<SkuResolution>.toWarehouseStatus(identifier: String): WarehouseStatus = when (this) {
    is ApiResult.Success -> when (value.outcome) {
        "RESOLVED" -> WarehouseStatus.Resolved(value)
        "NOT_FOUND" -> WarehouseStatus.NotFound(value.normalizedIdentifier.ifBlank { identifier })
        "AMBIGUOUS" -> WarehouseStatus.Ambiguous(
            normalizedIdentifier = value.normalizedIdentifier.ifBlank { identifier },
            candidateCount = value.candidateCount,
        )
        else -> WarehouseStatus.Failed(WarehouseFailure.UNKNOWN)
    }
    is ApiResult.Failure -> WarehouseStatus.Failed(error.toWarehouseFailure())
}

private fun ApiError.toWarehouseFailure(): WarehouseFailure = when (category) {
    ApiErrorCategory.VALIDATION -> WarehouseFailure.VALIDATION
    ApiErrorCategory.UNAUTHORIZED -> WarehouseFailure.UNAUTHORIZED
    ApiErrorCategory.FORBIDDEN -> WarehouseFailure.FORBIDDEN
    ApiErrorCategory.RATE_LIMITED -> WarehouseFailure.RATE_LIMITED
    ApiErrorCategory.NETWORK -> WarehouseFailure.NETWORK
    ApiErrorCategory.SERVER -> WarehouseFailure.SERVER
    ApiErrorCategory.CONFLICT,
    ApiErrorCategory.STALE,
    ApiErrorCategory.PRECONDITION_REQUIRED,
    ApiErrorCategory.UNKNOWN,
    -> WarehouseFailure.UNKNOWN
}
