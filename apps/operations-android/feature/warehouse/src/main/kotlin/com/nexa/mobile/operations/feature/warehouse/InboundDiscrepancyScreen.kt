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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyKind
import java.text.DateFormat
import java.util.Date

@Composable
fun InboundDiscrepancyScreen(
    state: InboundDiscrepancyUiState,
    onBack: () -> Unit,
    onWarehouseChanged: (String) -> Unit,
    onExpectedSkuChanged: (String) -> Unit,
    onObservedSkuChanged: (String) -> Unit,
    onExpectedBatchChanged: (String) -> Unit,
    onObservedBatchChanged: (String) -> Unit,
    onKindChanged: (InboundDiscrepancyKind) -> Unit,
    onReasonDetailsChanged: (String) -> Unit,
    onExpectedQuantityChanged: (String) -> Unit,
    onObservedQuantityChanged: (String) -> Unit,
    onUnitChanged: (String) -> Unit,
    onObservationNotesChanged: (String) -> Unit,
    onSaveDraft: () -> Unit,
    onCreateCase: () -> Unit,
    onSelectEvidence: () -> Unit,
    onUploadEvidence: () -> Unit,
    onRefreshEvidence: () -> Unit,
    onSubmitForReview: () -> Unit,
    onRetryPendingAction: () -> Unit,
    onRequestDiscard: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onCancelDiscard: () -> Unit
) {
    val editable = state.canSave && state.caseId == null
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.discrepancy_back)) }
            Text(
                stringResource(R.string.discrepancy_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.discrepancy_limit),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                storageMessage(state.metadata),
                color = if (state.metadata ==
                    InboundDiscrepancyMetadataStatus.Unavailable
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            state.validationError?.let {
                Text(validationMessage(it), color = MaterialTheme.colorScheme.error)
            }
            state.notice?.let {
                Text(noticeMessage(it), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.rejectionCode?.let {
                Text(
                    stringResource(R.string.discrepancy_rejected, it),
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.caseStatus?.let {
                Text(
                    stringResource(R.string.discrepancy_case_status, it),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            state.caseId?.let {
                Text(
                    stringResource(R.string.discrepancy_case_id, it),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedTextField(
                value = state.warehouseId,
                onValueChange = onWarehouseChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.discrepancy_warehouse_id))
                },
                singleLine = true
            )
            OutlinedTextField(
                value = state.expectedSkuId,
                onValueChange = onExpectedSkuChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.discrepancy_expected_sku_id)) },
                supportingText = {
                    Text(stringResource(R.string.discrepancy_expected_sku_note))
                },
                singleLine = true
            )
            OutlinedTextField(
                value = state.observedSkuId,
                onValueChange = onObservedSkuChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.discrepancy_observed_sku_id)) },
                supportingText = {
                    Text(
                        state.observedSkuLabel?.let {
                            stringResource(R.string.discrepancy_confirmed_product, it)
                        }
                            ?: stringResource(R.string.discrepancy_observed_sku_note)
                    )
                },
                singleLine = true
            )
            OutlinedTextField(
                value = state.expectedBatchReference,
                onValueChange = onExpectedBatchChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.discrepancy_expected_batch))
                },
                singleLine = true
            )
            OutlinedTextField(
                value = state.observedBatchReference,
                onValueChange = onObservedBatchChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.discrepancy_observed_batch))
                },
                singleLine = true
            )

            Text(
                stringResource(R.string.discrepancy_reason_title),
                style = MaterialTheme.typography.titleMedium
            )
            InboundDiscrepancyKind.entries.forEach { kind ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.kind == kind, onClick = {
                        onKindChanged(kind)
                    }, enabled = editable)
                    Text(kindLabel(kind))
                }
            }
            OutlinedTextField(
                value = state.reasonDetails,
                onValueChange = onReasonDetailsChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.discrepancy_reason_details_label))
                },
                minLines = 2,
                maxLines = 4
            )
            OutlinedTextField(
                value = state.expectedQuantityText,
                onValueChange = onExpectedQuantityChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.discrepancy_expected_quantity_label)) },
                supportingText = {
                    Text(stringResource(R.string.discrepancy_expected_quantity_note))
                },
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
            OutlinedTextField(
                value = state.unit,
                onValueChange = onUnitChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.discrepancy_unit_label))
                },
                singleLine = true
            )
            OutlinedTextField(
                value = state.observationNotes,
                onValueChange = onObservationNotesChanged,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.discrepancy_notes_label))
                },
                minLines = 2,
                maxLines = 6
            )

            if (state.hasSavedDraft && state.caseId == null && state.pendingAction == null) {
                OutlinedButton(
                    onClick = onSaveDraft,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.discrepancy_update_local))
                }
                TextButton(onClick = onRequestDiscard, enabled = editable) {
                    Text(stringResource(R.string.discrepancy_discard_local))
                }
            } else if (state.caseId == null) {
                OutlinedButton(
                    onClick = onSaveDraft,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.discrepancy_save_local))
                }
                Button(
                    onClick = onCreateCase,
                    enabled = state.canCreateCase,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.discrepancy_create_case))
                }
            }

            state.artifact?.let { artifact ->
                Text(
                    pluralStringResource(
                        R.plurals.discrepancy_photo_staged,
                        artifact.byteSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        artifact.filename,
                        artifact.contentType,
                        artifact.byteSize
                    )
                )
            }
            if (state.canSelectEvidence) {
                OutlinedButton(onClick = onSelectEvidence, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discrepancy_select_photo))
                }
            }
            if (state.canUploadEvidence) {
                Button(onClick = onUploadEvidence, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discrepancy_upload_photo))
                }
            }
            if (state.canRefreshEvidence) {
                OutlinedButton(onClick = onRefreshEvidence, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discrepancy_refresh_evidence))
                }
            }
            if (state.canSubmitForReview) {
                Button(onClick = onSubmitForReview, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discrepancy_submit_review))
                }
            }
            if (state.flow == InboundDiscrepancyFlowStatus.UnknownOutcome) {
                OutlinedButton(onClick = onRetryPendingAction, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discrepancy_retry_same_intent))
                }
            }
            if (state.isSaving) Text(stringResource(R.string.discrepancy_working))
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
                TextButton(onClick = onConfirmDiscard) {
                    Text(stringResource(R.string.discrepancy_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDiscard) {
                    Text(stringResource(R.string.discrepancy_cancel))
                }
            }
        )
    }
}

