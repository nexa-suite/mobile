package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceStage
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentType

@Composable
fun DriverDeliveryIncidentScreen(
    state: DriverDeliveryIncidentUiState,
    onTypeChanged: (DriverIncidentType) -> Unit,
    onReasonChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onPlaceChanged: (String) -> Unit,
    onSaveDraft: () -> Unit,
    onReviewDraft: () -> Unit,
    onSubmitIncident: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectEvidence: () -> Unit = {},
    onUploadEvidence: () -> Unit = {},
    onCheckEvidenceAvailability: () -> Unit = {},
    onReviewEvidenceLink: () -> Unit = {},
    onAttachEvidence: () -> Unit = {},
    onOpenOperationalExceptions: (String) -> Unit = {},
    onUseRecordedIncident: ((DriverIncidentSummary, String) -> Unit)? = null
) {
    val editingEnabled = state.command == null && state.status in setOf(
        DriverIncidentUiStatus.EditingDraft,
        DriverIncidentUiStatus.NeedsReview,
        DriverIncidentUiStatus.ReadyForReview,
        DriverIncidentUiStatus.DraftSaved,
        DriverIncidentUiStatus.Rejected,
        DriverIncidentUiStatus.Stale
    )
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.driver_incident_title))
        Text(stringResource(R.string.driver_incident_append_only_notice))
        Text(stringResource(R.string.driver_incident_scope_notice))
        state.deliveryId?.let { Text(stringResource(R.string.driver_incident_delivery, it)) }
        state.attemptId?.let { Text(stringResource(R.string.driver_incident_attempt, it)) }
        statusText(state)?.let { Text(it) }
        state.rejectionCode?.let { Text(stringResource(R.string.driver_incident_error, it)) }

        Text(stringResource(R.string.driver_incident_type_title))
        DriverIncidentType.entries.forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.type == type,
                        enabled = editingEnabled,
                        role = Role.RadioButton,
                        onClick = { onTypeChanged(type) }
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = state.type == type, onClick = null, enabled = editingEnabled)
                Text(stringResource(type.labelResource()))
            }
        }

        OutlinedTextField(
            value = state.reason,
            onValueChange = onReasonChanged,
            enabled = editingEnabled,
            label = { Text(stringResource(R.string.driver_incident_reason)) },
            supportingText = { Text("500") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChanged,
            enabled = editingEnabled,
            label = { Text(stringResource(R.string.driver_incident_description)) },
            supportingText = { Text("2000") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        OutlinedTextField(
            value = state.place,
            onValueChange = onPlaceChanged,
            enabled = editingEnabled,
            label = { Text(stringResource(R.string.driver_incident_place)) },
            supportingText = { Text("500") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSaveDraft, enabled = editingEnabled && state.validDraft) {
                Text(stringResource(R.string.driver_incident_save_draft))
            }
            Button(onClick = onReviewDraft, enabled = state.draftSaved && editingEnabled) {
                Text(stringResource(R.string.driver_incident_review))
            }
        }
        Button(
            onClick = onSubmitIncident,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.driver_incident_submit))
        }
        Button(
            onClick = onRetryUnknownOutcome,
            enabled =
                state.status == DriverIncidentUiStatus.UnknownOutcome && state.command != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.driver_incident_retry_same))
        }

        if (state.canSelectEvidence) {
            Button(onClick = onSelectEvidence, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.driver_incident_select_evidence))
            }
        }
        state.evidence?.let { evidence ->
            Text(
                pluralStringResource(
                    R.plurals.driver_incident_evidence_file,
                    evidence.byteSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    evidence.originalFilename,
                    evidence.byteSize
                )
            )
            state.evidenceLifecycleStatus?.let {
                Text(stringResource(R.string.driver_incident_evidence_lifecycle, it))
            }
            state.evidenceError?.let {
                Text(stringResource(R.string.driver_incident_evidence_error, it))
            }
            if (state.evidenceBusy) Text(stringResource(R.string.driver_incident_evidence_working))
            if (state.canUploadEvidence) {
                val label = if (evidence.stage ==
                    DriverIncidentEvidenceStage.UploadUnknownOutcome
                ) {
                    R.string.driver_incident_evidence_retry_upload
                } else {
                    R.string.driver_incident_evidence_upload
                }
                Button(onClick = onUploadEvidence, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(label))
                }
            }
            if (state.canCheckEvidence) {
                Button(onClick = onCheckEvidenceAvailability, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.driver_incident_evidence_check))
                }
            }
            if (state.canReviewEvidenceLink) {
                Button(onClick = onReviewEvidenceLink, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.driver_incident_evidence_review_link))
                }
            }
            if (state.canAttachEvidence) {
                val label = if (evidence.stage ==
                    DriverIncidentEvidenceStage.AttachUnknownOutcome
                ) {
                    R.string.driver_incident_evidence_retry_link
                } else {
                    R.string.driver_incident_evidence_attach
                }
                Button(onClick = onAttachEvidence, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(label))
                }
            }
        }

        state.summary?.let { summary ->
            val incidentType = summary.type
            if (incidentType == null) {
                Text(stringResource(R.string.driver_incident_unclassified_historical))
            } else {
                Text(
                    stringResource(
                        R.string.driver_incident_classification,
                        stringResource(incidentType.labelResource())
                    )
                )
            }
            summary.severity?.let {
                Text(stringResource(R.string.driver_incident_server_severity, it))
            }
            summary.operationalExceptionId?.let { caseId ->
                Text(stringResource(R.string.driver_incident_operational_exception, caseId))
                Button(onClick = {
                    onOpenOperationalExceptions(summary.deliveryId)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.driver_incident_open_exceptions))
                }
            }
            Text(
                stringResource(
                    R.string.driver_incident_recorded,
                    summary.recordedByMembershipId,
                    summary.recordedAt
                )
            )
            Text(stringResource(R.string.driver_incident_evidence_status, summary.evidenceLabel))
            if (summary.type == DriverIncidentType.TEMPERATURE_EXCURSION &&
                onUseRecordedIncident != null
            ) {
                summary.evidenceObjectIds.forEachIndexed { index, evidenceId ->
                    TextButton(onClick = { onUseRecordedIncident(summary, evidenceId) }) {
                        Text(
                            stringResource(R.string.driver_incident_use_thermal_evidence, index + 1)
                        )
                    }
                }
            }
            Text(stringResource(R.string.driver_incident_evidence_not_resolution))
        }
        if (state.persistenceCleanupPending) {
            Text(stringResource(R.string.driver_incident_cleanup_pending))
        }
    }
}

