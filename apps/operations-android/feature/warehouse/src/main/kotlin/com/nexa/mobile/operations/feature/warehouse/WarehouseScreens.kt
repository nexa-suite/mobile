package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaActiveContextBar
import com.nexa.mobile.operations.core.designsystem.NexaColdChainTone
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaConfirmedSkuSummary
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.core.designsystem.NexaPrimaryButton
import com.nexa.mobile.operations.core.designsystem.NexaProductCandidateRow
import com.nexa.mobile.operations.core.designsystem.NexaSearchField
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as DesignR

@Composable
fun OperationsWorkEntryScreen(
    state: WarehouseUiState,
    modifier: Modifier = Modifier,
    onChangeContext: () -> Unit,
    onIdentifyProduct: () -> Unit,
    onScanProductCode: () -> Unit = {},
    onReceiveStock: () -> Unit = {},
    onPickStock: () -> Unit = {},
    onViewStock: () -> Unit = {},
    capabilities: List<WorkEntryCapability> = listOf(WorkEntryCapability.CatalogIdentification),
    additionalWorkContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        NexaTopAppBar(title = stringResource(R.string.warehouse_operations_title))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            state.activeContext?.let { context ->
                NexaActiveContextBar(
                    companyName = context.companyName,
                    workspaceName = context.workspaceName,
                    enabled = true,
                    onClick = onChangeContext
                )
            }
            if (state.activeContext != null &&
                state.workEntryStatus == WorkEntryStatus.TaskAvailable
            ) {
                additionalWorkContent()
            }
            when (state.workEntryStatus) {
                WorkEntryStatus.TaskAvailable -> if (capabilities.isNotEmpty()) {
                    Text(
                        stringResource(R.string.warehouse_work_available),
                        style = MaterialTheme.typography.titleMedium,
                        color = NexaColors.TextPrimary
                    )
                    capabilities.forEach { capability ->
                        when (capability) {
                            WorkEntryCapability.CatalogIdentification -> NexaTaskRow(
                                title = stringResource(R.string.warehouse_identify_product),
                                description = stringResource(
                                    R.string.warehouse_identify_product_support
                                ),
                                onClick = onIdentifyProduct
                            )

                            WorkEntryCapability.Picking -> NexaTaskRow(
                                title = stringResource(R.string.picking_title),
                                description = stringResource(R.string.picking_entry_support),
                                onClick = onPickStock
                            )

                            WorkEntryCapability.StockCondition -> NexaTaskRow(
                                title = stringResource(R.string.stock_condition_title),
                                description = stringResource(R.string.stock_condition_disclaimer),
                                onClick = onViewStock
                            )

                            WorkEntryCapability.Receiving -> NexaTaskRow(
                                title = stringResource(R.string.receiving_title),
                                description = stringResource(R.string.receiving_choose_product),
                                onClick = onReceiveStock
                            )

                            WorkEntryCapability.BarcodeIdentification -> NexaTaskRow(
                                title = stringResource(R.string.warehouse_scan_product_code),
                                description = stringResource(
                                    R.string.warehouse_scan_product_code_support
                                ),
                                onClick = onScanProductCode
                            )
                        }
                    }
                } else {
                    NexaStatePanel(
                        title = stringResource(R.string.warehouse_no_task_title),
                        description = stringResource(R.string.warehouse_no_task_body)
                    )
                }

                WorkEntryStatus.NoPermittedTask -> NexaStatePanel(
                    title = stringResource(R.string.warehouse_no_task_title),
                    description = stringResource(R.string.warehouse_no_task_body)
                )

                WorkEntryStatus.PermissionUnavailable -> NexaStatePanel(
                    title = stringResource(R.string.warehouse_permission_title),
                    description = stringResource(R.string.warehouse_permission_body)
                )

                WorkEntryStatus.PermissionUnknown -> NexaStatePanel(
                    title = stringResource(R.string.warehouse_permission_unknown_title),
                    description = stringResource(R.string.warehouse_permission_unknown_body)
                )

                WorkEntryStatus.ContextInvalidated -> NexaStatePanel(
                    title = stringResource(R.string.warehouse_context_invalid_title),
                    description = stringResource(R.string.warehouse_context_invalid_body)
                )

                WorkEntryStatus.SessionInvalidated -> NexaStatePanel(
                    title = stringResource(R.string.warehouse_session_invalid_title),
                    description = stringResource(R.string.warehouse_session_invalid_body)
                )
            }
        }
    }
}

