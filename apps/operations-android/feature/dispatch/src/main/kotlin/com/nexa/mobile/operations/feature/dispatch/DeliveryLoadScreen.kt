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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DeliveryLoadScreen(
    state: DeliveryLoadUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFulfillment: (String) -> Unit,
    onMoveStop: (String, Int) -> Unit,
    onReasonChanged: (String) -> Unit,
    onSelectWindowPlan: (String?) -> Unit,
    onWindowStartChanged: (String) -> Unit,
    onWindowEndChanged: (String) -> Unit,
    onWindowReasonChanged: (String) -> Unit,
    onPlanWindow: () -> Unit,
    onAttestationChanged: (DeliveryLoadAttestationField, Boolean) -> Unit,
    onSelectDriver: (String?) -> Unit,
    onCreate: () -> Unit,
    onAssign: (String) -> Unit,
    onOffer: (String) -> Unit,
    onConfirmHandoff: (String) -> Unit,
    onAccept: (String) -> Unit,
    onRetrySameCommand: () -> Unit,
    onOpenDelivery: (String) -> Unit,
    onRouteClosed: () -> Unit
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { closeAction.value() } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack) { Text(stringResource(R.string.delivery_load_back)) }
                Text(
                    stringResource(R.string.delivery_load_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(
                        if (state.driverMode) {
                            R.string.delivery_load_driver_intro
                        } else {
                            R.string.delivery_load_dispatch_intro
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item { LoadStatus(state) }
            item {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled =
                        state.status != DeliveryLoadScreenStatus.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.delivery_load_refresh))
                }
            }
            if (state.status == DeliveryLoadScreenStatus.Loading) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.delivery_load_loading))
                    }
                }
            }
            if (state.hasRecoverableCommand) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.delivery_load_unknown_title),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(stringResource(R.string.delivery_load_unknown_body))
                            Button(
                                onClick = onRetrySameCommand,
                                enabled = state.status in setOf(
                                    DeliveryLoadScreenStatus.Current,
                                    DeliveryLoadScreenStatus.Empty
                                )
                            ) { Text(stringResource(R.string.delivery_load_retry)) }
                        }
                    }
                }
            }
            if (!state.driverMode && state.readyCandidates.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.delivery_load_candidates),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(state.readyCandidates, key = { "ready-${it.fulfillmentId}" }) { candidate ->
                    val checked = candidate.fulfillmentId in state.selectedFulfillmentIds
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = {
                            onToggleFulfillment(candidate.fulfillmentId)
                        })
                        Column {
                            Text(
                                stringResource(
                                    R.string.delivery_load_fulfillment,
                                    candidate.fulfillmentId
                                ),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                stringResource(
                                    R.string.delivery_load_candidate_version,
                                    candidate.fulfillmentStatus,
                                    candidate.fulfillmentVersion
                                )
                            )
                            if (candidate.windowStart != null || candidate.windowEnd != null) {
                                Text(
                                    stringResource(
                                        R.string.delivery_load_window_current,
                                        candidate.windowSource.orEmpty(),
                                        candidate.windowStart?.toString().orEmpty(),
                                        candidate.windowEnd?.toString().orEmpty()
                                    )
                                )
                            } else {
                                Text(stringResource(R.string.delivery_load_window_missing))
                                if (state.canSelectWindowPlan(candidate)) {
                                    TextButton(onClick = {
                                        onSelectWindowPlan(candidate.fulfillmentId)
                                    }) {
                                        Text(stringResource(R.string.delivery_load_plan_window))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!state.driverMode && state.windowPlanFulfillmentId != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.delivery_load_plan_window_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(
                                    R.string.delivery_load_plan_window_fulfillment,
                                    state.windowPlanFulfillmentId
                                )
                            )
                            Text(stringResource(R.string.delivery_load_plan_window_body))
                            OutlinedTextField(
                                value = state.windowPlanStart,
                                onValueChange = onWindowStartChanged,
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(stringResource(R.string.delivery_load_window_start))
                                },
                                supportingText = {
                                    Text(stringResource(R.string.delivery_load_window_iso_hint))
                                },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = state.windowPlanEnd,
                                onValueChange = onWindowEndChanged,
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(stringResource(R.string.delivery_load_window_end))
                                },
                                supportingText = {
                                    Text(stringResource(R.string.delivery_load_window_iso_hint))
                                },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = state.windowPlanReason,
                                onValueChange = onWindowReasonChanged,
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(stringResource(R.string.delivery_load_window_reason))
                                },
                                minLines = 2,
                                maxLines = 4
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onPlanWindow, enabled = state.canPlanWindow) {
                                    Text(stringResource(R.string.delivery_load_save_window))
                                }
                                TextButton(onClick = { onSelectWindowPlan(null) }) {
                                    Text(stringResource(R.string.delivery_load_cancel_window))
                                }
                            }
                        }
                    }
                }
            }
            if (!state.driverMode && state.selectedFulfillmentIds.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.delivery_load_stop_order),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(stringResource(R.string.delivery_load_stop_order_body))
                }
                items(state.selectedFulfillmentIds, key = { "selected-$it" }) { fulfillmentId ->
                    val position = state.selectedFulfillmentIds.indexOf(fulfillmentId)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.delivery_load_positioned_fulfillment,
                                position + 1,
                                fulfillmentId
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onMoveStop(fulfillmentId, -1) },
                            enabled =
                                position > 0
                        ) { Text(stringResource(R.string.delivery_load_up)) }
                        TextButton(
                            onClick = { onMoveStop(fulfillmentId, 1) },
                            enabled = position < state.selectedFulfillmentIds.lastIndex
                        ) {
                            Text(stringResource(R.string.delivery_load_down))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.delivery_load_compatibility),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(stringResource(R.string.delivery_load_compatibility_body))
                        AttestationRow(
                            stringResource(R.string.delivery_load_capacity),
                            state.attestation.capacitySufficient
                        ) {
                            onAttestationChanged(DeliveryLoadAttestationField.Capacity, it)
                        }
                        AttestationRow(
                            stringResource(R.string.delivery_load_handling),
                            state.attestation.handlingCompatible
                        ) {
                            onAttestationChanged(DeliveryLoadAttestationField.Handling, it)
                        }
                        AttestationRow(
                            stringResource(R.string.delivery_load_zone),
                            state.attestation.zoneReasonable
                        ) {
                            onAttestationChanged(DeliveryLoadAttestationField.Zone, it)
                        }
                        AttestationRow(
                            stringResource(R.string.delivery_load_exclusive),
                            state.attestation.noExclusiveTransportRestriction
                        ) {
                            onAttestationChanged(
                                DeliveryLoadAttestationField.ExclusiveTransport,
                                it
                            )
                        }
                        OutlinedTextField(
                            value = state.createReason,
                            onValueChange = onReasonChanged,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.delivery_load_reason)) },
                            supportingText = {
                                Text(stringResource(R.string.delivery_load_reason_hint))
                            },
                            minLines = 2,
                            maxLines = 4
                        )
                        Button(
                            onClick = onCreate,
                            enabled = state.canCreate,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.delivery_load_create))
                        }
                    }
                }
            }
            if (!state.driverMode && state.drivers.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.delivery_load_drivers),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(state.drivers, key = { "driver-${it.membershipId}" }) { driver ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected =
                                state.selectedDriverMembershipId == driver.membershipId,
                            onClick = { onSelectDriver(driver.membershipId) }
                        )
                        Text(driver.displayName)
                    }
                }
            }
            if (state.loads.isNotEmpty()) {
                item {
                    Text(
                        stringResource(
                            if (state.driverMode) {
                                R.string.delivery_load_assigned_list
                            } else {
                                R.string.delivery_load_current_list
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(state.loads, key = { "load-${it.id}" }) { load ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.delivery_load_id, load.id),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.delivery_load_version_stops,
                                    load.orderedStops.size,
                                    load.status,
                                    load.version,
                                    load.orderedStops.size
                                )
                            )
                            if (load.assignedDriverMembershipId !=
                                null
                            ) {
                                Text(
                                    stringResource(
                                        R.string.delivery_load_driver_id,
                                        load.assignedDriverMembershipId
                                    )
                                )
                            }
                            load.orderedStops.forEachIndexed { index, stop ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(
                                            R.string.delivery_load_stop_delivery,
                                            index + 1,
                                            stop.deliveryId
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (state.driverMode) {
                                        TextButton(onClick = {
                                            onOpenDelivery(stop.deliveryId)
                                        }) {
                                            Text(
                                                stringResource(
                                                    R.string.delivery_load_review_delivery
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            if (!state.driverMode && load.status == "DRAFT") {
                                Button(onClick = {
                                    onAssign(load.id)
                                }, enabled = state.canAssign(load)) {
                                    Text(stringResource(R.string.delivery_load_assign))
                                }
                            }
                            if (!state.driverMode && load.status == "ASSIGNED") {
                                Button(onClick = {
                                    onOffer(load.id)
                                }, enabled = state.canOffer(load)) {
                                    Text(stringResource(R.string.delivery_load_offer))
                                }
                            }
                            if (!state.driverMode && state.canConfirmHandoff(load)) {
                                Button(onClick = {
                                    onConfirmHandoff(load.id)
                                }) { Text(stringResource(R.string.delivery_load_confirm)) }
                            }
                            if (state.driverMode &&
                                load.assignedDriverMembershipId == state.membershipId
                            ) {
                                Text(stringResource(R.string.delivery_load_acceptance_note))
                                if (state.canAccept(load)) {
                                    Button(onClick = {
                                        onAccept(load.id)
                                    }) { Text(stringResource(R.string.delivery_load_accept)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttestationRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChecked)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LoadStatus(state: DeliveryLoadUiState) {
    val text = when (state.status) {
        DeliveryLoadScreenStatus.Initial -> R.string.delivery_load_status_initial

        DeliveryLoadScreenStatus.Loading -> R.string.delivery_load_status_loading

        DeliveryLoadScreenStatus.Current -> R.string.delivery_load_status_current

        DeliveryLoadScreenStatus.Empty -> R.string.delivery_load_status_empty

        DeliveryLoadScreenStatus.Failed -> if (state.failureCode ==
            null
        ) {
            R.string.delivery_load_status_failed_generic
        } else {
            R.string.delivery_load_status_failed
        }

        DeliveryLoadScreenStatus.PermissionDenied -> R.string.delivery_load_status_permission

        DeliveryLoadScreenStatus.ContextInvalidated -> R.string.delivery_load_status_context

        DeliveryLoadScreenStatus.SessionInvalidated -> R.string.delivery_load_status_session
    }
    if (state.status == DeliveryLoadScreenStatus.Failed && state.failureCode != null) {
        Text(
            stringResource(text, state.failureCode),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.commandStatus ==
        DeliveryLoadCommandStatus.Changed
    ) {
        Text(stringResource(R.string.delivery_load_confirmed))
    }
    if (state.commandStatus == DeliveryLoadCommandStatus.Rejected) {
        Text(
            if (state.failureCode == null) {
                stringResource(R.string.delivery_load_rejected_generic)
            } else {
                stringResource(R.string.delivery_load_rejected, state.failureCode)
            }
        )
    }
    if (state.commandStatus ==
        DeliveryLoadCommandStatus.UnknownOutcome
    ) {
        Text(stringResource(R.string.delivery_load_outcome_unknown))
    }
    if (state.commandStatus ==
        DeliveryLoadCommandStatus.PersistenceUnavailable
    ) {
        Text(stringResource(R.string.delivery_load_recovery_unavailable))
    }
}
