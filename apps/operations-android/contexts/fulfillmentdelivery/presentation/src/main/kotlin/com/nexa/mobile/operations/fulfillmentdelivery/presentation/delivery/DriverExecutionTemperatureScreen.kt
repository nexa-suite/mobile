package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureDisposition as ExecutionTemperatureDisposition
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureMode
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R.string.execution_temperature_disposition_reason as DispositionReasonLabel
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R.string.execution_temperature_report_excursion as ReportExcursionLabel
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverExecutionTemperatureCommandStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverExecutionTemperatureEvidenceStatus as TemperatureEvidenceStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverExecutionTemperatureLoadStatus as LoadStatus

@Composable
fun DriverExecutionTemperatureScreen(
    state: DriverExecutionTemperatureUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRecord: (String, String, String) -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onDisposition: (String, ExecutionTemperatureDisposition, String) -> Unit,
    onReportExcursion: (String) -> Unit,
    onQuantityChanged: (String, String) -> Unit = { _, _ -> },
    onCelsiusChanged: (String, String) -> Unit = { _, _ -> },
    onDispositionReasonChanged: (String, String) -> Unit = { _, _ -> }
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.execution_temperature_back))
            }
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.execution_temperature_refresh))
            }
        }
        Text(
            if (state.mode == DriverExecutionTemperatureMode.DRIVER) {
                stringResource(R.string.execution_temperature_title)
            } else {
                stringResource(R.string.execution_temperature_disposition_title)
            }
        )
        state.deliveryId?.let { Text(stringResource(R.string.execution_temperature_delivery, it)) }
        when (state.loadStatus) {
            LoadStatus.Loading ->
                Text(stringResource(R.string.execution_temperature_loading))

            LoadStatus.NotRequested ->
                Text(stringResource(R.string.execution_temperature_loading))

            LoadStatus.NotFound ->
                Text(stringResource(R.string.execution_temperature_not_found))

            LoadStatus.NetworkUnavailable ->
                Text(stringResource(R.string.execution_temperature_network))

            LoadStatus.ServiceUnavailable ->
                Text(stringResource(R.string.execution_temperature_service))

            LoadStatus.PermissionDenied ->
                Text(stringResource(R.string.execution_temperature_permission))

            LoadStatus.ContextInvalidated -> Text(
                stringResource(R.string.execution_temperature_context)
            )

            LoadStatus.SessionInvalidated -> Text(
                stringResource(R.string.execution_temperature_session)
            )

            LoadStatus.Ready -> Unit
        }
        state.snapshot?.let { snapshot ->
            Text(
                stringResource(
                    R.string.execution_temperature_version,
                    snapshot.deliveryVersion,
                    snapshot.deliveryStatus
                )
            )
            Spacer(Modifier.height(8.dp))
            if (state.mode == DriverExecutionTemperatureMode.DRIVER) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(snapshot.lines, key = { it.fulfillmentLineId }) { line ->
                        val quantity = state.quantitiesByLine[line.fulfillmentLineId].orEmpty()
                        val celsius = state.valuesCelsiusByLine[line.fulfillmentLineId].orEmpty()
                        val value = celsius.trim().toBigDecimalOrNull()
                        val excursion = value != null && !line.isWithinRange(value)
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    stringResource(R.string.execution_temperature_line, line.skuId)
                                )
                                Text(
                                    stringResource(
                                        R.string.execution_temperature_remaining,
                                        line.remainingQuantity.stripTrailingZeros().toPlainString(),
                                        line.unit
                                    )
                                )
                                val minimumCelsius = line.minimumCelsius
                                val maximumCelsius = line.maximumCelsius
                                if (minimumCelsius != null && maximumCelsius != null) {
                                    Text(
                                        stringResource(
                                            R.string.execution_temperature_range,
                                            minimumCelsius.stripTrailingZeros().toPlainString(),
                                            maximumCelsius.stripTrailingZeros().toPlainString()
                                        )
                                    )
                                }
                                if (!line.supportsReading) {
                                    Text(
                                        stringResource(R.string.execution_temperature_not_required)
                                    )
                                } else {
                                    OutlinedTextField(
                                        value = quantity,
                                        onValueChange = {
                                            onQuantityChanged(line.fulfillmentLineId, it)
                                        },
                                        label = {
                                            Text(
                                                stringResource(
                                                    R.string.execution_temperature_quantity
                                                )
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = state.canRecord && !state.hasRecoverableCommand,
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = celsius,
                                        onValueChange = {
                                            onCelsiusChanged(line.fulfillmentLineId, it)
                                        },
                                        label = {
                                            Text(
                                                stringResource(
                                                    R.string.execution_temperature_celsius
                                                )
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = state.canRecord && !state.hasRecoverableCommand,
                                        singleLine = true
                                    )
                                    if (excursion) {
                                        Text(
                                            stringResource(
                                                R.string.execution_temperature_excursion_notice
                                            )
                                        )
                                        if (state.sourceEvidenceStatus !=
                                            TemperatureEvidenceStatus.Available
                                        ) {
                                            Button(
                                                onClick = {
                                                    state.deliveryId?.let(onReportExcursion)
                                                },
                                                enabled =
                                                    state.canRecord && snapshot.attemptId != null &&
                                                        !state.hasRecoverableCommand
                                            ) {
                                                Text(
                                                    stringResource(
                                                        ReportExcursionLabel
                                                    )
                                                )
                                            }
                                        } else {
                                            Text(
                                                stringResource(
                                                    R.string.execution_temperature_source_linked,
                                                    state.sourceIncidentId.orEmpty(),
                                                    state.sourceEvidenceObjectId.orEmpty()
                                                )
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            onRecord(line.fulfillmentLineId, quantity, celsius)
                                        },
                                        enabled =
                                            state.canRecord && quantity.isNotBlank() &&
                                                value != null &&
                                                (
                                                    !excursion ||
                                                        state.sourceEvidenceStatus ==
                                                        TemperatureEvidenceStatus.Available
                                                    ) &&
                                                !state.hasRecoverableCommand
                                    ) {
                                        Text(stringResource(R.string.execution_temperature_record))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(snapshot.holds, key = { it.id }) { hold ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    stringResource(
                                        R.string.execution_temperature_hold,
                                        hold.id,
                                        hold.status
                                    )
                                )
                                Text(
                                    stringResource(
                                        R.string.execution_temperature_hold_quantity,
                                        hold.affectedQuantity.stripTrailingZeros().toPlainString(),
                                        hold.quantityUnit
                                    )
                                )
                                if (hold.status == "HELD") {
                                    OutlinedTextField(
                                        value = state.dispositionReasonsByHold[hold.id].orEmpty(),
                                        onValueChange = { onDispositionReasonChanged(hold.id, it) },
                                        label = {
                                            Text(
                                                stringResource(
                                                    DispositionReasonLabel
                                                )
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    val reason = state.dispositionReasonsByHold[hold.id].orEmpty()
                                    ExecutionTemperatureDisposition.entries.forEach { disposition ->
                                        Button(
                                            onClick = {
                                                onDisposition(
                                                    hold.id,
                                                    disposition,
                                                    reason
                                                )
                                            },
                                            enabled =
                                                state.canDispose && !state.hasRecoverableCommand &&
                                                    reason.isNotBlank()
                                        ) { Text(disposition.displayText()) }
                                    }
                                }
                            }
                        }
                    }
                    if (snapshot.holds.isEmpty()) {
                        item { Text(stringResource(R.string.execution_temperature_no_holds)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        CommandStatus(state = state, onRetryUnknownOutcome = onRetryUnknownOutcome)
        state.lastReading?.let { reading ->
            Text(
                stringResource(
                    R.string.execution_temperature_last_reading,
                    reading.valueCelsius.stripTrailingZeros().toPlainString(),
                    reading.affectedQuantity.stripTrailingZeros().toPlainString(),
                    reading.quantityUnit,
                    reading.status,
                    reading.deliveryVersion
                )
            )
        }
        if (state.rejectionCode != null) {
            Text(stringResource(R.string.execution_temperature_rejected, state.rejectionCode))
        }
        Text(stringResource(R.string.execution_temperature_authority_notice))
    }
}

@Composable
private fun CommandStatus(
    state: DriverExecutionTemperatureUiState,
    onRetryUnknownOutcome: () -> Unit
) {
    val text = when (state.commandStatus) {
        DriverExecutionTemperatureCommandStatus.Idle -> null

        DriverExecutionTemperatureCommandStatus.PersistingIntent ->
            stringResource(R.string.execution_temperature_persisting)

        DriverExecutionTemperatureCommandStatus.Pending ->
            stringResource(R.string.execution_temperature_pending)

        DriverExecutionTemperatureCommandStatus.UnknownOutcome ->
            stringResource(R.string.execution_temperature_unknown)

        DriverExecutionTemperatureCommandStatus.Recorded ->
            stringResource(R.string.execution_temperature_recorded)

        DriverExecutionTemperatureCommandStatus.Disposed ->
            stringResource(R.string.execution_temperature_disposed)

        DriverExecutionTemperatureCommandStatus.StaleVersion ->
            stringResource(R.string.execution_temperature_stale)

        DriverExecutionTemperatureCommandStatus.Rejected -> stringResource(
            R.string.execution_temperature_rejected,
            state.rejectionCode.orEmpty()
        )

        DriverExecutionTemperatureCommandStatus.PersistenceUnavailable ->
            stringResource(R.string.execution_temperature_storage_unavailable)
    }
    if (text != null) Text(text)
    if (state.canRetryUnknownOutcome) {
        Button(onClick = onRetryUnknownOutcome) {
            Text(stringResource(R.string.execution_temperature_retry_same))
        }
    }
}

@Composable
private fun ExecutionTemperatureDisposition.displayText(): String = when (this) {
    ExecutionTemperatureDisposition.RELEASE -> stringResource(
        R.string.execution_temperature_release
    )

    ExecutionTemperatureDisposition.CONTINUE_HOLD -> stringResource(
        R.string.execution_temperature_continue_hold
    )

    ExecutionTemperatureDisposition.REJECT -> stringResource(
        R.string.execution_temperature_reject
    )

    ExecutionTemperatureDisposition.WASTE -> stringResource(
        R.string.execution_temperature_waste
    )
}
