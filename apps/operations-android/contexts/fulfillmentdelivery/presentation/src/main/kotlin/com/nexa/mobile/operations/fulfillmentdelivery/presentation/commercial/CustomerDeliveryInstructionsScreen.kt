package com.nexa.mobile.operations.fulfillmentdelivery.presentation.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.R as SharedR
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R

@Composable
fun CustomerDeliveryInstructionsScreen(
    state: CustomerInstructionsState,
    onBack: () -> Unit,
    viewModel: CustomerDeliveryInstructionsViewModel
) {
    Column(
        Modifier.fillMaxSize().semantics { isTraversalGroup = true }
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text(stringResource(SharedR.string.commercial_back)) }
        Text(
            stringResource(R.string.customer_instructions_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(stringResource(R.string.customer_instructions_description))
        OutlinedTextField(
            state.orderId,
            viewModel::orderChanged,
            label = { Text(stringResource(R.string.customer_instructions_order_id)) },
            enabled = !state.pending && state.recoverable == null
        )
        OutlinedButton(viewModel::refresh, enabled = !state.pending) {
            Text(stringResource(R.string.customer_instructions_refresh))
        }
        if (state.pending) {
            val loadingDescription = stringResource(R.string.customer_instructions_loading)
            CircularProgressIndicator(
                Modifier.semantics {
                    contentDescription = loadingDescription
                }
            )
        }
        state.message?.let { message ->
            Text(
                stringResource(message.resource()),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        state.recoverable?.let {
            Text(stringResource(R.string.customer_instructions_pending_command))
            Button(viewModel::retry, enabled = !state.pending) {
                Text(stringResource(R.string.customer_instructions_retry))
            }
        }
        state.snapshot?.let { snapshot ->
            Text(
                stringResource(
                    R.string.customer_instructions_version,
                    snapshot.version,
                    stringResource(
                        if (snapshot.editable) {
                            R.string.customer_instructions_edit_available
                        } else {
                            R.string.customer_instructions_edit_closed
                        }
                    )
                )
            )
            snapshot.rows.forEach { row ->
                Card {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.customer_instructions_row_revision,
                                stringResource(row.kind.resource()),
                                row.version
                            )
                        )
                        Text(row.content)
                        Text(
                            stringResource(
                                R.string.customer_instructions_row_source,
                                row.source,
                                row.recordedBy,
                                row.recordedAt
                            )
                        )
                        if (snapshot.editable) {
                            TextButton(
                                { viewModel.edit(row) },
                                enabled =
                                    !state.pending && state.recoverable == null
                            ) { Text(stringResource(R.string.customer_instructions_edit)) }
                        }
                    }
                }
            }
            if (snapshot.editable && state.recoverable == null) {
                OutlinedButton(viewModel::newInstruction, enabled = !state.pending) {
                    Text(stringResource(R.string.customer_instructions_new))
                }
                CustomerDeliveryInstructionsViewModel.kinds.forEach { kind ->
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = {
                            viewModel.kindChanged(kind)
                        },
                        label = { Text(stringResource(kind.resource())) },
                        enabled = !state.pending
                    )
                }
                OutlinedTextField(state.content, viewModel::contentChanged, label = {
                    Text(stringResource(R.string.customer_instructions_content))
                }, enabled = !state.pending)
                OutlinedTextField(state.source, viewModel::sourceChanged, label = {
                    Text(stringResource(R.string.customer_instructions_source))
                }, enabled = !state.pending)
                Text(
                    stringResource(R.string.customer_instructions_no_buyer_attribution)
                )
                Button(viewModel::publish, enabled = state.canPublish) {
                    Text(stringResource(R.string.customer_instructions_publish))
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CustomerInstructionsNotice.resource(): Int = when (this) {
    CustomerInstructionsNotice.ProtectedStorageUnavailable ->
        R.string.customer_instructions_notice_storage_unavailable

    CustomerInstructionsNotice.SubmissionBlocked ->
        R.string.customer_instructions_notice_storage_blocked

    CustomerInstructionsNotice.PermissionUnavailable ->
        R.string.customer_instructions_notice_permission

    CustomerInstructionsNotice.SessionUnavailable -> R.string.customer_instructions_notice_session

    CustomerInstructionsNotice.ContextChanged ->
        R.string.customer_instructions_notice_context_changed

    CustomerInstructionsNotice.ServiceUnavailable -> R.string.customer_instructions_notice_service

    CustomerInstructionsNotice.Registered -> R.string.customer_instructions_notice_registered

    CustomerInstructionsNotice.RegisteredNeedsRecovery ->
        R.string.customer_instructions_notice_registered_recovery

    CustomerInstructionsNotice.UnknownOutcome -> R.string.customer_instructions_notice_unknown

    CustomerInstructionsNotice.OperationUnavailable ->
        R.string.customer_instructions_notice_failed
}

@androidx.compose.runtime.Composable
private fun String.resource(): Int = when (this) {
    "NORMAL" -> R.string.customer_instructions_kind_normal
    "COLD_CHAIN" -> R.string.customer_instructions_kind_cold_chain
    "ACCESS_RESTRICTION" -> R.string.customer_instructions_kind_access_restriction
    "SPECIAL_UNLOADING" -> R.string.customer_instructions_kind_special_unloading
    "CUSTOMER_SAFETY" -> R.string.customer_instructions_kind_customer_safety
    "GOODS_HANDLING" -> R.string.customer_instructions_kind_goods_handling
    else -> R.string.customer_instructions_kind_other
}
