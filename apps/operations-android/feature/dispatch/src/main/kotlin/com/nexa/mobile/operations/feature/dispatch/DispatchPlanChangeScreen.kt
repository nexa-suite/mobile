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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant

@Composable
fun DispatchPlanChangeScreen(
    state: DispatchPlanChangeUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDriver: (String) -> Unit,
    onScheduleChanged: (String) -> Unit,
    onSave: () -> Unit,
    onReplay: () -> Unit,
    onRouteClosed: () -> Unit
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
    val plannedDispatchPreview = state.plannedDispatchAtText.takeIf {
        state.canSchedule && !state.inputInvalid && it.isNotBlank()
    }?.let { runCatching { Instant.parse(it).toString() }.getOrNull() }
    DisposableEffect(Unit) {
        onDispose { closeAction.value() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.dispatch_plan_back))
                }
                Text(
                    stringResource(R.string.dispatch_plan_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(stringResource(R.string.dispatch_plan_authority_note))
            }

            item { DispatchPlanStatus(state.status, onRefresh) }

            state.assignment?.let { assignment ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                stringResource(R.string.dispatch_plan_current_driver),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(assignment.responsibleDisplayName)
                            Text(
                                stringResource(
                                    R.string.dispatch_plan_current_schedule,
                                    assignment.plannedDispatchAt?.toString()
                                        ?: stringResource(R.string.dispatch_plan_no_schedule)
                                )
                            )
                            Text(
                                stringResource(
                                    R.string.dispatch_plan_current_version,
                                    assignment.fulfillmentVersion
                                )
                            )
                        }
                    }
                }

                item {
                    Text(
                        stringResource(R.string.dispatch_plan_driver_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!state.canReassign) {
                        Text(stringResource(R.string.dispatch_plan_driver_permission))
                    }
                }
                items(state.candidates, key = { it.membershipId }) { candidate ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        RadioButton(
                            selected = state.selectedMembershipId == candidate.membershipId,
                            onClick = { onSelectDriver(candidate.membershipId) },
                            enabled = state.canReassign && state.status == DispatchPlanChangeStatus.Current &&
                                state.readiness?.ready == true
                        )
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            Text(candidate.displayName)
                            Text(candidate.email, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = state.plannedDispatchAtText,
                        onValueChange = onScheduleChanged,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.canSchedule && state.status == DispatchPlanChangeStatus.Current &&
                            state.readiness?.ready == true,
                        label = { Text(stringResource(R.string.dispatch_plan_schedule_label)) },
                        supportingText = {
                            Text(
                                if (state.inputInvalid) {
                                    stringResource(R.string.dispatch_plan_schedule_invalid)
                                } else {
                                    stringResource(R.string.dispatch_plan_schedule_help)
                                }
                            )
                        },
                        singleLine = true
                    )
                    if (!state.canSchedule) {
                        Text(stringResource(R.string.dispatch_plan_schedule_permission))
                    }
                    plannedDispatchPreview?.let {
                        Text(stringResource(R.string.dispatch_plan_schedule_preview, it))
                    }
                }

                item {
                    Button(onClick = onSave, enabled = state.canSave) {
                        Text(stringResource(R.string.dispatch_plan_save))
                    }
                }
            } ?: item {
                Text(stringResource(R.string.dispatch_plan_no_assignment))
            }

            if (state.pendingIntent != null && state.status == DispatchPlanChangeStatus.UnknownOutcome) {
                item {
                    Text(stringResource(R.string.dispatch_plan_unknown_outcome))
                    OutlinedButton(onClick = onReplay, enabled = state.canReplay) {
                        Text(stringResource(R.string.dispatch_plan_replay))
                    }
                }
            }

            if (state.history.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.dispatch_plan_history_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(state.history, key = { it.id }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(entry.responsibleDisplayName)
                            Text(
                                stringResource(
                                    R.string.dispatch_plan_history_fact,
                                    entry.fulfillmentVersion,
                                    entry.assignedAt.toString(),
                                    if (entry.current) stringResource(R.string.dispatch_plan_history_current)
                                    else stringResource(R.string.dispatch_plan_history_prior)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DispatchPlanStatus(status: DispatchPlanChangeStatus, onRefresh: () -> Unit) {
    val text = when (status) {
        DispatchPlanChangeStatus.Initial -> R.string.dispatch_plan_status_initial
        DispatchPlanChangeStatus.Loading -> R.string.dispatch_plan_status_loading
        DispatchPlanChangeStatus.Saving -> R.string.dispatch_plan_status_saving
        DispatchPlanChangeStatus.Current -> R.string.dispatch_plan_status_current
        DispatchPlanChangeStatus.NotReady -> R.string.dispatch_plan_status_not_ready
        DispatchPlanChangeStatus.PermissionDenied -> R.string.dispatch_plan_status_denied
        DispatchPlanChangeStatus.Stale -> R.string.dispatch_plan_status_stale
        DispatchPlanChangeStatus.Conflict -> R.string.dispatch_plan_status_conflict
        DispatchPlanChangeStatus.UnknownOutcome -> R.string.dispatch_plan_status_unknown
        DispatchPlanChangeStatus.NetworkUnavailable -> R.string.dispatch_plan_status_network
        DispatchPlanChangeStatus.ServiceUnavailable -> R.string.dispatch_plan_status_service
        DispatchPlanChangeStatus.ContextInvalidated -> R.string.dispatch_plan_status_context
        DispatchPlanChangeStatus.SessionInvalidated -> R.string.dispatch_plan_status_session
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(text))
        if (status !in setOf(DispatchPlanChangeStatus.Loading, DispatchPlanChangeStatus.Saving)) {
            OutlinedButton(onClick = onRefresh) {
                Text(stringResource(R.string.dispatch_plan_refresh))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.dispatch_plan_loading))
            }
        }
    }
}
