package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaActiveContextBar
import com.nexa.mobile.operations.core.designsystem.NexaConfirmedSkuSummary
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.core.designsystem.NexaPrimaryButton
import com.nexa.mobile.operations.core.designsystem.NexaProductCandidateRow
import com.nexa.mobile.operations.core.designsystem.NexaSearchField
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar

@Composable
fun OperationsWorkEntryScreen(
    state: WarehouseUiState,
    modifier: Modifier = Modifier,
    onChangeContext: () -> Unit,
    onIdentifyProduct: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NexaTopAppBar(title = stringResource(R.string.warehouse_operations_title))
        state.activeContext?.let { context ->
            NexaActiveContextBar(
                companyName = context.companyName,
                workspaceName = context.workspaceName,
                enabled = true,
                onClick = onChangeContext
            )
        }
        when (state.workEntryStatus) {
            WorkEntryStatus.TaskAvailable -> {
                Text(
                    stringResource(R.string.warehouse_work_available),
                    style = MaterialTheme.typography.titleMedium
                )
                NexaTaskRow(
                    title = stringResource(R.string.warehouse_identify_product),
                    description = stringResource(R.string.warehouse_identify_product_support),
                    onClick = onIdentifyProduct
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        WarehouseSurfaceHeader(
            title = stringResource(R.string.warehouse_operations_title),
            activeContext = state.context,
            onChangeContext = onChangeContext,
            onBack = onBack
        )
        ConfirmedSkuHierarchy(state = state)
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
        style = MaterialTheme.typography.headlineSmall
    )
    Text(
        stringResource(R.string.warehouse_search_helper),
        style = MaterialTheme.typography.bodyMedium
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
        style = MaterialTheme.typography.titleMedium
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
    Text(
        stringResource(R.string.warehouse_confirmation_title),
        style = MaterialTheme.typography.headlineSmall
    )
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
        activeContext = "${state.context.companyName} · ${state.context.workspaceName}"
    )
    Text(
        stringResource(R.string.warehouse_inventory_disclaimer),
        style = MaterialTheme.typography.bodyMedium
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
