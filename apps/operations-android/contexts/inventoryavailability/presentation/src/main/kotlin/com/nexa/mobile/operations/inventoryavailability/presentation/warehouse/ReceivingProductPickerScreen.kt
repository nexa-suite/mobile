package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterChoice
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterKind
import com.nexa.mobile.operations.inventoryavailability.presentation.R

@Composable
internal fun ReceivingProductPickerScreen(
    state: ReceivingProductPickerState,
    onBack: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenFilterOptions: (ReceivingCatalogFilterKind) -> Unit,
    onFilterSearchChanged: (ReceivingCatalogFilterKind, String) -> Unit,
    onLoadFilterOptions: (ReceivingCatalogFilterKind, String, Int, Boolean) -> Unit,
    onSelectFilter: (ReceivingCatalogFilterKind, ReceivingCatalogFilterChoice?) -> Unit,
    onSelectCandidate: (String) -> Unit
) {
    var openFilter by remember { mutableStateOf<ReceivingCatalogFilterKind?>(null) }
    BackHandler(onBack = onBack)

    openFilter?.let { kind ->
        ReceivingProductFilterDialog(
            state = state,
            kind = kind,
            onClose = { openFilter = null },
            onSearchChanged = onFilterSearchChanged,
            onLoadOptions = onLoadFilterOptions,
            onSelect = { choice ->
                onSelectFilter(kind, choice)
                openFilter = null
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.receiving_back)) }
            Text(
                text = stringResource(R.string.receiving_catalog_picker_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(stringResource(R.string.receiving_catalog_picker_support))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.receiving_catalog_query)) },
                singleLine = true
            )
            OutlinedButton(
                onClick = {
                    openFilter = ReceivingCatalogFilterKind.Brand
                    onOpenFilterOptions(ReceivingCatalogFilterKind.Brand)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    state.brand?.let {
                        stringResource(R.string.receiving_catalog_brand_value, it.name)
                    } ?: stringResource(R.string.receiving_catalog_brand_filter)
                )
            }
            OutlinedButton(
                onClick = {
                    openFilter = ReceivingCatalogFilterKind.Category
                    onOpenFilterOptions(ReceivingCatalogFilterKind.Category)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    state.category?.let {
                        stringResource(R.string.receiving_catalog_category_value, it.name)
                    }
                        ?: stringResource(R.string.receiving_catalog_category_filter)
                )
            }
            Button(
                onClick = onSearch,
                enabled = state.searchStatus !in setOf(
                    ReceivingCatalogSearchStatus.Loading,
                    ReceivingCatalogSearchStatus.LoadingMore
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.receiving_catalog_search))
            }
            CatalogSearchStatus(state.searchStatus)
            state.selectionNotice?.let { notice -> CatalogSelectionNotice(notice) }

            if (state.searchStatus == ReceivingCatalogSearchStatus.Loading) {
                CircularProgressIndicator()
            }
            state.candidates.forEach { candidate ->
                OutlinedButton(
                    onClick = { onSelectCandidate(candidate.catalogItemId) },
                    enabled = state.selectedCandidateId == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(candidate.itemName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.receiving_product_sku, candidate.skuCode),
                            style = MaterialTheme.typography.bodySmall
                        )
                        listOfNotNull(
                            candidate.brandName?.let {
                                stringResource(R.string.receiving_catalog_brand_value, it)
                            },
                            candidate.categoryName?.let {
                                stringResource(R.string.receiving_catalog_category_value, it)
                            },
                            candidate.presentation.takeIf(String::isNotBlank)
                        ).takeIf { details -> details.isNotEmpty() }?.let { details ->
                            Text(
                                details.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (state.selectedCandidateId == candidate.catalogItemId) {
                            Text(stringResource(R.string.receiving_catalog_detail_loading))
                        }
                    }
                }
            }
            if (state.searchStatus == ReceivingCatalogSearchStatus.LoadingMore) {
                CircularProgressIndicator()
            }
            state.nextPageKey?.let {
                TextButton(
                    onClick = onLoadMore,
                    enabled = state.searchStatus == ReceivingCatalogSearchStatus.Ready
                ) {
                    Text(stringResource(R.string.receiving_catalog_next_page))
                }
            }
            if (state.searchStatus in setOf(
                    ReceivingCatalogSearchStatus.NetworkUnavailable,
                    ReceivingCatalogSearchStatus.ServiceUnavailable,
                    ReceivingCatalogSearchStatus.LoadMoreFailed
                )
            ) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.receiving_catalog_retry))
                }
            }
        }
    }
}

