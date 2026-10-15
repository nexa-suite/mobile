package com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.core.designsystem.NexaPrimaryButton
import com.nexa.mobile.operations.core.designsystem.NexaWorkSection
import com.nexa.mobile.operations.core.designsystem.NexaWorkSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkItem
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R

/** Lists current server-authorized picking work; opening a row triggers a fresh detail read. */
@Composable
fun PickingWorkListScreen(
    state: PickingWorkListUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onOpenByReference: () -> Unit = {},
    onSelectFulfillment: (String) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.picking_work_back)) }
            NexaWorkSummary(
                title = stringResource(R.string.picking_work_title),
                description = stringResource(R.string.picking_work_support)
            )

            when (state.status) {
                PickingWorkListStatus.Loading -> Text(stringResource(R.string.picking_work_loading))

                PickingWorkListStatus.NetworkUnavailable -> Text(
                    stringResource(R.string.picking_work_network_error),
                    color = MaterialTheme.colorScheme.error
                )

                PickingWorkListStatus.ServiceUnavailable -> Text(
                    stringResource(R.string.picking_work_service_error),
                    color = MaterialTheme.colorScheme.error
                )

                PickingWorkListStatus.PermissionDenied -> Text(
                    stringResource(R.string.picking_work_permission_error),
                    color = MaterialTheme.colorScheme.error
                )

                PickingWorkListStatus.ContextInvalidated -> Text(
                    stringResource(R.string.warehouse_context_invalid_body),
                    color = MaterialTheme.colorScheme.error
                )

                PickingWorkListStatus.SessionInvalidated -> Text(
                    stringResource(R.string.warehouse_session_invalid_body),
                    color = MaterialTheme.colorScheme.error
                )

                PickingWorkListStatus.Ready -> {
                    NexaWorkSection(stringResource(R.string.picking_work_queue)) {
                        Text(
                            stringResource(R.string.picking_work_total, state.totalItems),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(stringResource(R.string.picking_work_selection_support))
                    }
                    Text(
                        stringResource(
                            R.string.picking_work_freshness,
                            state.asOf?.toString().orEmpty(),
                            state.totalItems
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.items.isEmpty()) {
                        NexaFeedbackBanner(
                            stringResource(R.string.picking_work_empty),
                            NexaFeedbackTone.Information
                        )
                    } else {
                        state.items.forEach { item ->
                            PickingWorkItemCard(item, onSelectFulfillment)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(onClick = onPreviousPage, enabled = state.page > 0) {
                                Text(stringResource(R.string.picking_work_previous))
                            }
                            OutlinedButton(
                                onClick = onNextPage,
                                enabled = (state.page.toLong() + 1) * state.size < state.totalItems
                            ) {
                                Text(stringResource(R.string.picking_work_next))
                            }
                        }
                    }
                }

                PickingWorkListStatus.NotRequested -> Unit
            }

            OutlinedButton(
                onClick = onReload,
                enabled = state.status != PickingWorkListStatus.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.picking_work_reload))
            }
            OutlinedButton(
                onClick = onOpenByReference,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.picking_work_open_by_reference))
            }
        }
    }
}

@Composable
private fun PickingWorkItemCard(item: PickingWorkItem, onSelectFulfillment: (String) -> Unit) {
    NexaWorkSection(stringResource(R.string.picking_work_item_title, item.fulfillmentId)) {
        Text(
            stringResource(R.string.picking_work_item_lines, item.lineCount),
            style = MaterialTheme.typography.titleLarge
        )
        Text(stringResource(R.string.picking_work_item_order, item.salesOrderId))
        Text(
            stringResource(R.string.picking_work_item_status, item.status, item.version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NexaPrimaryButton(
            label = stringResource(R.string.picking_work_open),
            onClick = { onSelectFulfillment(item.fulfillmentId) }
        )
    }
}
