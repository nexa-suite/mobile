package com.nexa.mobile.operations.salescommitment.presentation.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.nexa.mobile.operations.core.designsystem.R as SharedR
import com.nexa.mobile.operations.salescommitment.application.commercial.isValidForSubmission
import com.nexa.mobile.operations.salescommitment.presentation.R

@Composable
fun FieldRequestScreen(
    state: FieldRequestState,
    onBack: () -> Unit,
    viewModel: FieldRequestViewModel
) {
    val draft = state.record.draft
    val editable = state.record.intent == null && state.status !in setOf(
        FieldRequestStatus.Loading,
        FieldRequestStatus.MetadataUnavailable,
        FieldRequestStatus.Reviewing,
        FieldRequestStatus.Pending
    )
    Column(
        Modifier.fillMaxSize().semantics { isTraversalGroup = true }
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = onBack) { Text(stringResource(SharedR.string.commercial_back)) }
        Text(
            stringResource(R.string.field_request_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            fieldRequestStatus(state),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        state.record.intent?.receipt?.let { receipt ->
            Text(
                stringResource(
                    R.string.field_request_receipt,
                    receipt.number,
                    receipt.total,
                    receipt.currency,
                    receipt.version
                )
            )
        }
        OutlinedTextField(
            draft.customerId,
            viewModel::customerChanged,
            enabled = editable,
            label = {
                Text(stringResource(R.string.field_request_customer_reference))
            }
        )
        OutlinedTextField(state.productId, viewModel::productChanged, enabled = editable, label = {
            Text(stringResource(R.string.field_request_catalog_product))
        })
        OutlinedTextField(state.quantity, viewModel::quantityChanged, enabled = editable, label = {
            Text(stringResource(R.string.field_request_quantity))
        })
        Button(
            onClick = viewModel::addProduct,
            enabled =
                editable && draft.customerId.isNotBlank() && state.productId.isNotBlank()
        ) {
            Text(stringResource(R.string.field_request_add_product))
        }
        draft.lines.forEach { line ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(line.name)
                    Text(
                        stringResource(
                            R.string.field_request_quote_details,
                            line.catalogItemId,
                            line.price,
                            line.currency,
                            line.unit ?: stringResource(R.string.field_request_unit_unavailable)
                        )
                    )
                    Text(stringResource(R.string.field_request_quote_reference, line.asOf))
                    OutlinedTextField(
                        line.quantity,
                        {
                            viewModel.lineQuantityChanged(line.catalogItemId, it)
                        },
                        enabled = editable,
                        label = { Text(stringResource(R.string.field_request_quantity_expected)) }
                    )
                    TextButton(onClick = {
                        viewModel.removeLine(line.catalogItemId)
                    }, enabled = editable) { Text(stringResource(R.string.field_request_remove)) }
                }
            }
        }
        OutlinedTextField(
            draft.deliveryDate,
            viewModel::deliveryDateChanged,
            enabled = editable,
            label = {
                Text(stringResource(R.string.field_request_delivery_date))
            }
        )
        OutlinedTextField(
            draft.deliveryProfile,
            viewModel::deliveryProfileChanged,
            enabled = editable,
            label = {
                Text(stringResource(R.string.field_request_delivery_instructions))
            }
        )
        val paymentOptions = listOf(
            "CASH_ON_DELIVERY" to R.string.field_request_payment_cash_on_delivery,
            "CREDIT_LINE" to R.string.field_request_payment_credit_line,
            "BANK_TRANSFER" to R.string.field_request_payment_bank_transfer,
            "PREPAID" to R.string.field_request_payment_prepaid
        )
        val selectedPayment = paymentOptions.firstOrNull { it.first == draft.paymentOption }
        Text(
            stringResource(
                R.string.field_request_payment_option,
                selectedPayment?.second?.let { stringResource(it) }
                    ?: stringResource(SharedR.string.commercial_catalog_missing)
            )
        )
        paymentOptions.forEach { (option, label) ->
            TextButton(onClick = {
                viewModel.paymentChanged(option)
            }, enabled = editable) { Text(stringResource(label)) }
        }
        OutlinedTextField(draft.comment, viewModel::commentChanged, enabled = editable, label = {
            Text(stringResource(R.string.field_request_comment))
        })
        Button(onClick = viewModel::review, enabled = editable && draft.isValidForSubmission()) {
            Text(stringResource(R.string.field_request_review))
        }
        if (state.status == FieldRequestStatus.Changed) {
            Button(onClick = viewModel::acceptChangedInformation) {
                Text(stringResource(R.string.field_request_accept_changed))
            }
        }
        if (state.status == FieldRequestStatus.Reviewed) {
            Button(onClick = viewModel::submit) {
                Text(stringResource(R.string.field_request_submit))
            }
        }
        if (state.status == FieldRequestStatus.UnknownOutcome) {
            Button(onClick = viewModel::retryUnknownOutcome) {
                Text(stringResource(SharedR.string.field_request_retry))
            }
        }
        if (state.status.permitsNewDecision) {
            Button(onClick = viewModel::startNewDecision) {
                Text(stringResource(R.string.field_request_new_decision))
            }
        }
    }
}

@Composable
private fun fieldRequestStatus(state: FieldRequestState): String = when (state.status) {
    FieldRequestStatus.Confirmed -> stringResource(
        R.string.field_request_status_confirmed,
        state.record.intent?.receiptId ?: stringResource(SharedR.string.commercial_catalog_missing)
    )

    FieldRequestStatus.PrepaidPending -> stringResource(
        R.string.field_request_status_prepaid_pending
    )

    FieldRequestStatus.Rejected -> stringResource(R.string.field_request_status_rejected)

    FieldRequestStatus.LegacyIntent -> stringResource(R.string.field_request_status_legacy)

    FieldRequestStatus.UnknownOutcome -> stringResource(R.string.field_request_status_unknown)

    FieldRequestStatus.Conflict -> stringResource(R.string.field_request_status_conflict)

    FieldRequestStatus.Changed -> stringResource(R.string.field_request_status_changed)

    FieldRequestStatus.Reviewed -> stringResource(R.string.field_request_status_reviewed)

    FieldRequestStatus.MetadataUnavailable ->
        stringResource(R.string.field_request_status_storage_unavailable)

    FieldRequestStatus.PermissionDenied ->
        stringResource(R.string.field_request_status_permission_denied)

    FieldRequestStatus.Unavailable -> stringResource(R.string.field_request_status_unavailable)

    FieldRequestStatus.Pending -> stringResource(R.string.field_request_status_pending)

    FieldRequestStatus.Reviewing -> stringResource(R.string.field_request_status_reviewing)

    FieldRequestStatus.Loading -> stringResource(R.string.field_request_status_loading)

    FieldRequestStatus.Draft -> stringResource(R.string.field_request_status_draft)
}
