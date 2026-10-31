package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R

@Composable
fun DriverHandoffTokenScreen(
    state: DriverHandoffTokenUiState,
    onIssueOrRetrySame: () -> Unit,
    onRefresh: () -> Unit,
    onClearToken: () -> Unit,
    onRenewConfirmedToken: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var showCode by remember(state.token) { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
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
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.driver_handoff_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(stringResource(R.string.driver_handoff_scope_notice))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusText(state), style = MaterialTheme.typography.bodyLarge)
                state.receipt?.let {
                    Text(
                        stringResource(R.string.driver_handoff_expiry, it.expiresAt),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        state.token?.let { token ->
            Text(stringResource(R.string.driver_handoff_qr_description))
            DeliveryHandoffQr(
                token = token,
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 280.dp)
            )
            TextButton(onClick = {
                showCode = !showCode
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(
                        if (showCode) {
                            R.string.driver_handoff_hide_manual
                        } else {
                            R.string.driver_handoff_show_manual
                        }
                    )
                )
            }
            if (showCode) Text(token, fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = onClearToken,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.driver_handoff_hide))
            }
        }
        if (state.canIssue) {
            Button(
                onClick = onIssueOrRetrySame,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.driver_handoff_issue))
            }
        }
        if (state.canRetrySame) {
            Button(
                onClick = onIssueOrRetrySame,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.driver_handoff_retry_same))
            }
        }
        if (state.canRenew) {
            OutlinedButton(
                onClick = onRenewConfirmedToken,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.driver_handoff_renew))
            }
        }
        if (!state.busy && state.command == null &&
            state.status != DriverHandoffUiStatus.TokenVisible &&
            state.status != DriverHandoffUiStatus.Loading
        ) {
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.driver_handoff_refresh))
            }
        }
        Text(stringResource(R.string.driver_handoff_separation))
        TextButton(onClick = {
            showDetails = !showDetails
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.driver_handoff_details))
        }
        if (showDetails) {
            state.deliveryId?.let { Text(stringResource(R.string.driver_handoff_delivery, it)) }
            state.attemptId?.let { Text(stringResource(R.string.driver_handoff_attempt, it)) }
            state.delivery?.let {
                Text(stringResource(R.string.driver_handoff_current, it.status, it.version))
            }
            state.errorCode?.let { Text(stringResource(R.string.driver_handoff_error, it)) }
            state.receipt?.let { Text(stringResource(R.string.driver_handoff_status, it.status)) }
        }
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

    DriverHandoffUiStatus.Rejected -> stringResource(
        if (state.errorCode == "DELIVERY_NOT_ATTEMPTABLE") {
            R.string.driver_handoff_not_attemptable
        } else {
            R.string.driver_handoff_rejected
        }
    )

    DriverHandoffUiStatus.NotFound -> stringResource(R.string.driver_handoff_not_found)

    DriverHandoffUiStatus.Unavailable -> stringResource(R.string.driver_handoff_service_unavailable)

    DriverHandoffUiStatus.PermissionDenied -> stringResource(
        R.string.driver_handoff_permission_denied
    )

    DriverHandoffUiStatus.PersistenceUnavailable -> stringResource(
        R.string.driver_handoff_persistence_unavailable
    )
}
