package com.nexa.mobile.operations.visibility

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.R
import com.nexa.mobile.operations.core.designsystem.R as DesignR
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchReadinessAutoRefresh
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchReadinessStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchReadinessUiState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun OperationsOverviewScreen(
    state: DispatchReadinessUiState,
    tenantId: String,
    workspaceId: String,
    exceptionsOnly: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOwningWork: (String) -> Unit
) {
    DispatchReadinessAutoRefresh(state = state, onRefresh = onRefresh)
    val hasVisibleFacts = state.status in
        setOf(DispatchReadinessStatus.Current, DispatchReadinessStatus.Empty) ||
        (state.status == DispatchReadinessStatus.Loading && state.observedAt != null)
    val visibleItems = if (!hasVisibleFacts) {
        emptyList()
    } else if (exceptionsOnly) {
        state.items.filter { !it.ready || it.reasons.isNotEmpty() }
    } else {
        state.items
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(DesignR.drawable.ic_arrow_back),
                        stringResource(R.string.overview_back)
                    )
                }
                Text(
                    stringResource(
                        if (exceptionsOnly) R.string.overview_attention else R.string.overview_title
                    ),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Text(
                stringResource(R.string.overview_description),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.status == DispatchReadinessStatus.Loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (!hasVisibleFacts) {
                    Text(
                        stringResource(state.status.message()),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = state.status != DispatchReadinessStatus.Loading
                ) { Text(stringResource(R.string.overview_refresh)) }
            }
        }
        if (hasVisibleFacts && visibleItems.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    val emptyMessage =
                        if (exceptionsOnly) {
                            R.string.overview_no_pending
                        } else {
                            R.string.overview_empty
                        }
                    Text(
                        stringResource(emptyMessage),
                        Modifier.padding(24.dp)
                    )
                }
            }
        }
        items(visibleItems, key = { "$tenantId:$workspaceId:${it.fulfillmentId}" }) { item ->
            ReadinessCard(item, tenantId, workspaceId, onOpenOwningWork)
        }
    }
}

@Composable
private fun ReadinessCard(
    item: DispatchReadiness,
    tenantId: String,
    workspaceId: String,
    onOpen: (String) -> Unit
) {
    var showDetails by rememberSaveable(tenantId, workspaceId, item.fulfillmentId) {
        mutableStateOf(false)
    }
    val locale = LocalConfiguration.current.locales[0]
    val updatedAt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(locale).withZone(ZoneId.systemDefault()).format(item.asOf)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.overview_short_reference, item.fulfillmentId.take(8)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(
                    if (item.ready) R.string.overview_ready else R.string.overview_pending
                ),
                style = MaterialTheme.typography.titleLarge,
                color =
                    if (item.ready) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
            )
            ReadinessStep(R.string.overview_stock, item.allocationComplete)
            ReadinessStep(R.string.overview_picking, item.pickingComplete)
            ReadinessStep(R.string.overview_evidence, item.pickingEvidenceComplete)
            if (item.reasons.isNotEmpty()) {
                Text(
                    stringResource(R.string.overview_review),
                    style = MaterialTheme.typography.labelLarge
                )
                item.reasons.distinct().forEach { reason ->
                    Text(
                        stringResource(reason.reasonLabel()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Text(
                stringResource(R.string.overview_updated, updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = {
                onOpen(item.fulfillmentId)
            }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.overview_open))
            }
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(stringResource(R.string.overview_reference))
                Icon(
                    painterResource(DesignR.drawable.ic_chevron_right),
                    null,
                    Modifier.rotate(if (showDetails) -90f else 90f)
                )
            }
            if (showDetails) Text(item.fulfillmentId, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReadinessStep(@StringRes label: Int, complete: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(
                if (complete) DesignR.drawable.ic_check else DesignR.drawable.ic_warning
            ),
            contentDescription = null,
            tint =
                if (complete) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
        )
        Text(
            stringResource(
                if (complete) R.string.overview_step_complete else R.string.overview_step_pending,
                stringResource(label)
            ),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@StringRes
private fun DispatchReadinessStatus.message(): Int = when (this) {
    DispatchReadinessStatus.Initial, DispatchReadinessStatus.Loading -> R.string.overview_loading

    DispatchReadinessStatus.PermissionUnknown,
    DispatchReadinessStatus.PermissionDenied -> R.string.overview_denied

    DispatchReadinessStatus.ContextInvalidated,
    DispatchReadinessStatus.SessionInvalidated -> R.string.overview_session

    DispatchReadinessStatus.NetworkUnavailable -> R.string.overview_offline

    else -> R.string.overview_unavailable
}

@StringRes
private fun String.reasonLabel(): Int = when (this) {
    "FULFILLMENT_NOT_READY_FOR_DISPATCH" -> R.string.overview_reason_preparation
    "PHYSICAL_ALLOCATION_NOT_CURRENT" -> R.string.overview_reason_stock
    "PICKING_INCOMPLETE" -> R.string.overview_reason_picking
    "PICKING_EVIDENCE_INCOMPLETE" -> R.string.overview_reason_evidence
    else -> R.string.overview_reason_other
}