@Composable
private fun storageMessage(status: InboundDiscrepancyMetadataStatus): String = when (status) {
    InboundDiscrepancyMetadataStatus.Loading -> stringResource(R.string.discrepancy_storage_loading)

    InboundDiscrepancyMetadataStatus.Available -> stringResource(R.string.discrepancy_storage_ready)

    InboundDiscrepancyMetadataStatus.Unavailable -> stringResource(
        R.string.discrepancy_storage_unavailable
    )
}

@Composable
private fun kindLabel(kind: InboundDiscrepancyKind): String = when (kind) {
    InboundDiscrepancyKind.Damage -> stringResource(R.string.discrepancy_reason_damage)

    InboundDiscrepancyKind.Leakage -> stringResource(R.string.discrepancy_reason_leakage)

    InboundDiscrepancyKind.WrongProduct -> stringResource(R.string.discrepancy_reason_wrong_product)

    InboundDiscrepancyKind.QuantityDifference -> stringResource(
        R.string.discrepancy_reason_quantity
    )

    InboundDiscrepancyKind.Other -> stringResource(R.string.discrepancy_reason_other)
}

@Composable
private fun validationMessage(error: InboundDiscrepancyValidationError): String = when (error) {
    InboundDiscrepancyValidationError.WarehouseRequired -> stringResource(
        R.string.discrepancy_warehouse_required
    )

    InboundDiscrepancyValidationError.WarehouseInvalid -> stringResource(
        R.string.discrepancy_uuid_invalid
    )

    InboundDiscrepancyValidationError.ObservedSkuRequired -> stringResource(
        R.string.discrepancy_observed_sku_required
    )

    InboundDiscrepancyValidationError.ObservedSkuInvalid,
    InboundDiscrepancyValidationError.ExpectedSkuInvalid -> stringResource(
        R.string.discrepancy_uuid_invalid
    )

    InboundDiscrepancyValidationError.BatchReferenceInvalid -> stringResource(
        R.string.discrepancy_batch_invalid
    )

    InboundDiscrepancyValidationError.ExpectedQuantityRequired -> stringResource(
        R.string.discrepancy_expected_required
    )

    InboundDiscrepancyValidationError.ObservedQuantityRequired -> stringResource(
        R.string.discrepancy_observed_required
    )

    InboundDiscrepancyValidationError.QuantityInvalid -> stringResource(
        R.string.discrepancy_quantity_invalid
    )

    InboundDiscrepancyValidationError.QuantityDifferenceRequired -> stringResource(
        R.string.discrepancy_quantity_must_differ
    )

    InboundDiscrepancyValidationError.UnitRequired -> stringResource(
        R.string.discrepancy_unit_required
    )

    InboundDiscrepancyValidationError.ReasonRequired -> stringResource(
        R.string.discrepancy_reason_required
    )

    InboundDiscrepancyValidationError.EvidenceRequired -> stringResource(
        R.string.discrepancy_evidence_required
    )
}

