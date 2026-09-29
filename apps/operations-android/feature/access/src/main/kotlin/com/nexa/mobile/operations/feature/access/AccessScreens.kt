package com.nexa.mobile.operations.feature.access

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaActiveContextBar
import com.nexa.mobile.operations.core.designsystem.NexaContextChoiceRow
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.core.designsystem.NexaPasswordField
import com.nexa.mobile.operations.core.designsystem.NexaPrimaryButton
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTextField
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar

@Composable
fun AccessScreen(
    state: AccessUiState,
    onIdentifierChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPasswordVisibilityChanged: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onClearLocalSession: () -> Unit = {},
    showRetry: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        NexaTopAppBar(title = stringResource(R.string.access_product_label))
        Image(
            painter = painterResource(R.drawable.nexa_brand),
            contentDescription = stringResource(R.string.access_brand_description),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            contentScale = ContentScale.Fit
        )

        when (state.stage) {
            AccessStage.RestoringSession -> {
                NexaStatePanel(
                    title = stringResource(R.string.access_session_restoring_title),
                    description = stringResource(R.string.access_session_restoring_body)
                )
                CenteredProgress()
                if (showRetry) {
                    NexaPrimaryButton(
                        label = stringResource(R.string.access_context_retry),
                        onClick = onRetry
                    )
                }
            }

            AccessStage.Authenticating -> {
                NexaStatePanel(
                    title = stringResource(R.string.access_sign_in_loading_title),
                    description = stringResource(R.string.access_sign_in_loading_body)
                )
                CenteredProgress()
            }

            AccessStage.ResolvingContexts -> {
                NexaStatePanel(
                    title = stringResource(R.string.access_context_loading_title),
                    description = stringResource(R.string.access_context_loading_body)
                )
                CenteredProgress()
            }

            AccessStage.NoContexts -> NexaStatePanel(
                title = stringResource(R.string.access_no_context_title),
                description = stringResource(R.string.access_no_context_body),
                actionLabel = stringResource(R.string.access_local_logout),
                onAction = onClearLocalSession
            )

            AccessStage.LocalProtectionError -> NexaStatePanel(
                title = stringResource(R.string.access_local_error_title),
                description = stringResource(R.string.access_local_error_body),
                actionLabel = stringResource(R.string.access_local_logout),
                onAction = onClearLocalSession
            )

            AccessStage.IdentityRequired, AccessStage.SessionExpired, AccessStage.ContextChooser,
            AccessStage.WorkAuthorized
            -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.access_sign_in_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        stringResource(R.string.access_sign_in_support),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    NexaTextField(
                        value = state.identifier,
                        onValueChange = onIdentifierChanged,
                        label = stringResource(R.string.access_identifier_label),
                        errorText = if (state.identifierError) {
                            stringResource(
                                R.string.access_identifier_error
                            )
                        } else {
                            null
                        }
                    )
                    NexaPasswordField(
                        value = state.password,
                        onValueChange = onPasswordChanged,
                        visible = state.passwordVisible,
                        onVisibilityChange = onPasswordVisibilityChanged,
                        label = stringResource(R.string.access_password_label),
                        showLabel = stringResource(R.string.access_password_show),
                        hideLabel = stringResource(R.string.access_password_hide),
                        errorText = if (state.passwordError) {
                            stringResource(
                                R.string.access_password_error
                            )
                        } else {
                            null
                        }
                    )
                    NexaPrimaryButton(
                        label = stringResource(R.string.access_submit),
                        onClick = onSignIn
                    )
                }
            }
        }
        state.notice?.let { notice ->
            NexaFeedbackBanner(
                message = stringResource(notice.messageResource()),
                tone = notice.tone()
            )
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
fun ContextChooserScreen(
    state: ContextChooserUiState,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    notice: AccessNotice? = null,
    onClearLocalSession: (() -> Unit)? = null
) {
    val changeMode = state.mode == ContextChooserMode.Change
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NexaTopAppBar(
            title = stringResource(R.string.access_product_label),
            onBack = if (changeMode &&
                state.phase != ContextChooserPhase.SelectionPending
            ) {
                onBack
            } else {
                null
            }
        )
        Text(
            stringResource(
                if (changeMode) R.string.context_change_title else R.string.context_initial_title
            ),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            stringResource(
                if (changeMode) {
                    R.string.context_change_support
                } else {
                    R.string.context_initial_support
                }
            ),
            style = MaterialTheme.typography.bodyLarge
        )
        if (notice != null) {
            NexaFeedbackBanner(
                message = stringResource(notice.messageResource()),
                tone = notice.tone()
            )
        }
        when (state.phase) {
            ContextChooserPhase.Loading -> {
                NexaStatePanel(
                    title = stringResource(R.string.context_loading_title),
                    description = stringResource(R.string.context_loading_body)
                )
                CenteredProgress()
            }

            ContextChooserPhase.ListUnavailable -> {
                val currentValid =
                    state.unavailableReason == ContextUnavailableReason.CurrentContextRemainsValid
                NexaStatePanel(
                    title = stringResource(
                        if (currentValid) {
                            R.string.context_current_valid_title
                        } else {
                            R.string.context_no_active_title
                        }
                    ),
                    description = stringResource(
                        if (currentValid) {
                            R.string.context_current_valid_body
                        } else {
                            R.string.context_no_active_body
                        }
                    ),
                    actionLabel = stringResource(
                        if (currentValid) R.string.context_back_to_work else R.string.context_retry
                    ),
                    onAction = if (currentValid) onBack else onRetry
                )
                if (currentValid) {
                    state.current?.let { context -> CurrentContextRow(context) }
                    NexaPrimaryButton(
                        label = stringResource(R.string.context_retry),
                        onClick = onRetry
                    )
                }
                if (!currentValid && onClearLocalSession != null) {
                    NexaPrimaryButton(
                        label = stringResource(R.string.access_local_logout),
                        onClick = onClearLocalSession
                    )
                }
            }

            ContextChooserPhase.OnlyCurrent -> {
                state.current?.let { context -> CurrentContextRow(context) }
                NexaStatePanel(
                    title = stringResource(R.string.context_only_current_title),
                    description = stringResource(R.string.context_only_current_body),
                    actionLabel = if (changeMode) {
                        stringResource(
                            R.string.context_back_to_work
                        )
                    } else {
                        null
                    },
                    onAction = if (changeMode) onBack else null
                )
            }

            ContextChooserPhase.SelectionPending -> {
                state.choices.forEach { context ->
                    NexaContextChoiceRow(
                        companyName = context.companyName,
                        workspaceName = context.workspaceName,
                        current = context.isCurrent,
                        pending = state.pendingKey == context.key,
                        enabled = false,
                        onClick = {}
                    )
                }
                NexaStatePanel(
                    title = stringResource(R.string.context_loading_title),
                    description = stringResource(R.string.context_selection_pending)
                )
                CenteredProgress()
            }

            ContextChooserPhase.SelectionRejected -> {
                NexaStatePanel(
                    title = stringResource(R.string.context_selection_rejected),
                    description = stringResource(R.string.context_initial_support)
                )
                state.choices.filterNot { it.isCurrent }.forEach { context ->
                    NexaContextChoiceRow(
                        companyName = context.companyName,
                        workspaceName = context.workspaceName,
                        onClick = { onSelect(context.key) }
                    )
                }
            }

            ContextChooserPhase.Choices -> {
                state.current?.takeIf { current -> state.choices.none { it.key == current.key } }
                    ?.let { CurrentContextRow(it) }
                state.choices.forEach { context ->
                    NexaContextChoiceRow(
                        companyName = context.companyName,
                        workspaceName = context.workspaceName,
                        current = context.isCurrent,
                        pending = state.pendingKey == context.key,
                        enabled = state.phase == ContextChooserPhase.Choices,
                        onClick = { onSelect(context.key) }
                    )
                }
            }
        }
        if (notice == AccessNotice.ContextSelectionRejected) {
            NexaPrimaryButton(label = stringResource(R.string.context_retry), onClick = onRetry)
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun CurrentContextRow(context: WorkforceContextSummary) {
    NexaActiveContextBar(
        companyName = context.companyName,
        workspaceName = context.workspaceName,
        enabled = false,
        onClick = {}
    )
}

@Composable
private fun CenteredProgress() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AccessNotice.messageResource(): Int = when (this) {
    AccessNotice.AuthenticationRejected -> R.string.access_notice_auth_rejected
    AccessNotice.NetworkUnavailable -> R.string.access_notice_network
    AccessNotice.ServiceUnavailable -> R.string.access_notice_service
    AccessNotice.IntegrationUnavailable -> R.string.access_notice_integration
    AccessNotice.SessionExpired -> R.string.access_notice_expired
    AccessNotice.ContextListUnavailable -> R.string.access_notice_context_list
    AccessNotice.ContextSelectionRejected -> R.string.access_notice_context_rejected
    AccessNotice.ContextSelectionUnavailable -> R.string.access_notice_context_unavailable
    AccessNotice.UnknownContextOutcome -> R.string.access_notice_context_unknown
    AccessNotice.NoContexts -> R.string.access_notice_no_context
}

private fun AccessNotice.tone(): NexaFeedbackTone = when (this) {
    AccessNotice.AuthenticationRejected,
    AccessNotice.NetworkUnavailable,
    AccessNotice.ServiceUnavailable,
    AccessNotice.ContextListUnavailable,
    AccessNotice.ContextSelectionRejected,
    AccessNotice.ContextSelectionUnavailable,
    AccessNotice.UnknownContextOutcome
    -> NexaFeedbackTone.Warning

    AccessNotice.IntegrationUnavailable,
    AccessNotice.SessionExpired,
    AccessNotice.NoContexts
    -> NexaFeedbackTone.Information
}
