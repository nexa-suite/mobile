package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

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
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsLine
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun DispatchOutgoingGoodsScreen(
    state: DispatchOutgoingGoodsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onObservedLotChanged: (String, String) -> Unit,
    onObservedQuantityChanged: (String, String) -> Unit,
    onRecord: () -> Unit,
    onRetry: () -> Unit,
    onRouteClosed: () -> Unit,
    onResolutionReasonChanged: (String) -> Unit = {},
    onResolveDiscrepancy: () -> Unit = {}
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) {
        onDispose { closeAction.value() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.dispatch_outgoing_back))
                }
                Text(
                    stringResource(R.string.dispatch_outgoing_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                state.fulfillment?.let {
                    Text(stringResource(R.string.dispatch_outgoing_fulfillment, it.fulfillmentId))
                }
                Text(
                    stringResource(R.string.dispatch_outgoing_authority_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { OutgoingStatusPanel(state.status, state.observedAt, state.hasPendingCommand) }
            item {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = state.status !in setOf(
                        DispatchOutgoingGoodsStatus.Loading,
                        DispatchOutgoingGoodsStatus.Submitting
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dispatch_outgoing_refresh)) }
            }
            state.currentCheck?.let { check ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                if (check.openDiscrepancy) {
                                    stringResource(R.string.dispatch_outgoing_discrepancy_open)
                                } else if (check.matches && check.current) {
                                    stringResource(R.string.dispatch_outgoing_last_match)
                                } else {
                                    stringResource(R.string.dispatch_outgoing_last_check_stale)
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(
                                    R.string.dispatch_outgoing_checked_at,
                                    formatInstant(check.checkedAt)
                                )
                            )
                        }
                    }
                }
                check.discrepancy?.let { discrepancy ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    stringResource(R.string.dispatch_outgoing_discrepancy_history),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(
                                        R.string.dispatch_outgoing_discrepancy_identity,
                                        discrepancy.id,
                                        discrepancy.checkedByMembershipId,
                                        formatInstant(discrepancy.checkedAt)
                                    )
                                )
                                discrepancy.lines.forEach { line ->
                                    Text(
                                        stringResource(
                                            R.string.dispatch_outgoing_discrepancy_line,
                                            line.expectedLotId ?: "—",
                                            line.expectedQuantity.toPlainString(),
                                            line.observedLotId ?: "—",
                                            line.observedQuantity.toPlainString(),
                                            line.unit
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (check.matches && check.current && check.openDiscrepancy) {
                        item {
                            OutlinedTextField(
                                value = state.resolutionReason,
                                onValueChange = onResolutionReasonChanged,
                                label = {
                                    Text(
                                        stringResource(R.string.dispatch_outgoing_resolution_reason)
                                    )
                                },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            R.string.dispatch_outgoing_resolution_explainer
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = onResolveDiscrepancy,
                                enabled = state.canResolveDiscrepancy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.dispatch_outgoing_resolve_discrepancy))
                            }
                        }
                    }
                }
            }
            state.currentResolution?.let { resolution ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                stringResource(R.string.dispatch_outgoing_resolution_recorded),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(
                                    R.string.dispatch_outgoing_resolution_identity,
                                    resolution.actorMembershipId,
                                    formatInstant(resolution.resolvedAt)
                                )
                            )
                            Text(resolution.reason)
                            if (!resolution.current) {
                                Text(
                                    stringResource(R.string.dispatch_outgoing_resolution_historical)
                                )
                            }
                        }
                    }
                }
            }
            state.allocation?.let { allocation ->
                item {
                    Text(
                        stringResource(
                            R.string.dispatch_outgoing_allocation,
                            allocation.id,
                            allocation.version
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(
                            R.string.dispatch_outgoing_as_of,
                            formatInstant(allocation.asOf)
                        )
                    )
                }
                items(allocation.lines, key = { it.physicalAllocationLineId }) { line ->
                    OutgoingLineCard(line, onObservedLotChanged, onObservedQuantityChanged)
                }
            }
            if (state.status == DispatchOutgoingGoodsStatus.Loading ||
                state.status == DispatchOutgoingGoodsStatus.Submitting
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.dispatch_outgoing_busy))
                    }
                }
            }
            item {
                Button(
                    onClick = onRecord,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dispatch_outgoing_record)) }
                if (state.hasPendingCommand && state.status in setOf(
                        DispatchOutgoingGoodsStatus.UnknownOutcome,
                        DispatchOutgoingGoodsStatus.NetworkUnavailable,
                        DispatchOutgoingGoodsStatus.ServiceUnavailable
                    )
                ) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.dispatch_outgoing_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun OutgoingLineCard(
    line: DispatchOutgoingGoodsLine,
    onObservedLotChanged: (String, String) -> Unit,
    onObservedQuantityChanged: (String, String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.catalogItemId, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(
                    R.string.dispatch_outgoing_expected_quantity,
                    line.remainingQuantity.toPlainString(),
                    line.unit
                )
            )
            Text(stringResource(R.string.dispatch_outgoing_expected_lot, line.expectedLotId ?: "—"))
            OutlinedTextField(
                value = line.observedLotId,
                onValueChange = { onObservedLotChanged(line.physicalAllocationLineId, it) },
                label = { Text(stringResource(R.string.dispatch_outgoing_observed_lot)) },
                singleLine = true,
                enabled = line.remainingQuantity.signum() != 0,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = line.observedQuantity,
                onValueChange = { onObservedQuantityChanged(line.physicalAllocationLineId, it) },
                label = {
                    Text(stringResource(R.string.dispatch_outgoing_observed_quantity, line.unit))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OutgoingStatusPanel(
    status: DispatchOutgoingGoodsStatus,
    observedAt: Instant?,
    hasPendingCommand: Boolean
) {
    val (title, body) = when (status) {
        DispatchOutgoingGoodsStatus.Initial ->
            R.string.dispatch_outgoing_initial_title to
                R.string.dispatch_outgoing_initial_body

        DispatchOutgoingGoodsStatus.Loading ->
            R.string.dispatch_outgoing_loading_title to
                R.string.dispatch_outgoing_busy

        DispatchOutgoingGoodsStatus.Current ->
            R.string.dispatch_outgoing_current_title to
                R.string.dispatch_outgoing_current_body

        DispatchOutgoingGoodsStatus.Submitting ->
            R.string.dispatch_outgoing_submitting_title to
                R.string.dispatch_outgoing_busy

        DispatchOutgoingGoodsStatus.UnknownOutcome ->
            R.string.dispatch_outgoing_unknown_title to
                R.string.dispatch_outgoing_unknown_body

        DispatchOutgoingGoodsStatus.NetworkUnavailable ->
            R.string.dispatch_outgoing_network_title to
                R.string.dispatch_outgoing_network_body

        DispatchOutgoingGoodsStatus.ServiceUnavailable ->
            R.string.dispatch_outgoing_service_title to
                R.string.dispatch_outgoing_service_body

        DispatchOutgoingGoodsStatus.PermissionDenied ->
            R.string.dispatch_outgoing_permission_title to
                R.string.dispatch_outgoing_permission_body

        DispatchOutgoingGoodsStatus.Stale ->
            R.string.dispatch_outgoing_stale_title to
                R.string.dispatch_outgoing_stale_body

        DispatchOutgoingGoodsStatus.Conflict ->
            R.string.dispatch_outgoing_conflict_title to
                R.string.dispatch_outgoing_conflict_body

        DispatchOutgoingGoodsStatus.ContextInvalidated ->
            R.string.dispatch_outgoing_context_title to
                R.string.dispatch_outgoing_context_body

        DispatchOutgoingGoodsStatus.SessionInvalidated ->
            R.string.dispatch_outgoing_session_title to
                R.string.dispatch_outgoing_session_body
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(stringResource(body))
        if (status == DispatchOutgoingGoodsStatus.Current && observedAt != null) {
            Text(stringResource(R.string.dispatch_outgoing_observed_at, formatInstant(observedAt)))
        }
        if (hasPendingCommand) Text(stringResource(R.string.dispatch_outgoing_pending_note))
    }
}

private fun formatInstant(value: Instant): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atOffset(java.time.ZoneOffset.UTC))
