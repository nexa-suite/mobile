package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedScannedSku
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ScannerIdentifierType
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.R
import com.nexa.mobile.operations.core.designsystem.NexaColdChainTone
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaConfirmedSkuSummary
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.core.designsystem.NexaPrimaryButton
import com.nexa.mobile.operations.core.designsystem.NexaProductCandidateRow
import com.nexa.mobile.operations.core.designsystem.NexaSearchField
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as DesignR

@Composable
@Suppress("UNUSED_PARAMETER")
fun ProductSearchScreen(
    state: ProductSearchUiState,
    activeContext: CatalogOperationsContext?,
    modifier: Modifier = Modifier,
    onChangeContext: () -> Unit,
    onBack: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenFilterOptions: (WarehouseCatalogFilterKind) -> Unit,
    onCloseFilterOptions: () -> Unit,
    onFilterSearchChanged: (String) -> Unit,
    onLoadFilterOptions: () -> Unit,
    onLoadMoreFilterOptions: () -> Unit,
    onRetryFilterOptions: () -> Unit,
    onSelectFilter: (WarehouseCatalogFilterKind, WarehouseCatalogFilterChoice?) -> Unit,
    onSelectCandidate: (String) -> Unit
) {
    state.filterKind?.let { kind ->
        WarehouseCatalogFilterDialog(
            state = state,
            kind = kind,
            onClose = onCloseFilterOptions,
            onSearchChanged = onFilterSearchChanged,
            onSearch = onLoadFilterOptions,
            onLoadMore = onLoadMoreFilterOptions,
            onRetry = onRetryFilterOptions,
            onSelect = onSelectFilter
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        WarehouseSurfaceHeader(
            title = stringResource(R.string.warehouse_catalog_title),
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1280.dp)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProductSearchQuerySection(
                state = state,
                onQueryChanged = onQueryChanged,
                onSearch = onSearch,
                onOpenFilterOptions = onOpenFilterOptions
            )
            ProductSearchResultsSection(
                state = state,
                onLoadMore = onLoadMore,
                onSearch = onSearch,
                onSelectCandidate = onSelectCandidate
            )
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun ConfirmedSkuScreen(
    state: ConfirmedSkuProjection,
    modifier: Modifier = Modifier,
    onChangeContext: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        WarehouseSurfaceHeader(
            title = stringResource(R.string.warehouse_visual_product_detail_title),
            onBack = onBack
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConfirmedSkuHierarchy(state = state)
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun ProductScannerScreen(
    state: ProductScannerUiState,
    activeContext: CatalogOperationsContext?,
    modifier: Modifier = Modifier,
    cameraPreview: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
    onChangeContext: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryScan: () -> Unit,
    onManualSearch: () -> Unit,
    onViewStorage: ((String) -> Unit)? = null,
    onReceiveProduct: ((ConfirmedScannedSku) -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        NexaTopAppBar(
            title = stringResource(R.string.warehouse_scanner_title),
            onBack = onBack
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state) {
                is ProductScannerUiState.PermissionNotRequested,
                is ProductScannerUiState.PermissionRequestPending -> {
                    NexaStatePanel(
                        title = stringResource(R.string.warehouse_scanner_permission_title),
                        description = stringResource(R.string.warehouse_scanner_permission_body)
                    )
                    if (state is ProductScannerUiState.PermissionNotRequested) {
                        NexaPrimaryButton(
                            label = stringResource(R.string.warehouse_scanner_permission_action),
                            onClick = onRequestPermission
                        )
                    }
                }

                is ProductScannerUiState.PermissionDenied -> {
                    NexaStatePanel(
                        title = stringResource(R.string.warehouse_scanner_permission_denied_title),
                        description = stringResource(
                            R.string.warehouse_scanner_permission_denied_body
                        )
                    )
                    NexaPrimaryButton(
                        label = stringResource(R.string.warehouse_scanner_permission_action),
                        onClick = onRequestPermission
                    )
                }

                is ProductScannerUiState.PermissionPermanentlyDenied -> {
                    NexaStatePanel(
                        title = stringResource(R.string.warehouse_scanner_permission_denied_title),
                        description = stringResource(
                            R.string.warehouse_scanner_permission_denied_body
                        )
                    )
                    NexaPrimaryButton(
                        label = stringResource(R.string.warehouse_scanner_settings_action),
                        onClick = onOpenSettings
                    )
                }

                is ProductScannerUiState.CameraStarting,
                is ProductScannerUiState.Capturing,
                is ProductScannerUiState.CameraUnavailable -> {
                    if (state is ProductScannerUiState.CameraUnavailable) {
                        NexaStatePanel(
                            title = stringResource(
                                R.string.warehouse_scanner_camera_unavailable_title
                            ),
                            description = stringResource(
                                R.string.warehouse_scanner_camera_unavailable_body
                            )
                        )
                        NexaPrimaryButton(
                            label = stringResource(R.string.warehouse_scanner_start_action),
                            onClick = onRetryScan
                        )
                    } else {
                        Text(
                            stringResource(
                                if (state is ProductScannerUiState.CameraStarting) {
                                    R.string.warehouse_scanner_starting_title
                                } else {
                                    R.string.warehouse_scanner_capturing_title
                                }
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = NexaColors.TextPrimary
                        )
                        Text(
                            stringResource(R.string.warehouse_scanner_instructions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexaColors.TextSecondary
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 260.dp, max = 420.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(NexaColors.Surface)
                        ) {
                            cameraPreview(Modifier.fillMaxSize())
                        }
                    }
                }

                is ProductScannerUiState.Resolving -> {
                    CircularProgressIndicator(color = NexaColors.PrimaryStrong)
                    NexaStatePanel(
                        title = stringResource(R.string.warehouse_scanner_resolving_title),
                        description = stringResource(R.string.warehouse_scanner_resolving_body)
                    )
                }

                is ProductScannerUiState.Unverified -> {
                    val title = when (state.reason) {
                        ScannerUnverifiedReason.UnknownCode ->
                            R.string.warehouse_scanner_unknown_title

                        ScannerUnverifiedReason.AmbiguousCode ->
                            R.string.warehouse_scanner_ambiguous_title

                        ScannerUnverifiedReason.InvalidCode ->
                            R.string.warehouse_scanner_invalid_title

                        ScannerUnverifiedReason.PermissionDenied ->
                            R.string.warehouse_scanner_permission_error_title

                        ScannerUnverifiedReason.NetworkUnavailable ->
                            R.string.warehouse_scanner_network_title

                        ScannerUnverifiedReason.ServiceUnavailable ->
                            R.string.warehouse_scanner_service_title
                    }
                    NexaStatePanel(
                        title = stringResource(title),
                        description = stringResource(R.string.warehouse_scanner_unverified_body)
                    )
                    state.candidateCount?.let { count ->
                        Text(
                            pluralStringResource(
                                R.plurals.warehouse_scanner_ambiguous_count,
                                count,
                                count
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexaColors.TextSecondary
                        )
                    }
                    NexaPrimaryButton(
                        label = stringResource(R.string.warehouse_scanner_start_action),
                        onClick = onRetryScan
                    )
                }

                is ProductScannerUiState.Confirmed -> {
                    Text(
                        stringResource(R.string.warehouse_scanner_confirmed_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = NexaColors.TextPrimary
                    )
                    ScannerValue(
                        stringResource(R.string.warehouse_scanner_identifier_type),
                        stringResource(
                            when (state.sku.identifierType) {
                                ScannerIdentifierType.SkuCode ->
                                    R.string.warehouse_scanner_identifier_sku

                                ScannerIdentifierType.Gtin ->
                                    R.string.warehouse_scanner_identifier_gtin

                                ScannerIdentifierType.SkuCodeAndGtin ->
                                    R.string.warehouse_scanner_identifier_both
                            }
                        )
                    )
                    ScannerValue(
                        stringResource(R.string.warehouse_scanner_sku_code),
                        state.sku.skuCode
                    )
                    state.sku.gtin?.let {
                        ScannerValue(stringResource(R.string.warehouse_scanner_gtin), it)
                    }
                    ScannerValue(
                        stringResource(R.string.warehouse_scanner_presentation),
                        state.sku.presentation
                    )
                    state.sku.unitOfMeasure?.let {
                        ScannerValue(stringResource(R.string.warehouse_scanner_unit), it)
                    }
                    ScannerValue(
                        stringResource(R.string.warehouse_scanner_status),
                        state.sku.status
                    )
                    Text(
                        stringResource(R.string.warehouse_scanner_no_inventory_change),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaColors.TextSecondary
                    )
                    onViewStorage?.let { openStorage ->
                        NexaPrimaryButton(
                            label = "Consultar lotes y ubicación de este SKU",
                            onClick = { openStorage(state.sku.skuId.toString()) }
                        )
                    }
                    onReceiveProduct?.takeIf {
                        state.sku.status.equals("ACTIVE", ignoreCase = true) &&
                            !state.sku.unitOfMeasure.isNullOrBlank()
                    }?.let { receiveProduct ->
                        NexaPrimaryButton(
                            label = stringResource(R.string.warehouse_scanner_receive_action),
                            onClick = { receiveProduct(state.sku) }
                        )
                    }
                    NexaPrimaryButton(
                        label = stringResource(R.string.warehouse_scanner_start_action),
                        onClick = onRetryScan
                    )
                }

                is ProductScannerUiState.ContextInvalidated -> NexaStatePanel(
                    title = stringResource(R.string.warehouse_context_invalid_title),
                    description = stringResource(R.string.warehouse_context_error)
                )

                is ProductScannerUiState.SessionInvalidated -> NexaStatePanel(
                    title = stringResource(R.string.warehouse_session_invalid_title),
                    description = stringResource(R.string.warehouse_session_error)
                )
            }
            NexaPrimaryButton(
                label = stringResource(R.string.warehouse_scanner_manual_action),
                onClick = onManualSearch
            )
        }
    }
}

@Composable
private fun ScannerValue(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = NexaColors.Surface,
        border = BorderStroke(1.dp, NexaColors.Border)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = NexaColors.TextSecondary
            )
            Text(value, style = MaterialTheme.typography.bodyLarge, color = NexaColors.TextPrimary)
        }
    }
}

@Composable
private fun WarehouseSurfaceHeader(title: String, onBack: (() -> Unit)? = null) {
    NexaTopAppBar(title = title, onBack = onBack)
}

@Composable
private fun ProductSearchQuerySection(
    state: ProductSearchUiState,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenFilterOptions: (WarehouseCatalogFilterKind) -> Unit
) {
    Text(
        stringResource(R.string.warehouse_search_helper),
        style = MaterialTheme.typography.bodyMedium,
        color = NexaColors.TextSecondary
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NexaSearchField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier.weight(1f),
            label = stringResource(
                com.nexa.mobile.operations.core.designsystem.R.string.nexa_search_label
            ),
            hint = stringResource(
                com.nexa.mobile.operations.core.designsystem.R.string.nexa_search_hint
            ),
            errorText = if (state.status == ProductSearchStatus.InvalidQuery) {
                stringResource(R.string.warehouse_search_invalid)
            } else {
                null
            },
            onImeSearch = onSearch
        )
        IconButton(
            onClick = onSearch,
            enabled = state.status !in setOf(
                ProductSearchStatus.Loading,
                ProductSearchStatus.LoadingMore
            )
        ) {
            if (state.status == ProductSearchStatus.Loading) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp))
            } else {
                Icon(
                    painter = painterResource(DesignR.drawable.ic_search),
                    contentDescription = stringResource(
                        DesignR.string.nexa_search_button
                    )
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = state.brand != null,
            onClick = { onOpenFilterOptions(WarehouseCatalogFilterKind.Brand) },
            label = {
                Text(
                    state.brand?.let {
                        stringResource(R.string.warehouse_filter_brand_value, it.name)
                    } ?: stringResource(R.string.warehouse_filter_brand)
                )
            }
        )
        FilterChip(
            selected = state.category != null,
            onClick = { onOpenFilterOptions(WarehouseCatalogFilterKind.Category) },
            label = {
                Text(
                    state.category?.let {
                        stringResource(R.string.warehouse_filter_category_value, it.name)
                    } ?: stringResource(R.string.warehouse_filter_category)
                )
            }
        )
    }
}

@Composable
private fun WarehouseCatalogFilterDialog(
    state: ProductSearchUiState,
    kind: WarehouseCatalogFilterKind,
    onClose: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (WarehouseCatalogFilterKind, WarehouseCatalogFilterChoice?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                stringResource(
                    if (kind == WarehouseCatalogFilterKind.Brand) {
                        R.string.warehouse_filter_brand
                    } else {
                        R.string.warehouse_filter_category
                    }
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.filterSearch,
                    onValueChange = onSearchChanged,
                    label = { Text(stringResource(R.string.warehouse_filter_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = onSearch,
                    enabled = state.filterStatus !in setOf(
                        ProductFilterStatus.Loading,
                        ProductFilterStatus.LoadingMore
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.warehouse_filter_search_action))
                }
                WarehouseFilterStatus(state.filterStatus)
                TextButton(onClick = { onSelect(kind, null) }) {
                    Text(stringResource(R.string.warehouse_filter_all))
                }
                state.filterOptions.forEach { option ->
                    TextButton(
                        onClick = { onSelect(kind, option) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(option.name)
                    }
                }
                if (state.filterStatus == ProductFilterStatus.LoadingMore) {
                    CircularProgressIndicator()
                }
                if (state.nextFilterPage != null &&
                    state.filterStatus in setOf(
                        ProductFilterStatus.Ready,
                        ProductFilterStatus.Empty,
                        ProductFilterStatus.LoadMoreFailed
                    )
                ) {
                    TextButton(onClick = onLoadMore) {
                        Text(stringResource(R.string.warehouse_filter_more))
                    }
                }
                if (state.filterStatus in setOf(
                        ProductFilterStatus.NetworkUnavailable,
                        ProductFilterStatus.ServiceUnavailable,
                        ProductFilterStatus.LoadMoreFailed
                    )
                ) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.warehouse_filter_retry))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.warehouse_filter_close))
            }
        }
    )
}

