package com.nexa.mobile.operations.feature.warehouse

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidenceFacts
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidenceSubject
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidenceSubjectType
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidenceUnit
import java.math.BigDecimal

@Composable
fun TemperatureEvidenceScreen(
    state: TemperatureEvidenceUiState,
    onBack: () -> Unit,
    onSubjectTypeChanged: (TemperatureEvidenceSubjectType) -> Unit,
    onSelectSubject: (TemperatureEvidenceSubject) -> Unit,
    onValueChanged: (String) -> Unit,
    onUnitChanged: (TemperatureEvidenceUnit) -> Unit,
    onOccurredAtChanged: (String) -> Unit,
    onAffectedQuantityChanged: (String) -> Unit,
    onReasonChanged: (String) -> Unit,
    onSourceEvidenceIdChanged: (String) -> Unit,
    onLoadSourceEvidence: () -> Unit,
    onChoosePhoto: () -> Unit,
    onRefreshPhotoStatus: () -> Unit,
    onReloadSubjects: () -> Unit,
    onSaveDraft: () -> Unit,
    onStageAndRecord: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onRetryIntentCleanup: () -> Unit,
    onStartAnotherReading: () -> Unit,
    onRefreshConfirmedSnapshot: () -> Unit,
    onOpenStockDisposition: ((String, String, BigDecimal) -> Unit)? = null
) {
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
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.temperature_evidence_back))
            }
            Text(
                text = stringResource(R.string.temperature_evidence_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.temperature_evidence_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TemperatureNotice(state)

            TemperatureSection(stringResource(R.string.temperature_evidence_subject_title)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSubjectTypeChanged(TemperatureEvidenceSubjectType.LOT) },
                        enabled = editable(state)
                    ) {
                        Text(
                            stringResource(
                                if (state.subjectType == TemperatureEvidenceSubjectType.LOT) {
                                    R.string.temperature_evidence_lot_selected
                                } else {
                                    R.string.temperature_evidence_lot
                                }
                            )
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onSubjectTypeChanged(TemperatureEvidenceSubjectType.WAREHOUSE)
                        },
                        enabled = editable(state)
                    ) {
                        Text(
                            stringResource(
                                if (state.subjectType == TemperatureEvidenceSubjectType.WAREHOUSE) {
                                    R.string.temperature_evidence_warehouse_selected
                                } else {
                                    R.string.temperature_evidence_warehouse
                                }
                            )
                        )
                    }
                }
                Text(
                    stringResource(
                        if (state.subjectType == TemperatureEvidenceSubjectType.LOT) {
                            R.string.temperature_evidence_lot_help
                        } else {
                            R.string.temperature_evidence_warehouse_help
                        }
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                if (state.canLookUpSubjects) {
                    TextButton(onClick = onReloadSubjects, enabled = !state.isFrozen) {
                        Text(stringResource(R.string.temperature_evidence_reload_subjects))
                    }
                }
                TemperatureLookupNotice(state.lookup)
                state.subjects.forEach { subject ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = editable(state)) { onSelectSubject(subject) }
                            .semantics {
                                contentDescription =
                                    "${subject.primaryLabel} ${subject.detailLabel}"
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.selectedSubject?.id == subject.id) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(subject.primaryLabel, fontWeight = FontWeight.Medium)
                            if (subject.detailLabel.isNotBlank()) {
                                Text(
                                    subject.detailLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                state.selectedSubject?.let { subject ->
                    Text(stringResource(R.string.temperature_excursion_current_subject, subject.id))
                    subject.warehouseId?.let {
                        Text(stringResource(R.string.temperature_excursion_warehouse, it))
                    }
                    if (subject.type == TemperatureEvidenceSubjectType.LOT) {
                        subject.lotVersion?.let {
                            Text(stringResource(R.string.temperature_excursion_lot_version, it))
                        }
                        subject.physicalRemaining?.let { remaining ->
                            Text(
                                stringResource(
                                    R.string.temperature_excursion_remaining,
                                    remaining.toPlainString(),
                                    subject.quantityUnit.orEmpty()
                                )
                            )
                        }
                    }
                }
            }

            TemperatureSection(stringResource(R.string.temperature_evidence_reading_title)) {
                OutlinedTextField(
                    value = state.valueText,
                    onValueChange = onValueChanged,
                    enabled = editable(state),
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.temperature_evidence_value)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TemperatureUnitButton(
                        unit = TemperatureEvidenceUnit.CELSIUS,
                        selected = state.unit == TemperatureEvidenceUnit.CELSIUS,
                        enabled = editable(state),
                        onClick = onUnitChanged
                    )
                    TemperatureUnitButton(
                        unit = TemperatureEvidenceUnit.FAHRENHEIT,
                        selected = state.unit == TemperatureEvidenceUnit.FAHRENHEIT,
                        enabled = editable(state),
                        onClick = onUnitChanged
                    )
                }
                OutlinedTextField(
                    value = state.occurredAtText,
                    onValueChange = onOccurredAtChanged,
                    enabled = editable(state),
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.temperature_evidence_time)) },
                    supportingText = {
                        Text(stringResource(R.string.temperature_evidence_time_help))
                    },
                    singleLine = true
                )
            }

            TemperatureSection(stringResource(R.string.temperature_excursion_evidence_title)) {
                if (state.subjectType == TemperatureEvidenceSubjectType.LOT) {
                    OutlinedTextField(
                        value = state.affectedQuantityText,
                        onValueChange = onAffectedQuantityChanged,
                        enabled = editable(state),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.temperature_excursion_quantity)) },
                        supportingText = {
                            Text(stringResource(R.string.temperature_excursion_quantity_help))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = state.reasonText,
                    onValueChange = onReasonChanged,
                    enabled = editable(state),
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.temperature_excursion_reason)) },
                    supportingText = {
                        Text(
                            if (state.subjectType == TemperatureEvidenceSubjectType.WAREHOUSE) {
                                stringResource(R.string.temperature_excursion_warehouse_reason_help)
                            } else {
                                stringResource(R.string.temperature_excursion_reason_help)
                            }
                        )
                    }
                )
                if (state.subjectType == TemperatureEvidenceSubjectType.LOT) {
                    OutlinedTextField(
                        value = state.sourceEvidenceIdText,
                        onValueChange = onSourceEvidenceIdChanged,
                        enabled = editable(state),
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.temperature_excursion_source_id))
                        },
                        supportingText = {
                            Text(stringResource(R.string.temperature_excursion_source_help))
                        },
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = onLoadSourceEvidence,
                        enabled = editable(state) && state.sourceEvidenceIdText.isNotBlank()
                    ) {
                        Text(stringResource(R.string.temperature_excursion_load_source))
                    }
                    if (state.sourceLookup != TemperatureLookupStatus.NotRequested) {
                        Text(
                            stringResource(
                                R.string.temperature_excursion_source_status,
                                state.sourceLookup.name
                            )
                        )
                    }
                    state.sourceEvidence?.let { source ->
                        Text(
                            stringResource(
                                R.string.temperature_excursion_source_fact,
                                source.id,
                                source.status,
                                source.exceptionStatus.orEmpty(),
                                source.warehouseId.orEmpty()
                            )
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.temperature_excursion_warehouse_scope_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onChoosePhoto,
                        enabled = editable(state) && state.selectedSubject != null &&
                            state.metadata == TemperatureMetadataStatus.Available &&
                            state.photoStatus !in setOf(
                                TemperaturePhotoStatus.Uploading,
                                TemperaturePhotoStatus.Checking
                            )
                    ) {
                        Text(stringResource(R.string.temperature_excursion_choose_photo))
                    }
                    if (state.photoEvidenceObjectId != null) {
                        OutlinedButton(
                            onClick = onRefreshPhotoStatus,
                            enabled = editable(state) &&
                                state.photoStatus != TemperaturePhotoStatus.Checking
                        ) {
                            Text(stringResource(R.string.temperature_excursion_refresh_photo))
                        }
                    }
                }
                Text(
                    stringResource(
                        R.string.temperature_excursion_photo_status,
                        state.photoStatus.name,
                        state.photoEvidenceObjectId.orEmpty()
                    ),
                    color = if (state.photoStatus == TemperaturePhotoStatus.Rejected ||
                        state.photoStatus == TemperaturePhotoStatus.ContextInvalidated
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                state.photoFailureCode?.let {
                    Text(stringResource(R.string.temperature_excursion_photo_rejection, it))
                }
            }

            state.validationError?.let { error ->
                Text(stringResource(error.message()), color = MaterialTheme.colorScheme.error)
            }
            TemperatureSection(stringResource(R.string.temperature_evidence_actions_title)) {
                Button(
                    onClick = onStageAndRecord,
                    enabled = state.canRecord && editable(state) &&
                        state.metadata == TemperatureMetadataStatus.Available,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.temperature_evidence_submit))
                }
                OutlinedButton(
                    onClick = onSaveDraft,
                    enabled =
                        editable(state) && state.metadata == TemperatureMetadataStatus.Available,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.temperature_evidence_save_draft))
                }
                if (state.command == TemperatureCommandStatus.UnknownOutcome) {
                    Button(
                        onClick = onRetryUnknownOutcome,
                        enabled = state.canRecord,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.temperature_evidence_retry_same))
                    }
                }
                if (state.intentCleanupPending) {
                    OutlinedButton(
                        onClick = onRetryIntentCleanup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.temperature_evidence_retry_cleanup))
                    }
                }
                if (state.command in setOf(
                        TemperatureCommandStatus.Confirmed,
                        TemperatureCommandStatus.Rejected,
                        TemperatureCommandStatus.Stale
                    ) && !state.intentCleanupPending
                ) {
                    OutlinedButton(
                        onClick = onStartAnotherReading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.temperature_evidence_new_reading))
                    }
                }
            }
            state.confirmed?.let { facts ->
                TemperatureConfirmation(
                    facts,
                    state.snapshotLoading,
                    onRefreshConfirmedSnapshot,
                    onOpenStockDisposition
                )
            }
        }
    }
}

