package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.R as CatalogR
import com.nexa.mobile.operations.core.designsystem.LocalNexaProfileAction
import com.nexa.mobile.operations.core.designsystem.NexaActiveContextBar
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaProfileAvatar
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as DesignSystemR

@Composable
fun OperationsWorkEntryScreen(
    state: WarehouseUiState,
    modifier: Modifier = Modifier,
    additionalCapabilityLabels: Map<WorkEntryCapability, WorkEntryCapabilityLabel> = emptyMap(),
    tenantLabel: String? = null,
    workspaceLabel: String? = null,
    roleLabel: String? = null,
    onChangeContext: () -> Unit,
    onIdentifyProduct: () -> Unit,
    onScanProductCode: () -> Unit = {},
    onReceiveStock: () -> Unit = {},
    onPickStock: () -> Unit = {},
    onViewStock: () -> Unit = {},
    onOpenProfile: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    capabilities: List<WorkEntryCapability> = listOf(WorkEntryCapability.CatalogIdentification),
    hasAdditionalWork: Boolean = false,
    promoteAdditionalWork: Boolean = false,
    additionalWorkContent: @Composable () -> Unit = {}
) {
    val capabilityLabels = defaultCapabilityLabels() + additionalCapabilityLabels
    val profileActionLabel = stringResource(CatalogR.string.warehouse_profile_action)
    var showWarehouseTools by remember(state.authorityEpoch, promoteAdditionalWork) {
        mutableStateOf(!promoteAdditionalWork)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
    ) {
        NexaTopAppBar(
            title = stringResource(CatalogR.string.warehouse_operations_title),
            actions = {
                if (LocalNexaProfileAction.current == null && onOpenProfile != null) {
                    NexaProfileAvatar(onClick = {
                        val openProfile = onOpenProfile
                        if (openProfile != null) openProfile() else onLogout?.invoke()
                    })
                }
            }
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            state.activeContext?.let { context ->
                NexaActiveContextBar(
                    companyName = context.companyName,
                    workspaceName = context.workspaceName,
                    tenantLabel = tenantLabel,
                    workspaceLabel = workspaceLabel,
                    roleLabel = roleLabel,
                    compact = true,
                    enabled = true,
                    onClick = onChangeContext
                )
            }
            if (promoteAdditionalWork && hasAdditionalWork &&
                state.workEntryStatus == WorkEntryStatus.TaskAvailable
            ) {
                additionalWorkContent()
            }
            if (hasAdditionalWork && capabilities.isEmpty()) {
                Text(
                    stringResource(CatalogR.string.warehouse_other_tasks_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
            }
            when (state.workEntryStatus) {
                WorkEntryStatus.TaskAvailable -> if (capabilities.isNotEmpty()) {
                    Text(
                        stringResource(CatalogR.string.warehouse_work_available),
                        style = MaterialTheme.typography.titleMedium,
                        color = NexaColors.TextPrimary
                    )
                    if (promoteAdditionalWork) {
                        TextButton(onClick = { showWarehouseTools = !showWarehouseTools }) {
                            Text(stringResource(CatalogR.string.warehouse_work_toggle))
                        }
                    }
                    if (showWarehouseTools) {
                        capabilities.sortedBy { capability ->
                            when (capability) {
                                WorkEntryCapability.Picking -> 0
                                WorkEntryCapability.Receiving -> 1
                                WorkEntryCapability.StockCondition -> 2
                                WorkEntryCapability.BarcodeIdentification -> 3
                                WorkEntryCapability.CatalogIdentification -> 4
                            }
                        }.forEach { capability ->
                            val label = requireNotNull(capabilityLabels[capability]) {
                                "Missing work-entry label for $capability"
                            }
                            NexaTaskRow(
                                title = label.title,
                                description = label.description,
                                iconResource = when (capability) {
                                    WorkEntryCapability.Picking ->
                                        DesignSystemR.drawable.ic_check_square

                                    WorkEntryCapability.Receiving -> DesignSystemR.drawable.ic_truck

                                    WorkEntryCapability.StockCondition ->
                                        DesignSystemR.drawable.ic_box

                                    WorkEntryCapability.BarcodeIdentification ->
                                        DesignSystemR.drawable.ic_barcode

                                    WorkEntryCapability.CatalogIdentification ->
                                        DesignSystemR.drawable.ic_search
                                },
                                onClick = when (capability) {
                                    WorkEntryCapability.CatalogIdentification -> onIdentifyProduct
                                    WorkEntryCapability.BarcodeIdentification -> onScanProductCode
                                    WorkEntryCapability.Receiving -> onReceiveStock
                                    WorkEntryCapability.StockCondition -> onViewStock
                                    WorkEntryCapability.Picking -> onPickStock
                                }
                            )
                        }
                    }
                } else if (!hasAdditionalWork) {
                    NexaStatePanel(
                        title = stringResource(CatalogR.string.warehouse_no_task_title),
                        description = stringResource(CatalogR.string.warehouse_no_task_body)
                    )
                }

                WorkEntryStatus.NoPermittedTask -> if (!hasAdditionalWork) {
                    NexaStatePanel(
                        title = stringResource(CatalogR.string.warehouse_no_task_title),
                        description = stringResource(CatalogR.string.warehouse_no_task_body)
                    )
                }

                WorkEntryStatus.PermissionUnavailable -> NexaStatePanel(
                    title = stringResource(CatalogR.string.warehouse_permission_title),
                    description = stringResource(CatalogR.string.warehouse_permission_body)
                )

                WorkEntryStatus.PermissionUnknown -> NexaStatePanel(
                    title = stringResource(CatalogR.string.warehouse_permission_unknown_title),
                    description = stringResource(CatalogR.string.warehouse_permission_unknown_body)
                )

                WorkEntryStatus.ContextInvalidated -> NexaStatePanel(
                    title = stringResource(CatalogR.string.warehouse_context_invalid_title),
                    description = stringResource(
                        CatalogR.string.warehouse_catalog_context_invalid_body
                    )
                )

                WorkEntryStatus.SessionInvalidated -> NexaStatePanel(
                    title = stringResource(CatalogR.string.warehouse_session_invalid_title),
                    description = stringResource(
                        CatalogR.string.warehouse_catalog_session_invalid_body
                    )
                )
            }
            if (!promoteAdditionalWork && hasAdditionalWork && state.activeContext != null &&
                state.workEntryStatus == WorkEntryStatus.TaskAvailable
            ) {
                additionalWorkContent()
            }
            if (onOpenProfile == null) {
                onLogout?.let { logout ->
                    OutlinedButton(
                        onClick = logout,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(CatalogR.string.warehouse_logout))
                    }
                }
            }
        }
    }
}

@Composable
private fun defaultCapabilityLabels(): Map<WorkEntryCapability, WorkEntryCapabilityLabel> = mapOf(
    WorkEntryCapability.CatalogIdentification to WorkEntryCapabilityLabel(
        title = stringResource(CatalogR.string.warehouse_identify_product),
        description = stringResource(CatalogR.string.warehouse_identify_product_support)
    ),
    WorkEntryCapability.BarcodeIdentification to WorkEntryCapabilityLabel(
        title = stringResource(CatalogR.string.warehouse_scan_product_code),
        description = stringResource(CatalogR.string.warehouse_scan_product_code_support)
    )
)