@Composable
private fun WarehouseFilterStatus(status: ProductFilterStatus) {
    val message = when (status) {
        ProductFilterStatus.Idle -> R.string.warehouse_filter_idle

        ProductFilterStatus.Loading, ProductFilterStatus.LoadingMore ->
            R.string.warehouse_filter_loading

        ProductFilterStatus.Ready -> R.string.warehouse_filter_ready

        ProductFilterStatus.Empty -> R.string.warehouse_filter_empty

        ProductFilterStatus.LoadMoreFailed -> R.string.warehouse_filter_load_more_failed

        ProductFilterStatus.InvalidQuery -> R.string.warehouse_filter_invalid

        ProductFilterStatus.NetworkUnavailable -> R.string.warehouse_filter_network

        ProductFilterStatus.ServiceUnavailable -> R.string.warehouse_filter_service

        ProductFilterStatus.PermissionDenied -> R.string.warehouse_filter_permission

        ProductFilterStatus.ContextInvalidated -> R.string.warehouse_context_error

        ProductFilterStatus.SessionInvalidated -> R.string.warehouse_session_error
    }
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ProductSearchResultsSection(
    state: ProductSearchUiState,
    onLoadMore: () -> Unit,
    onSearch: () -> Unit,
    onSelectCandidate: (String) -> Unit
) {
    Text(
        stringResource(R.string.warehouse_results_title),
        style = MaterialTheme.typography.titleMedium,
        color = NexaColors.TextPrimary
    )
    when (state.status) {
        ProductSearchStatus.Initial, ProductSearchStatus.Typing -> NexaStatePanel(
            title = stringResource(R.string.warehouse_search_initial_title),
            description = stringResource(R.string.warehouse_search_initial_body)
        )

        ProductSearchStatus.InvalidQuery -> Unit

        ProductSearchStatus.Loading -> LoadingPanel()

        ProductSearchStatus.Empty -> NexaStatePanel(
            title = stringResource(R.string.warehouse_search_empty_title),
            description = stringResource(R.string.warehouse_search_empty_body)
        )

        ProductSearchStatus.OneCandidate,
        ProductSearchStatus.MultipleCandidates,
        ProductSearchStatus.LoadingMore,
        ProductSearchStatus.LoadMoreFailed,
        ProductSearchStatus.ConfirmationPending,
        ProductSearchStatus.CandidateUnavailable,
        ProductSearchStatus.NetworkUnavailable,
        ProductSearchStatus.ServiceUnavailable,
        ProductSearchStatus.PermissionDenied,
        ProductSearchStatus.IntegrationUnavailable
        -> {
            ProductSearchFeedback(
                status = state.status,
                errorMessage = state.errorMessage
            )
            val candidateSelectionEnabled = state.status in setOf(
                ProductSearchStatus.OneCandidate,
                ProductSearchStatus.MultipleCandidates,
                ProductSearchStatus.LoadMoreFailed
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 720.dp) 2 else 1
                val spacing = 12.dp
                val cardWidth = if (columns == 1) maxWidth else (maxWidth - spacing) / 2
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    state.candidates.chunked(columns).forEach { rowCandidates ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            rowCandidates.forEach { candidate ->
                                ProductCandidateResult(
                                    candidate = candidate,
                                    pending = state.pendingCandidateKey == candidate.key,
                                    enabled = candidateSelectionEnabled,
                                    modifier = Modifier.width(cardWidth),
                                    onSelectCandidate = onSelectCandidate
                                )
                            }
                            if (rowCandidates.size < columns) {
                                Spacer(modifier = Modifier.width(cardWidth))
                            }
                        }
                    }
                }
            }
            if (state.status == ProductSearchStatus.LoadMoreFailed) {
                NexaPrimaryButton(
                    label = stringResource(R.string.warehouse_load_more_retry),
                    onClick = onLoadMore
                )
            } else if (state.nextPageKey != null &&
                state.status != ProductSearchStatus.ConfirmationPending
            ) {
                NexaPrimaryButton(
                    label = stringResource(
                        com.nexa.mobile.operations.core.designsystem.R.string.nexa_show_more
                    ),
                    onClick = onLoadMore,
                    enabled = state.status != ProductSearchStatus.LoadingMore,
                    loading = state.status == ProductSearchStatus.LoadingMore
                )
            }
            if (state.status in setOf(
                    ProductSearchStatus.NetworkUnavailable,
                    ProductSearchStatus.ServiceUnavailable,
                    ProductSearchStatus.IntegrationUnavailable
                )
            ) {
                NexaPrimaryButton(
                    label = stringResource(R.string.warehouse_search_retry),
                    onClick = onSearch
                )
            }
        }

        ProductSearchStatus.ContextInvalidated -> NexaStatePanel(
            title = stringResource(R.string.warehouse_context_invalid_title),
            description = stringResource(R.string.warehouse_context_error)
        )

        ProductSearchStatus.SessionInvalidated -> NexaStatePanel(
            title = stringResource(R.string.warehouse_session_invalid_title),
            description = stringResource(R.string.warehouse_session_error)
        )
    }
}

