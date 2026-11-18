package com.nexa.mobile.operations.tenantaccessgovernance.presentation.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaPrimaryButton
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsDocument
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.R

@Composable
fun LegalAcceptanceScreen(
    state: LegalAcceptanceUiState,
    onAccept: (MobileTermsDocument, Boolean) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        NexaTopAppBar(
            title = stringResource(R.string.mobile_terms_title),
            actions = {
                androidx.compose.material3.TextButton(onClick = onSignOut) {
                    Text(stringResource(R.string.mobile_terms_sign_out))
                }
            }
        )
        when (state) {
            LegalAcceptanceUiState.Inactive,
            LegalAcceptanceUiState.Loading -> LoadingContent()

            LegalAcceptanceUiState.Unavailable -> NexaStatePanel(
                title = stringResource(R.string.mobile_terms_unavailable_title),
                description = stringResource(R.string.mobile_terms_unavailable_body),
                actionLabel = stringResource(R.string.mobile_terms_retry),
                onAction = onRetry,
                modifier = Modifier.padding(16.dp)
            )

            LegalAcceptanceUiState.SessionExpired -> NexaStatePanel(
                title = stringResource(R.string.mobile_terms_session_expired_title),
                description = stringResource(R.string.mobile_terms_session_expired_body),
                actionLabel = stringResource(R.string.mobile_terms_sign_out),
                onAction = onSignOut,
                modifier = Modifier.padding(16.dp)
            )

            is LegalAcceptanceUiState.Accepted -> Unit

            is LegalAcceptanceUiState.RequiresAcceptance -> AcceptanceContent(
                state = state,
                onAccept = onAccept,
                onRetry = onRetry
            )
        }
    }
}

@Composable
fun PublicLegalTermsScreen(
    state: PublicTermsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        NexaTopAppBar(
            title = stringResource(R.string.mobile_terms_title),
            onBack = onBack
        )
        when (state) {
            PublicTermsUiState.Idle,
            PublicTermsUiState.Loading -> LoadingContent()

            PublicTermsUiState.Unavailable -> NexaStatePanel(
                title = stringResource(R.string.mobile_terms_unavailable_title),
                description = stringResource(R.string.mobile_terms_unavailable_body),
                actionLabel = stringResource(R.string.mobile_terms_retry),
                onAction = onRetry,
                modifier = Modifier.padding(16.dp)
            )

            is PublicTermsUiState.Available -> ReadTermsContent(state.document)
        }
    }
}

@Composable
private fun AcceptanceContent(
    state: LegalAcceptanceUiState.RequiresAcceptance,
    onAccept: (MobileTermsDocument, Boolean) -> Unit,
    onRetry: () -> Unit
) {
    val document = state.document
    key(document.documentId, document.version, document.contentDigest) {
        var confirmed by rememberSaveable { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.mobile_terms_acceptance_heading),
                style = MaterialTheme.typography.headlineSmall
            )
            ReadTermsContent(document)
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = confirmed,
                    role = Role.Checkbox,
                    onValueChange = { confirmed = it }
                ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = confirmed, onCheckedChange = null)
                Text(
                    stringResource(R.string.mobile_terms_acceptance_checkbox),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            state.error?.let { error ->
                val message = when (error) {
                    LegalAcceptanceError.StaleDocument ->
                        R.string.mobile_terms_stale_document

                    LegalAcceptanceError.UnknownOutcome -> R.string.mobile_terms_unknown_outcome

                    LegalAcceptanceError.Unavailable -> R.string.mobile_terms_unavailable_body
                }
                Text(
                    stringResource(message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                androidx.compose.material3.TextButton(
                    onClick = onRetry,
                    enabled = !state.submitting
                ) {
                    Text(stringResource(R.string.mobile_terms_retry_status))
                }
            }
            NexaPrimaryButton(
                label = stringResource(R.string.mobile_terms_continue),
                onClick = { onAccept(document, confirmed) },
                enabled = confirmed && !state.submitting,
                loading = state.submitting
            )
        }
    }
}

@Composable
private fun ReadTermsContent(document: MobileTermsDocument) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DocumentIdentity(document)
        SelectionContainer {
            Text(
                document.content,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun DocumentIdentity(document: MobileTermsDocument) {
    Text(
        stringResource(R.string.mobile_terms_version, document.version),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun LoadingContent() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.mobile_terms_loading))
    }
}