@Composable
fun ProductSearchScreen(
    state: ProductSearchUiState,
    activeContext: ActiveOperationsContext?,
    modifier: Modifier = Modifier,
    onChangeContext: () -> Unit,
    onBack: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectCandidate: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        WarehouseSurfaceHeader(
            title = stringResource(R.string.warehouse_operations_title),
            activeContext = activeContext.takeUnless {
                state.status in setOf(
                    ProductSearchStatus.ContextInvalidated,
                    ProductSearchStatus.SessionInvalidated
                )
            },
            onChangeContext = onChangeContext,
            onBack = onBack
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProductSearchQuerySection(
                state = state,
                onQueryChanged = onQueryChanged,
                onSearch = onSearch
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
fun ConfirmedSkuScreen(
    state: ConfirmedSkuUiState,
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
            title = stringResource(R.string.warehouse_operations_title),
            activeContext = state.context,
            onChangeContext = onChangeContext,
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
fun ProductScannerScreen(
    state: ProductScannerUiState,
    activeContext: ActiveOperationsContext?,
    modifier: Modifier = Modifier,
    cameraPreview: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
    onChangeContext: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryScan: () -> Unit,
    onManualSearch: () -> Unit,
    onViewStorage: ((String) -> Unit)? = null
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
        activeContext?.let { context ->
            NexaActiveContextBar(
                companyName = context.companyName,
                workspaceName = context.workspaceName,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                enabled = true,
                onClick = onChangeContext
            )
        }
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
private fun WarehouseSurfaceHeader(
    title: String,
    activeContext: ActiveOperationsContext?,
    onChangeContext: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    NexaTopAppBar(title = title, onBack = onBack)
    activeContext?.let { context ->
        NexaActiveContextBar(
            companyName = context.companyName,
            workspaceName = context.workspaceName,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            enabled = true,
            onClick = onChangeContext
        )
    }
}

@Composable
private fun ProductSearchQuerySection(
    state: ProductSearchUiState,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit
) {
    Text(
        stringResource(R.string.warehouse_search_title),
        style = MaterialTheme.typography.headlineSmall,
        color = NexaColors.TextPrimary
    )
    Text(
        stringResource(R.string.warehouse_search_helper),
        style = MaterialTheme.typography.bodyMedium,
        color = NexaColors.TextSecondary
    )
    NexaSearchField(
        value = state.query,
        onValueChange = onQueryChanged,
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
    NexaPrimaryButton(
        label = stringResource(
            com.nexa.mobile.operations.core.designsystem.R.string.nexa_search_button
        ),
        onClick = onSearch,
        loading = state.status == ProductSearchStatus.Loading
    )
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
            state.candidates.forEach { candidate ->
                ProductCandidateResult(
                    candidate = candidate,
                    pending = state.pendingCandidateKey == candidate.key,
                    enabled = candidateSelectionEnabled,
                    onSelectCandidate = onSelectCandidate
                )
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
    onSelectCandidate: (String) -> Unit
) {
    NexaProductCandidateRow(
        name = candidate.productDisplayName,
        variant = candidate.brandOrVariant,
        presentation = candidate.presentation,
        sku = candidate.sku,
        pending = pending,
        enabled = enabled,
        onClick = { onSelectCandidate(candidate.key) }
    )
}

@Composable
private fun ConfirmedSkuHierarchy(state: ConfirmedSkuUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(shape = CircleShape, color = NexaColors.SuccessSurface) {
            Icon(
                painter = painterResource(DesignR.drawable.ic_check),
                contentDescription = null,
                tint = NexaColors.Success,
                modifier = Modifier.padding(8.dp)
            )
        }
        Text(
            stringResource(R.string.warehouse_confirmation_title),
            style = MaterialTheme.typography.headlineSmall,
            color = NexaColors.TextPrimary
        )
    }
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
            "UNSPECIFIED" -> stringResource(R.string.warehouse_value_packaging_unspecified)
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
        activeContext = "${state.context.companyName} · ${state.context.workspaceName}"
    )
    Text(
        stringResource(R.string.warehouse_inventory_disclaimer),
        style = MaterialTheme.typography.bodyMedium,
        color = NexaColors.TextSecondary
    )
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
