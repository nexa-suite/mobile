package com.nexa.mobile.operations.workentry

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
import com.nexa.mobile.operations.R
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.R as CatalogR
import com.nexa.mobile.operations.core.designsystem.NexaActiveContextBar
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R as FulfillmentR
import com.nexa.mobile.operations.inventoryavailability.presentation.R as InventoryR

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
    onLogout: (() -> Unit)? = null,
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
                    Text(stringResource(R.string.warehouse_logout))
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
                                title = stringResource(FulfillmentR.string.picking_title),
                                description = stringResource(
                                    FulfillmentR.string.picking_entry_support
                                ),
                                onClick = onPickStock
                            )

                            WorkEntryCapability.StockCondition -> NexaTaskRow(
                                title = stringResource(InventoryR.string.stock_condition_title),
                                description = stringResource(
                                    InventoryR.string.stock_condition_disclaimer
                                ),
                                onClick = onViewStock
                            )

                            WorkEntryCapability.Receiving -> NexaTaskRow(
                                title = stringResource(InventoryR.string.receiving_title),
                                description = stringResource(
                                    InventoryR.string.receiving_choose_product
                                ),
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
                    title = stringResource(CatalogR.string.warehouse_context_invalid_title),
                    description = stringResource(FulfillmentR.string.warehouse_context_invalid_body)
                )

                WorkEntryStatus.SessionInvalidated -> NexaStatePanel(
                    title = stringResource(CatalogR.string.warehouse_session_invalid_title),
                    description = stringResource(FulfillmentR.string.warehouse_session_invalid_body)
                )
            }
        }
    }
}
