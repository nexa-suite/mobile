package com.nexa.mobile.operations.salescommitment.presentation.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraftLine
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestProductFacts
import com.nexa.mobile.operations.salescommitment.presentation.R

@Composable
fun FieldRequestDraftScreen(
    state: FieldRequestDraftState,
    onBack: () -> Unit,
    onCustomerChanged: (String) -> Unit,
    onOpenCatalog: () -> Unit,
    onQuantityChanged: (String, String) -> Unit,
    onRemoveProduct: (String) -> Unit,
    onReview: () -> Unit,
    onRouteClosed: () -> Unit
) {
    val close = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { close.value() } }
    LazyColumn(
        Modifier.fillMaxSize().semantics { isTraversalGroup = true }
            .imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(SharedR.string.commercial_back)) }
            Text(
                stringResource(R.string.field_request_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(stringResource(R.string.field_request_no_commitment))
            OutlinedTextField(
                state.draft.customerId,
                onCustomerChanged,
                label = { Text(stringResource(R.string.field_request_customer)) },
                modifier = Modifier.fillMaxWidth()
            )
            state.draft.customerName?.let {
                Text(stringResource(R.string.field_request_customer_snapshot, it))
            }
            state.currentCustomer?.let { current ->
                Text(stringResource(R.string.field_request_customer_current, current.businessName))
                if (state.customerChanged) {
                    Text(stringResource(R.string.field_request_customer_changed))
                }
            }
            Text(
                stringResource(
                    when (state.status) {
                        FieldRequestDraftStatus.Loading -> R.string.field_request_loading

                        FieldRequestDraftStatus.Saved -> R.string.field_request_saved

                        FieldRequestDraftStatus.Saving -> R.string.field_request_saving

                        FieldRequestDraftStatus.AddingProduct ->
                            R.string.field_request_adding_product

                        FieldRequestDraftStatus.Reviewing -> R.string.field_request_reviewing

                        FieldRequestDraftStatus.Reviewed -> R.string.field_request_reviewed

                        FieldRequestDraftStatus.ReviewUnavailable ->
                            R.string.field_request_review_unavailable

                        FieldRequestDraftStatus.ProductUnavailable ->
                            R.string.field_request_product_unavailable

                        FieldRequestDraftStatus.MetadataUnavailable ->
                            R.string.field_request_storage_unavailable
                    }
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Button(
                onClick = onOpenCatalog,
                enabled = state.draft.customerId.isNotBlank() &&
                    state.status !in setOf(
                        FieldRequestDraftStatus.Loading,
                        FieldRequestDraftStatus.AddingProduct,
                        FieldRequestDraftStatus.Reviewing
                    )
            ) {
                Text(stringResource(R.string.field_request_open_catalog))
            }
            Button(
                onClick = onReview,
                enabled = state.draft.customerId.isNotBlank() &&
                    state.status !in setOf(
                        FieldRequestDraftStatus.Loading,
                        FieldRequestDraftStatus.AddingProduct,
                        FieldRequestDraftStatus.Reviewing
                    )
            ) {
                Text(stringResource(R.string.field_request_review))
            }
        }
        items(state.draft.lines, key = { it.catalogItemId }) { line ->
            FieldRequestDraftLineCard(
                line = line,
                current = state.currentProducts[line.catalogItemId],
                changed = line.catalogItemId in state.changedProducts,
                onQuantityChanged = { onQuantityChanged(line.catalogItemId, it) },
                onRemove = { onRemoveProduct(line.catalogItemId) }
            )
        }
        if (state.draft.lines.isEmpty()) {
            item { Text(stringResource(R.string.field_request_empty)) }
        }
    }
}

@Composable
private fun FieldRequestDraftLineCard(
    line: FieldRequestDraftLine,
    current: FieldRequestProductFacts?,
    changed: Boolean,
    onQuantityChanged: (String) -> Unit,
    onRemove: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(line.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.field_request_product_reference, line.catalogItemId))
            line.brandName?.let { Text(stringResource(R.string.field_request_brand, it)) }
            line.categoryName?.let { Text(stringResource(R.string.field_request_type, it)) }
            line.variantName?.let { Text(stringResource(R.string.field_request_variety, it)) }
            line.familyName?.let { Text(stringResource(R.string.field_request_group, it)) }
            Text(
                stringResource(
                    R.string.field_request_saved_price,
                    line.price ?: stringResource(SharedR.string.commercial_catalog_missing),
                    line.currency ?: "",
                    line.pricingAsOf ?: stringResource(SharedR.string.commercial_catalog_missing)
                )
            )
            Text(
                stringResource(
                    R.string.field_request_saved_availability,
                    line.availability ?: stringResource(SharedR.string.commercial_catalog_missing),
                    line.availabilityStatus
                        ?: stringResource(SharedR.string.commercial_catalog_missing),
                    line.availabilityAsOf
                        ?: stringResource(SharedR.string.commercial_catalog_missing)
                )
            )
            if (changed && current != null) {
                Text(stringResource(R.string.field_request_product_changed))
                Text(stringResource(R.string.field_request_current_name, current.name))
                current.brandName?.let { Text(stringResource(R.string.field_request_brand, it)) }
                current.categoryName?.let { Text(stringResource(R.string.field_request_type, it)) }
                current.variantName?.let {
                    Text(stringResource(R.string.field_request_variety, it))
                }
                current.familyName?.let { Text(stringResource(R.string.field_request_group, it)) }
                Text(
                    stringResource(
                        R.string.field_request_current_price,
                        current.price ?: stringResource(SharedR.string.commercial_catalog_missing),
                        current.currency ?: "",
                        current.pricingAsOf
                            ?: stringResource(SharedR.string.commercial_catalog_missing)
                    )
                )
                Text(
                    stringResource(
                        R.string.field_request_current_availability,
                        current.availability
                            ?: stringResource(SharedR.string.commercial_catalog_missing),
                        current.availabilityStatus
                            ?: stringResource(SharedR.string.commercial_catalog_missing),
                        current.availabilityAsOf
                            ?: stringResource(SharedR.string.commercial_catalog_missing)
                    )
                )
            }
            OutlinedTextField(
                line.quantity,
                onQuantityChanged,
                label = { Text(stringResource(R.string.field_request_quantity)) },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.field_request_remove))
            }
        }
    }
}
