package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogFilterChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.R
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial.CommercialCatalogStatus
import com.nexa.mobile.operations.core.designsystem.NexaCatalogImage
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaProductCandidateRow
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaSizes
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as SharedR
import java.util.Locale

@Composable
@Suppress("UNUSED_PARAMETER")
fun CommercialCatalogScreen(
    state: CommercialCatalogState,
    onBack: () -> Unit,
    onCustomerIdChanged: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onOpenFilterOptions: (CommercialCatalogFilterKind) -> Unit,
    onSearchFilterChanged: (CommercialCatalogFilterKind, String) -> Unit,
    onSearchFilterOptions: (CommercialCatalogFilterKind, String, Int, Boolean) -> Unit,
    onSelectFilterOption: (CommercialCatalogFilterKind, CommercialCatalogFilterChoice?) -> Unit,
    onSearch: () -> Unit,
    onNextPage: () -> Unit,
    onSelectProduct: (String) -> Unit,
    onRouteClosed: () -> Unit,
    onPrepareRequest: ((String, String) -> Unit)? = null,
    onCreateDirectOrder: ((String, String) -> Unit)? = null,
    onChooseCustomer: (() -> Unit)? = null
) {
    val close = rememberUpdatedState(onRouteClosed)
    var openFilter by remember { mutableStateOf<CommercialCatalogFilterKind?>(null) }
    DisposableEffect(Unit) { onDispose { close.value() } }
    openFilter?.let { kind ->
        AlertDialog(
            onDismissRequest = { openFilter = null },
            title = {
                Text(
                    stringResource(
                        if (kind == CommercialCatalogFilterKind.BRAND) {
                            R.string.commercial_catalog_brand_filter
                        } else {
                            R.string.commercial_catalog_type_filter
                        }
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.filterSearch,
                        onValueChange = { onSearchFilterChanged(kind, it) },
                        label = { Text(stringResource(R.string.commercial_catalog_filter_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = {
                            onSearchFilterOptions(kind, state.filterSearch, 0, false)
                        },
                        enabled = state.filterStatus != CommercialCatalogFilterStatus.Pending
                    ) { Text(stringResource(SharedR.string.customer_search)) }
                    Text(
                        stringResource(
                            when (state.filterStatus) {
                                CommercialCatalogFilterStatus.Idle ->
                                    R.string.commercial_catalog_filter_idle

                                CommercialCatalogFilterStatus.Pending ->
                                    SharedR.string.progress_pending

                                CommercialCatalogFilterStatus.Current ->
                                    SharedR.string.progress_current

                                CommercialCatalogFilterStatus.Unavailable ->
                                    SharedR.string.progress_unavailable

                                CommercialCatalogFilterStatus.PermissionDenied ->
                                    SharedR.string.progress_denied
                            }
                        )
                    )
                    Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        TextButton(
                            onClick = {
                                onSelectFilterOption(kind, null)
                                openFilter = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.commercial_catalog_filter_all)) }
                        state.filterOptions.forEach { choice ->
                            TextButton(
                                onClick = {
                                    onSelectFilterOption(kind, choice)
                                    openFilter = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(choice.name) }
                        }
                        state.nextFilterPage?.let { nextPage ->
                            TextButton(
                                onClick = {
                                    onSearchFilterOptions(kind, state.filterSearch, nextPage, true)
                                },
                                enabled =
                                    state.filterStatus != CommercialCatalogFilterStatus.Pending,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(SharedR.string.customer_next)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { openFilter = null }) {
                    Text(stringResource(SharedR.string.commercial_back))
                }
            }
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = NexaColors.Canvas) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            NexaTopAppBar(
                title = stringResource(SharedR.string.commercial_catalog_title),
                onBack = onBack
            )
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
                        colors = CardDefaults.cardColors(containerColor = NexaColors.CardSurface),
                        border = null
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (state.customerId.isBlank()) {
                                Text(
                                    stringResource(R.string.commercial_catalog_customer_required),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NexaColors.TextSecondary
                                )
                                onChooseCustomer?.let { chooseCustomer ->
                                    Button(
                                        onClick = chooseCustomer,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.commercial_catalog_choose_customer
                                            )
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = onQueryChanged,
                                label = { Text(stringResource(R.string.commercial_catalog_query)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        openFilter = CommercialCatalogFilterKind.BRAND
                                        onOpenFilterOptions(CommercialCatalogFilterKind.BRAND)
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        state.brandName.ifBlank {
                                            stringResource(R.string.commercial_catalog_brand_filter)
                                        }
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        openFilter = CommercialCatalogFilterKind.CATEGORY
                                        onOpenFilterOptions(CommercialCatalogFilterKind.CATEGORY)
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        state.categoryName.ifBlank {
                                            stringResource(R.string.commercial_catalog_type_filter)
                                        }
                                    )
                                }
                            }
                            Button(
                                onClick = onSearch,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = state.customerId.isNotBlank() &&
                                    state.status != CommercialCatalogStatus.Pending
                            ) {
                                Text(stringResource(SharedR.string.customer_search))
                            }
                            if (state.status != CommercialCatalogStatus.Idle &&
                                state.status != CommercialCatalogStatus.Current
                            ) {
                                Text(
                                    stringResource(
                                        when (state.status) {
                                            CommercialCatalogStatus.Idle ->
                                                R.string.commercial_catalog_idle

                                            CommercialCatalogStatus.Pending ->
                                                SharedR.string.progress_pending

                                            CommercialCatalogStatus.Current ->
                                                SharedR.string.progress_current

                                            CommercialCatalogStatus.Unavailable ->
                                                SharedR.string.progress_unavailable

                                            CommercialCatalogStatus.PermissionDenied ->
                                                SharedR.string.progress_denied
                                        }
                                    ),
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
                if (state.status == CommercialCatalogStatus.Current) {
                    state.product?.let { product ->
                        item {
                            val price = product.price
                            val currency = product.currency
                            val sellableAvailability = product.sellableAvailability
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = NexaShapes.card,
                                colors = CardDefaults.cardColors(
                                    containerColor = NexaColors.CardSurface
                                ),
                                border = null
                            ) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NexaCatalogImage(
                                        fileName = product.imageFileName,
                                        modifier = Modifier.size(NexaSizes.catalogDetailImage),
                                        targetSize = NexaSizes.catalogDetailImage,
                                        contentDescription = product.name
                                    )
                                    Text(
                                        product.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NexaColors.TextPrimary
                                    )
                                    product.brandName?.let {
                                        Text(
                                            stringResource(
                                                R.string.commercial_catalog_brand_value,
                                                it
                                            )
                                        )
                                    }
                                    product.categoryName?.let {
                                        Text(
                                            stringResource(
                                                R.string.commercial_catalog_type_value,
                                                it
                                            )
                                        )
                                    }
                                    product.variantName?.let {
                                        Text(
                                            stringResource(
                                                R.string.commercial_catalog_variety_value,
                                                it
                                            )
                                        )
                                    }
                                    product.familyName?.let {
                                        Text(
                                            stringResource(
                                                R.string.commercial_catalog_group_value,
                                                it
                                            )
                                        )
                                    }
                                    product.skuCode?.let {
                                        Text(stringResource(R.string.commercial_catalog_sku, it))
                                    }
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
                                            stringResource(
                                                R.string.commercial_catalog_price_unavailable
                                            )
                                        }
                                    )
                                    Text(
                                        if (sellableAvailability != null) {
                                            stringResource(
                                                R.string.commercial_catalog_sellable,
                                                sellableAvailability
                                            )
                                        } else {
                                            stringResource(
                                                R.string.commercial_catalog_availability_unavailable
                                            )
                                        }
                                    )
                                    product.availabilityStatus?.let {
                                        Text(
                                            stringResource(
                                                R.string.commercial_catalog_availability_status,
                                                it.humanLabel()
                                            )
                                        )
                                    }
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
                            variant = listOfNotNull(choice.brandName, choice.categoryName)
                                .distinct().joinToString(" · ").ifBlank { null },
                            presentation = listOfNotNull(choice.variantName, choice.presentation)
                                .distinct().joinToString(" · ").ifBlank { null },
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
                if (state.status == CommercialCatalogStatus.Current && state.product != null &&
                    onCreateDirectOrder != null
                ) {
                    item {
                        TextButton(onClick = {
                            onCreateDirectOrder(state.customerId, state.product.catalogItemId)
                        }) {
                            Text(stringResource(R.string.commercial_catalog_direct_order))
                        }
                    }
                }
            }
        }
    }
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
