package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.core.device.scanner.ProductCodeCandidate
import com.nexa.mobile.operations.core.device.scanner.ProductCodeScannerEvent
import com.nexa.mobile.operations.feature.warehouse.application.ProductScannerGateway
import com.nexa.mobile.operations.feature.warehouse.model.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.model.ProductScannerResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProductScannerViewModel(
    private val gateway: ProductScannerGateway,
    initialState: ProductScannerUiState = ProductScannerUiState.PermissionNotRequested(0)
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState)
    val state = mutableState.asStateFlow()

    private var activeContext: ActiveOperationsContext? = null
    private var requestGeneration = 0L

    fun enterOperations(context: ActiveOperationsContext) {
        if (activeContext == context &&
            mutableState.value.authorityEpoch == context.authorityEpoch
        ) {
            return
        }
        requestGeneration++
        activeContext = context
        mutableState.value = ProductScannerUiState.PermissionNotRequested(context.authorityEpoch)
    }

    fun scannerRouteOpened(permissionGranted: Boolean, permanentlyDenied: Boolean = false) {
        val epoch = mutableState.value.authorityEpoch
        requestGeneration++
        mutableState.value = when {
            permissionGranted -> ProductScannerUiState.CameraStarting(epoch)
            permanentlyDenied -> ProductScannerUiState.PermissionPermanentlyDenied(epoch)
            else -> ProductScannerUiState.PermissionNotRequested(epoch)
        }
    }

    fun cameraPermissionDenied(permanentlyDenied: Boolean) {
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = if (permanentlyDenied) {
            ProductScannerUiState.PermissionPermanentlyDenied(epoch)
        } else {
            ProductScannerUiState.PermissionDenied(epoch)
        }
    }

    fun permissionRequestStarted() {
        val current = mutableState.value
        if (current !is ProductScannerUiState.PermissionNotRequested &&
            current !is ProductScannerUiState.PermissionDenied
        ) {
            return
        }
        mutableState.value = ProductScannerUiState.PermissionRequestPending(current.authorityEpoch)
    }

    fun permissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        val current = mutableState.value
        if (current !is ProductScannerUiState.PermissionRequestPending) return
        mutableState.value = when {
            granted -> ProductScannerUiState.CameraStarting(current.authorityEpoch)

            permanentlyDenied ->
                ProductScannerUiState.PermissionPermanentlyDenied(current.authorityEpoch)

            else -> ProductScannerUiState.PermissionDenied(current.authorityEpoch)
        }
    }

    fun cameraEvent(event: ProductScannerEvent) {
        val current = mutableState.value
        when (event) {
            ProductScannerEvent.PermissionRequired -> {
                if (current !is ProductScannerUiState.Resolving &&
                    current !is ProductScannerUiState.Confirmed
                ) {
                    mutableState.value = ProductScannerUiState.PermissionNotRequested(
                        current.authorityEpoch
                    )
                }
            }

            ProductScannerEvent.Capturing -> {
                if (current is ProductScannerUiState.CameraStarting ||
                    current is ProductScannerUiState.PermissionNotRequested
                ) {
                    mutableState.value = ProductScannerUiState.Capturing(current.authorityEpoch)
                }
            }

            ProductScannerEvent.Unavailable -> {
                if (current is ProductScannerUiState.CameraStarting ||
                    current is ProductScannerUiState.Capturing
                ) {
                    mutableState.value = ProductScannerUiState.CameraUnavailable(
                        current.authorityEpoch
                    )
                }
            }

            is ProductScannerEvent.Candidate -> resolveCandidate(event.value, current)
        }
    }

    fun cameraDeviceEvent(event: ProductCodeScannerEvent) {
        cameraEvent(event.toScannerEvent())
    }

    fun startScanning(permissionGranted: Boolean) {
        val current = mutableState.value
        if (current !is ProductScannerUiState.CameraUnavailable &&
            current !is ProductScannerUiState.Unverified &&
            current !is ProductScannerUiState.Confirmed
        ) {
            return
        }
        requestGeneration++
        mutableState.value = if (permissionGranted) {
            ProductScannerUiState.CameraStarting(current.authorityEpoch)
        } else {
            ProductScannerUiState.PermissionNotRequested(current.authorityEpoch)
        }
    }

    fun routeClosed() {
        requestGeneration++
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = ProductScannerUiState.PermissionNotRequested(epoch)
    }

    fun contextInvalidated() {
        requestGeneration++
        val epoch = mutableState.value.authorityEpoch + 1
        activeContext = null
        mutableState.value = ProductScannerUiState.ContextInvalidated(epoch)
    }

    fun sessionInvalidated() {
        requestGeneration++
        val epoch = mutableState.value.authorityEpoch + 1
        activeContext = null
        mutableState.value = ProductScannerUiState.SessionInvalidated(epoch)
    }

    private fun resolveCandidate(candidate: ProductCodeCandidate, current: ProductScannerUiState) {
        if (current !is ProductScannerUiState.Capturing) return
        val context = activeContext ?: return
        if (candidate.value.isBlank() || candidate.value.length > 160) {
            mutableState.value = ProductScannerUiState.Unverified(
                authorityEpoch = current.authorityEpoch,
                reason = ScannerUnverifiedReason.InvalidCode
            )
            return
        }

        val generation = ++requestGeneration
        val epoch = current.authorityEpoch
        mutableState.value = ProductScannerUiState.Resolving(epoch)
        viewModelScope.launch {
            val result = runCatching {
                gateway.resolve(candidate.value, epoch, context)
            }.getOrElse {
                if (it is CancellationException) throw it
                ProductScannerResolution.ServiceUnavailable
            }
            if (!isCurrent(generation, epoch)) return@launch
            when (result) {
                is ProductScannerResolution.Resolved -> {
                    val sku = result.sku
                    if (sku.authorityEpoch != epoch || sku.context != context) {
                        mutableState.value = ProductScannerUiState.Unverified(
                            authorityEpoch = epoch,
                            reason = ScannerUnverifiedReason.ServiceUnavailable
                        )
                    } else {
                        mutableState.value = ProductScannerUiState.Confirmed(epoch, sku)
                    }
                }

                ProductScannerResolution.NotFound ->
                    mutableState.value =
                        ProductScannerUiState.Unverified(epoch, ScannerUnverifiedReason.UnknownCode)

                is ProductScannerResolution.Ambiguous ->
                    mutableState.value =
                        ProductScannerUiState.Unverified(
                            epoch,
                            ScannerUnverifiedReason.AmbiguousCode,
                            result.candidateCount
                        )

                ProductScannerResolution.InvalidIdentifier ->
                    mutableState.value =
                        ProductScannerUiState.Unverified(epoch, ScannerUnverifiedReason.InvalidCode)

                ProductScannerResolution.NetworkUnavailable ->
                    mutableState.value =
                        ProductScannerUiState.Unverified(
                            epoch,
                            ScannerUnverifiedReason.NetworkUnavailable
                        )

                ProductScannerResolution.ServiceUnavailable ->
                    mutableState.value =
                        ProductScannerUiState.Unverified(
                            epoch,
                            ScannerUnverifiedReason.ServiceUnavailable
                        )

                ProductScannerResolution.PermissionDenied ->
                    mutableState.value =
                        ProductScannerUiState.Unverified(
                            epoch,
                            ScannerUnverifiedReason.PermissionDenied
                        )

                ProductScannerResolution.ContextInvalidated -> {
                    requestGeneration++
                    activeContext = null
                    mutableState.value = ProductScannerUiState.ContextInvalidated(epoch + 1)
                }

                ProductScannerResolution.SessionInvalidated -> {
                    requestGeneration++
                    activeContext = null
                    mutableState.value = ProductScannerUiState.SessionInvalidated(epoch + 1)
                }
            }
        }
    }

    private fun isCurrent(generation: Long, epoch: Long): Boolean =
        generation == requestGeneration && epoch == mutableState.value.authorityEpoch
}
