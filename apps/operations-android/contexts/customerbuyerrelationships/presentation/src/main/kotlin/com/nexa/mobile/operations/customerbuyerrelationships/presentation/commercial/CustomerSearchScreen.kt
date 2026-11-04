package com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as SharedR
import com.nexa.mobile.operations.customerbuyerrelationships.presentation.R

@Composable
fun CustomerSearchScreen(
    state: CustomerSearchState,
    onBack: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectCustomer: (String) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onRouteClosed: () -> Unit,
    onReviewProgress: ((String) -> Unit)? = null,
    onReviewProducts: ((String) -> Unit)? = null,
    onPrepareRequest: ((String) -> Unit)? = null
) {
    val close = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { close.value() } }
    Surface(modifier = Modifier.fillMaxSize(), color = NexaColors.Canvas) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            NexaTopAppBar(title = stringResource(R.string.customer_title), onBack = onBack)
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
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = onQueryChanged,
                                label = { Text(stringResource(R.string.customer_search_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Button(
                                onClick = onSearch,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = state.status != CustomerSearchStatus.Loading &&
                                    state.status != CustomerSearchStatus.ContextInvalidated
                            ) {
                                Text(stringResource(SharedR.string.customer_search))
                            }
                            Text(
                                stringResource(state.statusLabelResource()),
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaColors.TextSecondary
                            )
                        }
                    }
                }

                if (state.status == CustomerSearchStatus.Current) {
                    state.detail?.let { customer ->
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
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        customer.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NexaColors.TextPrimary
                                    )
                                    Text(
                                        stringResource(R.string.customer_code, customer.code),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NexaColors.TextSecondary
                                    )
                                    customer.commercialName?.let {
                                        Text(
                                            stringResource(R.string.customer_commercial_name, it),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Text(
                                        stringResource(
                                            if (customer.active) {
                                                R.string.customer_active
                                            } else {
                                                R.string.customer_suspended
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (customer.active) {
                                            NexaColors.Success
                                        } else {
                                            NexaColors.Warning
                                        }
                                    )
                                    Text(
                                        stringResource(
                                            if (customer.buyerLinked) {
                                                R.string.customer_buyer_linked
                                            } else {
                                                R.string.customer_no_buyer
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NexaColors.TextSecondary
                                    )
                                    customer.contactPerson?.let {
                                        Text(stringResource(R.string.customer_contact_person, it))
                                    }
                                    customer.email?.let {
                                        Text(stringResource(R.string.customer_email, it))
                                    }
                                    customer.phone?.let {
                                        Text(stringResource(R.string.customer_phone, it))
                                    }
                                    if (customer.active && onPrepareRequest != null) {
                                        TextButton(
                                            onClick = { onPrepareRequest(customer.id) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                stringResource(
                                                    SharedR.string.customer_prepare_request
                                                )
                                            )
                                        }
                                    }
                                    if (customer.active && onReviewProducts != null) {
                                        TextButton(
                                            onClick = { onReviewProducts(customer.id) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                stringResource(
                                                    SharedR.string.commercial_catalog_title
                                                )
                                            )
                                        }
                                    }
                                    if (customer.active && onReviewProgress != null) {
                                        TextButton(
                                            onClick = { onReviewProgress(customer.id) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(stringResource(SharedR.string.progress_title))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.items.isEmpty() && state.detail == null) {
                        item {
                            CustomerEmptyCard()
                        }
                    }

                    items(state.items, key = { it.id }) { customer ->
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
                                    customer.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexaColors.TextPrimary
                                )
                                Text(
                                    stringResource(R.string.customer_code, customer.code),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NexaColors.TextSecondary
                                )
                                Text(
                                    stringResource(
                                        if (customer.active) {
                                            R.string.customer_active
                                        } else {
                                            R.string.customer_suspended
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (customer.active) {
                                        NexaColors.Success
                                    } else {
                                        NexaColors.Warning
                                    }
                                )
                                TextButton(
                                    onClick = { onSelectCustomer(customer.id) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.customer_open))
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
            }
        }
    }
}

@Composable
private fun CustomerEmptyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaShapes.card,
        colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
        border = BorderStroke(1.dp, NexaColors.Border)
    ) {
        Text(
            stringResource(R.string.customer_empty),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = NexaColors.TextSecondary
        )
    }
}

private fun CustomerSearchState.statusLabelResource(): Int = when (status) {
    CustomerSearchStatus.Idle -> R.string.customer_idle
    CustomerSearchStatus.Loading -> R.string.customer_loading
    CustomerSearchStatus.Current -> SharedR.string.progress_current
    CustomerSearchStatus.Unavailable -> R.string.customer_unavailable
    CustomerSearchStatus.PermissionDenied -> R.string.customer_denied
    CustomerSearchStatus.ContextInvalidated -> R.string.customer_context_invalidated
}
