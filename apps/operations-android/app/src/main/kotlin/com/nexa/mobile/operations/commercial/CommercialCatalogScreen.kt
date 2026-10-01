package com.nexa.mobile.operations.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.R

@Composable
fun CommercialCatalogScreen(state: CommercialCatalogState, onBack: () -> Unit,
    onCustomerIdChanged: (String) -> Unit, onQueryChanged: (String) -> Unit, onSearch: () -> Unit,
    onNextPage: () -> Unit, onSelectProduct: (String) -> Unit, onRouteClosed: () -> Unit, onPrepareRequest: ((String, String) -> Unit)? = null) {
    val close = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { close.value() } }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.commercial_back)) }
            Text(stringResource(R.string.commercial_catalog_title), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(state.customerId, onCustomerIdChanged, label = { Text(stringResource(R.string.progress_customer_reference)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.query, onQueryChanged, label = { Text(stringResource(R.string.commercial_catalog_query)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSearch, enabled = state.status != CommercialCatalogStatus.Pending) { Text(stringResource(R.string.customer_search)) }
            Text(stringResource(when (state.status) {
                CommercialCatalogStatus.Idle -> R.string.commercial_catalog_idle
                CommercialCatalogStatus.Pending -> R.string.progress_pending
                CommercialCatalogStatus.Current -> R.string.progress_current
                CommercialCatalogStatus.Unavailable -> R.string.progress_unavailable
                CommercialCatalogStatus.PermissionDenied -> R.string.progress_denied
            }))
        }
        if (state.status == CommercialCatalogStatus.Current) {
            state.product?.let { product -> item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.commercial_catalog_product, product.productId))
                    Text(stringResource(R.string.commercial_catalog_sku, product.skuCode ?: stringResource(R.string.commercial_catalog_missing)))
                    product.unit?.let { Text(it) }
                    Text(if (product.price != null && product.currency != null)
                        stringResource(R.string.commercial_catalog_price, product.price, product.currency)
                        else stringResource(R.string.commercial_catalog_price_missing))
                    product.pricingAsOf?.let { Text(stringResource(R.string.progress_source_time, it)) }
                    Text(if (product.sellableAvailability != null)
                        stringResource(R.string.commercial_catalog_sellable, product.sellableAvailability)
                        else stringResource(R.string.commercial_catalog_availability_missing))
                    product.availabilityStatus?.let { Text(it) }
                    product.availabilityAsOf?.let { Text(stringResource(R.string.progress_source_time, it)) }
                    Text(stringResource(R.string.commercial_catalog_no_commitment))
                } }
            } }
            if (state.choices.isEmpty() && state.product == null) item { Text(stringResource(R.string.commercial_catalog_empty)) }
            items(state.choices, key = { it.id }) { choice ->
                TextButton(onClick = { onSelectProduct(choice.id) }) { Text("${choice.name} · ${choice.skuCode}") }
            }
            if (state.nextPage != null) item { TextButton(onClick = onNextPage) { Text(stringResource(R.string.customer_next)) } }
        }
        if (state.status == CommercialCatalogStatus.Current && state.product != null && onPrepareRequest != null) {
            item {
                TextButton(onClick = { onPrepareRequest(state.customerId, state.product.catalogItemId) }) {
                    Text(stringResource(R.string.customer_prepare_request))
                }
            }
        }

    }
}
