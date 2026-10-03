package com.nexa.mobile.operations.feature.delivery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun DriverHandoffTokenScreen(
    state: DriverHandoffTokenUiState,
    onIssueOrRetrySame: () -> Unit,
    onRefresh: () -> Unit,
    onClearToken: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, onClearToken) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE ||
                event == Lifecycle.Event.ON_STOP
            ) {
                onClearToken()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.driver_handoff_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.driver_handoff_scope_notice))
        state.deliveryId?.let { Text(stringResource(R.string.driver_handoff_delivery, it)) }
        state.attemptId?.let { Text(stringResource(R.string.driver_handoff_attempt, it)) }
        state.delivery?.let {
            Text(stringResource(R.string.driver_handoff_current, it.status, it.version))
        }
        Text(statusText(state))
        state.errorCode?.let { Text(stringResource(R.string.driver_handoff_error, it)) }
        state.receipt?.let {
            Text(stringResource(R.string.driver_handoff_expiry, it.expiresAt))
            Text(stringResource(R.string.driver_handoff_status, it.status))
        }
        state.token?.let {
            Text(stringResource(R.string.driver_handoff_code), fontWeight = FontWeight.SemiBold)
            Text(it, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onClearToken, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.driver_handoff_hide))
            }
        }
        if (state.canIssue) {
            Button(onClick = onIssueOrRetrySame, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.driver_handoff_issue))
            }
        }
        if (state.canRetrySame) {
            Button(onClick = onIssueOrRetrySame, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.driver_handoff_retry_same))
            }
        }
        if (!state.busy && state.command == null &&
            state.status != DriverHandoffUiStatus.TokenVisible &&
            state.status != DriverHandoffUiStatus.Loading
        ) {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.driver_handoff_refresh))
            }
        }
        Text(stringResource(R.string.driver_handoff_separation))
    }
}

@Composable
private fun statusText(state: DriverHandoffTokenUiState): String = when (state.status) {
    DriverHandoffUiStatus.Loading -> stringResource(R.string.driver_handoff_loading)

    DriverHandoffUiStatus.Ready -> stringResource(R.string.driver_handoff_ready)

    DriverHandoffUiStatus.Issuing -> stringResource(R.string.driver_handoff_issuing)

    DriverHandoffUiStatus.TokenVisible -> stringResource(R.string.driver_handoff_visible)

    DriverHandoffUiStatus.UnknownOutcome -> stringResource(R.string.driver_handoff_unknown)

    DriverHandoffUiStatus.TokenUnavailable -> stringResource(R.string.driver_handoff_unavailable)

    DriverHandoffUiStatus.TokenExpired -> stringResource(R.string.driver_handoff_expired)

    DriverHandoffUiStatus.Cleared -> stringResource(R.string.driver_handoff_cleared)

    DriverHandoffUiStatus.Stale -> stringResource(R.string.driver_handoff_stale)

    DriverHandoffUiStatus.Rejected -> stringResource(R.string.driver_handoff_rejected)

    DriverHandoffUiStatus.NotFound -> stringResource(R.string.driver_handoff_not_found)

    DriverHandoffUiStatus.Unavailable -> stringResource(R.string.driver_handoff_service_unavailable)

    DriverHandoffUiStatus.PermissionDenied -> stringResource(
        R.string.driver_handoff_permission_denied
    )

    DriverHandoffUiStatus.PersistenceUnavailable -> stringResource(
        R.string.driver_handoff_persistence_unavailable
    )
}
