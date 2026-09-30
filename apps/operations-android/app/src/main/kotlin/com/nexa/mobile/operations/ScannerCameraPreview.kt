package com.nexa.mobile.operations

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nexa.mobile.operations.core.device.scanner.ProductCodeScanner
import com.nexa.mobile.operations.core.device.scanner.ProductCodeScannerEvent

@Composable
internal fun ScannerCameraPreview(
    scanner: ProductCodeScanner,
    modifier: Modifier = Modifier,
    onEvent: (ProductCodeScannerEvent) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val currentOnEvent = rememberUpdatedState(onEvent)

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
    DisposableEffect(scanner, previewView, lifecycleOwner) {
        scanner.bind(lifecycleOwner, previewView) { event -> currentOnEvent.value(event) }
        onDispose { scanner.unbind() }
    }
}