@Composable
private fun ProductSearchFeedback(status: ProductSearchStatus, errorMessage: ProductSearchStatus?) {
    if (status == ProductSearchStatus.ConfirmationPending) {
        NexaFeedbackBanner(
            message = stringResource(R.string.warehouse_confirmation_pending_body),
            tone = NexaFeedbackTone.Information
        )
    }
    if (status == ProductSearchStatus.CandidateUnavailable) {
        NexaStatePanel(
            title = stringResource(R.string.warehouse_candidate_unavailable_title),
            description = stringResource(R.string.warehouse_candidate_unavailable_body)
        )
    }
    errorMessage?.let { messageStatus ->
        NexaFeedbackBanner(
            message = stringResource(messageStatus.messageResource()),
            tone = messageStatus.tone()
        )
    }
}

@Composable
private fun ProductCandidateResult(
    candidate: ProductCandidate,
    pending: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelectCandidate: (String) -> Unit
) {
    val variantDetails = listOfNotNull(
        candidate.brandOrVariant?.takeIf(String::isNotBlank),
        candidate.categoryName?.takeIf(String::isNotBlank)
    ).joinToString(" · ").ifBlank { null }
    val coldChain = when (candidate.coldChainRequirement?.uppercase()) {
        "NONE" -> stringResource(R.string.warehouse_value_cold_chain_none)
        "REFRIGERATED" -> stringResource(R.string.warehouse_value_cold_chain_refrigerated)
        "FROZEN" -> stringResource(R.string.warehouse_value_cold_chain_frozen)
        else -> candidate.coldChainRequirement?.takeIf { it.isNotBlank() }
    }
    val presentationDetails = listOfNotNull(
        candidate.presentation.takeIf(String::isNotBlank),
        coldChain
    ).joinToString(" · ").ifBlank { null }
    NexaProductCandidateRow(
        name = candidate.productDisplayName,
        variant = variantDetails,
        presentation = presentationDetails,
        sku = candidate.sku,
        modifier = modifier,
        imageFileName = candidate.imageFileName,
        pending = pending,
        enabled = enabled,
        onClick = { onSelectCandidate(candidate.key) }
    )
}

