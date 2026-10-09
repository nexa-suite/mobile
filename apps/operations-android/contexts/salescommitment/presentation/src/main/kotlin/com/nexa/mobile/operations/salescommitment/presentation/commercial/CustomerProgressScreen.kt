package com.nexa.mobile.operations.salescommitment.presentation.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.R as SharedR
import com.nexa.mobile.operations.salescommitment.application.commercial.ProgressStatus
import com.nexa.mobile.operations.salescommitment.presentation.R

@Composable
fun CustomerProgressScreen(
    state: CustomerProgressState,
    onBack: () -> Unit,
    onCustomerIdChanged: (String) -> Unit,
    onCurrencyChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onRouteClosed: () -> Unit,
    onOpenDeliveryInstructions: ((String) -> Unit)? = null
) {
    val close = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { close.value() } }
    LazyColumn(
        Modifier.fillMaxSize().semantics { isTraversalGroup = true }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(SharedR.string.commercial_back)) }
            Text(
                stringResource(SharedR.string.progress_title),
                style = MaterialTheme.typography.headlineSmall
            )
            OutlinedTextField(state.customerId, onCustomerIdChanged, label = {
                Text(stringResource(SharedR.string.progress_customer_reference))
            }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.currency, onCurrencyChanged, label = {
                Text(stringResource(R.string.progress_currency))
            })
            Button(
                onClick = onRefresh,
                enabled =
                    state.commitmentsStatus != ProgressStatus.Pending &&
                        state.creditStatus != ProgressStatus.Pending
            ) {
                Text(stringResource(R.string.progress_refresh))
            }
            state.customerName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            state.receivedAt?.let {
                Text(stringResource(R.string.progress_received, it.toString()))
            }
            Text(
                stringResource(R.string.progress_commitments),
                style = MaterialTheme.typography.titleMedium
            )
            ProgressStateLabel(state.commitmentsStatus)
        }
        if (state.commitmentsStatus == ProgressStatus.Current) {
            if (state.commitments.isEmpty()) {
                item {
                    Text(stringResource(R.string.progress_no_orders))
                }
            }
            items(state.commitments, key = { it.id }) { order ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.progress_commitment_number, order.number))
                        Text(stringResource(R.string.progress_commitment_status, order.status))
                        Text(
                            stringResource(
                                R.string.progress_commitment_total,
                                order.total,
                                order.currency
                            )
                        )
                        Text(stringResource(SharedR.string.customer_version, order.version))
                        Text(stringResource(SharedR.string.progress_source_time, order.updatedAt))
                        onOpenDeliveryInstructions?.let { open ->
                            TextButton(onClick = {
                                open(order.id)
                            }) {
                                Text(stringResource(R.string.customer_progress_open_instructions))
                            }
                        }
                    }
                }
            }
            item {
                Row {
                    TextButton(onClick = onPreviousPage, enabled = state.page > 0) {
                        Text(stringResource(SharedR.string.customer_previous))
                    }
                    TextButton(
                        onClick = onNextPage,
                        enabled =
                            (state.page + 1L) * 25 < (state.total ?: 0)
                    ) {
                        Text(stringResource(SharedR.string.customer_next))
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.progress_credit),
                style = MaterialTheme.typography.titleMedium
            )
            ProgressStateLabel(state.creditStatus)
        }
        if (state.creditStatus == ProgressStatus.Current) {
            state.credit?.let { credit ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                stringResource(
                                    R.string.progress_credit_limit,
                                    credit.limit,
                                    credit.currency
                                )
                            )
                            Text(
                                stringResource(
                                    R.string.progress_ledger,
                                    credit.ledgerExposure,
                                    credit.currency
                                )
                            )
                            Text(
                                stringResource(
                                    R.string.progress_outstanding,
                                    credit.outstanding,
                                    credit.currency
                                )
                            )
                            Text(
                                stringResource(
                                    R.string.progress_reserved,
                                    credit.reserved,
                                    credit.currency
                                )
                            )
                            Text(
                                stringResource(R.string.progress_used, credit.used, credit.currency)
                            )
                            Text(
                                stringResource(
                                    R.string.progress_available,
                                    credit.available,
                                    credit.currency
                                )
                            )
                            Text(
                                stringResource(
                                    if (credit.active) {
                                        R.string.progress_credit_active
                                    } else {
                                        R.string.progress_credit_inactive
                                    }
                                )
                            )
                            Text(stringResource(SharedR.string.progress_source_time, credit.asOf))
                            Text(stringResource(R.string.progress_no_decision))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ProgressStateLabel(status: ProgressStatus) {
    Text(
        stringResource(
            when (status) {
                ProgressStatus.Idle -> R.string.progress_idle
                ProgressStatus.Pending -> SharedR.string.progress_pending
                ProgressStatus.Current -> SharedR.string.progress_current
                ProgressStatus.Unavailable -> SharedR.string.progress_unavailable
                ProgressStatus.PermissionDenied -> SharedR.string.progress_denied
            }
        ),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}
