package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DispatchAssignmentScreen(
    state: DispatchAssignmentUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDriver: (String) -> Unit,
    onAssign: () -> Unit,
    onReplay: () -> Unit,
    onRouteClosed: () -> Unit,
    onChangePlan: (() -> Unit)? = null,
    onIdentifyHandoff: (() -> Unit)? = null
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
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
                    Text(stringResource(R.string.dispatch_assignment_back))
                }
                Text(
                    stringResource(R.string.dispatch_assignment_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.dispatch_assignment_authority_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { AssignmentStatusCard(state.status) }

            state.fulfillmentId?.let { fulfillmentId ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                stringResource(
                                    R.string.dispatch_assignment_fulfillment,
                                    fulfillmentId
                                ),
                                fontWeight = FontWeight.SemiBold
                            )
                            state.readiness?.let { readiness ->
                                Text(
                                    stringResource(
                                        R.string.dispatch_assignment_version,
                                        readiness.fulfillmentVersion,
                                        readiness.fulfillmentStatus
                                    )
                                )
                                Text(
                                    stringResource(
                                        R.string.dispatch_assignment_allocation,
                                        readiness.physicalAllocationId,
                                        readiness.physicalAllocationVersion
                                    )
                                )
                                Text(
                                    if (readiness.ready) {
                                        stringResource(R.string.dispatch_assignment_ready)
                                    } else {
                                        stringResource(R.string.dispatch_assignment_not_ready)
                                    },
                                    color = if (readiness.ready) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (state.status == DispatchAssignmentStatus.Loading ||
                state.status == DispatchAssignmentStatus.Saving
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.dispatch_assignment_loading))
                    }
                }
            }

            state.assignment?.let { assignment ->
                item {
                    AssignmentFactCard(assignment)
                }
                if (onIdentifyHandoff != null && assignment.current && assignment.deliveryId != null &&
                    state.status == DispatchAssignmentStatus.Current && state.pendingIntent == null) {
                    item {
                        OutlinedButton(onClick = onIdentifyHandoff) { Text("Identificar traspaso preparado") }
                    }
                }
                if (onChangePlan != null) {
                    item {
                        OutlinedButton(onClick = onChangePlan) {
                            Text(stringResource(R.string.dispatch_assignment_change_plan))
                        }
                    }
                }
            }

            if (state.assignment == null && state.candidates.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.dispatch_assignment_choose_driver),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(state.candidates, key = { it.membershipId.lowercase() }) { candidate ->
                    DriverCandidateCard(
                        candidate = candidate,
                        selected = state.selectedMembershipId == candidate.membershipId,
                        enabled = state.status == DispatchAssignmentStatus.Current &&
                            state.readiness?.ready == true && state.assignmentPermission,
                        onClick = { onSelectDriver(candidate.membershipId) }
                    )
                }
            }

            item {
                Button(
                    onClick = onAssign,
                    enabled = state.canAssign,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dispatch_assignment_assign)) }
                if (state.canReplay) {
                    OutlinedButton(
                        onClick = onReplay,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.dispatch_assignment_replay)) }
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = state.status != DispatchAssignmentStatus.Loading &&
                        state.status != DispatchAssignmentStatus.Saving,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dispatch_assignment_refresh)) }
            }
        }
    }
}

@Composable
private fun AssignmentStatusCard(status: DispatchAssignmentStatus) {
    val (title, body) = when (status) {
        DispatchAssignmentStatus.Initial ->
            R.string.dispatch_assignment_initial_title to R.string.dispatch_assignment_initial_body

        DispatchAssignmentStatus.Loading ->
            R.string.dispatch_assignment_loading_title to R.string.dispatch_assignment_loading

        DispatchAssignmentStatus.Saving ->
            R.string.dispatch_assignment_saving_title to R.string.dispatch_assignment_saving_body

        DispatchAssignmentStatus.Current ->
            R.string.dispatch_assignment_current_title to R.string.dispatch_assignment_current_body

        DispatchAssignmentStatus.NotReady ->
            R.string.dispatch_assignment_not_ready_title to
                R.string.dispatch_assignment_not_ready_body

        DispatchAssignmentStatus.PermissionUnknown ->
            R.string.dispatch_assignment_permission_unknown_title to
                R.string.dispatch_assignment_permission_unknown_body

        DispatchAssignmentStatus.PermissionDenied ->
            R.string.dispatch_assignment_permission_denied_title to
                R.string.dispatch_assignment_permission_denied_body

        DispatchAssignmentStatus.Stale ->
            R.string.dispatch_assignment_stale_title to R.string.dispatch_assignment_stale_body

        DispatchAssignmentStatus.Conflict ->
            R.string.dispatch_assignment_conflict_title to
                R.string.dispatch_assignment_conflict_body

        DispatchAssignmentStatus.UnknownOutcome ->
            R.string.dispatch_assignment_unknown_title to R.string.dispatch_assignment_unknown_body

        DispatchAssignmentStatus.NetworkUnavailable ->
            R.string.dispatch_assignment_network_title to R.string.dispatch_assignment_network_body

        DispatchAssignmentStatus.ServiceUnavailable ->
            R.string.dispatch_assignment_service_title to R.string.dispatch_assignment_service_body

        DispatchAssignmentStatus.ContextInvalidated ->
            R.string.dispatch_assignment_context_title to R.string.dispatch_assignment_context_body

        DispatchAssignmentStatus.SessionInvalidated ->
            R.string.dispatch_assignment_session_title to R.string.dispatch_assignment_session_body
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DriverCandidateCard(
    candidate: DispatchDriverCandidate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "${candidate.displayName}, ${candidate.email}" },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(candidate.displayName, fontWeight = FontWeight.SemiBold)
                Text(candidate.email, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AssignmentFactCard(assignment: PreparedFulfillmentDriverAssignment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.dispatch_assignment_assigned),
                fontWeight = FontWeight.SemiBold
            )
            Text(assignment.responsibleDisplayName)
            Text(
                stringResource(R.string.dispatch_assignment_when, assignment.assignedAt.toString())
            )
            assignment.deliveryId?.let {
                Text(stringResource(R.string.dispatch_assignment_delivery, it))
            }
        }
    }
}
