package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.feature.warehouse.model.DispositionLotFacts
import com.nexa.mobile.operations.feature.warehouse.model.LotDispositionAction
import com.nexa.mobile.operations.feature.warehouse.model.PartialDispositionEvaluation

@Composable
fun DispositionScreen(
    state: DispositionUiState,
    onBack: () -> Unit,
    onLotIdChanged: (String) -> Unit,
    onLoadLot: () -> Unit,
    onDispositionSelected: (LotDispositionAction) -> Unit,
    onReasonChanged: (String) -> Unit,
    onSaveLocalNote: () -> Unit,
    onSubmit: () -> Unit,
    onReplayUnknownOutcome: () -> Unit,
    onStartNewDecision: () -> Unit,
    onRouteClosed: () -> Unit
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { closeAction.value() } }

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
                TextButton(onClick = onBack) { Text(stringResource(R.string.disposition_back)) }
                Text(
                    stringResource(R.string.disposition_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.disposition_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = state.lotIdText,
                    onValueChange = onLotIdChanged,
                    enabled = !state.isIntentFrozen,
                    label = { Text(stringResource(R.string.disposition_lot_id)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedButton(
                    onClick = onLoadLot,
                    enabled = state.canReadLots && state.lotStatus != DispositionLotStatus.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (state.lotFacts == null) {
                                R.string.disposition_load_lot
                            } else {
                                R.string.disposition_refresh_lot
                            }
                        )
                    )
                }
            }
            if (state.validationError != null) {
                item {
                    Text(
                        stringResource(state.validationError.toLabel()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (state.lotStatus != DispositionLotStatus.Idle) {
                item { LotStatusPanel(state.lotStatus) }
            }
            state.lotFacts?.let { facts ->
                item { LotFactsPanel(facts) }
            }
            state.partialEvaluation?.let { evaluation ->
                item { PartialEvaluationPanel(evaluation) }
            }
            item {
                Text(
                    stringResource(R.string.disposition_action_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                LotDispositionAction.entries.forEach { action ->
                    val accessibilityLabel = stringResource(action.toLabel())
                    val permittedHint = state.permissions.any {
                        it == when (action) {
                            LotDispositionAction.RELEASE -> "inventory.release"

                            LotDispositionAction.HOLD,
                            LotDispositionAction.WASTE,
                            LotDispositionAction.RETURN_TO_SUPPLIER -> "inventory.waste"
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = accessibilityLabel
                            },
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RadioButton(
                            selected = state.disposition == action,
                            onClick = { onDispositionSelected(action) },
                            enabled = !state.isIntentFrozen && permittedHint
                        )
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Text(stringResource(action.toLabel()))
                            if (!permittedHint) {
                                Text(
                                    stringResource(R.string.disposition_permission_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.reasonText,
                    onValueChange = onReasonChanged,
                    enabled = !state.isIntentFrozen,
                    label = { Text(stringResource(R.string.disposition_reason)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.disposition_reason_limit,
                                state.reasonText.length
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            }
            item {
                OutlinedButton(
                    onClick = onSaveLocalNote,
                    enabled =
                        state.metadata == DispositionMetadataStatus.Available &&
                            !state.isIntentFrozen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.disposition_save_note))
                }
                Button(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.disposition_submit))
                }
            }
            if (state.commandStatus != DispositionCommandStatus.Editing) {
                item { CommandStatusPanel(state) }
            }
            if (state.canReplay) {
                item {
                    Button(onClick = onReplayUnknownOutcome, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.disposition_replay))
                    }
                }
            }
            if (state.commandStatus in setOf(
                    DispositionCommandStatus.PreconditionFailed,
                    DispositionCommandStatus.Conflict,
                    DispositionCommandStatus.Rejected
                )
            ) {
                item {
                    Text(
                        stringResource(R.string.disposition_review_before_new),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onStartNewDecision,
                        enabled = state.canStartNewDecision,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.disposition_start_new_decision))
                    }
                }
            }
        }
    }
}

@Composable
private fun LotStatusPanel(status: DispositionLotStatus) {
    val message = when (status) {
        DispositionLotStatus.Idle -> R.string.disposition_status_idle
        DispositionLotStatus.Loading -> R.string.disposition_status_loading
        DispositionLotStatus.Current -> R.string.disposition_status_current
        DispositionLotStatus.InvalidIdentifier -> R.string.disposition_status_invalid_id
        DispositionLotStatus.NetworkUnavailable -> R.string.disposition_status_network
        DispositionLotStatus.ServiceUnavailable -> R.string.disposition_status_service
        DispositionLotStatus.PermissionDenied -> R.string.disposition_status_permission
        DispositionLotStatus.ContextInvalidated -> R.string.disposition_status_context
        DispositionLotStatus.SessionInvalidated -> R.string.disposition_status_session
    }
    StatusCard { Text(stringResource(message)) }
}

@Composable
private fun LotFactsPanel(facts: DispositionLotFacts) {
    StatusCard {
        Text(
            stringResource(R.string.disposition_server_facts),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        FactLine(stringResource(R.string.disposition_batch), facts.batchNumber)
        FactLine(stringResource(R.string.disposition_status), facts.status)
        FactLine(stringResource(R.string.disposition_expiry), facts.expirationDate.toString())
        FactLine(stringResource(R.string.disposition_warehouse), facts.warehouseId)
        FactLine(stringResource(R.string.disposition_zone), facts.zoneId)
        QuantityFact(
            stringResource(R.string.disposition_on_hand),
            facts.onHand.toPlainString(),
            facts.unit
        )
        QuantityFact(
            stringResource(R.string.disposition_reserved),
            facts.reserved.toPlainString(),
            facts.unit
        )
        QuantityFact(
            stringResource(R.string.disposition_available),
            facts.available.toPlainString(),
            facts.unit
        )
        FactLine(stringResource(R.string.disposition_version), facts.version.toString())
        FactLine(stringResource(R.string.disposition_received_at), facts.receivedAt.toString())
    }
}

@Composable
private fun PartialEvaluationPanel(evaluation: PartialDispositionEvaluation) {
    StatusCard {
        Text(
            stringResource(R.string.disposition_partial_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.disposition_partial_description),
            style = MaterialTheme.typography.bodyMedium
        )
        FactLine(
            stringResource(R.string.disposition_partial_affected_quantity),
            evaluation.affectedQuantity.toPlainString()
        )
        FactLine(
            stringResource(R.string.disposition_partial_evaluation_id),
            evaluation.temperatureEvaluationId
        )
    }
}

@Composable
private fun CommandStatusPanel(state: DispositionUiState) {
    val message = when (state.commandStatus) {
        DispositionCommandStatus.Editing -> R.string.disposition_command_editing
        DispositionCommandStatus.SavingNote -> R.string.disposition_command_saving_note
        DispositionCommandStatus.NoteSavedUnconfirmed -> R.string.disposition_command_note_saved
        DispositionCommandStatus.PersistingIntent -> R.string.disposition_command_persisting
        DispositionCommandStatus.Pending -> R.string.disposition_command_pending
        DispositionCommandStatus.UnknownOutcome -> R.string.disposition_command_unknown
        DispositionCommandStatus.PreconditionFailed -> R.string.disposition_command_stale
        DispositionCommandStatus.Conflict -> R.string.disposition_command_conflict
        DispositionCommandStatus.Rejected -> R.string.disposition_command_rejected
        DispositionCommandStatus.Confirmed -> R.string.disposition_command_confirmed
    }
    StatusCard {
        Text(stringResource(message))
        state.rejectionCode?.let {
            FactLine(stringResource(R.string.disposition_rejection_code), it)
        }
        if (state.noteSaved ||
            state.commandStatus == DispositionCommandStatus.NoteSavedUnconfirmed
        ) {
            Text(stringResource(R.string.disposition_command_no_stock_change))
        }
        if (state.intentCleanupPending) Text(stringResource(R.string.disposition_intent_cleanup))
    }
}

@Composable
private fun StatusCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun QuantityFact(label: String, value: String, unit: String) {
    Text("$label: $value $unit", style = MaterialTheme.typography.bodyMedium)
}

private fun LotDispositionAction.toLabel(): Int = when (this) {
    LotDispositionAction.RELEASE -> R.string.disposition_action_release
    LotDispositionAction.HOLD -> R.string.disposition_action_hold
    LotDispositionAction.WASTE -> R.string.disposition_action_waste
    LotDispositionAction.RETURN_TO_SUPPLIER -> R.string.disposition_action_return
}

private fun DispositionValidationError.toLabel(): Int = when (this) {
    DispositionValidationError.LotIdRequired -> R.string.disposition_error_lot_required

    DispositionValidationError.LotIdInvalid -> R.string.disposition_error_lot_invalid

    DispositionValidationError.LotMustBeReloaded -> R.string.disposition_error_lot_reload

    DispositionValidationError.PartialEvaluationInvalid ->
        R.string.disposition_error_partial_evaluation

    DispositionValidationError.ActionRequired -> R.string.disposition_error_action

    DispositionValidationError.ReasonRequired -> R.string.disposition_error_reason

    DispositionValidationError.ReasonTooLong -> R.string.disposition_error_reason_long

    DispositionValidationError.PermissionRequired -> R.string.disposition_error_permission

    DispositionValidationError.MetadataUnavailable -> R.string.disposition_error_metadata

    DispositionValidationError.IntentMustBeReviewed -> R.string.disposition_error_review
}
