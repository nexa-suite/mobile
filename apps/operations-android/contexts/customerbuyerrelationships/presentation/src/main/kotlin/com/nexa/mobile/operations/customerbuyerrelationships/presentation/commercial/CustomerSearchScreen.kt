package com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial

import com.nexa.mobile.operations.core.designsystem.R as SharedR

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
import com.nexa.mobile.operations.customerbuyerrelationships.presentation.R
import com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial.CustomerSearchStatus

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
    LazyColumn(
        Modifier.fillMaxSize().semantics { isTraversalGroup = true }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(SharedR.string.commercial_back)) }
            Text(
                stringResource(R.string.customer_title),
                style = MaterialTheme.typography.headlineSmall
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                label = {
                    Text(stringResource(R.string.customer_search_label))
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSearch, enabled = state.status != CustomerSearchStatus.Loading) {
                Text(stringResource(SharedR.string.customer_search))
            }
            Text(
                stringResource(
                    when (state.status) {
                        CustomerSearchStatus.Idle -> R.string.customer_idle
                        CustomerSearchStatus.Loading -> R.string.customer_loading
                        CustomerSearchStatus.Current -> R.string.customer_current
                        CustomerSearchStatus.Unavailable -> R.string.customer_unavailable
                        CustomerSearchStatus.PermissionDenied -> R.string.customer_denied
                    }
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            state.receivedAt?.let {
                Text(stringResource(R.string.customer_received, it.toString()))
            }
        }
        if (state.status == CustomerSearchStatus.Current) {
            state.detail?.let { customer ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(customer.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.customer_code, customer.code))
                            customer.commercialName?.let {
                                Text(stringResource(R.string.customer_commercial_name, it))
                            }
                            Text(
                                stringResource(
                                    if (customer.active) {
                                        R.string.customer_active
                                    } else {
                                        R.string.customer_suspended
                                    }
                                )
                            )
                            Text(
                                stringResource(
                                    if (customer.buyerLinked) {
                                        R.string.customer_buyer_linked
                                    } else {
                                        R.string.customer_no_buyer
                                    }
                                )
                            )
                            Text(stringResource(SharedR.string.customer_version, customer.version))
                            customer.contactPerson?.let {
                                Text(stringResource(R.string.customer_contact_person, it))
                            }
                            customer.email?.let { Text(stringResource(R.string.customer_email, it)) }
                            customer.phone?.let { Text(stringResource(R.string.customer_phone, it)) }
                            if (customer.active && onPrepareRequest != null) {
                                TextButton(onClick = { onPrepareRequest(customer.id) }) {
                                    Text(stringResource(SharedR.string.customer_prepare_request))
                                }
                            }
                            if (customer.active && onReviewProducts != null) {
                                TextButton(onClick = { onReviewProducts(customer.id) }) {
                                    Text(stringResource(SharedR.string.commercial_catalog_title))
                                }
                            }
                            if (customer.active && onReviewProgress != null) {
                                TextButton(onClick = { onReviewProgress(customer.id) }) {
                                    Text(stringResource(SharedR.string.progress_title))
                                }
                            }
                        }
                    }
                }
            }
            if (state.items.isEmpty()) item { Text(stringResource(R.string.customer_empty)) }
            items(state.items, key = { it.id }) { customer ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(customer.name)
                        Text(stringResource(R.string.customer_code, customer.code))
                        Text(
                            stringResource(
                                if (customer.active) {
                                    R.string.customer_active
                                } else {
                                    R.string.customer_suspended
                                }
                            )
                        )
                        TextButton(onClick = {
                            onSelectCustomer(customer.id)
                        }) { Text(stringResource(R.string.customer_open)) }
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
    }
}
