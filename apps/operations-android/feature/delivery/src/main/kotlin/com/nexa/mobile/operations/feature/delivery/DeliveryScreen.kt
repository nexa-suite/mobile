package com.nexa.mobile.operations.feature.delivery

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DriverDeliveryScreen(
    state: DriverDeliveryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDelivery: (String) -> Unit,
    onBeginDelivery: () -> Unit,
    onRetryUnknownStart: () -> Unit,
    onOpenDirections: (String) -> Boolean = { false }
) {
    var navigationUnavailable by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.driver_delivery_back)) }
            Text(
                text = stringResource(R.string.driver_delivery_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.driver_delivery_authority_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!state.canRead) {
                Notice(stringResource(R.string.driver_delivery_read_permission), isError = true)
            }
            if (!state.canStart) {
                Notice(stringResource(R.string.driver_delivery_start_permission), isError = true)
            }
            when (state.listStatus) {
                DriverDeliveryLoadStatus.Loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.driver_delivery_loading))
                }

                DriverDeliveryLoadStatus.NotFound -> Notice(
                    stringResource(R.string.driver_delivery_not_found),
                    isError = true
                )

                DriverDeliveryLoadStatus.NetworkUnavailable -> Notice(
                    stringResource(R.string.driver_delivery_network),
                    isError = true
                )

                DriverDeliveryLoadStatus.ServiceUnavailable -> Notice(
                    stringResource(R.string.driver_delivery_service),
                    isError = true
                )

                DriverDeliveryLoadStatus.PermissionDenied -> Notice(
                    stringResource(R.string.driver_delivery_read_permission),
                    isError = true
                )

                DriverDeliveryLoadStatus.ContextInvalidated -> Notice(
                    stringResource(R.string.driver_delivery_context),
                    isError = true
                )

                DriverDeliveryLoadStatus.SessionInvalidated -> Notice(
                    stringResource(R.string.driver_delivery_session),
                    isError = true
                )

                DriverDeliveryLoadStatus.NotRequested,
                DriverDeliveryLoadStatus.Ready -> Unit
            }

            OutlinedButton(onClick = onRefresh, enabled = state.canRead) {
                Text(stringResource(R.string.driver_delivery_refresh))
            }

            if (state.listStatus == DriverDeliveryLoadStatus.Ready && state.deliveries.isEmpty()) {
                Notice(stringResource(R.string.driver_delivery_empty))
            }
            state.deliveries.forEach { delivery ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state.commandStatus !in FROZEN_COMMANDS) {
                            onSelectDelivery(delivery.id)
                        }
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            delivery.destination
                                ?: stringResource(R.string.driver_delivery_destination_unknown)
                        )
                        Text(stringResource(R.string.driver_delivery_status, delivery.status))
                        delivery.activeAttempt?.let { attempt ->
                            Text(
                                stringResource(
                                    R.string.driver_delivery_active_attempt,
                                    attempt.attemptNumber
                                )
                            )
                        }
                    }
                    val destination = state.authorizedDirectionsDestination
                    if (destination == null) {
                        Notice(stringResource(R.string.driver_delivery_directions_missing))
                    } else {
                        OutlinedButton(
                            onClick = { navigationUnavailable = !onOpenDirections(destination) }
                        ) {
                            Text(stringResource(R.string.driver_delivery_directions_open))
                        }
                        if (navigationUnavailable) {
                            Notice(
                                stringResource(R.string.driver_delivery_directions_unavailable),
                                isError = true
                            )
                        }
                    }
                }
            }

            when (state.commandStatus) {
                DriverDeliveryCommandStatus.PersistingIntent -> Notice(
                    stringResource(R.string.driver_delivery_persisting)
                )

                DriverDeliveryCommandStatus.UnknownOutcome -> {
                    Notice(stringResource(R.string.driver_delivery_unknown), isError = true)
                    if (state.hasRecoverableStart) {
                        OutlinedButton(
                            onClick = onRetryUnknownStart,
                            enabled = state.canStart
                        ) {
                            Text(stringResource(R.string.driver_delivery_retry_same))
                        }
                    }
                }

                DriverDeliveryCommandStatus.PersistenceUnavailable -> {
                    Notice(
                        stringResource(R.string.driver_delivery_storage_unavailable),
                        isError = true
                    )
                    if (state.hasRecoverableStart) {
                        OutlinedButton(
                            onClick = onRetryUnknownStart,
                            enabled = state.canStart
                        ) {
                            Text(stringResource(R.string.driver_delivery_retry_same))
                        }
                    }
                }

                else -> Unit
            }

            state.selectedDelivery?.let { delivery ->
                Text(
                    stringResource(R.string.driver_delivery_detail_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    delivery.destination
                        ?: stringResource(R.string.driver_delivery_destination_unknown)
                )
                Text(stringResource(R.string.driver_delivery_status, delivery.status))
                delivery.scheduledAt?.let {
                    Text(stringResource(R.string.driver_delivery_scheduled, it))
                }
                delivery.activeAttempt?.let { attempt ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(stringResource(R.string.driver_delivery_attempt_active))
                            Text(
                                stringResource(
                                    R.string.driver_delivery_attempt_number,
                                    attempt.attemptNumber
                                )
                            )
                            Text(stringResource(R.string.driver_delivery_attempt_id, attempt.id))
                            attempt.startedAt?.let {
                                Text(stringResource(R.string.driver_delivery_attempt_started, it))
                            }
                        }
                    }
                }
                when (state.detailStatus) {
                    DriverDeliveryLoadStatus.Loading -> Notice(
                        stringResource(R.string.driver_delivery_detail_loading)
                    )

                    DriverDeliveryLoadStatus.NetworkUnavailable -> Notice(
                        stringResource(R.string.driver_delivery_network),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.ServiceUnavailable -> Notice(
                        stringResource(R.string.driver_delivery_service),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.NotFound -> Notice(
                        stringResource(R.string.driver_delivery_not_found),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.PermissionDenied -> Notice(
                        stringResource(R.string.driver_delivery_read_permission),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.ContextInvalidated -> Notice(
                        stringResource(R.string.driver_delivery_context),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.SessionInvalidated -> Notice(
                        stringResource(R.string.driver_delivery_session),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.NotRequested,
                    DriverDeliveryLoadStatus.Ready -> Unit
                }
                when (state.commandStatus) {
                    DriverDeliveryCommandStatus.CheckingCurrent -> Notice(
                        stringResource(R.string.driver_delivery_checking)
                    )

                    DriverDeliveryCommandStatus.Pending -> Notice(
                        stringResource(R.string.driver_delivery_start_pending)
                    )

                    DriverDeliveryCommandStatus.PersistingIntent,
                    DriverDeliveryCommandStatus.UnknownOutcome,
                    DriverDeliveryCommandStatus.PersistenceUnavailable -> Unit

                    DriverDeliveryCommandStatus.Started -> Notice(
                        stringResource(R.string.driver_delivery_started)
                    )

                    DriverDeliveryCommandStatus.Rejected -> Notice(
                        state.rejectionCode?.let {
                            stringResource(R.string.driver_delivery_rejected_code, it)
                        }
                            ?: stringResource(R.string.driver_delivery_rejected),
                        isError = true
                    )

                    DriverDeliveryCommandStatus.StaleVersion -> Notice(
                        stringResource(R.string.driver_delivery_stale),
                        isError = true
                    )

                    DriverDeliveryCommandStatus.Idle -> Unit
                }
                if (delivery.activeAttempt == null && state.commandStatus in setOf(
                        DriverDeliveryCommandStatus.Idle,
                        DriverDeliveryCommandStatus.Rejected,
                        DriverDeliveryCommandStatus.StaleVersion
                    )
                ) {
                    Button(
                        onClick = onBeginDelivery,
                        enabled =
                            state.canStart && state.detailStatus == DriverDeliveryLoadStatus.Ready
                    ) {
                        Text(stringResource(R.string.driver_delivery_begin))
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean = false) {
    Text(
        text = text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

private val FROZEN_COMMANDS = setOf(
    DriverDeliveryCommandStatus.CheckingCurrent,
    DriverDeliveryCommandStatus.PersistingIntent,
    DriverDeliveryCommandStatus.Pending,
    DriverDeliveryCommandStatus.UnknownOutcome,
    DriverDeliveryCommandStatus.PersistenceUnavailable
)
