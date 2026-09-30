package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.core.device.scanner.ProductCodeCandidate
import com.nexa.mobile.operations.core.device.scanner.ProductCodeScannerEvent
import java.util.UUID

enum class ScannerUnverifiedReason {
    UnknownCode,
    AmbiguousCode,
    InvalidCode,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable
}

enum class ScannerIdentifierType { SkuCode, Gtin, SkuCodeAndGtin }

@Immutable
data class ConfirmedScannedSku(
    val skuId: UUID,
    val skuCode: String,
    val gtin: String?,
    val presentation: String,
    val unitOfMeasure: String?,
    val status: String,
    val identifierType: ScannerIdentifierType,
    val context: ActiveOperationsContext,
    val authorityEpoch: Long
) {
    override fun toString(): String =
        "ConfirmedScannedSku(skuId=REDACTED, skuCode=REDACTED, authorityEpoch=$authorityEpoch)"
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

sealed interface ProductScannerResolution {
    data class Resolved(val sku: ConfirmedScannedSku) : ProductScannerResolution
    data object NotFound : ProductScannerResolution
    data class Ambiguous(val candidateCount: Int) : ProductScannerResolution
    data object InvalidIdentifier : ProductScannerResolution
    data object NetworkUnavailable : ProductScannerResolution
    data object ServiceUnavailable : ProductScannerResolution
    data object PermissionDenied : ProductScannerResolution
    data object ContextInvalidated : ProductScannerResolution
    data object SessionInvalidated : ProductScannerResolution
}

/** Read-only Catalog resolution port. Result fields are server projections, never stock facts. */
interface ProductScannerGateway {
    suspend fun resolve(
        candidate: String,
        authorityEpoch: Long,
        context: ActiveOperationsContext
    ): ProductScannerResolution
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
