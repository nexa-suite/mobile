package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.NexaWorkSection
import com.nexa.mobile.operations.core.designsystem.NexaWorkSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyph
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyphKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun DispatchReadinessScreen(
    state: DispatchReadinessUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectFulfillment: (String) -> Unit,
    onClearSelection: () -> Unit,
    onRouteClosed: () -> Unit,
    onAssignFulfillment: ((String) -> Unit)? = null,
    onVerifyOutgoingGoods: ((String) -> Unit)? = null,
    onConfirmHandover: ((String) -> Unit)? = null,
    onRecordTemperature: ((String) -> Unit)? = null,
    onEditDeliveryInstructions: ((String) -> Unit)? = null,
    onReviewExecutionHold: ((String) -> Unit)? = null,
    onRefreshDeliveryPod: (() -> Unit)? = null,
    onSealPod: ((String) -> Unit)? = null,
    onRetryPodSeal: (() -> Unit)? = null,
    canSealPod: Boolean = false
) {
    DispatchReadinessAutoRefresh(
        state = state,
        onRefresh = onRefresh,
        enabled = state.selectedFulfillmentId == null
    )
    val closeAction = rememberUpdatedState(onRouteClosed)
    var showWorkList by remember(state.selectedFulfillmentId) {
        mutableStateOf(state.selectedFulfillmentId == null)
    }
    DisposableEffect(Unit) {
        onDispose { closeAction.value() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
        ) {
            val showTabletBoard = maxWidth >= 840.dp
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    NexaTopAppBar(
                        title = stringResource(R.string.dispatch_visual_title),
                        onBack = onBack
                    )
                }
                if (state.status != DispatchReadinessStatus.Current) {
                    item { ReadinessStatusPanel(state.status, state.asOf) }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        state.asOf?.let { asOf ->
                            Text(
                                stringResource(
                                    R.string.dispatch_visual_updated,
                                    formatInstant(asOf)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = NexaColors.TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        TextButton(
                            onClick = onRefresh,
                            enabled = state.status != DispatchReadinessStatus.Loading,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.dispatch_visual_refresh)) }
                    }
                }
                if (state.status == DispatchReadinessStatus.Loading) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.dispatch_readiness_loading))
                        }
                    }
                }
                if (state.items.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.dispatch_readiness_count, state.items.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (state.selectedFulfillmentId != null && state.items.size > 1) {
                            TextButton(onClick = { showWorkList = !showWorkList }) {
                                Text(
                                    stringResource(
                                        if (showWorkList) {
                                            R.string.dispatch_readiness_hide_list
                                        } else {
                                            R.string.dispatch_readiness_change_selection
                                        }
                                    )
                                )
                            }
                        }
                    }
                    if (showWorkList) {
                        if (showTabletBoard) {
                            item {
                                DispatchReadinessBoard(
                                    readinessItems = state.items,
                                    selectedFulfillmentId = state.selectedFulfillmentId,
                                    onSelectFulfillment = onSelectFulfillment
                                )
                            }
                        } else {
                            items(state.items, key = { it.fulfillmentId.lowercase() }) { item ->
                                ReadinessCard(
                                    item = item,
                                    selected = state.selectedFulfillmentId == item.fulfillmentId,
                                    onClick = { onSelectFulfillment(item.fulfillmentId) }
                                )
                            }
                        }
                    }
                }
                if (state.selectedFulfillmentId != null) {
                    item {
                        val selectedDetail = state.detail
                        if (selectedDetail != null &&
                            state.detailStatus == DispatchReadinessDetailStatus.Current
                        ) {
                            ReadinessDetail(selectedDetail, onClearSelection)
                            val deliveryId = selectedDetail.deliveryId
                            if (deliveryId != null) {
                                DeliveryPodPanel(
                                    state = state,
                                    canSeal = canSealPod,
                                    onRefresh = onRefreshDeliveryPod ?: {},
                                    onSeal = { onSealPod?.invoke(deliveryId) },
                                    onRetrySeal = onRetryPodSeal ?: {}
                                )
                            }
                            NexaWorkSection(stringResource(R.string.dispatch_work_actions)) {
                                if (onVerifyOutgoingGoods != null) {
                                    TextButton(onClick = {
                                        onVerifyOutgoingGoods(selectedDetail.fulfillmentId)
                                    }) {
                                        Text(stringResource(R.string.dispatch_work_outgoing))
                                    }
                                }
                                if (onRecordTemperature != null) {
                                    TextButton(onClick = {
                                        onRecordTemperature(selectedDetail.fulfillmentId)
                                    }) {
                                        Text(stringResource(R.string.dispatch_work_temperature))
                                    }
                                }
                                if (onConfirmHandover != null) {
                                    TextButton(onClick = {
                                        onConfirmHandover(selectedDetail.fulfillmentId)
                                    }) { Text(stringResource(R.string.dispatch_work_handover)) }
                                }
                                if (deliveryId != null && onEditDeliveryInstructions != null) {
                                    TextButton(
                                        onClick = { onEditDeliveryInstructions(deliveryId) }
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.dispatch_readiness_open_instructions
                                            )
                                        )
                                    }
                                }
                                if (deliveryId != null && onReviewExecutionHold != null) {
                                    TextButton(onClick = { onReviewExecutionHold(deliveryId) }) {
                                        Text(
                                            stringResource(
                                                R.string.dispatch_readiness_execution_hold
                                            )
                                        )
                                    }
                                }
                                if (onAssignFulfillment != null) {
                                    TextButton(onClick = {
                                        onAssignFulfillment(selectedDetail.fulfillmentId)
                                    }) {
                                        Text(stringResource(R.string.dispatch_assignment_title))
                                    }
                                }
                            }
                        } else {
                            DetailStatusPanel(state.detailStatus, onClearSelection)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryPodPanel(
    state: DispatchReadinessUiState,
    canSeal: Boolean,
    onRefresh: () -> Unit,
    onSeal: () -> Unit,
    onRetrySeal: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.dispatch_pod_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            when (state.deliveryPodStatus) {
                DispatchDeliveryPodStatus.NotRequested ->
                    Text(stringResource(R.string.dispatch_pod_unavailable))

                DispatchDeliveryPodStatus.Loading -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.dispatch_pod_loading))
                }

                DispatchDeliveryPodStatus.Current -> {
                    val current = state.deliveryPod
                    if (current == null) {
                        Text(stringResource(R.string.dispatch_pod_unavailable))
                    } else {
                        Text(
                            stringResource(
                                R.string.dispatch_pod_delivery_state,
                                deliveryStatusLabel(current.deliveryStatus)
                            )
                        )
                        val pod = current.pod
                        if (pod == null) {
                            Text(stringResource(R.string.dispatch_pod_not_captured))
                        } else {
                            Text(stringResource(R.string.dispatch_pod_status, pod.status))
                            pod.receiverName?.let {
                                Text(stringResource(R.string.dispatch_pod_receiver, it))
                            }
                            pod.capturedAt?.let {
                                Text(
                                    stringResource(
                                        R.string.dispatch_pod_captured_at,
                                        formatInstant(it)
                                    )
                                )
                            }
                            pod.sealedAt?.let {
                                Text(
                                    stringResource(
                                        R.string.dispatch_pod_sealed_at,
                                        formatInstant(it)
                                    )
                                )
                            }
                            if (pod.status == "SEALED") {
                                Text(stringResource(R.string.dispatch_pod_async_documents))
                            }
                        }
                    }
                }

                DispatchDeliveryPodStatus.NetworkUnavailable ->
                    Text(stringResource(R.string.dispatch_pod_network))

                DispatchDeliveryPodStatus.ServiceUnavailable ->
                    Text(stringResource(R.string.dispatch_pod_service))

                DispatchDeliveryPodStatus.PermissionDenied ->
                    Text(stringResource(R.string.dispatch_pod_permission))

                DispatchDeliveryPodStatus.ContextInvalidated ->
                    Text(stringResource(R.string.dispatch_pod_context))

                DispatchDeliveryPodStatus.SessionInvalidated ->
                    Text(stringResource(R.string.dispatch_pod_session))
            }

            when (state.podSealStatus) {
                DispatchPodSealStatus.Idle -> Unit

                DispatchPodSealStatus.Submitting -> Text(
                    stringResource(R.string.dispatch_pod_sealing)
                )

                DispatchPodSealStatus.Sealed -> Text(stringResource(R.string.dispatch_pod_sealed))

                DispatchPodSealStatus.UnknownOutcome -> Text(
                    stringResource(R.string.dispatch_pod_unknown)
                )

                DispatchPodSealStatus.Stale -> Text(stringResource(R.string.dispatch_pod_stale))

                DispatchPodSealStatus.Conflict -> Text(
                    stringResource(R.string.dispatch_pod_conflict)
                )

                DispatchPodSealStatus.ServiceUnavailable -> Text(
                    stringResource(R.string.dispatch_pod_service)
                )

                DispatchPodSealStatus.PermissionDenied -> Text(
                    stringResource(R.string.dispatch_pod_permission)
                )
            }

            if (canSeal && state.podSealStatus == DispatchPodSealStatus.Idle) {
                Button(onClick = onSeal, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dispatch_pod_seal))
                }
            }
            if (state.pendingPodSeal != null && state.podSealStatus in setOf(
                    DispatchPodSealStatus.UnknownOutcome,
                    DispatchPodSealStatus.ServiceUnavailable
                )
            ) {
                Button(onClick = onRetrySeal, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dispatch_pod_retry_same))
                }
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = state.deliveryPodStatus != DispatchDeliveryPodStatus.Loading &&
                    state.podSealStatus != DispatchPodSealStatus.Submitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.dispatch_pod_refresh))
            }
        }
    }
}

