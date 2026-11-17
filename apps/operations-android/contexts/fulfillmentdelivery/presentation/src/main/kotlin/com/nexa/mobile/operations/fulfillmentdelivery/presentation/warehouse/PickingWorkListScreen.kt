package com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListCacheItem
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkItem
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyph
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyphKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Shows current work or an explicitly stale, summary-only read-only snapshot. */
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
    val totalItems = state.totalItems.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NexaTopAppBar(title = stringResource(R.string.picking_work_title), onBack = onBack)

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
                        pluralStringResource(R.plurals.picking_work_total, totalItems, totalItems),
                        style = MaterialTheme.typography.titleMedium,
                        color = NexaColors.TextSecondary
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

                PickingWorkListStatus.Cached -> {
                    val cached = state.cachedProjection
                    if (cached == null) {
                        NexaFeedbackBanner(
                            stringResource(R.string.picking_work_network_error),
                            NexaFeedbackTone.Warning
                        )
                    } else {
                        NexaFeedbackBanner(
                            stringResource(
                                R.string.picking_work_cached_notice,
                                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                                    .withLocale(LocalLocale.current.platformLocale)
                                    .withZone(ZoneId.systemDefault())
                                    .format(cached.asOf)
                            ),
                            NexaFeedbackTone.Warning
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.picking_work_total,
                                cached.totalItems.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                                cached.totalItems.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = NexaColors.TextSecondary
                        )
                        if (cached.items.isEmpty()) {
                            NexaFeedbackBanner(
                                stringResource(R.string.picking_work_empty),
                                NexaFeedbackTone.Information
                            )
                        } else {
                            cached.items.forEach { item ->
                                PickingCachedWorkItemCard(item)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = onPreviousPage,
                                    enabled = cached.page > 0
                                ) {
                                    Text(stringResource(R.string.picking_work_previous))
                                }
                                OutlinedButton(
                                    onClick = onNextPage,
                                    enabled = (cached.page.toLong() + 1) * cached.size <
                                        cached.totalItems
                                ) {
                                    Text(stringResource(R.string.picking_work_next))
                                }
                            }
                        }
                    }
                }

                PickingWorkListStatus.NotRequested -> Unit
            }

            TextButton(
                onClick = onReload,
                enabled = state.status != PickingWorkListStatus.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.picking_work_reload))
            }
            TextButton(
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
    val status = pickingStatusLabel(item.status)
    val accessibilityLabel = pluralStringResource(
        R.plurals.picking_work_item_accessibility,
        item.lineCount,
        item.lineCount,
        status
    )
    Card(
        onClick = { onSelectFulfillment(item.fulfillmentId) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .semantics { contentDescription = accessibilityLabel },
        shape = NexaShapes.card,
        colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
        border = BorderStroke(1.dp, NexaColors.Border)
    ) {
        PickingWorkItemCardContent(item.orderNumber, item.lineCount, item.status, item.version)
    }
}

@Composable
private fun PickingCachedWorkItemCard(item: PickingWorkListCacheItem) {
    val status = pickingStatusLabel(item.status)
    val accessibilityLabel = pluralStringResource(
        R.plurals.picking_work_item_accessibility,
        item.lineCount,
        item.lineCount,
        status
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .semantics { contentDescription = accessibilityLabel },
        shape = NexaShapes.card,
        colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
        border = BorderStroke(1.dp, NexaColors.Border)
    ) {
        PickingWorkItemCardContent(item.orderNumber, item.lineCount, item.status, item.version)
    }
}

@Composable
private fun PickingWorkItemCardContent(
    orderNumber: String?,
    lineCount: Int,
    rawStatus: String,
    version: Long
) {
    val status = pickingStatusLabel(rawStatus)
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OperationsGlyph(OperationsGlyphKind.Picking)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    orderNumber?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.picking_work_item_heading),
                    style = MaterialTheme.typography.titleMedium,
                    color = NexaColors.TextPrimary
                )
                Text(
                    stringResource(R.string.picking_work_item_lines, lineCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )
                Text(
                    stringResource(R.string.picking_work_item_status, status, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )
            }
            Surface(
                shape = NexaShapes.pill,
                color = NexaColors.InfoSurface,
                contentColor = NexaColors.PrimaryStrong,
                border = BorderStroke(1.dp, NexaColors.InfoBorder)
            ) {
                Text(
                    status,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun pickingStatusLabel(status: String): String = when (status.uppercase()) {
    "ALLOCATED" -> stringResource(R.string.picking_work_status_allocated)
    "PICKING" -> stringResource(R.string.picking_work_status_picking)
    "PICKED" -> stringResource(R.string.picking_work_status_picked)
    "PACKED" -> stringResource(R.string.picking_work_status_packed)
    "STAGED" -> stringResource(R.string.picking_work_status_staged)
    "READY_FOR_DISPATCH" -> stringResource(R.string.picking_work_status_ready)
    else -> status.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
}
