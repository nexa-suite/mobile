package com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesMessagingAuthority
import com.nexa.mobile.operations.customerbuyerrelationships.presentation.R

@Composable
fun SalesConversationAction(
    accountId: String,
    authority: SalesMessagingAuthority,
    viewModel: SalesConversationViewModel
) {
    var open by remember(accountId, authority) { mutableStateOf(false) }
    DisposableEffect(viewModel) {
        onDispose(viewModel::deactivate)
    }
    TextButton(onClick = { open = true }) {
        Text(stringResource(R.string.sales_messages_title))
    }
    LaunchedEffect(open, accountId, authority) {
        if (open) viewModel.activate(authority, accountId) else viewModel.deactivate()
    }
    if (!open) return

    val state by viewModel.state.collectAsState()
    val pendingMessage = state.pending
    var recipientId by remember(accountId, authority) { mutableStateOf<String?>(null) }
    var body by remember(accountId, authority) { mutableStateOf("") }
    LaunchedEffect(state.confirmedSendRevision) {
        if (state.confirmedSendRevision > 0) body = ""
    }

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.sales_messages_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                when (state.status) {
                    SalesConversationStatus.Loading ->
                        Text(stringResource(R.string.sales_messages_loading))

                    SalesConversationStatus.Unavailable ->
                        Text(stringResource(R.string.sales_messages_unavailable))

                    SalesConversationStatus.PermissionDenied ->
                        Text(stringResource(R.string.sales_messages_permission_denied))

                    SalesConversationStatus.ContextInvalidated ->
                        Text(stringResource(R.string.sales_messages_context_changed))

                    else -> Unit
                }
                state.projection?.messages?.forEach { message ->
                    Text(
                        stringResource(
                            R.string.sales_messages_byline,
                            message.sender,
                            message.createdAt
                        )
                    )
                    Text(message.body)
                }
                if (state.projection?.messages?.isEmpty() == true) {
                    Text(stringResource(R.string.sales_messages_empty))
                }
                if (pendingMessage != null) {
                    Text(stringResource(R.string.sales_messages_pending))
                    if (pendingMessage.accountId == accountId && authority.canManageMessages) {
                        Button(
                            enabled = state.status != SalesConversationStatus.Sending &&
                                state.pendingStoreAvailable,
                            onClick = viewModel::retryPending
                        ) {
                            Text(stringResource(R.string.sales_messages_retry))
                        }
                    }
                } else if (state.pendingStoreAvailable && authority.canManageMessages) {
                    state.projection?.buyers?.forEach { buyer ->
                        TextButton(onClick = { recipientId = buyer.membershipId }) {
                            Text(
                                stringResource(
                                    if (recipientId == buyer.membershipId) {
                                        R.string.sales_messages_selected_buyer
                                    } else {
                                        R.string.sales_messages_choose_buyer
                                    },
                                    buyer.displayName
                                )
                            )
                        }
                    }
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it.take(MAX_MESSAGE_LENGTH) },
                        label = { Text(stringResource(R.string.sales_messages_body)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = state.status == SalesConversationStatus.Current &&
                            recipientId != null && body.isNotBlank(),
                        onClick = {
                            recipientId?.let { viewModel.send(it, body) }
                        }
                    ) {
                        Text(stringResource(R.string.sales_messages_send))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.status != SalesConversationStatus.Loading &&
                    state.status != SalesConversationStatus.Sending,
                onClick = viewModel::refresh
            ) {
                Text(stringResource(R.string.sales_messages_refresh))
            }
        },
        dismissButton = {
            TextButton(
                enabled = state.status != SalesConversationStatus.Sending,
                onClick = { open = false }
            ) {
                Text(stringResource(R.string.sales_messages_close))
            }
        }
    )
}

private const val MAX_MESSAGE_LENGTH = 4000
