package com.nexa.mobile.feature.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SignInScreen(
    state: SignInFormState,
    onIdentifierChanged: (String) -> Unit,
    onWorkspaceChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val statusMessage = when (val status = state.status) {
        SignInStatus.Blocked -> R.string.access_sign_in_blocked
        is SignInStatus.Failed -> status.reason.messageResource()
        SignInStatus.Authenticated -> R.string.access_sign_in_success
        SignInStatus.Idle,
        SignInStatus.Loading -> null
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { contentPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.access_sign_in_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.access_sign_in_subtitle),
                style = MaterialTheme.typography.bodyLarge,
            )

            if (state.status != SignInStatus.Blocked) {
                OutlinedTextField(
                    value = state.identifier,
                    onValueChange = onIdentifierChanged,
                    label = { Text(stringResource(R.string.access_identifier_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = state.workspaceSlug,
                    onValueChange = onWorkspaceChanged,
                    label = { Text(stringResource(R.string.access_workspace_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChanged,
                    label = { Text(stringResource(R.string.access_password_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
                Button(
                    onClick = onSubmit,
                    enabled = state.status != SignInStatus.Loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.status == SignInStatus.Loading) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(R.string.access_sign_in_submit))
                    }
                }
            }

            if (statusMessage != null) {
                Text(
                    text = stringResource(statusMessage),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Button(onClick = onBack) {
                Text(stringResource(R.string.launch_back))
            }
        }
    }
}

private fun SignInFailure.messageResource(): Int = when (this) {
    SignInFailure.VALIDATION -> R.string.access_sign_in_validation
    SignInFailure.UNAUTHORIZED -> R.string.access_sign_in_unauthorized
    SignInFailure.FORBIDDEN -> R.string.access_sign_in_forbidden
    SignInFailure.CONFLICT -> R.string.access_sign_in_conflict
    SignInFailure.RATE_LIMITED -> R.string.access_sign_in_rate_limited
    SignInFailure.NETWORK -> R.string.access_sign_in_network
    SignInFailure.SERVER -> R.string.access_sign_in_server
    SignInFailure.STORAGE -> R.string.access_sign_in_storage
    SignInFailure.UNKNOWN -> R.string.access_sign_in_unknown
}
