package com.nexa.mobile.feature.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
    val normalizedWorkspaceSlug = state.workspaceSlug.trim()
    val readyPreview = (state.workspacePreview as? WorkspacePreviewState.Ready)
        ?.takeIf { it.workspaceSlug == normalizedWorkspaceSlug }
    val previewAllowsSignIn = readyPreview?.preview?.recognized == true && readyPreview.preview.loginAvailable
    val previewConfigured = state.workspacePreview != WorkspacePreviewState.NotConfigured
    val showCredentials = state.status != SignInStatus.Blocked && (!previewConfigured || previewAllowsSignIn)
    val isLoading = state.status == SignInStatus.Loading
    val isPreviewLoading = isLoading && state.workspacePreview == WorkspacePreviewState.Loading
    val statusMessage = when (val status = state.status) {
        SignInStatus.Blocked -> stringResource(R.string.access_sign_in_blocked)
        is SignInStatus.Failed -> stringResource(status.reason.messageResource())
        SignInStatus.Authenticated -> stringResource(R.string.access_sign_in_success)
        SignInStatus.Loading -> if (isPreviewLoading) {
            stringResource(R.string.access_workspace_preview_loading)
        } else {
            null
        }
        SignInStatus.Idle -> when (val preview = readyPreview) {
            null -> when (val previewState = state.workspacePreview) {
                is WorkspacePreviewState.Failed -> stringResource(previewState.reason.messageResource())
                else -> null
            }
            else -> when {
                !preview.preview.recognized -> stringResource(R.string.access_workspace_preview_unknown)
                !preview.preview.loginAvailable -> stringResource(R.string.access_workspace_preview_login_unavailable)
                else -> stringResource(
                    R.string.access_workspace_preview_recognized,
                    preview.preview.displayName?.takeIf(String::isNotBlank) ?: preview.workspaceSlug,
                )
            }
        }
    }
    val statusTone = state.status.statusTone(state.workspacePreview, previewAllowsSignIn)
    val loadingDescription = stringResource(
        if (isPreviewLoading) R.string.access_workspace_preview_loading else R.string.access_sign_in_loading,
    )
    val actionLabel = when {
        isLoading -> loadingDescription
        showCredentials -> stringResource(R.string.access_sign_in_submit)
        state.workspacePreview is WorkspacePreviewState.Failed || readyPreview != null ->
            stringResource(R.string.access_workspace_preview_retry)
        else -> stringResource(R.string.access_workspace_preview_submit)
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
                    value = state.workspaceSlug,
                    onValueChange = onWorkspaceChanged,
                    label = { Text(stringResource(R.string.access_workspace_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                if (showCredentials) {
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
                }
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
                            Text(actionLabel)
                        }
                    } else {
                        Text(actionLabel)
                    }
                }
            }

            if (statusMessage != null) {
                NexaStatusBanner(
                    message = statusMessage,
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

private fun SignInStatus.statusTone(
    workspacePreview: WorkspacePreviewState,
    previewAllowsSignIn: Boolean,
): NexaStatusTone = when (this) {
    SignInStatus.Blocked -> NexaStatusTone.WARNING
    SignInStatus.Authenticated -> NexaStatusTone.SUCCESS
    is SignInStatus.Failed -> if (reason == SignInFailure.VALIDATION) {
        NexaStatusTone.WARNING
    } else {
        NexaStatusTone.DANGER
    }
    SignInStatus.Loading -> NexaStatusTone.INFO
    SignInStatus.Idle -> when {
        workspacePreview is WorkspacePreviewState.Failed ->
            if (workspacePreview.reason == WorkspacePreviewFailure.VALIDATION) {
                NexaStatusTone.WARNING
            } else {
                NexaStatusTone.DANGER
            }
        previewAllowsSignIn -> NexaStatusTone.SUCCESS
        workspacePreview is WorkspacePreviewState.Ready -> NexaStatusTone.WARNING
        else -> NexaStatusTone.INFO
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

private fun WorkspacePreviewFailure.messageResource(): Int = when (this) {
    WorkspacePreviewFailure.VALIDATION -> R.string.access_workspace_preview_validation
    WorkspacePreviewFailure.RATE_LIMITED -> R.string.access_workspace_preview_rate_limited
    WorkspacePreviewFailure.NETWORK -> R.string.access_workspace_preview_network
    WorkspacePreviewFailure.SERVER -> R.string.access_workspace_preview_server
    WorkspacePreviewFailure.UNKNOWN -> R.string.access_workspace_preview_unknown_error
}