@Composable
private fun ReadinessStatusPanel(status: DispatchReadinessStatus, asOf: Instant?) {
    val title = when (status) {
        DispatchReadinessStatus.Initial -> R.string.dispatch_readiness_initial_title

        DispatchReadinessStatus.Loading -> R.string.dispatch_readiness_loading_title

        DispatchReadinessStatus.Current -> R.string.dispatch_readiness_current_title

        DispatchReadinessStatus.Empty -> R.string.dispatch_readiness_empty_title

        DispatchReadinessStatus.PermissionUnknown ->
            R.string.dispatch_readiness_permission_unknown_title

        DispatchReadinessStatus.PermissionDenied ->
            R.string.dispatch_readiness_permission_denied_title

        DispatchReadinessStatus.NetworkUnavailable -> R.string.dispatch_readiness_network_title

        DispatchReadinessStatus.ServiceUnavailable -> R.string.dispatch_readiness_service_title

        DispatchReadinessStatus.ContextInvalidated -> R.string.dispatch_readiness_context_title

        DispatchReadinessStatus.SessionInvalidated -> R.string.dispatch_readiness_session_title
    }
    val body = when (status) {
        DispatchReadinessStatus.Initial -> R.string.dispatch_readiness_initial_body

        DispatchReadinessStatus.Loading -> R.string.dispatch_readiness_loading

        DispatchReadinessStatus.Current -> R.string.dispatch_readiness_current_body

        DispatchReadinessStatus.Empty -> R.string.dispatch_readiness_empty_body

        DispatchReadinessStatus.PermissionUnknown ->
            R.string.dispatch_readiness_permission_unknown_body

        DispatchReadinessStatus.PermissionDenied ->
            R.string.dispatch_readiness_permission_denied_body

        DispatchReadinessStatus.NetworkUnavailable -> R.string.dispatch_readiness_network_body

        DispatchReadinessStatus.ServiceUnavailable -> R.string.dispatch_readiness_service_body

        DispatchReadinessStatus.ContextInvalidated -> R.string.dispatch_readiness_context_body

        DispatchReadinessStatus.SessionInvalidated -> R.string.dispatch_readiness_session_body
    }
    StatusCard(stringResource(title), stringResource(body)) {
        if (status == DispatchReadinessStatus.Current && asOf != null) {
            Text(
                stringResource(R.string.dispatch_readiness_as_of, formatInstant(asOf)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailStatusPanel(status: DispatchReadinessDetailStatus, onClose: () -> Unit) {
    val (title, body) = when (status) {
        DispatchReadinessDetailStatus.NotRequested ->
            R.string.dispatch_readiness_detail_title to R.string.dispatch_readiness_detail_select

        DispatchReadinessDetailStatus.Loading ->
            R.string.dispatch_readiness_detail_title to R.string.dispatch_readiness_loading

        DispatchReadinessDetailStatus.Current ->
            R.string.dispatch_readiness_detail_title to R.string.dispatch_readiness_detail_select

        DispatchReadinessDetailStatus.NetworkUnavailable ->
            R.string.dispatch_readiness_network_title to R.string.dispatch_readiness_network_body

        DispatchReadinessDetailStatus.ServiceUnavailable ->
            R.string.dispatch_readiness_service_title to R.string.dispatch_readiness_service_body

        DispatchReadinessDetailStatus.PermissionDenied ->
            R.string.dispatch_readiness_permission_denied_title to
                R.string.dispatch_readiness_permission_denied_body

        DispatchReadinessDetailStatus.ContextInvalidated ->
            R.string.dispatch_readiness_context_title to R.string.dispatch_readiness_context_body

        DispatchReadinessDetailStatus.SessionInvalidated ->
            R.string.dispatch_readiness_session_title to R.string.dispatch_readiness_session_body
    }
    StatusCard(stringResource(title), stringResource(body)) {
        if (status == DispatchReadinessDetailStatus.Loading) CircularProgressIndicator()
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.dispatch_readiness_close_detail))
        }
    }
}

@Composable
private fun ReadinessCard(item: DispatchReadiness, selected: Boolean, onClick: () -> Unit) {
    val orderNumber = item.orderNumber?.trim()?.takeIf(String::isNotEmpty)
    val orderLabel = orderNumber?.let {
        stringResource(R.string.dispatch_readiness_order_label, it)
    } ?: stringResource(R.string.dispatch_readiness_order_fallback)
    val customerLabel = item.customerName?.trim()?.takeIf(String::isNotEmpty)
        ?: stringResource(R.string.dispatch_readiness_customer_fallback)
    val priorityLabel = dispatchPriorityResource(item.priority)?.let { stringResource(it) }
        ?: stringResource(R.string.dispatch_readiness_priority_not_provided)
    val hasDispatchedDelivery = item.deliveryStatus?.uppercase(Locale.ROOT) in
        setOf("DISPATCHED", "IN_TRANSIT", "DELIVERED", "PARTIAL", "FAILED", "CANCELLED")
    val statusLabel = if (hasDispatchedDelivery) {
        deliveryStatusLabel(item.deliveryStatus)
    } else {
        stringResource(
            if (item.ready) {
                R.string.dispatch_readiness_ready
            } else {
                R.string.dispatch_readiness_not_ready
            }
        )
    }
    val statusColor = when {
        item.deliveryStatus?.uppercase(
            Locale.ROOT
        ) in setOf("FAILED", "CANCELLED") -> NexaColors.Danger

        item.ready || item.deliveryStatus.equals("DELIVERED", true) -> NexaColors.Success

        hasDispatchedDelivery -> NexaColors.Info

        else -> NexaColors.Warning
    }
    val statusSurface = when (statusColor) {
        NexaColors.Danger -> NexaColors.DangerSurface
        NexaColors.Success -> NexaColors.SuccessSurface
        NexaColors.Info -> NexaColors.InfoSurface
        else -> NexaColors.WarningSurface
    }
    val statusBorder = when (statusColor) {
        NexaColors.Danger -> NexaColors.DangerBorder
        NexaColors.Success -> NexaColors.SuccessBorder
        NexaColors.Info -> NexaColors.InfoBorder
        else -> NexaColors.WarningBorder
    }
    val accessibilityLabel = stringResource(
        R.string.dispatch_readiness_card_accessibility,
        orderLabel,
        customerLabel,
        priorityLabel,
        statusLabel,
        stringResource(
            if (item.allocationComplete) {
                R.string.dispatch_readiness_complete
            } else {
                R.string.dispatch_readiness_pending
            }
        ),
        stringResource(
            if (item.pickingComplete) {
                R.string.dispatch_readiness_complete
            } else {
                R.string.dispatch_readiness_pending
            }
        ),
        stringResource(
            if (item.pickingEvidenceComplete) {
                R.string.dispatch_readiness_complete
            } else {
                R.string.dispatch_readiness_pending
            }
        ),
        item.lines.size,
        item.deliveryStatus?.let { deliveryStatusLabel(it) }
            ?: stringResource(R.string.dispatch_readiness_no_delivery)
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                role = Role.Button
            },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                NexaColors.InfoSurface
            } else {
                NexaColors.Surface
            }
        ),
        border = BorderStroke(
            1.dp,
            if (selected) {
                NexaColors.Primary
            } else if (item.ready) {
                NexaColors.SuccessBorder
            } else {
                NexaColors.Border
            }
        ),
        shape = NexaShapes.card
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OperationsGlyph(OperationsGlyphKind.Dispatch)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.dispatch_readiness_card_eyebrow),
                        style = MaterialTheme.typography.labelSmall,
                        color = NexaColors.PrimaryStrong,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        orderLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = NexaColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    customerLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                DispatchPriorityBadge(item.priority)
            }

            HorizontalDivider(color = NexaColors.Border)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.dispatch_readiness_lines, item.lines.size),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = NexaShapes.pill,
                    color = statusSurface,
                    contentColor = statusColor,
                    border = BorderStroke(1.dp, statusBorder)
                ) {
                    Text(
                        statusLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (item.reasons.isNotEmpty() && !hasDispatchedDelivery) {
                Surface(
                    shape = NexaShapes.control,
                    color = NexaColors.WarningSurface,
                    border = BorderStroke(1.dp, NexaColors.WarningBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item.reasons.take(1).forEach { reason ->
                            Text(
                                reasonLabel(reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaColors.Warning
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    stringResource(R.string.dispatch_readiness_open_detail) + "  →",
                    style = MaterialTheme.typography.labelLarge,
                    color = NexaColors.PrimaryStrong,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private enum class DispatchBoardLane(val labelResource: Int) {
    Preparation(R.string.dispatch_board_preparation),
    Ready(R.string.dispatch_board_ready),
    InRoute(R.string.dispatch_board_in_route),
    Finished(R.string.dispatch_board_finished),
    Pending(R.string.dispatch_board_pending)
}

private val dispatchBoardLanes = listOf(
    DispatchBoardLane.Preparation,
    DispatchBoardLane.Ready,
    DispatchBoardLane.InRoute,
    DispatchBoardLane.Finished,
    DispatchBoardLane.Pending
)

@Composable
private fun DispatchReadinessBoard(
    readinessItems: List<DispatchReadiness>,
    selectedFulfillmentId: String?,
    onSelectFulfillment: (String) -> Unit
) {
    val itemsByLane = remember(readinessItems) { readinessItems.groupBy { it.boardLane() } }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(dispatchBoardLanes, key = { it.name }) { lane ->
            val laneItems = itemsByLane[lane].orEmpty()
            val (surface, border, foreground) = laneTone(lane)
            Surface(
                modifier = Modifier.width(300.dp),
                shape = NexaShapes.card,
                color = NexaColors.Surface,
                border = BorderStroke(1.dp, NexaColors.Border)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(lane.labelResource),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            color = NexaColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Surface(
                            shape = NexaShapes.pill,
                            color = surface,
                            contentColor = foreground,
                            border = BorderStroke(1.dp, border)
                        ) {
                            Text(
                                laneItems.size.toString(),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (laneItems.isEmpty()) {
                        Text(
                            stringResource(R.string.dispatch_board_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = NexaColors.TextSecondary
                        )
                    } else {
                        laneItems.forEach { item ->
                            ReadinessCard(
                                item = item,
                                selected = selectedFulfillmentId == item.fulfillmentId,
                                onClick = { onSelectFulfillment(item.fulfillmentId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DispatchReadiness.boardLane(): DispatchBoardLane {
    when (deliveryStatus?.uppercase(Locale.ROOT)) {
        "DELIVERED" -> return DispatchBoardLane.Finished
        "DISPATCHED", "IN_TRANSIT" -> return DispatchBoardLane.InRoute
        "PARTIAL", "FAILED", "CANCELLED", "PLANNED" -> return DispatchBoardLane.Pending
        null, "ASSIGNED" -> Unit
        else -> return DispatchBoardLane.Pending
    }

    val status = fulfillmentStatus.uppercase(Locale.ROOT)
    return when {
        ready && status == "READY_FOR_DISPATCH" -> DispatchBoardLane.Ready

        !ready && (status == "ALLOCATED" || status == "PICKING") ->
            DispatchBoardLane.Preparation

        else -> DispatchBoardLane.Pending
    }
}

private fun laneTone(lane: DispatchBoardLane): Triple<Color, Color, Color> = when (lane) {
    DispatchBoardLane.Preparation ->
        Triple(NexaColors.WarningSurface, NexaColors.WarningBorder, NexaColors.Warning)

    DispatchBoardLane.Ready,
    DispatchBoardLane.Finished ->
        Triple(NexaColors.SuccessSurface, NexaColors.SuccessBorder, NexaColors.Success)

    DispatchBoardLane.InRoute ->
        Triple(NexaColors.InfoSurface, NexaColors.InfoBorder, NexaColors.Info)

    DispatchBoardLane.Pending ->
        Triple(NexaColors.WarningSurface, NexaColors.WarningBorder, NexaColors.Warning)
}

private data class DispatchPriorityPresentation(
    val labelResource: Int,
    val surface: Color,
    val border: Color,
    val foreground: Color
)

private fun dispatchPriorityPresentation(priority: String?): DispatchPriorityPresentation? =
    when (priority?.uppercase(Locale.ROOT)) {
        "NORMAL" -> DispatchPriorityPresentation(
            R.string.dispatch_readiness_priority_normal,
            NexaColors.InfoSurface,
            NexaColors.InfoBorder,
            NexaColors.Info
        )

        "HIGH" -> DispatchPriorityPresentation(
            R.string.dispatch_readiness_priority_high,
            NexaColors.WarningSurface,
            NexaColors.WarningBorder,
            NexaColors.Warning
        )

        "URGENT" -> DispatchPriorityPresentation(
            R.string.dispatch_readiness_priority_urgent,
            NexaColors.DangerSurface,
            NexaColors.DangerBorder,
            NexaColors.Danger
        )

        else -> null
    }

private fun dispatchPriorityResource(priority: String?): Int? =
    dispatchPriorityPresentation(priority)?.labelResource

@Composable
private fun DispatchPriorityBadge(priority: String?) {
    val presentation = dispatchPriorityPresentation(priority)
    if (presentation == null) {
        Text(
            stringResource(R.string.dispatch_readiness_priority_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = NexaColors.TextMuted
        )
        return
    }
    Surface(
        shape = NexaShapes.pill,
        color = presentation.surface,
        contentColor = presentation.foreground,
        border = BorderStroke(1.dp, presentation.border)
    ) {
        Text(
            stringResource(presentation.labelResource),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ReadinessProgress(item: DispatchReadiness) {
    val steps = listOf(
        stringResource(R.string.dispatch_readiness_step_allocation) to item.allocationComplete,
        stringResource(R.string.dispatch_readiness_step_picking) to item.pickingComplete,
        stringResource(R.string.dispatch_readiness_step_evidence) to item.pickingEvidenceComplete
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEach { (label, complete) ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (complete) NexaColors.Success else NexaColors.BorderStrong)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (complete) "✓" else "–",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (complete) NexaColors.Success else NexaColors.TextMuted,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (complete) NexaColors.Success else NexaColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessDetail(item: DispatchReadiness, onClose: () -> Unit) {
    var expanded by remember(item.fulfillmentId) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
        border = BorderStroke(1.dp, NexaColors.Border),
        shape = NexaShapes.card
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        item.orderNumber?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.dispatch_readiness_order_fallback),
                        style = MaterialTheme.typography.titleLarge
                    )
                    item.customerName?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexaColors.TextSecondary
                        )
                    }
                }
                DispatchPriorityBadge(item.priority)
            }
            Surface(
                shape = NexaShapes.pill,
                color = NexaColors.InfoSurface,
                contentColor = NexaColors.PrimaryStrong
            ) {
                Text(
                    deliveryStatusLabel(item.deliveryStatus ?: item.fulfillmentStatus),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            ReadinessProgress(item)
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(
                        if (expanded) {
                            R.string.dispatch_visual_hide_checks
                        } else {
                            R.string.dispatch_visual_show_checks
                        }
                    )
                )
            }
            if (expanded) {
                HorizontalDivider(color = NexaColors.Border)
                Text(
                    stringResource(
                        R.string.dispatch_readiness_fulfillment_state,
                        deliveryStatusLabel(item.fulfillmentStatus)
                    )
                )
                Text(
                    stringResource(
                        R.string.dispatch_readiness_allocation_state,
                        deliveryStatusLabel(item.physicalAllocationStatus)
                    )
                )
                for (reason in item.reasons) {
                    Text(
                        reasonLabel(reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaColors.Warning
                    )
                }
                item.lines.forEachIndexed { index, line ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.dispatch_readiness_line_number, index + 1),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(
                                R.string.dispatch_readiness_quantities,
                                line.allocatedQuantity.stripTrailingZeros().toPlainString(),
                                line.physicallyAllocatedQuantity
                                    .stripTrailingZeros().toPlainString(),
                                line.pickedQuantity.stripTrailingZeros().toPlainString(),
                                line.evidencedPickedQuantity.stripTrailingZeros().toPlainString()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = NexaColors.TextSecondary
                        )
                    }
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.dispatch_readiness_close_detail))
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable
private fun reasonLabel(reason: String): String = stringResource(
    when (reason) {
        "FULFILLMENT_NOT_READY_FOR_DISPATCH" -> R.string.dispatch_readiness_reason_fulfillment
        "PHYSICAL_ALLOCATION_NOT_CURRENT" -> R.string.dispatch_readiness_reason_allocation
        "PICKING_INCOMPLETE" -> R.string.dispatch_readiness_reason_picking
        "PICKING_EVIDENCE_INCOMPLETE" -> R.string.dispatch_readiness_reason_evidence
        else -> R.string.dispatch_readiness_reason_unknown
    }
)

@Composable
private fun yesNo(value: Boolean): String = stringResource(
    if (value) R.string.dispatch_readiness_complete else R.string.dispatch_readiness_pending
)

@Composable
private fun deliveryStatusLabel(status: String?): String = when (status?.uppercase()) {
    "ALLOCATED", "ASSIGNED" -> stringResource(R.string.dispatch_status_assigned)
    "PICKING" -> stringResource(R.string.dispatch_status_picking)
    "READY_FOR_DISPATCH" -> stringResource(R.string.dispatch_status_ready)
    "DISPATCHED" -> stringResource(R.string.dispatch_status_dispatched)
    "IN_TRANSIT" -> stringResource(R.string.dispatch_status_in_transit)
    "PARTIAL" -> stringResource(R.string.dispatch_status_partial)
    "DELIVERED" -> stringResource(R.string.dispatch_status_delivered)
    "CANCELLED" -> stringResource(R.string.dispatch_status_cancelled)
    "HANDED_OVER" -> stringResource(R.string.dispatch_visual_handed_over)
    "CONSUMED" -> stringResource(R.string.dispatch_visual_consumed)
    "RELEASED" -> stringResource(R.string.dispatch_visual_released)
    "PACKED" -> stringResource(R.string.dispatch_visual_packed)
    "STAGED" -> stringResource(R.string.dispatch_visual_staged)
    null, "" -> stringResource(R.string.dispatch_readiness_no_delivery)
    else -> stringResource(R.string.dispatch_visual_other_status)
}

private fun formatInstant(value: Instant): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.SHORT)
    .withLocale(Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(value)