@Composable
private fun TemperatureUnitButton(
    unit: TemperatureEvidenceUnit,
    selected: Boolean,
    enabled: Boolean,
    onClick: (TemperatureEvidenceUnit) -> Unit
) {
    OutlinedButton(onClick = { onClick(unit) }, enabled = enabled) {
        Text(
            stringResource(
                when (unit) {
                    TemperatureEvidenceUnit.CELSIUS -> if (selected) {
                        R.string.temperature_evidence_celsius_selected
                    } else {
                        R.string.temperature_evidence_celsius
                    }

                    TemperatureEvidenceUnit.FAHRENHEIT -> if (selected) {
                        R.string.temperature_evidence_fahrenheit_selected
                    } else {
                        R.string.temperature_evidence_fahrenheit
                    }
                }
            )
        )
    }
}

@Composable
private fun TemperatureSection(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            content()
        }
    }
}

@Composable
private fun TemperatureNotice(state: TemperatureEvidenceUiState) {
    val confirmed = state.confirmed
    if (confirmed != null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (confirmed.status == "OUT_OF_RANGE") {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        if (confirmed.status == "OUT_OF_RANGE") {
                            R.string.temperature_evidence_out_of_range
                        } else {
                            R.string.temperature_evidence_recorded
                        }
                    ),
                    fontWeight = FontWeight.SemiBold
                )
                Text(stringResource(R.string.temperature_evidence_no_silent_decision))
            }
        }
    } else if (state.command in setOf(
            TemperatureCommandStatus.UnknownOutcome,
            TemperatureCommandStatus.Pending
        )
    ) {
        TemperatureMessage(stringResource(R.string.temperature_evidence_pending), error = false)
    }
    state.notice?.let { notice ->
        TemperatureMessage(
            stringResource(notice.message()),
            error = notice !in setOf(
                TemperatureSubmitNotice.NetworkUnavailable,
                TemperatureSubmitNotice.ServiceUnavailable
            )
        )
    }
    if (state.command == TemperatureCommandStatus.Rejected ||
        state.command == TemperatureCommandStatus.Stale
    ) {
        TemperatureMessage(
            stringResource(R.string.temperature_evidence_rejected, state.rejectionCode.orEmpty()),
            error = true
        )
    }
    if (state.metadata == TemperatureMetadataStatus.Unavailable) {
        TemperatureMessage(stringResource(R.string.temperature_evidence_metadata_unavailable), true)
    }
}

