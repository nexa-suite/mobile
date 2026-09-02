package com.nexa.mobile.feature.warehouse

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import androidx.compose.ui.unit.dp

@Composable
internal fun BarcodeCamera(
    onBarcodeDetected: (String) -> Unit,
    onCameraError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcodeDetected by rememberUpdatedState(onBarcodeDetected)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val controller = remember(context) { LifecycleCameraController(context) }
    val scanner = rememberBarcodeScanner()
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraDescription = stringResource(R.string.warehouse_camera_preview)

    DisposableEffect(scanner) {
        onDispose { scanner.close() }
    }

    DisposableEffect(controller, lifecycleOwner, scanner, executor) {
        try {
            controller.setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(
                    listOf(scanner),
                    ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                    executor,
                ) { result ->
                    val rawValue = result
                        ?.getValue(scanner)
                        ?.firstNotNullOfOrNull { barcode ->
                            barcode.rawValue?.takeIf(String::isNotBlank)
                        }
                    if (rawValue != null) currentOnBarcodeDetected(rawValue)
                },
            )
            controller.bindToLifecycle(lifecycleOwner)
        } catch (_: RuntimeException) {
            currentOnCameraError()
        }

        onDispose {
            runCatching { controller.clearImageAnalysisAnalyzer() }
            runCatching { controller.unbind() }
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .semantics {
                contentDescription = cameraDescription
            },
        factory = { viewContext: Context ->
            PreviewView(viewContext).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        },
        update = { previewView -> previewView.controller = controller },
    )
}

@Composable
private fun rememberBarcodeScanner(): BarcodeScanner {
    return remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        BarcodeScanning.getClient(options)
    }
}
