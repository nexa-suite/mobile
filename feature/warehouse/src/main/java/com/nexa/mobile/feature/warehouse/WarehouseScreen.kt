package com.nexa.mobile.feature.warehouse

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nexa.mobile.core.network.SkuResolution

@Composable
fun WarehouseScreen(
    state: WarehouseUiState,
    onIdentifierChanged: (String) -> Unit,
    onStartCamera: () -> Unit,
    onCameraPermissionDenied: () -> Unit,
    onCameraUnavailable: () -> Unit,
    onBarcodeDetected: (String) -> Unit,
    onResolveManually: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onStartCamera() else onCameraPermissionDenied()
    }
    val requestCamera = remember(context, permissionLauncher) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                onStartCamera()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    val statusMessage = state.status.messageResource()
    val showForm = state.status != WarehouseStatus.Blocked
    val showCamera = state.status == WarehouseStatus.Scanning

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { contentPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.warehouse_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.warehouse_subtitle),
                style = MaterialTheme.typography.bodyLarge,
            )

            if (showCamera) {
                BarcodeCamera(
                    onBarcodeDetected = onBarcodeDetected,
                    onCameraError = onCameraUnavailable,
                )
                Text(
                    text = stringResource(R.string.warehouse_scanning),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (showForm) {
                Button(
                    onClick = requestCamera,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val scanLabel = when (state.status) {
                        WarehouseStatus.Idle -> R.string.warehouse_scan
                        else -> R.string.warehouse_scan_again
                    }
                    Text(text = stringResource(scanLabel))
                }
            }

            if (showForm) {
                OutlinedTextField(
                    value = state.identifier,
                    onValueChange = onIdentifierChanged,
                    label = { Text(stringResource(R.string.warehouse_identifier_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                )
                Button(
                    onClick = onResolveManually,
                    enabled = state.status != WarehouseStatus.Resolving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.status == WarehouseStatus.Resolving) {
                        CircularProgressIndicator()
                    } else {
                        Text(text = stringResource(R.string.warehouse_resolve))
                    }
                }
            }

            if (statusMessage != null) {
                Text(
                    text = stringResource(statusMessage, *state.status.messageArguments()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (state.status is WarehouseStatus.Resolved) {
                ResolutionCard(state.status.resolution)
            }

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.warehouse_back))
            }
        }
    }
}

@Composable
private fun ResolutionCard(resolution: SkuResolution) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.warehouse_resolved),
                style = MaterialTheme.typography.titleMedium,
            )
            ResolutionField(R.string.warehouse_sku_code, resolution.skuCode)
            ResolutionField(R.string.warehouse_gtin, resolution.gtin)
            ResolutionField(R.string.warehouse_presentation, resolution.presentation)
            ResolutionField(R.string.warehouse_unit, resolution.unitOfMeasure)
            ResolutionField(R.string.warehouse_status, resolution.status)
        }
    }
}

@Composable
private fun ResolutionField(label: Int, value: String?) {
    if (!value.isNullOrBlank()) {
        Text(text = stringResource(label, value), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun WarehouseStatus.messageResource(): Int? = when (this) {
    WarehouseStatus.Idle,
    WarehouseStatus.Scanning,
    -> null
    WarehouseStatus.Blocked -> R.string.warehouse_blocked
    WarehouseStatus.CameraPermissionDenied -> R.string.warehouse_camera_permission_denied
    WarehouseStatus.Resolving -> R.string.warehouse_resolving
    is WarehouseStatus.Resolved -> null
    is WarehouseStatus.NotFound -> R.string.warehouse_not_found
    is WarehouseStatus.Ambiguous -> R.string.warehouse_ambiguous
    is WarehouseStatus.Failed -> reason.messageResource()
}

private fun WarehouseStatus.messageArguments(): Array<Any> = when (this) {
    is WarehouseStatus.NotFound -> arrayOf(normalizedIdentifier)
    is WarehouseStatus.Ambiguous -> arrayOf(normalizedIdentifier, candidateCount)
    else -> emptyArray()
}

private fun WarehouseFailure.messageResource(): Int = when (this) {
    WarehouseFailure.VALIDATION -> R.string.warehouse_validation
    WarehouseFailure.UNAUTHORIZED -> R.string.warehouse_unauthorized
    WarehouseFailure.FORBIDDEN -> R.string.warehouse_forbidden
    WarehouseFailure.RATE_LIMITED -> R.string.warehouse_rate_limited
    WarehouseFailure.NETWORK -> R.string.warehouse_network
    WarehouseFailure.SERVER -> R.string.warehouse_server
    WarehouseFailure.STORAGE -> R.string.warehouse_storage
    WarehouseFailure.CAMERA_UNAVAILABLE -> R.string.warehouse_camera_unavailable
    WarehouseFailure.UNKNOWN -> R.string.warehouse_unknown
}
