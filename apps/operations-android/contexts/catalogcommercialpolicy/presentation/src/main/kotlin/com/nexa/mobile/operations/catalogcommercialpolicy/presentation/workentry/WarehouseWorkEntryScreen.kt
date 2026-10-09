package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.R as CatalogR
import com.nexa.mobile.operations.core.designsystem.NexaActiveContextBar
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar

@Composable
fun OperationsWorkEntryScreen(
    state: WarehouseUiState,
    modifier: Modifier = Modifier,
    additionalCapabilityLabels: Map<WorkEntryCapability, WorkEntryCapabilityLabel> = emptyMap(),
    onChangeContext: () -> Unit,
    onIdentifyProduct: () -> Unit,
    onScanProductCode: () -> Unit = {},
    onReceiveStock: () -> Unit = {},
    onPickStock: () -> Unit = {},
    onViewStock: () -> Unit = {},
    onLogout: (() -> Unit)? = null,
    capabilities: List<WorkEntryCapability> = listOf(WorkEntryCapability.CatalogIdentification),
    additionalWorkContent: @Composable () -> Unit = {}
) {
    val capabilityLabels = defaultCapabilityLabels() + additionalCapabilityLabels
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        NexaTopAppBar(title = stringResource(CatalogR.string.warehouse_operations_title))
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
            onLogout?.let { logout ->
                OutlinedButton(
                    onClick = logout,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(CatalogR.string.warehouse_logout))
                }
            }
            if (state.activeContext != null &&
                state.workEntryStatus == WorkEntryStatus.TaskAvailable
            ) {
                additionalWorkContent()
            }
            when (state.workEntryStatus) {
                WorkEntryStatus.TaskAvailable -> if (capabilities.isNotEmpty()) {
                    Text(
                        stringResource(CatalogR.string.warehouse_work_available),
                        style = MaterialTheme.typography.titleMedium,
                        color = NexaColors.TextPrimary
                    )
                    capabilities.forEach { capability ->
                        val label = requireNotNull(capabilityLabels[capability]) {
                            "Missing work-entry label for $capability"
                        }
                        NexaTaskRow(
                            title = label.title,
                            description = label.description,
                            onClick = when (capability) {
                                WorkEntryCapability.CatalogIdentification -> onIdentifyProduct
                                WorkEntryCapability.BarcodeIdentification -> onScanProductCode
                                WorkEntryCapability.Receiving -> onReceiveStock
                                WorkEntryCapability.StockCondition -> onViewStock
                                WorkEntryCapability.Picking -> onPickStock
                            }
                        )
                    }
                } else {
                    NexaStatePanel(
                        title = stringResource(CatalogR.string.warehouse_no_task_title),
                        description = stringResource(CatalogR.string.warehouse_no_task_body)
                    )
                }

                WorkEntryStatus.NoPermittedTask -> NexaStatePanel(
                    title = stringResource(CatalogR.string.warehouse_no_task_title),
                    description = stringResource(CatalogR.string.warehouse_no_task_body)
                )

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