@Composable
private fun DriverIncidentType.labelResource(): Int = when (this) {
    DriverIncidentType.DELAY -> R.string.driver_incident_type_delay

    DriverIncidentType.INCOMPLETE_INSTRUCTION ->
        R.string.driver_incident_type_incomplete_instruction

    DriverIncidentType.ACCESS_BLOCKED -> R.string.driver_incident_type_access_blocked

    DriverIncidentType.CUSTOMER_UNAVAILABLE -> R.string.driver_incident_type_customer_unavailable

    DriverIncidentType.DELIVERY_NOT_EXECUTABLE ->
        R.string.driver_incident_type_delivery_not_executable

    DriverIncidentType.TEMPERATURE_EXCURSION -> R.string.driver_incident_type_temperature_excursion

    DriverIncidentType.SAFETY_COMPROMISING_DAMAGE ->
        R.string.driver_incident_type_safety_compromising_damage
}

@Composable
private fun statusText(state: DriverDeliveryIncidentUiState): String? = when (state.status) {
    DriverIncidentUiStatus.Loading -> stringResource(R.string.driver_incident_loading)

    DriverIncidentUiStatus.EditingDraft -> stringResource(R.string.driver_incident_draft_editing)

    DriverIncidentUiStatus.NeedsReview -> stringResource(R.string.driver_incident_needs_review)

    DriverIncidentUiStatus.ReadyForReview -> stringResource(
        R.string.driver_incident_ready_for_review
    )

    DriverIncidentUiStatus.SavingDraft -> stringResource(R.string.driver_incident_saving_draft)

    DriverIncidentUiStatus.DraftSaved -> stringResource(R.string.driver_incident_draft_saved)

    DriverIncidentUiStatus.CheckingCurrent -> stringResource(
        R.string.driver_incident_checking_current
    )

    DriverIncidentUiStatus.PersistingIntent -> stringResource(R.string.driver_incident_persisting)

    DriverIncidentUiStatus.Pending -> stringResource(R.string.driver_incident_pending)

    DriverIncidentUiStatus.UnknownOutcome -> stringResource(R.string.driver_incident_unknown)

    DriverIncidentUiStatus.PersistenceUnavailable -> stringResource(
        R.string.driver_incident_persistence_unavailable
    )

    DriverIncidentUiStatus.Recorded -> stringResource(R.string.driver_incident_recorded_status)

    DriverIncidentUiStatus.Stale -> stringResource(R.string.driver_incident_stale)

    DriverIncidentUiStatus.Rejected -> stringResource(R.string.driver_incident_rejected)

    DriverIncidentUiStatus.NotFound -> stringResource(R.string.driver_incident_not_found)

    DriverIncidentUiStatus.Unavailable -> stringResource(R.string.driver_incident_unavailable)

    DriverIncidentUiStatus.PermissionDenied -> stringResource(
        R.string.driver_incident_permission_denied
    )
}
