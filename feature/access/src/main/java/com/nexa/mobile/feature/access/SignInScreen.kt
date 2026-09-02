package com.nexa.mobile.feature.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nexa.mobile.core.designsystem.NexaStatusBanner
import com.nexa.mobile.core.designsystem.NexaStatusTone

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
    val statusTone = state.status.statusTone()
    val isLoading = state.status == SignInStatus.Loading
    val loadingDescription = stringResource(R.string.access_sign_in_loading)

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
                    enabled = !isLoading,
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
                    enabled = !isLoading,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChanged,
                    label = { Text(stringResource(R.string.access_password_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
                Button(
                    onClick = onSubmit,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLoading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .semantics {
                                        contentDescription = loadingDescription
                                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                                    },
                            )
                            Text(stringResource(R.string.access_sign_in_loading))
                        }
                    } else {
                        Text(stringResource(R.string.access_sign_in_submit))
                    }
                }
            }

            if (statusMessage != null) {
                NexaStatusBanner(
                    message = stringResource(statusMessage),
                    tone = statusTone,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            TextButton(onClick = onBack) {
                Text(stringResource(R.string.launch_back))
            }
        }
    }
}

private fun SignInStatus.statusTone(): NexaStatusTone = when (this) {
    SignInStatus.Blocked -> NexaStatusTone.WARNING
    SignInStatus.Authenticated -> NexaStatusTone.SUCCESS
    is SignInStatus.Failed -> if (reason == SignInFailure.VALIDATION) {
        NexaStatusTone.WARNING
    } else {
        NexaStatusTone.DANGER
    }
    SignInStatus.Idle,
    SignInStatus.Loading -> NexaStatusTone.INFO
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
