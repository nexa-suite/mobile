package com.nexa.mobile.operations.core.device.scanner

import android.Manifest
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_IDENTIFIER_LENGTH = 160

/** A transient scanner value. Its string representation never contains the scanned identifier. */
class ProductCodeCandidate private constructor(val value: String) {
    override fun toString(): String = "ProductCodeCandidate(REDACTED)"

    companion object {
        fun from(rawValue: String?): ProductCodeCandidate? {
            val value = rawValue?.trim()?.takeIf {
                it.isNotEmpty() &&
                    it.length <= MAX_IDENTIFIER_LENGTH
            }
                ?: return null
            return ProductCodeCandidate(value)
        }
    }
}

sealed interface ProductCodeScannerEvent {
    data object PermissionRequired : ProductCodeScannerEvent
    data object Capturing : ProductCodeScannerEvent
    data object Unavailable : ProductCodeScannerEvent
    data class Candidate(val value: ProductCodeCandidate) : ProductCodeScannerEvent
}

/** Typed CameraX boundary. Product features receive candidates, never camera frames or ML Kit types. */
interface ProductCodeScanner : AutoCloseable {
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onEvent: (ProductCodeScannerEvent) -> Unit
    )

    fun unbind()
}

/** CameraX preview and bundled ML Kit adapter for transient package and label codes. */
class CameraXProductCodeScanner(context: Context) : ProductCodeScanner {
    private val appContext = context.applicationContext
    private var controller: LifecycleCameraController? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var analysisExecutor: ExecutorService? = null
    private var cameraStateObserver: Observer<androidx.camera.core.CameraState>? = null
    private var boundPreview: PreviewView? = null
    private var generation = 0L
    private var closed = false

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onEvent: (ProductCodeScannerEvent) -> Unit
    ) {
        check(!closed) { "Scanner has been closed" }
        unbind()
        val bindingGeneration = generation
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onEvent(ProductCodeScannerEvent.PermissionRequired)
            return
        }

        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E
                )
                .build()
        )
        val executor = Executors.newSingleThreadExecutor()
        val cameraController = LifecycleCameraController(appContext)
        val delivered = AtomicBoolean(false)
        try {
            cameraController.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraController.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            cameraController.setImageAnalysisBackpressureStrategy(
                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            )
            cameraController.setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(
                    listOf(scanner),
                    CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                    appContext.mainExecutor
                ) { result ->
                    if (generation != bindingGeneration) return@MlKitAnalyzer
                    if (result.getThrowable(scanner) != null) {
                        onEvent(ProductCodeScannerEvent.Unavailable)
                    } else {
                        val candidate = result.getValue(scanner)
                            ?.asSequence()
                            ?.mapNotNull { ProductCodeCandidate.from(it.rawValue) }
                            ?.firstOrNull()
                        if (candidate != null && delivered.compareAndSet(false, true)) {
                            onEvent(ProductCodeScannerEvent.Candidate(candidate))
                        }
                    }
                }
            )
            previewView.controller = cameraController
            cameraController.bindToLifecycle(lifecycleOwner)
            controller = cameraController
            barcodeScanner = scanner
            analysisExecutor = executor
            boundPreview = previewView
            val observer = Observer<androidx.camera.core.CameraState> { state ->
                if (generation == bindingGeneration && state.error != null) {
                    onEvent(ProductCodeScannerEvent.Unavailable)
                }
            }
            cameraStateObserver = observer
            cameraController.cameraInfo?.cameraState?.observe(lifecycleOwner, observer)
            onEvent(ProductCodeScannerEvent.Capturing)
        } catch (_: RuntimeException) {
            previewView.controller = null
            cameraController.unbind()
            scanner.close()
            executor.shutdownNow()
            onEvent(ProductCodeScannerEvent.Unavailable)
        }
    }

    override fun unbind() {
        generation++
        val activeController = controller
        cameraStateObserver?.let { observer ->
            activeController?.cameraInfo?.cameraState?.removeObserver(observer)
        }
        cameraStateObserver = null
        activeController?.clearImageAnalysisAnalyzer()
        activeController?.unbind()
        boundPreview?.controller = null
        controller = null
        boundPreview = null
        barcodeScanner?.close()
        barcodeScanner = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
    }

    override fun close() {
        if (closed) return
        unbind()
        closed = true
    }
}
