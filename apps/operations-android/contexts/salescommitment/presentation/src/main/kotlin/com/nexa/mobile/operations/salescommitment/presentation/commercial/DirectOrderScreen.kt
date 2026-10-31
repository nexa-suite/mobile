package com.nexa.mobile.operations.salescommitment.presentation.commercial

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as SharedR
import com.nexa.mobile.operations.salescommitment.presentation.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun DirectOrderScreen(
    state: DirectOrderState,
    onBack: () -> Unit,
    viewModel: DirectOrderViewModel,
    onOpenCatalog: () -> Unit = {},
    onChooseCustomer: (() -> Unit)? = null
) {
    val draft = state.record.draft
    val editable = state.record.intent == null && state.status !in setOf(
        DirectOrderStatus.Loading,
        DirectOrderStatus.MetadataUnavailable,
        DirectOrderStatus.Reviewing,
        DirectOrderStatus.Pending
    )
    Surface(modifier = Modifier.fillMaxSize(), color = NexaColors.Canvas) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            NexaTopAppBar(
                title = stringResource(R.string.direct_order_title),
                onBack = onBack
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .semantics { isTraversalGroup = true }
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaShapes.card,
                    colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
                    border = BorderStroke(1.dp, NexaColors.Border)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            directOrderStatus(state),
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        state.record.intent?.receipt?.let { receipt ->
                            Text(
                                stringResource(
                                    R.string.direct_order_receipt,
                                    receipt.number,
                                    receipt.total,
                                    receipt.currency
                                ),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaShapes.card,
                    colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
                    border = BorderStroke(1.dp, NexaColors.Border)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.direct_order_customer),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(
                                if (draft.customerId.isBlank()) {
                                    R.string.direct_order_customer_required
                                } else {
                                    R.string.direct_order_customer_set
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexaColors.TextSecondary
                        )
                        onChooseCustomer?.let { chooseCustomer ->
                            Button(
                                onClick = chooseCustomer,
                                enabled = editable,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(
                                        if (draft.customerId.isBlank()) {
                                            R.string.direct_order_choose_customer
                                        } else {
                                            R.string.direct_order_change_customer
                                        }
                                    )
                                )
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaShapes.card,
                    colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
                    border = BorderStroke(1.dp, NexaColors.Border)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.direct_order_products),
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedButton(
                            onClick = onOpenCatalog,
                            enabled = editable && draft.customerId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.direct_order_choose_catalog_product))
                        }
                        if (state.productId.isNotBlank()) {
                            Text(
                                stringResource(R.string.direct_order_product_selected),
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaColors.TextSecondary
                            )
                        }
                        OutlinedTextField(
                            state.quantity,
                            viewModel::quantityChanged,
                            enabled = editable,
                            label = { Text(stringResource(R.string.direct_order_quantity)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = viewModel::addProduct,
                            enabled = editable && draft.customerId.isNotBlank() &&
                                state.productId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.direct_order_add_product))
                        }
                    }
                }

                if (draft.lines.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NexaShapes.card,
                        colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
                        border = BorderStroke(1.dp, NexaColors.Border)
                    ) {
                        Text(
                            stringResource(R.string.direct_order_empty_lines),
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexaColors.TextSecondary
                        )
                    }
                }
                draft.lines.forEach { line ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NexaShapes.card,
                        colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
                        border = BorderStroke(1.dp, NexaColors.Border)
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                line.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(
                                    R.string.direct_order_quote_details,
                                    line.price,
                                    line.currency,
                                    line.unit
                                        ?: stringResource(R.string.direct_order_unit_unavailable)
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                stringResource(R.string.direct_order_quote_reference),
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaColors.TextSecondary
                            )
                            OutlinedTextField(
                                line.quantity,
                                { viewModel.lineQuantityChanged(line.catalogItemId, it) },
                                enabled = editable,
                                label = {
                                    Text(stringResource(R.string.direct_order_quantity_expected))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            TextButton(
                                onClick = { viewModel.removeLine(line.catalogItemId) },
                                enabled = editable,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.direct_order_remove))
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaShapes.card,
                    colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
                    border = BorderStroke(1.dp, NexaColors.Border)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DirectOrderDateButton(
                            value = draft.deliveryDate,
                            enabled = editable,
                            onDateSelected = viewModel::deliveryDateChanged
                        )
                        if (state.record.intent == null) {
                            when (state.deliveryPolicyStatus) {
                                DirectOrderDeliveryPolicyStatus.Loading ->
                                    Text(stringResource(R.string.direct_order_policy_loading))

                                DirectOrderDeliveryPolicyStatus.Available -> {
                                    state.deliveryLeadPolicy?.let { policy ->
                                        Text(
                                            pluralStringResource(
                                                R.plurals.direct_order_policy_minimum,
                                                policy.minimumDeliveryLeadDays,
                                                formatLocalDate(
                                                    policy.earliestAllowedDeliveryDate
                                                ),
                                                policy.minimumDeliveryLeadDays
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = NexaColors.TextSecondary
                                        )
                                    }
                                    if (state.isDeliveryDateBeforeMinimum()) {
                                        Text(
                                            stringResource(
                                                R.string.direct_order_policy_date_too_early,
                                                state.deliveryLeadPolicy?.let {
                                                    formatLocalDate(
                                                        it.earliestAllowedDeliveryDate
                                                    )
                                                }.orEmpty()
                                            ),
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.semantics {
                                                liveRegion = LiveRegionMode.Polite
                                            }
                                        )
                                    }
                                }

                                DirectOrderDeliveryPolicyStatus.PermissionDenied ->
                                    Text(
                                        stringResource(
                                            R.string.direct_order_policy_permission_denied
                                        )
                                    )

                                DirectOrderDeliveryPolicyStatus.Unavailable -> {
                                    Text(stringResource(R.string.direct_order_policy_unavailable))
                                    TextButton(
                                        onClick = viewModel::refreshDeliveryPolicy,
                                        enabled = editable
                                    ) {
                                        Text(stringResource(R.string.direct_order_policy_retry))
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            draft.deliveryProfile,
                            viewModel::deliveryProfileChanged,
                            enabled = editable,
                            label = {
                                Text(stringResource(R.string.direct_order_delivery_instructions))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaShapes.card,
                    colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
                    border = BorderStroke(1.dp, NexaColors.Border)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            stringResource(R.string.direct_order_payment_details),
                            style = MaterialTheme.typography.titleMedium
                        )
                        val paymentOptions = listOf(
                            "CASH_ON_DELIVERY" to R.string.direct_order_payment_cash_on_delivery,
                            "CREDIT_LINE" to R.string.direct_order_payment_credit_line,
                            "BANK_TRANSFER" to R.string.direct_order_payment_bank_transfer,
                            "PREPAID" to R.string.direct_order_payment_prepaid
                        )
                        val selectedPayment = paymentOptions.firstOrNull {
                            it.first == draft.paymentOption
                        }
                        Text(
                            stringResource(
                                R.string.direct_order_payment_option,
                                selectedPayment?.second?.let { stringResource(it) }
                                    ?: stringResource(SharedR.string.commercial_catalog_missing)
                            )
                        )
                        paymentOptions.forEach { (option, label) ->
                            TextButton(
                                onClick = { viewModel.paymentChanged(option) },
                                enabled = editable,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(label))
                            }
                        }
                        OutlinedTextField(
                            draft.comment,
                            viewModel::commentChanged,
                            enabled = editable,
                            label = { Text(stringResource(R.string.direct_order_comment)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Button(
                    onClick = viewModel::review,
                    enabled = editable && state.canReviewDirectOrder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.direct_order_review))
                }
                if (state.status == DirectOrderStatus.Changed) {
                    Button(
                        onClick = viewModel::acceptChangedInformation,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.direct_order_accept_changed))
                    }
                }
                if (state.status == DirectOrderStatus.Reviewed) {
                    Button(onClick = viewModel::submit, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.direct_order_submit))
                    }
                }
                if (state.status == DirectOrderStatus.UnknownOutcome) {
                    Button(
                        onClick = viewModel::retryUnknownOutcome,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(SharedR.string.same_submission_retry))
                    }
                }
                if (state.status.permitsNewDecision) {
                    Button(
                        onClick = viewModel::startNewDecision,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.direct_order_new_decision))
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectOrderDateButton(
    value: String,
    enabled: Boolean,
    onDateSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val selected = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now() }
    OutlinedButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    onDateSelected(LocalDate.of(year, month + 1, day).toString())
                },
                selected.year,
                selected.monthValue - 1,
                selected.dayOfMonth
            ).show()
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        val formatted = runCatching { formatLocalDate(LocalDate.parse(value)) }.getOrNull()
        Text(
            if (formatted == null) {
                stringResource(R.string.direct_order_select_delivery_date)
            } else {
                stringResource(
                    R.string.direct_order_delivery_date_value,
                    stringResource(R.string.direct_order_delivery_date),
                    formatted
                )
            }
        )
    }
}

