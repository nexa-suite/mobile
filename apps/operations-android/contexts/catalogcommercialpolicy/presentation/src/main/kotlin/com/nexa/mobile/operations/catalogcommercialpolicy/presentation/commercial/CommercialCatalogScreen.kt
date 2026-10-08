package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial

import com.nexa.mobile.operations.core.designsystem.R as SharedR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.nexa.mobile.operations.core.designsystem.NexaCatalogImage
import com.nexa.mobile.operations.core.designsystem.NexaProductCandidateRow
import com.nexa.mobile.operations.core.designsystem.NexaSizes
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.R
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial.CommercialCatalogStatus

@Composable
fun CommercialCatalogScreen(
    state: CommercialCatalogState,
    onBack: () -> Unit,
    onCustomerIdChanged: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onNextPage: () -> Unit,
    onSelectProduct: (String) -> Unit,
    onRouteClosed: () -> Unit,
    onPrepareRequest: ((String, String) -> Unit)? = null
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
                stringResource(SharedR.string.commercial_catalog_title),
                style = MaterialTheme.typography.headlineSmall
            )
            OutlinedTextField(state.customerId, onCustomerIdChanged, label = {
                Text(stringResource(SharedR.string.progress_customer_reference))
            }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.query, onQueryChanged, label = {
                Text(stringResource(R.string.commercial_catalog_query))
            }, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSearch, enabled = state.status != CommercialCatalogStatus.Pending) {
                Text(stringResource(SharedR.string.customer_search))
            }
            Text(
                stringResource(
                    when (state.status) {
                        CommercialCatalogStatus.Idle -> R.string.commercial_catalog_idle
                        CommercialCatalogStatus.Pending -> SharedR.string.progress_pending
                        CommercialCatalogStatus.Current -> SharedR.string.progress_current
                        CommercialCatalogStatus.Unavailable -> SharedR.string.progress_unavailable
                        CommercialCatalogStatus.PermissionDenied -> SharedR.string.progress_denied
                    }
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (state.status == CommercialCatalogStatus.Current) {
            state.product?.let { product ->
                item {
                    val price = product.price
                    val currency = product.currency
                    val sellableAvailability = product.sellableAvailability
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NexaCatalogImage(
                                fileName = product.imageFileName,
                                modifier = Modifier.size(NexaSizes.catalogDetailImage),
                                targetSize = NexaSizes.catalogDetailImage
                            )
                            Text(product.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    R.string.commercial_catalog_product,
                                    product.productId
                                )
                            )
                            Text(
                                stringResource(
                                    R.string.commercial_catalog_sku,
                                    product.skuCode
                                        ?: stringResource(SharedR.string.commercial_catalog_missing)
                                )
                            )
                            product.unit?.let {
                                Text(stringResource(R.string.commercial_catalog_unit, it))
                            }
                            Text(
                                if (price != null && currency != null) {
                                    stringResource(
                                        R.string.commercial_catalog_price,
                                        price,
                                        currency
                                    )
                                } else {
                                    stringResource(R.string.commercial_catalog_price_missing)
                                }
                            )
                            product.pricingAsOf?.let {
                                Text(stringResource(SharedR.string.progress_source_time, it))
                            }
                            Text(
                                if (sellableAvailability != null) {
                                    stringResource(
                                        R.string.commercial_catalog_sellable,
                                        sellableAvailability
                                    )
                                } else {
                                    stringResource(R.string.commercial_catalog_availability_missing)
                                }
                            )
                            product.availabilityStatus?.let {
                                Text(
                                    stringResource(
                                        R.string.commercial_catalog_availability_status,
                                        it
                                    )
                                )
                            }
                            product.availabilityAsOf?.let {
                                Text(stringResource(SharedR.string.progress_source_time, it))
                            }
                            Text(stringResource(R.string.commercial_catalog_no_commitment))
                        }
                    }
                }
            }
            if (state.choices.isEmpty() &&
                state.product == null
            ) {
                item { Text(stringResource(R.string.commercial_catalog_empty)) }
            }
            items(state.choices, key = { it.id }) { choice ->
                NexaProductCandidateRow(
                    name = choice.name,
                    variant = null,
                    presentation = null,
                    sku = choice.skuCode,
                    imageFileName = choice.imageFileName,
                    onClick = { onSelectProduct(choice.id) }
                )
            }
            if (state.nextPage !=
                null
            ) {
                item {
                    TextButton(onClick = onNextPage) {
                        Text(stringResource(SharedR.string.customer_next))
                    }
                }
            }
        }
        if (state.status == CommercialCatalogStatus.Current && state.product != null &&
            onPrepareRequest != null
        ) {
            item {
                TextButton(onClick = {
                    onPrepareRequest(state.customerId, state.product.catalogItemId)
                }) {
                    Text(stringResource(SharedR.string.customer_prepare_request))
                }
            }
        }
    }
}