@Composable
private fun CatalogSearchStatus(status: ReceivingCatalogSearchStatus) {
    val message = when (status) {
        ReceivingCatalogSearchStatus.Idle -> R.string.receiving_catalog_idle
        ReceivingCatalogSearchStatus.Typing -> R.string.receiving_catalog_typing
        ReceivingCatalogSearchStatus.Loading -> R.string.receiving_catalog_loading
        ReceivingCatalogSearchStatus.Ready -> R.string.receiving_catalog_ready
        ReceivingCatalogSearchStatus.Empty -> R.string.receiving_catalog_empty
        ReceivingCatalogSearchStatus.LoadingMore -> R.string.receiving_catalog_loading
        ReceivingCatalogSearchStatus.LoadMoreFailed -> R.string.receiving_catalog_load_more_failed
        ReceivingCatalogSearchStatus.InvalidQuery -> R.string.receiving_catalog_invalid_query
        ReceivingCatalogSearchStatus.NetworkUnavailable -> R.string.receiving_catalog_network
        ReceivingCatalogSearchStatus.ServiceUnavailable -> R.string.receiving_catalog_service
        ReceivingCatalogSearchStatus.PermissionDenied -> R.string.receiving_catalog_permission
        ReceivingCatalogSearchStatus.ContextInvalidated -> R.string.receiving_catalog_context
        ReceivingCatalogSearchStatus.SessionInvalidated -> R.string.receiving_catalog_session
    }
    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CatalogSelectionNotice(notice: ReceivingCatalogSelectionNotice) {
    val message = when (notice) {
        ReceivingCatalogSelectionNotice.CandidateUnavailable ->
            R.string.receiving_catalog_candidate_unavailable

        ReceivingCatalogSelectionNotice.NetworkUnavailable -> R.string.receiving_catalog_network

        ReceivingCatalogSelectionNotice.ServiceUnavailable -> R.string.receiving_catalog_service

        ReceivingCatalogSelectionNotice.PermissionDenied -> R.string.receiving_catalog_permission

        ReceivingCatalogSelectionNotice.ContextInvalidated -> R.string.receiving_catalog_context

        ReceivingCatalogSelectionNotice.SessionInvalidated -> R.string.receiving_catalog_session
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ReceivingProductFilterDialog(
    state: ReceivingProductPickerState,
    kind: ReceivingCatalogFilterKind,
    onClose: () -> Unit,
    onSearchChanged: (ReceivingCatalogFilterKind, String) -> Unit,
    onLoadOptions: (ReceivingCatalogFilterKind, String, Int, Boolean) -> Unit,
    onSelect: (ReceivingCatalogFilterChoice?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                stringResource(
                    if (kind == ReceivingCatalogFilterKind.Brand) {
                        R.string.receiving_catalog_brand_filter
                    } else {
                        R.string.receiving_catalog_category_filter
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.filterSearch,
                    onValueChange = { onSearchChanged(kind, it) },
                    label = { Text(stringResource(R.string.receiving_catalog_filter_query)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { onLoadOptions(kind, state.filterSearch, 0, false) },
                    enabled = state.filterStatus != ReceivingCatalogFilterStatus.Loading
                ) {
                    Text(stringResource(R.string.receiving_catalog_search))
                }
                val statusText = when (state.filterStatus) {
                    ReceivingCatalogFilterStatus.Idle -> R.string.receiving_catalog_filter_idle

                    ReceivingCatalogFilterStatus.Loading -> R.string.receiving_catalog_loading

                    ReceivingCatalogFilterStatus.Ready -> R.string.receiving_catalog_filter_ready

                    ReceivingCatalogFilterStatus.Empty -> R.string.receiving_catalog_empty

                    ReceivingCatalogFilterStatus.InvalidQuery ->
                        R.string.receiving_catalog_invalid_query

                    ReceivingCatalogFilterStatus.NetworkUnavailable ->
                        R.string.receiving_catalog_network

                    ReceivingCatalogFilterStatus.ServiceUnavailable ->
                        R.string.receiving_catalog_service

                    ReceivingCatalogFilterStatus.PermissionDenied ->
                        R.string.receiving_catalog_permission

                    ReceivingCatalogFilterStatus.ContextInvalidated ->
                        R.string.receiving_catalog_context

                    ReceivingCatalogFilterStatus.SessionInvalidated ->
                        R.string.receiving_catalog_session
                }
                Text(stringResource(statusText))
                Column(
                    modifier = Modifier.heightIn(
                        max = 320.dp
                    ).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.receiving_catalog_filter_all))
                    }
                    state.filterOptions.forEach { choice ->
                        TextButton(
                            onClick = { onSelect(choice) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(choice.name)
                        }
                    }
                    state.nextFilterPage?.let { nextPage ->
                        TextButton(
                            onClick = {
                                onLoadOptions(kind, state.filterSearch, nextPage, true)
                            },
                            enabled = state.filterStatus != ReceivingCatalogFilterStatus.Loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.receiving_catalog_next_page))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.receiving_back))
            }
        }
    )
}
