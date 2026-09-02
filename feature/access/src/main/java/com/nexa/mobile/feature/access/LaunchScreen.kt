package com.nexa.mobile.feature.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp
import com.nexa.mobile.core.designsystem.NexaStatusBanner
import com.nexa.mobile.core.designsystem.NexaStatusTone
import androidx.compose.ui.semantics.LiveRegionMode

@Composable
fun LaunchScreen(
    state: LaunchUiState,
    onRetry: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onOpenWarehouse: () -> Unit = {},
) {
    val message = when (state) {
        LaunchUiState.Initial -> R.string.launch_initial
        LaunchUiState.Loading -> R.string.launch_loading
        LaunchUiState.NoSession -> R.string.launch_no_session
        LaunchUiState.Confirmed -> R.string.launch_confirmed
        is LaunchUiState.Unavailable -> when (state.failure) {
            LaunchFailure.AUTH_SURFACE_BLOCKED -> R.string.launch_auth_surface_blocked
            LaunchFailure.STORAGE -> R.string.launch_storage_unavailable
            else -> R.string.launch_unavailable
        }
        LaunchUiState.Unauthorized -> R.string.launch_unauthorized
    }
    val progressDescription = stringResource(R.string.launch_loading)
    val statusTone = state.statusTone()

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { contentPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.launch_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.launch_subtitle),
                style = MaterialTheme.typography.titleMedium,
            )
            NexaStatusBanner(
                message = stringResource(message),
                tone = statusTone,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Text(
                text = stringResource(R.string.launch_provisional),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state == LaunchUiState.Initial || state == LaunchUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(40.dp)
                        .semantics {
                            contentDescription = progressDescription
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        },
                )
            }

            if (state == LaunchUiState.NoSession) {
                Button(
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.launch_sign_in))
                }
            }

            if (state == LaunchUiState.Confirmed) {
                Button(
                    onClick = onOpenWarehouse,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.launch_open_warehouse))
                }
            }

            if (state is LaunchUiState.Unavailable && state.failure != LaunchFailure.AUTH_SURFACE_BLOCKED) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.launch_retry))
                }
            }

        }
    }
}

private fun LaunchUiState.statusTone(): NexaStatusTone = when (this) {
    LaunchUiState.Initial,
    LaunchUiState.Loading,
    LaunchUiState.NoSession -> NexaStatusTone.INFO
    LaunchUiState.Confirmed -> NexaStatusTone.SUCCESS
    LaunchUiState.Unauthorized -> NexaStatusTone.DANGER
    is LaunchUiState.Unavailable -> if (failure == LaunchFailure.AUTH_SURFACE_BLOCKED) {
        NexaStatusTone.WARNING
    } else {
        NexaStatusTone.DANGER
    }
}
