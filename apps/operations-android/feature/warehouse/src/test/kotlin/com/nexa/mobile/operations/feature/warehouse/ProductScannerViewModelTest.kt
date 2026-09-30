package com.nexa.mobile.operations.feature.warehouse

import com.nexa.mobile.operations.core.device.scanner.ProductCodeCandidate
import com.nexa.mobile.operations.core.device.scanner.ProductCodeScannerEvent
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductScannerViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test fun cameraPermissionStatesFailClosed() {
        val viewModel = ProductScannerViewModel(FakeScannerGateway())
        viewModel.enterOperations(context(3))

        viewModel.cameraPermissionDenied(permanentlyDenied = false)
        assertEquals(ProductScannerUiState.PermissionDenied(3), viewModel.state.value)
        viewModel.cameraPermissionDenied(permanentlyDenied = true)
        assertEquals(ProductScannerUiState.PermissionPermanentlyDenied(3), viewModel.state.value)
    }

    @Test fun duplicateFramesAreIgnoredUntilExplicitRescanAndOnlyServerResultConfirms() = runTest {
        val deferred = CompletableDeferred<ProductScannerResolution>()
        val gateway = FakeScannerGateway(deferred)
        val viewModel = openCamera(gateway)
        val code = ProductCodeCandidate.from("  CODE-42  ")!!

        viewModel.cameraDeviceEvent(ProductCodeScannerEvent.Candidate(code))
        viewModel.cameraDeviceEvent(ProductCodeScannerEvent.Candidate(code))
        runCurrent()
        assertEquals(ProductScannerUiState.Resolving(3), viewModel.state.value)
        assertEquals(1, gateway.calls)
        assertEquals("CODE-42", gateway.candidates.single())

        deferred.complete(ProductScannerResolution.Ambiguous(2))
        advanceUntilIdle()
        assertEquals(
            ProductScannerUiState.Unverified(
                3,
                ScannerUnverifiedReason.AmbiguousCode,
                candidateCount = 2
            ),
            viewModel.state.value
        )
        assertFalse(viewModel.state.value is ProductScannerUiState.Confirmed)

        viewModel.cameraDeviceEvent(ProductCodeScannerEvent.Candidate(code))
        assertEquals(1, gateway.calls)
        viewModel.startScanning(permissionGranted = true)
        viewModel.cameraDeviceEvent(ProductCodeScannerEvent.Capturing)
        gateway.deferred = null
        gateway.result = ProductScannerResolution.Resolved(confirmedSku(context(3)))
        viewModel.cameraDeviceEvent(ProductCodeScannerEvent.Candidate(code))
        advanceUntilIdle()
        val confirmed = viewModel.state.value as ProductScannerUiState.Confirmed
        assertEquals(UUID.fromString("c2e78931-f127-433d-b84d-f98b381d2378"), confirmed.sku.skuId)
        assertEquals("SKU-42", confirmed.sku.skuCode)
        assertEquals(2, gateway.calls)
    }

    @Test fun unknownOfflineAndOutOfScopeResponsesNeverConfirm() = runTest {
        val gateway = FakeScannerGateway()
        val viewModel = openCamera(gateway)
        val code = ProductCodeCandidate.from("CODE-42")!!
        for ((response, reason) in listOf(
            ProductScannerResolution.NotFound to ScannerUnverifiedReason.UnknownCode,
            ProductScannerResolution.NetworkUnavailable to
                ScannerUnverifiedReason.NetworkUnavailable
        )) {
            gateway.result = response
            viewModel.cameraDeviceEvent(ProductCodeScannerEvent.Candidate(code))
            advanceUntilIdle()
            assertEquals(ProductScannerUiState.Unverified(3, reason), viewModel.state.value)
            viewModel.startScanning(permissionGranted = true)
            viewModel.cameraDeviceEvent(ProductCodeScannerEvent.Capturing)
        }
        gateway.result = ProductScannerResolution.ContextInvalidated
        viewModel.cameraDeviceEvent(ProductCodeScannerEvent.Candidate(code))
        advanceUntilIdle()
        assertEquals(ProductScannerUiState.ContextInvalidated(4), viewModel.state.value)
        assertFalse(viewModel.state.value is ProductScannerUiState.Confirmed)
    }

    @Test fun delayedResolutionIsDiscardedAfterSessionInvalidation() = runTest {
        val deferred = CompletableDeferred<ProductScannerResolution>()
        val gateway = FakeScannerGateway(deferred)
        val viewModel = openCamera(gateway)
        viewModel.cameraDeviceEvent(
            ProductCodeScannerEvent.Candidate(ProductCodeCandidate.from("CODE-42")!!)
        )
        runCurrent()

        viewModel.sessionInvalidated()
        deferred.complete(ProductScannerResolution.Resolved(confirmedSku(context(3))))
        advanceUntilIdle()

        assertEquals(ProductScannerUiState.SessionInvalidated(4), viewModel.state.value)
        assertNull(viewModel.state.value.let { (it as? ProductScannerUiState.Confirmed)?.sku })
    }

    private fun openCamera(gateway: FakeScannerGateway): ProductScannerViewModel =
        ProductScannerViewModel(gateway).apply {
            enterOperations(context(3))
            scannerRouteOpened(permissionGranted = true)
            cameraDeviceEvent(ProductCodeScannerEvent.Capturing)
        }

    private class FakeScannerGateway(
        var deferred: CompletableDeferred<ProductScannerResolution>? = null
    ) : ProductScannerGateway {
        var result: ProductScannerResolution = ProductScannerResolution.NotFound
        var calls = 0
        val candidates = mutableListOf<String>()

        override suspend fun resolve(
            candidate: String,
            authorityEpoch: Long,
            context: ActiveOperationsContext
        ): ProductScannerResolution {
            calls++
            candidates += candidate
            return deferred?.await() ?: result
        }
    }

    private fun context(epoch: Long) = ActiveOperationsContext(
        companyName = "Company",
        workspaceName = "Warehouse",
        authorityEpoch = epoch
    )

    private fun confirmedSku(context: ActiveOperationsContext) = ConfirmedScannedSku(
        skuId = UUID.fromString("c2e78931-f127-433d-b84d-f98b381d2378"),
        skuCode = "SKU-42",
        gtin = "12345678",
        presentation = "Pack",
        unitOfMeasure = "EA",
        status = "ACTIVE",
        identifierType = ScannerIdentifierType.SkuCode,
        context = context,
        authorityEpoch = context.authorityEpoch
    )
}
