package com.nexa.mobile.operations.salescommitment.presentation.commercial

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as SharedR
import com.nexa.mobile.operations.salescommitment.application.commercial.ProgressStatus
import com.nexa.mobile.operations.salescommitment.presentation.R
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("UNUSED_PARAMETER")
fun CustomerProgressScreen(
    state: CustomerProgressState,
    onBack: () -> Unit,
    onCustomerIdChanged: (String) -> Unit,
    onCurrencyChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onRouteClosed: () -> Unit,
    onOpenDeliveryInstructions: ((String) -> Unit)? = null,
    onChooseCustomer: (() -> Unit)? = null
) {
    val close = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { close.value() } }
    Surface(modifier = Modifier.fillMaxSize(), color = NexaColors.Canvas) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            NexaTopAppBar(title = stringResource(R.string.progress_title), onBack = onBack)
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .semantics { isTraversalGroup = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
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
                            val customerName = state.customerName?.takeIf { it.isNotBlank() }
                            if (customerName != null) {
                                Text(
                                    customerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexaColors.TextPrimary
                                )
                            } else if (state.customerId.isBlank()) {
                                Text(
                                    stringResource(R.string.progress_all_orders),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NexaColors.TextSecondary
                                )
                            } else if (state.commitmentsStatus != ProgressStatus.Pending) {
                                Text(
                                    stringResource(R.string.progress_customer_name_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NexaColors.TextSecondary
                                )
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                onChooseCustomer?.let { chooseCustomer ->
                                    OutlinedButton(
                                        onClick = chooseCustomer,
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Text(
                                            stringResource(
                                                if (state.customerId.isBlank()) {
                                                    R.string.progress_choose_customer
                                                } else {
                                                    R.string.progress_change_customer
                                                }
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = onRefresh,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    enabled = state.commitmentsStatus != ProgressStatus.Pending &&
                                        state.creditStatus != ProgressStatus.Pending
                                ) {
                                    Text(
                                        stringResource(R.string.progress_refresh),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (state.commitmentsStatus != ProgressStatus.Current) {
                                Text(
                                    stringResource(state.commitmentsStatus.labelResource()),
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NexaColors.TextSecondary
                                )
                            }
                        }
                    }
                }

                if (state.commitmentsStatus == ProgressStatus.Current) {
                    if (state.commitments.isEmpty()) {
                        item { ProgressEmptyCard(R.string.progress_no_orders) }
                    }
                    items(state.commitments, key = { it.id }) { order ->
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        order.number,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NexaColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    OrderStatusPill(order.status)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        stringResource(R.string.progress_total_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NexaColors.TextSecondary
                                    )
                                    Text(
                                        formatOrderCurrency(order.total, order.currency),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NexaColors.TextPrimary
                                    )
                                }
                                onOpenDeliveryInstructions?.let { open ->
                                    TextButton(
                                        onClick = { open(order.id) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.customer_progress_open_instructions
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = onPreviousPage, enabled = state.page > 0) {
                                Text(stringResource(SharedR.string.customer_previous))
                            }
                            TextButton(
                                onClick = onNextPage,
                                enabled = (state.page + 1L) * 25 < (state.total ?: 0)
                            ) {
                                Text(stringResource(SharedR.string.customer_next))
                            }
                        }
                    }
                }

                if (state.customerId.isNotBlank()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.progress_credit),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = NexaColors.TextPrimary
                            )
                            OutlinedTextField(
                                value = state.currency,
                                onValueChange = onCurrencyChanged,
                                label = { Text(stringResource(R.string.progress_currency)) },
                                modifier = Modifier.width(112.dp),
                                singleLine = true
                            )
                        }
                    }
                    if (state.creditStatus != ProgressStatus.Current) {
                        item {
                            Text(
                                stringResource(state.creditStatus.labelResource()),
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaColors.TextSecondary
                            )
                        }
                    }
                    if (state.creditStatus == ProgressStatus.Current) {
                        state.credit?.let { credit ->
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = NexaShapes.card,
                                    colors = CardDefaults.cardColors(
                                        containerColor = NexaColors.Surface
                                    ),
                                    border = BorderStroke(1.dp, NexaColors.Border)
                                ) {
                                    Column(
                                        Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ProgressValue(
                                                modifier = Modifier.weight(1f),
                                                value = stringResource(
                                                    R.string.progress_credit_limit,
                                                    credit.limit,
                                                    credit.currency
                                                )
                                            )
                                            ProgressValue(
                                                modifier = Modifier.weight(1f),
                                                value = stringResource(
                                                    R.string.progress_available,
                                                    credit.available,
                                                    credit.currency
                                                )
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ProgressValue(
                                                modifier = Modifier.weight(1f),
                                                value = stringResource(
                                                    R.string.progress_ledger,
                                                    credit.ledgerExposure,
                                                    credit.currency
                                                )
                                            )
                                            ProgressValue(
                                                modifier = Modifier.weight(1f),
                                                value = stringResource(
                                                    R.string.progress_outstanding,
                                                    credit.outstanding,
                                                    credit.currency
                                                )
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ProgressValue(
                                                modifier = Modifier.weight(1f),
                                                value = stringResource(
                                                    R.string.progress_reserved,
                                                    credit.reserved,
                                                    credit.currency
                                                )
                                            )
                                            ProgressValue(
                                                modifier = Modifier.weight(1f),
                                                value = stringResource(
                                                    R.string.progress_used,
                                                    credit.used,
                                                    credit.currency
                                                )
                                            )
                                        }
                                        Text(
                                            stringResource(
                                                if (credit.active) {
                                                    R.string.progress_credit_active
                                                } else {
                                                    R.string.progress_credit_inactive
                                                }
                                            ),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (credit.active) {
                                                NexaColors.Success
                                            } else {
                                                NexaColors.Warning
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressValue(modifier: Modifier, value: String) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = NexaColors.TextPrimary
        )
    }
}

@Composable
private fun ProgressEmptyCard(message: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaShapes.card,
        colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
        border = BorderStroke(1.dp, NexaColors.Border)
    ) {
        Text(
            stringResource(message),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = NexaColors.TextSecondary
        )
    }
}

@Composable
private fun OrderStatusPill(status: String) {
    val normalized = status.uppercase(Locale.ROOT)
    val labelResource = when (normalized) {
        "PENDING" -> R.string.progress_status_pending_order
        "CONFIRMED" -> R.string.progress_status_confirmed
        "IN_FULFILLMENT" -> R.string.progress_status_in_fulfillment
        "PARTIALLY_FULFILLED" -> R.string.progress_status_partially_fulfilled
        "FULFILLED" -> R.string.progress_status_fulfilled
        "PARTIALLY_DELIVERED" -> R.string.progress_status_partially_delivered
        "COMPLETED" -> R.string.progress_status_completed
        "REJECTED" -> R.string.progress_status_rejected
        "CANCELLED" -> R.string.progress_status_cancelled
        else -> null
    }
    val (background, foreground) = when (normalized) {
        "PENDING", "PARTIALLY_FULFILLED", "PARTIALLY_DELIVERED" ->
            NexaColors.WarningSurface to NexaColors.Warning

        "CONFIRMED", "IN_FULFILLMENT" -> NexaColors.InfoSurface to NexaColors.Info

        "FULFILLED", "COMPLETED" ->
            NexaColors.SuccessSurface to NexaColors.Success

        "REJECTED", "CANCELLED" -> NexaColors.DangerSurface to NexaColors.Danger

        else -> NexaColors.SurfaceInset to NexaColors.TextSecondary
    }
    Surface(color = background, shape = NexaShapes.pill) {
        Text(
            text = labelResource?.let { stringResource(it) } ?: status.humanLabel(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = foreground
        )
    }
}

private fun formatOrderCurrency(amountText: String, currencyCode: String): String {
    val amount = amountText.toBigDecimalOrNull() ?: return "$amountText $currencyCode"
    val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull()
        ?: return "${amount.stripTrailingZeros().toPlainString()} $currencyCode"
    val minimumDigits = currency.defaultFractionDigits.coerceAtLeast(0)
    val actualDigits = amount.stripTrailingZeros().scale().coerceAtLeast(0)
    return runCatching {
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            this.currency = currency
            minimumFractionDigits = minimumDigits
            maximumFractionDigits = maxOf(minimumDigits, actualDigits)
            roundingMode = RoundingMode.UNNECESSARY
        }.format(amount)
    }.getOrElse {
        "${amount.stripTrailingZeros().toPlainString()} $currencyCode"
    }
}

private fun ProgressStatus.labelResource(): Int = when (this) {
    ProgressStatus.Idle -> R.string.progress_idle
    ProgressStatus.Pending -> SharedR.string.progress_pending
    ProgressStatus.Current -> SharedR.string.progress_current
    ProgressStatus.Unavailable -> SharedR.string.progress_unavailable
    ProgressStatus.PermissionDenied -> SharedR.string.progress_denied
}

private fun String.humanLabel(): String = trim()
    .replace('_', ' ')
    .lowercase(Locale.getDefault())
    .replaceFirstChar { character ->
        if (character.isLowerCase()) {
            character.titlecase(
                Locale.getDefault()
            )
        } else {
            character.toString()
        }
    }
