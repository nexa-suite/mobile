package com.nexa.mobile.operations.feature.delivery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun DriverDeliveryIncidentScreen(
    state: DriverDeliveryIncidentUiState,
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
    onAttachEvidence: () -> Unit = {}
) {
    val editingEnabled = state.command == null && state.status != DriverIncidentUiStatus.Recorded
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.driver_incident_title))
        Text(stringResource(R.string.driver_incident_append_only_notice))
        state.deliveryId?.let { Text(stringResource(R.string.driver_incident_delivery, it)) }
        state.attemptId?.let { Text(stringResource(R.string.driver_incident_attempt, it)) }
        statusText(state)?.let { Text(it) }
        state.rejectionCode?.let { Text(stringResource(R.string.driver_incident_error, it)) }

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
        Button(onClick = onSubmitIncident, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.driver_incident_submit))
        }
        Button(
            onClick = onRetryUnknownOutcome,
            enabled = state.status == DriverIncidentUiStatus.UnknownOutcome && state.command != null,
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
            Text(stringResource(R.string.driver_incident_evidence_file, evidence.originalFilename, evidence.byteSize))
            state.evidenceLifecycleStatus?.let {
                Text(stringResource(R.string.driver_incident_evidence_lifecycle, it))
            }
            state.evidenceError?.let {
                Text(stringResource(R.string.driver_incident_evidence_error, it))
            }
            if (state.evidenceBusy) Text(stringResource(R.string.driver_incident_evidence_working))
            if (state.canUploadEvidence) {
                val label = if (evidence.stage == DriverIncidentEvidenceStage.UploadUnknownOutcome) {
                    R.string.driver_incident_evidence_retry_upload
                } else R.string.driver_incident_evidence_upload
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
                val label = if (evidence.stage == DriverIncidentEvidenceStage.AttachUnknownOutcome) {
                    R.string.driver_incident_evidence_retry_link
                } else R.string.driver_incident_evidence_attach
                Button(onClick = onAttachEvidence, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(label))
                }
            }
        }

        state.summary?.let { summary ->
            Text(stringResource(R.string.driver_incident_recorded, summary.recordedByMembershipId, summary.recordedAt))
            Text(stringResource(R.string.driver_incident_evidence_status, summary.evidenceLabel))
            Text(stringResource(R.string.driver_incident_evidence_not_resolution))
        }
        if (state.persistenceCleanupPending) {
            Text(stringResource(R.string.driver_incident_cleanup_pending))
        }
    }
}

@Composable
private fun statusText(state: DriverDeliveryIncidentUiState): String? = when (state.status) {
    DriverIncidentUiStatus.Loading -> stringResource(R.string.driver_incident_loading)
    DriverIncidentUiStatus.EditingDraft -> stringResource(R.string.driver_incident_draft_editing)
    DriverIncidentUiStatus.NeedsReview -> stringResource(R.string.driver_incident_needs_review)
    DriverIncidentUiStatus.ReadyForReview -> stringResource(R.string.driver_incident_ready_for_review)
    DriverIncidentUiStatus.SavingDraft -> stringResource(R.string.driver_incident_saving_draft)
    DriverIncidentUiStatus.DraftSaved -> stringResource(R.string.driver_incident_draft_saved)
    DriverIncidentUiStatus.CheckingCurrent -> stringResource(R.string.driver_incident_checking_current)
    DriverIncidentUiStatus.PersistingIntent -> stringResource(R.string.driver_incident_persisting)
    DriverIncidentUiStatus.Pending -> stringResource(R.string.driver_incident_pending)
    DriverIncidentUiStatus.UnknownOutcome -> stringResource(R.string.driver_incident_unknown)
    DriverIncidentUiStatus.PersistenceUnavailable -> stringResource(R.string.driver_incident_persistence_unavailable)
    DriverIncidentUiStatus.Recorded -> stringResource(R.string.driver_incident_recorded_status)
    DriverIncidentUiStatus.Stale -> stringResource(R.string.driver_incident_stale)
    DriverIncidentUiStatus.Rejected -> stringResource(R.string.driver_incident_rejected)
    DriverIncidentUiStatus.NotFound -> stringResource(R.string.driver_incident_not_found)
    DriverIncidentUiStatus.Unavailable -> stringResource(R.string.driver_incident_unavailable)
    DriverIncidentUiStatus.PermissionDenied -> stringResource(R.string.driver_incident_permission_denied)
}