@Composable
private fun ConfirmedSkuHierarchy(state: ConfirmedSkuProjection) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NexaConfirmedSkuSummary(
            productName = state.productDisplayName,
            variant = state.variant,
            presentation = state.presentation,
            sku = state.sku,
            brand = state.brand,
            unit = when (state.unit) {
                "UNIT" -> stringResource(R.string.warehouse_value_unit)
                else -> state.unit
            },
            packaging = when (state.packaging) {
                "UNSPECIFIED" -> null
                else -> state.packaging
            },
            coldChain = when (state.coldChain) {
                "NONE" -> stringResource(R.string.warehouse_value_cold_chain_none)
                "REFRIGERATED" -> stringResource(R.string.warehouse_value_cold_chain_refrigerated)
                "FROZEN" -> stringResource(R.string.warehouse_value_cold_chain_frozen)
                else -> state.coldChain
            },
            coldChainTone = when (state.coldChain) {
                "REFRIGERATED" -> NexaColdChainTone.Refrigerated
                "FROZEN" -> NexaColdChainTone.Frozen
                else -> NexaColdChainTone.Neutral
            },
            activeContext = "${state.context.companyName} · ${state.context.workspaceName}",
            imageFileName = state.imageFileName
        )
        NexaFeedbackBanner(
            message = stringResource(R.string.warehouse_inventory_disclaimer),
            tone = NexaFeedbackTone.Information
        )
    }
}