private fun formatLocalDate(date: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(date)

@Composable
private fun directOrderStatus(state: DirectOrderState): String = when (state.status) {
    DirectOrderStatus.Confirmed -> stringResource(R.string.direct_order_status_confirmed)

    DirectOrderStatus.PrepaidPending -> stringResource(
        R.string.direct_order_status_prepaid_pending
    )

    DirectOrderStatus.Rejected -> stringResource(R.string.direct_order_status_rejected)

    DirectOrderStatus.LegacyIntent -> stringResource(R.string.direct_order_status_legacy)

    DirectOrderStatus.UnknownOutcome -> stringResource(R.string.direct_order_status_unknown)

    DirectOrderStatus.Conflict -> stringResource(R.string.direct_order_status_conflict)

    DirectOrderStatus.Changed -> stringResource(R.string.direct_order_status_changed)

    DirectOrderStatus.Reviewed -> stringResource(R.string.direct_order_status_reviewed)

    DirectOrderStatus.MetadataUnavailable ->
        stringResource(R.string.direct_order_status_storage_unavailable)

    DirectOrderStatus.PermissionDenied ->
        stringResource(R.string.direct_order_status_permission_denied)

    DirectOrderStatus.Unavailable -> stringResource(R.string.direct_order_status_unavailable)

    DirectOrderStatus.Pending -> stringResource(R.string.direct_order_status_pending)

    DirectOrderStatus.Reviewing -> stringResource(R.string.direct_order_status_reviewing)

    DirectOrderStatus.Loading -> stringResource(R.string.direct_order_status_loading)

    DirectOrderStatus.Draft -> stringResource(R.string.direct_order_status_draft)
}
