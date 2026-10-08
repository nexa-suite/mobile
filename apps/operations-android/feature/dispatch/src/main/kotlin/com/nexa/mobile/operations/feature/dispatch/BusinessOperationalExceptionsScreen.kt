package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.feature.dispatch.BusinessOperationalExceptionCommandStatus as ExceptionCommandStatus
import com.nexa.mobile.operations.feature.dispatch.BusinessOperationalExceptionsStatus as OperationalExceptionsStatus
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalException

@Composable
fun BusinessOperationalExceptionsScreen(
    state: BusinessOperationalExceptionUiState,
    viewModel: BusinessOperationalExceptionsViewModel,
    onBack: () -> Unit,
    onRouteClosed: () -> Unit,
    onOpenExecutionHold: ((String) -> Unit)? = null
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { closeAction.value() } }
    var reason by remember(state.selectedExceptionId) { mutableStateOf("") }
    var note by remember(state.selectedExceptionId) { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.safeDrawing
            ).imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack) { Text(stringResource(R.string.bom_exceptions_back)) }
                Text(
                    stringResource(R.string.bom_exceptions_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.bom_exceptions_authority_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                StatusCard(state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::refresh,
                        enabled = state.canRead &&
                            state.status != OperationalExceptionsStatus.Loading
                    ) {
                        Text(stringResource(R.string.bom_exceptions_refresh))
                    }
                    if (state.commandStatus ==
                        ExceptionCommandStatus.UnknownOutcome
                    ) {
                        Button(
                            onClick = viewModel::retryUnknownOutcome,
                            enabled =
                                state.pendingCommand != null
                        ) {
                            Text(stringResource(R.string.bom_exceptions_retry_exact))
                        }
                    }
                }
            }
            if (state.status == OperationalExceptionsStatus.Loading) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.bom_exceptions_loading))
                    }
                }
            }
            state.snapshot?.let { snapshot ->
                item {
                    Text(
                        stringResource(R.string.bom_exceptions_as_of, snapshot.asOf),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (snapshot.exceptions.isEmpty()) {
                    item { InfoCard(stringResource(R.string.bom_exceptions_empty)) }
                } else {
                    items(snapshot.exceptions, key = { it.id.lowercase() }) { row ->
                        ExceptionListCard(row, selected = row.id == state.selectedExceptionId) {
                            viewModel.selectException(row.id)
                            reason = ""
                            note = ""
                        }
                    }
                    state.selectedException?.let { row ->
                        item { ExceptionDetailCard(row, onOpenExecutionHold) }
                        if (state.canCoordinate) {
                            item {
                                ExceptionActionCard(
                                    row = row,
                                    state = state,
                                    reason = reason,
                                    note = note,
                                    targetMembershipId = state.selectedAssigneeMembershipId,
                                    onReasonChanged = { reason = it.take(2_000) },
                                    onNoteChanged = { note = it.take(2_000) },
                                    onTargetChanged = viewModel::selectAssignee,
                                    onClaim = { viewModel.claim(reason) },
                                    onReassign = {
                                        state.selectedAssigneeMembershipId?.let {
                                            viewModel.reassign(it, reason)
                                        }
                                    },
                                    onFollowUp = { viewModel.followUp(reason, note) },
                                    onResolve = { viewModel.resolve(reason) },
                                    onClose = { viewModel.close(reason) }
                                )
                            }
                        } else {
                            item { InfoCard(stringResource(R.string.bom_exceptions_read_only)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: BusinessOperationalExceptionUiState) {
    val message = when {
        state.commandStatus == ExceptionCommandStatus.Persisting ->
            R.string.bom_exceptions_persisting

        state.commandStatus == ExceptionCommandStatus.Saving -> R.string.bom_exceptions_saving

        state.commandStatus == ExceptionCommandStatus.UnknownOutcome ->
            R.string.bom_exceptions_unknown

        state.commandStatus == ExceptionCommandStatus.PersistenceUnavailable ->
            R.string.bom_exceptions_storage

        state.commandStatus == ExceptionCommandStatus.Stale -> R.string.bom_exceptions_stale

        state.commandStatus == ExceptionCommandStatus.Rejected -> R.string.bom_exceptions_rejected

        state.commandStatus == ExceptionCommandStatus.Applied -> R.string.bom_exceptions_applied

        else -> when (state.status) {
            OperationalExceptionsStatus.Initial -> R.string.bom_exceptions_initial
            OperationalExceptionsStatus.Loading -> R.string.bom_exceptions_loading
            OperationalExceptionsStatus.Current -> R.string.bom_exceptions_current
            OperationalExceptionsStatus.Empty -> R.string.bom_exceptions_empty
            OperationalExceptionsStatus.PermissionDenied -> R.string.bom_exceptions_permission
            OperationalExceptionsStatus.NetworkUnavailable -> R.string.bom_exceptions_network
            OperationalExceptionsStatus.ServiceUnavailable -> R.string.bom_exceptions_service
            OperationalExceptionsStatus.ContextInvalidated -> R.string.bom_exceptions_context
            OperationalExceptionsStatus.SessionInvalidated -> R.string.bom_exceptions_session
        }
    }
    InfoCard(stringResource(message)) {
        state.rejectionCode?.let { Text(stringResource(R.string.bom_exceptions_error_code, it)) }
        if (state.replayed) Text(stringResource(R.string.bom_exceptions_replayed))
    }
}

@Composable
private fun ExceptionListCard(
    row: BusinessOperationalException,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${row.severity} · ${row.status} · ${row.type}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.bom_exceptions_case, row.id))
            Text(
                stringResource(
                    R.string.bom_exceptions_object,
                    row.affectedObjectType,
                    row.affectedObjectId
                )
            )
            Text(
                stringResource(
                    R.string.bom_exceptions_delivery_revision,
                    row.deliveryId,
                    row.deliveryVersion
                )
            )
            TextButton(onClick = onSelect) {
                Text(
                    stringResource(
                        if (selected) {
                            R.string.bom_exceptions_selected
                        } else {
                            R.string.bom_exceptions_open_detail
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun ExceptionDetailCard(
    row: BusinessOperationalException,
    onOpenExecutionHold: ((String) -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.bom_exceptions_detail),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(row.description, style = MaterialTheme.typography.bodyLarge)
            row.reason?.let { Text(stringResource(R.string.bom_exceptions_original_reason, it)) }
            row.place?.let { Text(stringResource(R.string.bom_exceptions_place, it)) }
            Text(
                stringResource(R.string.bom_exceptions_source, row.sourceKind, row.sourceIncidentId)
            )
            Text(stringResource(R.string.bom_exceptions_occurred, row.occurredAt))
            Text(
                stringResource(
                    R.string.bom_exceptions_reporter,
                    row.reportedByMembershipId,
                    row.reportedAt
                )
            )
            Text(
                stringResource(
                    R.string.bom_exceptions_responsible,
                    row.responsibleMembershipId ?: "—"
                )
            )
            Text(
                stringResource(
                    R.string.bom_exceptions_coordination_owner,
                    row.coordinationOwnerMembershipId ?: "—",
                    row.coordinationClaimedAt ?: "—"
                )
            )
            row.resolution?.let { Text(stringResource(R.string.bom_exceptions_resolution, it)) }
            row.outcome?.let { Text(stringResource(R.string.bom_exceptions_outcome, it)) }
            if (row.evidenceObjectIds.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.bom_exceptions_evidence,
                        row.evidenceObjectIds.joinToString()
                    )
                )
            }
            if (row.affectedObjectType == "DELIVERY" && onOpenExecutionHold != null) {
                OutlinedButton(onClick = { onOpenExecutionHold(row.deliveryId) }) {
                    Text(stringResource(R.string.bom_exceptions_open_hold))
                }
            }
        }
    }
}

@Composable
private fun ExceptionActionCard(
    row: BusinessOperationalException,
    state: BusinessOperationalExceptionUiState,
    reason: String,
    note: String,
    targetMembershipId: String?,
    onReasonChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onTargetChanged: (String) -> Unit,
    onClaim: () -> Unit,
    onReassign: () -> Unit,
    onFollowUp: () -> Unit,
    onResolve: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.bom_exceptions_actions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.bom_exceptions_server_authority))
            OutlinedTextField(value = reason, onValueChange = onReasonChanged, label = {
                Text(stringResource(R.string.bom_exceptions_reason))
            }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            if (state.canClaim(row)) {
                Button(onClick = onClaim, enabled = reason.isNotBlank()) {
                    Text(stringResource(R.string.bom_exceptions_claim))
                }
            }
            if (state.canReassign(row)) {
                val menu = remember { mutableStateOf(false) }
                val selectedActor = state.eligibleAssignees.firstOrNull {
                    it.membershipId ==
                        targetMembershipId
                }
                OutlinedButton(
                    onClick = { menu.value = true },
                    enabled =
                        !state.assigneesLoading && state.eligibleAssignees.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        selectedActor?.displayName
                            ?: stringResource(R.string.bom_exceptions_choose_assignee)
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = menu.value, onDismissRequest = {
                    menu.value =
                        false
                }) {
                    state.eligibleAssignees.forEach { actor ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(actor.displayName) },
                            onClick = {
                                menu.value = false
                                onTargetChanged(actor.membershipId)
                            }
                        )
                    }
                }
                if (state.assigneesLoading) {
                    Text(
                        stringResource(R.string.bom_exceptions_loading_assignees)
                    )
                }
                if (state.assigneesUnavailable) {
                    Text(
                        stringResource(R.string.bom_exceptions_assignees_unavailable)
                    )
                }
                OutlinedButton(
                    onClick = onReassign,
                    enabled =
                        reason.isNotBlank() && targetMembershipId != null
                ) {
                    Text(stringResource(R.string.bom_exceptions_reassign))
                }
            }
            if (state.canFollowUp(row)) {
                OutlinedTextField(value = note, onValueChange = onNoteChanged, label = {
                    Text(stringResource(R.string.bom_exceptions_follow_up_note))
                }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = onFollowUp, enabled = reason.isNotBlank() && note.isNotBlank()) {
                    Text(stringResource(R.string.bom_exceptions_follow_up))
                }
            }
            if (state.canResolve(row)) {
                Button(onClick = onResolve, enabled = reason.isNotBlank()) {
                    Text(stringResource(R.string.bom_exceptions_resolve))
                }
            }
            if (state.canClose(row)) {
                OutlinedButton(onClick = onClose, enabled = reason.isNotBlank()) {
                    Text(stringResource(R.string.bom_exceptions_close))
                }
            }
            if (row.severity == "BLOCKING" || row.severity == "CRITICAL") {
                Text(
                    stringResource(R.string.bom_exceptions_blocking_gate),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun InfoCard(text: String, content: @Composable (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text)
            content?.invoke()
        }
    }
}
