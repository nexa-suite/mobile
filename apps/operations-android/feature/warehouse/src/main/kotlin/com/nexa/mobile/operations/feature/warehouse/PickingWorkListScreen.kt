package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.feature.warehouse.model.PickingWorkItem

/** Lists current server-authorized picking work; opening a row triggers a fresh detail read. */
@Composable
fun PickingWorkListScreen(
    state: PickingWorkListUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSelectFulfillment: (String) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.picking_work_back)) }
            Text(
                text = stringResource(R.string.picking_work_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.picking_work_support),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    Text(
                        stringResource(
                            R.string.picking_work_freshness,
                            state.asOf?.toString().orEmpty(),
                            state.totalItems
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.items.isEmpty()) {
                        Text(stringResource(R.string.picking_work_empty))
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
        }
    }
}

@Composable
private fun PickingWorkItemCard(item: PickingWorkItem, onSelectFulfillment: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.picking_work_item_title, item.fulfillmentId),
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.picking_work_item_status, item.status, item.version))
            Text(stringResource(R.string.picking_work_item_order, item.salesOrderId))
            Text(stringResource(R.string.picking_work_item_lines, item.lineCount))
            Button(
                onClick = { onSelectFulfillment(item.fulfillmentId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.picking_work_open))
            }
        }
    }
}