@Composable
private fun noticeMessage(notice: InboundDiscrepancySaveNotice): String = when (notice) {
    InboundDiscrepancySaveNotice.SavedLocally -> stringResource(R.string.discrepancy_saved)

    InboundDiscrepancySaveNotice.StoreUnavailable -> stringResource(
        R.string.discrepancy_store_error
    )

    InboundDiscrepancySaveNotice.ExistingDraftConflict -> stringResource(
        R.string.discrepancy_existing_conflict
    )

    InboundDiscrepancySaveNotice.Discarded -> stringResource(R.string.discrepancy_discarded)

    InboundDiscrepancySaveNotice.CaseRecorded -> stringResource(R.string.discrepancy_case_recorded)

    InboundDiscrepancySaveNotice.EvidenceStaged -> stringResource(
        R.string.discrepancy_evidence_staged
    )

    InboundDiscrepancySaveNotice.EvidenceUploaded -> stringResource(
        R.string.discrepancy_evidence_uploaded
    )

    InboundDiscrepancySaveNotice.EvidenceAwaitingScan -> stringResource(
        R.string.discrepancy_evidence_scan
    )

    InboundDiscrepancySaveNotice.ReviewRequested -> stringResource(
        R.string.discrepancy_review_requested
    )

    InboundDiscrepancySaveNotice.UnknownOutcome -> stringResource(
        R.string.discrepancy_unknown_outcome
    )

    InboundDiscrepancySaveNotice.Stale -> stringResource(R.string.discrepancy_stale)

    InboundDiscrepancySaveNotice.PermissionDenied -> stringResource(R.string.discrepancy_denied)

    InboundDiscrepancySaveNotice.ContextInvalidated -> stringResource(
        R.string.discrepancy_context_invalid
    )

    InboundDiscrepancySaveNotice.SessionInvalidated -> stringResource(
        R.string.discrepancy_session_invalid
    )

    InboundDiscrepancySaveNotice.NetworkUnavailable -> stringResource(
        R.string.discrepancy_network_unavailable
    )

    InboundDiscrepancySaveNotice.ServiceUnavailable -> stringResource(
        R.string.discrepancy_service_unavailable
    )

    InboundDiscrepancySaveNotice.EvidenceRejected -> stringResource(
        R.string.discrepancy_evidence_rejected
    )
}
