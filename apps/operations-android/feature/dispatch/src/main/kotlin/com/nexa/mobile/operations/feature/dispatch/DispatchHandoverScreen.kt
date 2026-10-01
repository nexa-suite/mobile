package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun DispatchHandoverScreen(
    state: DispatchHandoverUiState,
    onRefresh: () -> Unit,
    onConfirm: () -> Unit,
    onReplay: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.dispatch_handover_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.dispatch_handover_authority))
        state.fulfillmentId?.let { Text(stringResource(R.string.dispatch_handover_fulfillment, it)) }
        Text(stringResource(statusLabel(state.status)))
        state.snapshot?.let { snapshot ->
            Text(stringResource(
                R.string.dispatch_handover_versions,
                snapshot.readiness.fulfillmentVersion,
                snapshot.allocation.version
            ))
            Text(stringResource(
                R.string.dispatch_handover_assignment,
                snapshot.driverAssignment?.responsibleDisplayName ?: stringResource(R.string.dispatch_handover_missing)
            ))
            Text(stringResource(
                R.string.dispatch_handover_check,
                if (snapshot.outgoingCheck?.current == true && snapshot.outgoingCheck.matches) {
                    stringResource(R.string.dispatch_handover_confirmed)
                } else {
                    stringResource(R.string.dispatch_handover_missing)
                }
            ))
        }
        state.receipt?.let { receipt ->
            Text(stringResource(R.string.dispatch_handover_completed, receipt.deliveryId, receipt.recordedAt.toString()))
            receipt.evidence?.let { evidence ->
                Text(stringResource(
                    R.string.dispatch_handover_evidence,
                    evidence.warehouseActorMembershipId,
                    evidence.driverMembershipId,
                    evidence.outgoingGoodsCheckId,
                    evidence.occurredAt.toString(),
                    stringResource(if (evidence.current) R.string.dispatch_handover_current_fact
                        else R.string.dispatch_handover_historical_fact)
                ))
            }
        }
        Button(onClick = onRefresh, enabled = state.status != DispatchHandoverStatus.Loading) {
            Text(stringResource(R.string.dispatch_handover_refresh))
        }
        Button(onClick = onConfirm, enabled = state.canConfirm) {
            Text(stringResource(R.string.dispatch_handover_confirm))
        }
        Button(onClick = onReplay, enabled = state.canReplay) {
            Text(stringResource(R.string.dispatch_handover_replay))
        }
    }
}

private fun statusLabel(status: DispatchHandoverStatus): Int = when (status) {
    DispatchHandoverStatus.Initial -> R.string.dispatch_handover_initial
    DispatchHandoverStatus.Loading -> R.string.dispatch_handover_loading
    DispatchHandoverStatus.Current -> R.string.dispatch_handover_current
    DispatchHandoverStatus.Submitting -> R.string.dispatch_handover_submitting
    DispatchHandoverStatus.Completed -> R.string.dispatch_handover_completed_label
    DispatchHandoverStatus.UnknownOutcome -> R.string.dispatch_handover_unknown
    DispatchHandoverStatus.NetworkUnavailable -> R.string.dispatch_handover_network
    DispatchHandoverStatus.ServiceUnavailable -> R.string.dispatch_handover_service
    DispatchHandoverStatus.PermissionDenied -> R.string.dispatch_handover_permission
    DispatchHandoverStatus.Stale -> R.string.dispatch_handover_stale
    DispatchHandoverStatus.Conflict -> R.string.dispatch_handover_conflict
    DispatchHandoverStatus.ContextInvalidated -> R.string.dispatch_handover_context
    DispatchHandoverStatus.SessionInvalidated -> R.string.dispatch_handover_session
}