@Composable
private fun TemperatureConfirmation(
    facts: TemperatureEvidenceFacts,
    snapshotLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenStockDisposition: ((String, String, BigDecimal) -> Unit)?
) {
    TemperatureSection(stringResource(R.string.temperature_evidence_confirmation_title)) {
        Text("${facts.value.toPlainString()} ${facts.unit.name}", fontWeight = FontWeight.SemiBold)
        Text("${facts.subjectType.name} · ${facts.subjectId}")
        facts.warehouseId?.let {
            Text(stringResource(R.string.temperature_evidence_warehouse_fact, it))
        }
        Text(stringResource(R.string.temperature_evidence_time_fact, facts.occurredAt))
        Text(stringResource(R.string.temperature_evidence_actor_fact, facts.actorMembershipId))
        Text(stringResource(R.string.temperature_evidence_status_fact, facts.status, facts.source))
        facts.reason?.let { Text(stringResource(R.string.temperature_excursion_reason_fact, it)) }
        facts.evidenceObjectId?.let {
            Text(stringResource(R.string.temperature_excursion_photo_fact, it))
        }
        facts.exceptionId?.let {
            Text(
                stringResource(
                    R.string.temperature_excursion_exception_fact,
                    it,
                    facts.exceptionStatus.orEmpty()
                )
            )
        }
        facts.sourceEvidenceId?.let {
            Text(stringResource(R.string.temperature_excursion_source_fact_id, it))
        }
        facts.inventoryTemperatureEvaluationId?.let {
            Text(stringResource(R.string.temperature_excursion_evaluation_id, it))
        }
        facts.evaluationStatus?.let {
            Text(stringResource(R.string.temperature_excursion_evaluation_fact, it))
        }
        facts.disposition?.let {
            Text(stringResource(R.string.temperature_excursion_disposition_fact, it))
        }
        facts.inventoryLotStatus?.let {
            Text(stringResource(R.string.temperature_excursion_lot_status_fact, it))
        }
        facts.affectedQuantity?.let {
            Text(
                stringResource(
                    R.string.temperature_excursion_original_quantity_fact,
                    it.toPlainString()
                )
            )
        }
        facts.remainingHeldQuantity?.let {
            Text(
                stringResource(
                    R.string.temperature_excursion_remaining_held_fact,
                    it.toPlainString()
                )
            )
        }
        if (facts.selections.isEmpty() && facts.subjectType == TemperatureEvidenceSubjectType.LOT) {
            val lotId = facts.lotId
            val evaluationId = facts.inventoryTemperatureEvaluationId
            val remainingHeld = facts.remainingHeldQuantity?.takeIf { it.signum() > 0 }
            if (onOpenStockDisposition != null && lotId != null && evaluationId != null &&
                remainingHeld != null
            ) {
                OutlinedButton(
                    onClick = { onOpenStockDisposition(lotId, evaluationId, remainingHeld) }
                ) {
                    Text(stringResource(R.string.temperature_excursion_open_disposition))
                }
            }
        }
        if (facts.selections.isNotEmpty()) {
            Text(
                stringResource(R.string.temperature_excursion_selections_title),
                fontWeight = FontWeight.Medium
            )
            facts.selections.forEach { selection ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(selection.lotId)
                    Text(
                        stringResource(
                            R.string.temperature_excursion_original_quantity_fact,
                            selection.affectedQuantity.toPlainString()
                        )
                    )
                    selection.remainingHeldQuantity?.let {
                        Text(
                            stringResource(
                                R.string.temperature_excursion_remaining_held_fact,
                                it.toPlainString()
                            )
                        )
                    }
                    selection.inventoryTemperatureEvaluationId?.let {
                        Text(stringResource(R.string.temperature_excursion_evaluation_id, it))
                    }
                    Text(
                        stringResource(
                            R.string.temperature_excursion_selection_versions,
                            selection.expectedLotVersion,
                            selection.resultingLotVersion,
                            selection.inventoryLotStatus.orEmpty()
                        )
                    )
                    selection.evaluationStatus?.let {
                        Text(stringResource(R.string.temperature_excursion_evaluation_fact, it))
                    }
                    selection.disposition?.let {
                        Text(stringResource(R.string.temperature_excursion_disposition_fact, it))
                    }
                    Text(
                        stringResource(
                            R.string.temperature_excursion_blocks_fact,
                            selection.blocksCommittedExecution
                        )
                    )
                    val remainingHeld = selection.remainingHeldQuantity?.takeIf { it.signum() > 0 }
                    val evaluationId = selection.inventoryTemperatureEvaluationId
                    if (onOpenStockDisposition != null && evaluationId != null &&
                        remainingHeld != null
                    ) {
                        OutlinedButton(
                            onClick = {
                                onOpenStockDisposition(
                                    selection.lotId,
                                    evaluationId,
                                    remainingHeld
                                )
                            }
                        ) {
                            Text(stringResource(R.string.temperature_excursion_open_disposition))
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onRefresh, enabled = !snapshotLoading) {
            Text(stringResource(R.string.temperature_excursion_refresh_snapshot))
        }
    }
}

@Composable
private fun TemperatureMessage(text: String, error: Boolean) {
    Text(
        text,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun TemperatureLookupNotice(status: TemperatureLookupStatus) {
    val message = when (status) {
        TemperatureLookupStatus.NotRequested -> return
        TemperatureLookupStatus.Loading -> R.string.temperature_evidence_lookup_loading
        TemperatureLookupStatus.Ready -> R.string.temperature_evidence_lookup_ready
        TemperatureLookupStatus.Empty -> R.string.temperature_evidence_lookup_empty
        TemperatureLookupStatus.NetworkUnavailable -> R.string.temperature_evidence_lookup_network
        TemperatureLookupStatus.ServiceUnavailable -> R.string.temperature_evidence_lookup_service
        TemperatureLookupStatus.PermissionDenied -> R.string.temperature_evidence_lookup_denied
        TemperatureLookupStatus.ContextInvalidated -> R.string.temperature_evidence_lookup_context
        TemperatureLookupStatus.SessionInvalidated -> R.string.temperature_evidence_lookup_session
        TemperatureLookupStatus.Stale -> R.string.temperature_excursion_stale_subject
        TemperatureLookupStatus.Rejected -> R.string.temperature_excursion_rejected_subject
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun editable(state: TemperatureEvidenceUiState): Boolean =
    !state.isFrozen && state.command == TemperatureCommandStatus.Editing

private fun TemperatureValidationError.message(): Int = when (this) {
    TemperatureValidationError.SubjectRequired -> R.string.temperature_evidence_error_subject

    TemperatureValidationError.UnitRequired -> R.string.temperature_evidence_error_unit

    TemperatureValidationError.ValueRequired -> R.string.temperature_evidence_error_value

    TemperatureValidationError.ValueInvalid -> R.string.temperature_evidence_error_value_invalid

    TemperatureValidationError.TimeRequired -> R.string.temperature_evidence_error_time

    TemperatureValidationError.TimeInvalid -> R.string.temperature_evidence_error_time_invalid

    TemperatureValidationError.MetadataUnavailable ->
        R.string.temperature_evidence_metadata_unavailable

    TemperatureValidationError.CurrentSubjectRequired ->
        R.string.temperature_excursion_current_subject_required

    TemperatureValidationError.QuantityInvalid -> R.string.temperature_excursion_quantity_invalid

    TemperatureValidationError.QuantityExceedsLot -> R.string.temperature_excursion_quantity_exceeds

    TemperatureValidationError.ReasonTooLong -> R.string.temperature_excursion_reason_too_long

    TemperatureValidationError.SourceEvidenceRequired ->
        R.string.temperature_excursion_source_required

    TemperatureValidationError.SourceEvidenceInvalid ->
        R.string.temperature_excursion_source_invalid

    TemperatureValidationError.PhotoNotAvailable -> R.string.temperature_excursion_photo_required
}

private fun TemperatureSubmitNotice.message(): Int = when (this) {
    TemperatureSubmitNotice.NetworkUnavailable -> R.string.temperature_evidence_notice_network

    TemperatureSubmitNotice.ServiceUnavailable -> R.string.temperature_evidence_notice_service

    TemperatureSubmitNotice.PermissionDenied -> R.string.temperature_evidence_notice_permission

    TemperatureSubmitNotice.ContextInvalidated -> R.string.temperature_evidence_notice_context

    TemperatureSubmitNotice.SessionInvalidated -> R.string.temperature_evidence_notice_session

    TemperatureSubmitNotice.IntentMetadataUnavailable ->
        R.string.temperature_evidence_metadata_unavailable
}
