package com.nexa.mobile.operations.feature.warehouse

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun InboundDiscrepancyScreen(
    state: InboundDiscrepancyUiState,
    onBack: () -> Unit,
    onProductReferenceChanged: (String) -> Unit,
    onLotOrBatchReferenceChanged: (String) -> Unit,
    onKindChanged: (InboundDiscrepancyKind) -> Unit,
    onReasonDetailsChanged: (String) -> Unit,
    onExpectedQuantityChanged: (String) -> Unit,
    onObservedQuantityChanged: (String) -> Unit,
    onEvidencePlanChanged: (String) -> Unit,
    onObservationNotesChanged: (String) -> Unit,
    onSaveDraft: () -> Unit,
    onRequestDiscard: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onCancelDiscard: () -> Unit
) {
    val editable = state.active && state.metadata == InboundDiscrepancyMetadataStatus.Available && !state.isSaving
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.discrepancy_back)) }
            Text(
                text = stringResource(R.string.discrepancy_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.discrepancy_limit),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = when (state.metadata) {
                    InboundDiscrepancyMetadataStatus.Loading -> stringResource(R.string.discrepancy_storage_loading)
                    InboundDiscrepancyMetadataStatus.Available -> stringResource(R.string.discrepancy_storage_ready)
                    InboundDiscrepancyMetadataStatus.Unavailable -> stringResource(R.string.discrepancy_storage_unavailable)
                },
                color = if (state.metadata == InboundDiscrepancyMetadataStatus.Unavailable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            state.validationError?.let { error ->
                Text(validationMessage(error), color = MaterialTheme.colorScheme.error)
            }
            state.notice?.let { notice ->
                Text(noticeMessage(notice), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.hasSavedDraft && !state.hasUnsavedChanges) {
                Text(stringResource(R.string.discrepancy_saved), color = MaterialTheme.colorScheme.primary)
            } else if (state.hasUnsavedChanges) {
                Text(stringResource(R.string.discrepancy_unsaved_changes), color = MaterialTheme.colorScheme.tertiary)
            }

            OutlinedTextField(
                value = state.productReference,
                onValueChange = onProductReferenceChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.discrepancy_product_label)) },
                supportingText = { Text(stringResource(R.string.discrepancy_unverified_reference)) },
                singleLine = true
            )
            OutlinedTextField(
                value = state.lotOrBatchReference,
                onValueChange = onLotOrBatchReferenceChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.discrepancy_lot_label)) },
                supportingText = { Text(stringResource(R.string.discrepancy_unverified_reference)) },
                singleLine = true
            )

            Text(stringResource(R.string.discrepancy_reason_title), style = MaterialTheme.typography.titleMedium)
            InboundDiscrepancyKind.entries.forEach { kind ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.kind == kind,
                        onClick = { onKindChanged(kind) },
                        enabled = editable
                    )
                    kindLabel(kind)
                }
            }
            OutlinedTextField(
                value = state.reasonDetails,
                onValueChange = onReasonDetailsChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.discrepancy_reason_details_label)) },
                minLines = 2,
                maxLines = 4
            )

            if (state.kind == InboundDiscrepancyKind.QuantityDifference) {
                OutlinedTextField(
                    value = state.expectedQuantityText,
                    onValueChange = onExpectedQuantityChanged,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discrepancy_expected_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.observedQuantityText,
                    onValueChange = onObservedQuantityChanged,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discrepancy_observed_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = state.evidencePlan,
                onValueChange = onEvidencePlanChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.discrepancy_evidence_plan_label)) },
                supportingText = { Text(stringResource(R.string.discrepancy_evidence_plan_note)) },
                minLines = 2,
                maxLines = 4
            )
            OutlinedTextField(
                value = state.observationNotes,
                onValueChange = onObservationNotesChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.discrepancy_notes_label)) },
                minLines = 2,
                maxLines = 6
            )

            Button(onClick = onSaveDraft, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (state.hasSavedDraft) R.string.discrepancy_update_local else R.string.discrepancy_save_local
                    )
                )
            }
            if (state.hasSavedDraft) {
                TextButton(onClick = onRequestDiscard, enabled = editable) {
                    Text(stringResource(R.string.discrepancy_discard_local))
                }
            }
            if (state.isSaving) Text(stringResource(R.string.discrepancy_saving))
            state.capturedAtDeviceMillis?.let { capturedAt ->
                Text(
                    stringResource(
                        R.string.discrepancy_device_time,
                        DateFormat.getDateTimeInstance().format(Date(capturedAt))
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (state.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelDiscard,
            title = { Text(stringResource(R.string.discrepancy_discard_title)) },
            text = { Text(stringResource(R.string.discrepancy_discard_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDiscard) { Text(stringResource(R.string.discrepancy_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelDiscard) { Text(stringResource(R.string.discrepancy_cancel)) }
            }
        )
    }
}

@Composable
private fun kindLabel(kind: InboundDiscrepancyKind) {
    Text(
        when (kind) {
        InboundDiscrepancyKind.Damage -> stringResource(R.string.discrepancy_reason_damage)
        InboundDiscrepancyKind.Leakage -> stringResource(R.string.discrepancy_reason_leakage)
        InboundDiscrepancyKind.WrongProduct -> stringResource(R.string.discrepancy_reason_wrong_product)
        InboundDiscrepancyKind.QuantityDifference -> stringResource(R.string.discrepancy_reason_quantity)
        InboundDiscrepancyKind.Other -> stringResource(R.string.discrepancy_reason_other)
        }
    )
}

@Composable
private fun validationMessage(error: InboundDiscrepancyValidationError): String = when (error) {
    InboundDiscrepancyValidationError.ProductReferenceRequired -> stringResource(R.string.discrepancy_product_required)
    InboundDiscrepancyValidationError.LotReferenceRequired -> stringResource(R.string.discrepancy_lot_required)
    InboundDiscrepancyValidationError.ReasonRequired -> stringResource(R.string.discrepancy_reason_required)
    InboundDiscrepancyValidationError.ExpectedQuantityRequired -> stringResource(R.string.discrepancy_expected_required)
    InboundDiscrepancyValidationError.ObservedQuantityRequired -> stringResource(R.string.discrepancy_observed_required)
    InboundDiscrepancyValidationError.QuantityInvalid -> stringResource(R.string.discrepancy_quantity_invalid)
    InboundDiscrepancyValidationError.QuantityDifferenceRequired -> stringResource(R.string.discrepancy_quantity_must_differ)
}

@Composable
private fun noticeMessage(notice: InboundDiscrepancySaveNotice): String = when (notice) {
    InboundDiscrepancySaveNotice.SavedLocally -> stringResource(R.string.discrepancy_saved)
    InboundDiscrepancySaveNotice.StoreUnavailable -> stringResource(R.string.discrepancy_store_error)
    InboundDiscrepancySaveNotice.ExistingDraftConflict -> stringResource(R.string.discrepancy_existing_conflict)
    InboundDiscrepancySaveNotice.Discarded -> stringResource(R.string.discrepancy_discarded)
}
