package com.nexa.mobile.feature.warehouse

import com.nexa.mobile.core.network.SkuResolution

data class WarehouseUiState(
    val identifier: String = "",
    val status: WarehouseStatus = WarehouseStatus.Idle,
)

sealed interface WarehouseStatus {
    data object Idle : WarehouseStatus

    data object Blocked : WarehouseStatus

    data object CameraPermissionDenied : WarehouseStatus

    data object Scanning : WarehouseStatus

    data object Resolving : WarehouseStatus

    data class Resolved(val resolution: SkuResolution) : WarehouseStatus

    data class NotFound(val normalizedIdentifier: String) : WarehouseStatus

    data class Ambiguous(
        val normalizedIdentifier: String,
        val candidateCount: Int,
    ) : WarehouseStatus

    data class Failed(val reason: WarehouseFailure) : WarehouseStatus
}

enum class WarehouseFailure {
    VALIDATION,
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    STORAGE,
    CAMERA_UNAVAILABLE,
    UNKNOWN,
}
