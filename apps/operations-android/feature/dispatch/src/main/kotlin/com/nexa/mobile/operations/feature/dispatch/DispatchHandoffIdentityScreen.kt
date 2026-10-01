package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun DispatchHandoffIdentityScreen(
    state: DispatchHandoffIdentityUiState,
    onIssue: () -> Unit,
    onRetrySame: () -> Unit,
    onIssueReplacement: () -> Unit,
    onValidate: () -> Unit,
    onTokenChanged: (String) -> Unit,
    onHideToken: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, onHideToken, onDeactivate) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) onHideToken()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onDeactivate()
        }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.dispatch_handoff_identity_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.dispatch_handoff_identity_scope_notice))
        state.deliveryId?.let { Text(stringResource(R.string.dispatch_handoff_identity_delivery, it)) }
        state.assignmentId?.let { Text(stringResource(R.string.dispatch_handoff_identity_assignment, it)) }
        Text(statusText(state.status))
        state.errorCode?.let { Text(stringResource(R.string.dispatch_handoff_identity_error, it)) }
        state.identity?.let {
            Text(stringResource(R.string.dispatch_handoff_identity_status, it.status))
            Text(stringResource(R.string.dispatch_handoff_identity_version, it.deliveryVersion))
            Text(stringResource(R.string.dispatch_handoff_identity_expiry, it.expiresAt))
        }
        state.oneTimeToken?.let { token ->
            Text(stringResource(R.string.dispatch_handoff_identity_token_label), fontWeight = FontWeight.SemiBold)
            Text(token, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onHideToken, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dispatch_handoff_identity_hide))
            }
        }
        if (state.canIssue && state.status == DispatchHandoffIdentityStatus.Ready) {
            Button(onClick = onIssue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dispatch_handoff_identity_issue))
            }
        }
        if (state.canRetrySame) {
            Button(onClick = onRetrySame, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dispatch_handoff_identity_retry_same))
            }
        }
        if (state.canIssue && state.status in REISSUE_STATUSES) {
            Button(onClick = onIssueReplacement, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dispatch_handoff_identity_issue_replacement))
            }
        }
        if (state.canValidate) {
            OutlinedTextField(
                value = state.enteredToken,
                onValueChange = onTokenChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dispatch_handoff_identity_token_input)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            Button(onClick = onValidate, enabled = !state.busy && state.enteredToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dispatch_handoff_identity_validate))
            }
        }
        Text(stringResource(R.string.dispatch_handoff_identity_separation))
    }
}

@Composable
private fun statusText(status: DispatchHandoffIdentityStatus): String = when (status) {
    DispatchHandoffIdentityStatus.Loading -> stringResource(R.string.dispatch_handoff_identity_loading)
    DispatchHandoffIdentityStatus.Ready -> stringResource(R.string.dispatch_handoff_identity_ready)
    DispatchHandoffIdentityStatus.Issuing -> stringResource(R.string.dispatch_handoff_identity_issuing)
    DispatchHandoffIdentityStatus.TokenVisible -> stringResource(R.string.dispatch_handoff_identity_token_visible)
    DispatchHandoffIdentityStatus.Validating -> stringResource(R.string.dispatch_handoff_identity_validating)
    DispatchHandoffIdentityStatus.IdentityValidated -> stringResource(R.string.dispatch_handoff_identity_validated)
    DispatchHandoffIdentityStatus.UnknownOutcome -> stringResource(R.string.dispatch_handoff_identity_unknown)
    DispatchHandoffIdentityStatus.ReissueRequired -> stringResource(R.string.dispatch_handoff_identity_reissue_required)
    DispatchHandoffIdentityStatus.ValidationRejected -> stringResource(R.string.dispatch_handoff_identity_validation_rejected)
    DispatchHandoffIdentityStatus.ValidationUnknown -> stringResource(R.string.dispatch_handoff_identity_validation_unknown)
    DispatchHandoffIdentityStatus.Rejected -> stringResource(R.string.dispatch_handoff_identity_rejected)
    DispatchHandoffIdentityStatus.NotFound -> stringResource(R.string.dispatch_handoff_identity_not_found)
    DispatchHandoffIdentityStatus.Unavailable -> stringResource(R.string.dispatch_handoff_identity_unavailable)
    DispatchHandoffIdentityStatus.PermissionDenied -> stringResource(R.string.dispatch_handoff_identity_permission_denied)
    DispatchHandoffIdentityStatus.ContextInvalidated -> stringResource(R.string.dispatch_handoff_identity_context_invalid)
    DispatchHandoffIdentityStatus.SessionInvalidated -> stringResource(R.string.dispatch_handoff_identity_session_invalid)
    DispatchHandoffIdentityStatus.PersistenceUnavailable -> stringResource(R.string.dispatch_handoff_identity_persistence_unavailable)
}

private val REISSUE_STATUSES = setOf(
    DispatchHandoffIdentityStatus.ReissueRequired,
    DispatchHandoffIdentityStatus.ValidationRejected,
    DispatchHandoffIdentityStatus.ValidationUnknown
)