@Composable
private fun LoadingPanel() {
    NexaStatePanel(
        title = stringResource(R.string.warehouse_search_loading_title),
        description = stringResource(R.string.warehouse_search_loading_body)
    )
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ProductSearchStatus.messageResource(): Int = when (this) {
    ProductSearchStatus.LoadMoreFailed -> R.string.warehouse_load_more_error
    ProductSearchStatus.CandidateUnavailable -> R.string.warehouse_candidate_unavailable_body
    ProductSearchStatus.NetworkUnavailable -> R.string.warehouse_network_error
    ProductSearchStatus.ServiceUnavailable -> R.string.warehouse_service_error
    ProductSearchStatus.PermissionDenied -> R.string.warehouse_permission_error
    ProductSearchStatus.ContextInvalidated -> R.string.warehouse_context_error
    ProductSearchStatus.SessionInvalidated -> R.string.warehouse_session_error
    ProductSearchStatus.IntegrationUnavailable -> R.string.warehouse_integration_error
    else -> R.string.warehouse_service_error
}

private fun ProductSearchStatus.tone(): NexaFeedbackTone = when (this) {
    ProductSearchStatus.CandidateUnavailable,
    ProductSearchStatus.PermissionDenied,
    ProductSearchStatus.ContextInvalidated,
    ProductSearchStatus.SessionInvalidated
    -> NexaFeedbackTone.Warning

    ProductSearchStatus.NetworkUnavailable,
    ProductSearchStatus.ServiceUnavailable,
    ProductSearchStatus.IntegrationUnavailable,
    ProductSearchStatus.LoadMoreFailed
    -> NexaFeedbackTone.Error

    else -> NexaFeedbackTone.Information
}
