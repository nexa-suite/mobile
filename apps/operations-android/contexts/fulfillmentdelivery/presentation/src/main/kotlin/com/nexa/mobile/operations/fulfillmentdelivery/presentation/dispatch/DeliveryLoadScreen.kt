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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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

    val candidateMinWidth = 150.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = candidateMinWidth),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onBack) {
                            Text(stringResource(R.string.delivery_load_back))
                        }
                        TextButton(
                            onClick = onRefresh,
                            enabled = state.status != DeliveryLoadScreenStatus.Loading
                        ) {
                            Text(stringResource(R.string.delivery_load_refresh))
                        }
                    }
                    Text(
                        stringResource(R.string.delivery_load_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    LoadStatus(state)
                }
            }
            if (state.status == DeliveryLoadScreenStatus.Loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
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
                item(span = { GridItemSpan(maxLineSpan) }) {
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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.delivery_load_candidates),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(state.readyCandidates, key = { "ready-${it.fulfillmentId}" }) { candidate ->
                    ReadyCandidateCard(
                        candidate = candidate,
                        selected = candidate.fulfillmentId in state.selectedFulfillmentIds,
                        onToggle = { onToggleFulfillment(candidate.fulfillmentId) },
                        onPlanWindow = if (state.canSelectWindowPlan(candidate)) {
                            { onSelectWindowPlan(candidate.fulfillmentId) }
                        } else {
                            null
                        }
                    )
                }
            }
            if (!state.driverMode && state.windowPlanFulfillmentId != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.delivery_load_stop_order),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(stringResource(R.string.delivery_load_stop_order_body))
                    }
                }
                items(
                    state.selectedFulfillmentIds,
                    key = { "selected-$it" },
                    span = { GridItemSpan(maxLineSpan) }
                ) { fulfillmentId ->
                    val position = state.selectedFulfillmentIds.indexOf(fulfillmentId)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.delivery_load_positioned_fulfillment,
                                position + 1,
                                state.readyCandidates.firstOrNull {
                                    it.fulfillmentId == fulfillmentId
                                }?.orderNumber?.takeIf { it.isNotBlank() }
                                    ?: fulfillmentId.take(8)
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
                item(span = { GridItemSpan(maxLineSpan) }) {
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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.delivery_load_drivers),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(
                    state.drivers,
                    key = { "driver-${it.membershipId}" },
                    span = { GridItemSpan(if (state.drivers.size == 1) maxLineSpan else 1) }
                ) { driver ->
                    val selected = state.selectedDriverMembershipId == driver.membershipId
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSelectDriver(driver.membershipId) }
                            ).padding(horizontal = 12.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(
                                driver.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            if (state.loads.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
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
                items(
                    state.loads,
                    key = { "load-${it.id}" },
                    span = { GridItemSpan(maxLineSpan) }
                ) { load ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.delivery_load_id, load.id.take(8)),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.delivery_load_stop_count,
                                    load.orderedStops.size,
                                    load.orderedStops.size
                                )
                            )
                            Text(
                                loadStatusLabel(load.status),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val assignedDriverMembershipId = load.assignedDriverMembershipId
                            if (assignedDriverMembershipId != null) {
                                Text(
                                    stringResource(
                                        R.string.delivery_load_driver_id,
                                        state.drivers.firstOrNull {
                                            it.membershipId == assignedDriverMembershipId
                                        }?.displayName ?: assignedDriverMembershipId.take(8)
                                    )
                                )
                            }
                            load.orderedStops.forEachIndexed { index, stop ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(
                                            R.string.delivery_load_stop_delivery,
                                            index + 1,
                                            stop.deliveryId.take(8)
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
private fun ReadyCandidateCard(
    candidate: DispatchReadiness,
    selected: Boolean,
    onToggle: () -> Unit,
    onPlanWindow: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(
            Modifier.fillMaxWidth()
                .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                candidate.orderNumber?.takeIf { it.isNotBlank() }
                    ?: stringResource(
                        R.string.delivery_load_fulfillment,
                        candidate.fulfillmentId.take(8)
                    ),
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    candidate.customerName.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Checkbox(checked = selected, onCheckedChange = null)
            }
            Text(
                candidateWindowLabel(candidate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onPlanWindow != null) {
            TextButton(onClick = onPlanWindow, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(stringResource(R.string.delivery_load_plan_window))
            }
        }
    }
}

@Composable
private fun candidateWindowLabel(candidate: DispatchReadiness): String {
    val start = candidate.windowStart?.atZone(ZoneId.systemDefault())
    val end = candidate.windowEnd?.atZone(ZoneId.systemDefault())
    if (start == null && end == null) return stringResource(R.string.delivery_load_window_missing)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
    return when {
        start == null -> stringResource(R.string.delivery_load_window_until, dateTime.format(end))

        end == null -> stringResource(R.string.delivery_load_window_from, dateTime.format(start))

        start.toLocalDate() == end.toLocalDate() -> stringResource(
            R.string.delivery_load_window_same_day,
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(start),
            time.format(start),
            time.format(end)
        )

        else -> stringResource(
            R.string.delivery_load_window_range,
            dateTime.format(start),
            dateTime.format(end)
        )
    }
}

@Composable
private fun loadStatusLabel(status: String): String = when (status) {
    "DRAFT" -> stringResource(R.string.delivery_load_state_draft)
    "ASSIGNED" -> stringResource(R.string.delivery_load_state_assigned)
    "OFFERED" -> stringResource(R.string.delivery_load_state_offered)
    "HANDOFF_CONFIRMED" -> stringResource(R.string.delivery_load_state_confirmed)
    "DRIVER_ACCEPTED" -> stringResource(R.string.delivery_load_state_accepted)
    "RESPONSIBILITY_TRANSFERRED" -> stringResource(R.string.delivery_load_state_transferred)
    else -> status
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

        DeliveryLoadScreenStatus.Current -> null

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
            stringResource(R.string.delivery_load_status_failed, state.failureCode),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else if (text != null) {
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
