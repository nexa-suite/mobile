package com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial

import com.nexa.mobile.operations.core.designsystem.R as SharedR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.customerbuyerrelationships.presentation.R

@Composable
fun FieldVisitScreen(
    state: FieldVisitState,
    onBack: () -> Unit,
    viewModel: FieldVisitViewModel,
    canRecord: Boolean
) {
    val editable =
        state.record.intent == null &&
            state.status !in setOf("Loading", "MetadataUnavailable", "Pending")
    Column(
        Modifier.fillMaxSize().semantics { isTraversalGroup = true }
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text(stringResource(SharedR.string.commercial_back)) }
        Text(stringResource(R.string.field_visit_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.field_visit_description))
        Text(
            stringResource(R.string.field_visit_status_label, fieldVisitStatus(state.status)),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        OutlinedTextField(
            state.record.customerId,
            viewModel::customerChanged,
            enabled = editable,
            label = {
                Text(stringResource(R.string.field_visit_customer_reference))
            }
        )
        OutlinedTextField(
            state.record.purpose,
            viewModel::purposeChanged,
            enabled = editable,
            label = {
                Text(stringResource(R.string.field_visit_purpose))
            }
        )
        OutlinedTextField(
            state.record.followUp,
            viewModel::followUpChanged,
            enabled = editable,
            label = {
                Text(stringResource(R.string.field_visit_follow_up))
            }
        )
        Button(onClick = viewModel::reviewCustomer, enabled = editable) {
            Text(stringResource(R.string.field_visit_review_relation))
        }
        state.customer?.let { customer ->
            Text(
                stringResource(
                    R.string.field_visit_customer_details,
                    customer.name,
                    customer.code,
                    customer.version
                )
            )
            Text(
                stringResource(
                    R.string.field_visit_relation_details,
                    stringResource(
                        if (customer.active) R.string.field_visit_active
                        else R.string.field_visit_suspended
                    ),
                    stringResource(
                        if (customer.buyerLinked) R.string.field_visit_yes
                        else R.string.field_visit_no
                    )
                )
            )
            state.receivedAt?.let {
                Text(stringResource(R.string.field_visit_context_received, it.toString()))
            }
        }
        if (state.status == "Reviewed" &&
            !canRecord
        ) {
            Text(
                stringResource(R.string.field_visit_manage_permission_required)
            )
        }
        if (state.status ==
            "Reviewed"
        ) {
            Button(onClick = viewModel::recordFollowUp, enabled = canRecord) {
                Text(stringResource(R.string.field_visit_confirm))
            }
        }
        if (state.status == "UnknownOutcome") {
            Text(stringResource(R.string.field_visit_unknown_detail))
            Button(onClick = viewModel::retryUnknownOutcome) {
                Text(stringResource(SharedR.string.field_request_retry))
            }
        }
        if (state.status == "Recorded") {
            Text(
                stringResource(
                    R.string.field_visit_receipt,
                    state.record.intent?.receiptId
                        ?: stringResource(SharedR.string.commercial_catalog_missing)
                )
            )
        }
        if (state.status ==
            "Conflict"
        ) {
            Text(stringResource(R.string.field_visit_conflict_detail))
        }
        if (state.status ==
            "MetadataUnavailable"
        ) {
            Text(stringResource(R.string.field_visit_storage_unavailable_detail))
        }
        if (state.status in
            setOf("Recorded", "Conflict")
        ) {
            Button(onClick = viewModel::newDecision) {
                Text(stringResource(R.string.field_visit_new_decision))
            }
        }
    }
}

@Composable
private fun fieldVisitStatus(status: String): String = when (status) {
    "Loading" -> stringResource(R.string.field_visit_status_loading)
    "Draft" -> stringResource(R.string.field_visit_status_draft)
    "Reviewed" -> stringResource(R.string.field_visit_status_reviewed)
    "Pending" -> stringResource(R.string.field_visit_status_pending)
    "Recorded" -> stringResource(R.string.field_visit_status_recorded)
    "UnknownOutcome" -> stringResource(R.string.field_visit_status_unknown)
    "Conflict" -> stringResource(R.string.field_visit_status_conflict)
    "MetadataUnavailable" -> stringResource(R.string.field_visit_status_storage_unavailable)
    else -> stringResource(R.string.field_visit_status_unavailable)
}
