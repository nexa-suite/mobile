package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.core.device.scanner.ProductCodeCandidate
import com.nexa.mobile.operations.core.device.scanner.ProductCodeScannerEvent
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedScannedSku

enum class ScannerUnverifiedReason {
    UnknownCode,
    AmbiguousCode,
    InvalidCode,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable
}

@Immutable
sealed interface ProductScannerUiState {
    val authorityEpoch: Long

    data class PermissionNotRequested(override val authorityEpoch: Long) : ProductScannerUiState
    data class PermissionRequestPending(override val authorityEpoch: Long) : ProductScannerUiState
    data class PermissionDenied(override val authorityEpoch: Long) : ProductScannerUiState
    data class PermissionPermanentlyDenied(override val authorityEpoch: Long) :
        ProductScannerUiState
    data class CameraStarting(override val authorityEpoch: Long) : ProductScannerUiState
    data class CameraUnavailable(override val authorityEpoch: Long) : ProductScannerUiState
    data class Capturing(override val authorityEpoch: Long) : ProductScannerUiState
    data class Resolving(override val authorityEpoch: Long) : ProductScannerUiState

    data class Unverified(
        override val authorityEpoch: Long,
        val reason: ScannerUnverifiedReason,
        val candidateCount: Int? = null
    ) : ProductScannerUiState

    data class Confirmed(override val authorityEpoch: Long, val sku: ConfirmedScannedSku) :
        ProductScannerUiState

    data class ContextInvalidated(override val authorityEpoch: Long) : ProductScannerUiState
    data class SessionInvalidated(override val authorityEpoch: Long) : ProductScannerUiState
}

/** Maps technical device events into the warehouse scanner state machine. */
internal fun ProductCodeScannerEvent.toScannerEvent(): ProductScannerEvent = when (this) {
    ProductCodeScannerEvent.PermissionRequired -> ProductScannerEvent.PermissionRequired
    ProductCodeScannerEvent.Capturing -> ProductScannerEvent.Capturing
    ProductCodeScannerEvent.Unavailable -> ProductScannerEvent.Unavailable
    is ProductCodeScannerEvent.Candidate -> ProductScannerEvent.Candidate(value)
}

sealed interface ProductScannerEvent {
    data object PermissionRequired : ProductScannerEvent
    data object Capturing : ProductScannerEvent
    data object Unavailable : ProductScannerEvent
    data class Candidate(val value: ProductCodeCandidate) : ProductScannerEvent
}
