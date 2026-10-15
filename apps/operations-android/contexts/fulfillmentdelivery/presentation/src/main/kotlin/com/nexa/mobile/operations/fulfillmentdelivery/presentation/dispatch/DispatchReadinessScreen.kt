package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaWorkSection
import com.nexa.mobile.operations.core.designsystem.NexaWorkSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import java.time.Instant
import java.time.format.DateTimeFormatter

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
    val closeAction = rememberUpdatedState(onRouteClosed)
    var showWorkList by remember(state.selectedFulfillmentId) {
        mutableStateOf(state.selectedFulfillmentId == null)
    }
    DisposableEffect(Unit) {
        onDispose { closeAction.value() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.dispatch_readiness_back))
                }
                NexaWorkSummary(
                    title = stringResource(R.string.dispatch_readiness_title),
                    description = stringResource(R.string.dispatch_readiness_authority_note)
                )
            }
            item { ReadinessStatusPanel(state.status, state.asOf) }
            item {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = state.status != DispatchReadinessStatus.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dispatch_readiness_refresh)) }
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
                    items(state.items, key = { it.fulfillmentId.lowercase() }) { item ->
                        ReadinessCard(
                            item = item,
                            selected = state.selectedFulfillmentId == item.fulfillmentId,
                            onClick = { onSelectFulfillment(item.fulfillmentId) }
                        )
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
                                    Text(stringResource(R.string.dispatch_readiness_execution_hold))
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
                                R.string.dispatch_pod_delivery,
                                current.deliveryId,
                                current.deliveryStatus,
                                current.deliveryVersion
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${item.fulfillmentId}, ${item.fulfillmentStatus}" },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.dispatch_readiness_fulfillment, item.fulfillmentId),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.fulfillmentStatus, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        if (item.ready) {
                            R.string.dispatch_readiness_ready
                        } else {
                            R.string.dispatch_readiness_not_ready
                        }
                    ),
                    color = if (item.ready) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                stringResource(R.string.dispatch_readiness_version, item.fulfillmentVersion),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!item.ready) {
                for (reason in item.reasons) {
                    Text(
                        reasonLabel(reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                stringResource(R.string.dispatch_readiness_open_detail),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ReadinessDetail(item: DispatchReadiness, onClose: () -> Unit) {
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
                stringResource(R.string.dispatch_readiness_detail_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.dispatch_readiness_fulfillment, item.fulfillmentId))
            Text(
                stringResource(
                    R.string.dispatch_readiness_fulfillment_version,
                    item.fulfillmentVersion,
                    item.fulfillmentStatus
                )
            )
            Text(stringResource(R.string.dispatch_readiness_allocation, item.physicalAllocationId))
            Text(
                stringResource(
                    R.string.dispatch_readiness_allocation_version,
                    item.physicalAllocationVersion,
                    item.physicalAllocationStatus
                )
            )
            Text(
                stringResource(
                    R.string.dispatch_readiness_check_allocation,
                    yesNo(item.allocationComplete)
                )
            )
            Text(
                stringResource(
                    R.string.dispatch_readiness_check_picking,
                    yesNo(item.pickingComplete)
                )
            )
            Text(
                stringResource(
                    R.string.dispatch_readiness_check_evidence,
                    yesNo(item.pickingEvidenceComplete)
                )
            )
            item.deliveryId?.let { deliveryId ->
                Text(
                    stringResource(
                        R.string.dispatch_readiness_delivery,
                        deliveryId,
                        item.deliveryStatus.orEmpty()
                    )
                )
            }
            Text(stringResource(R.string.dispatch_readiness_as_of, formatInstant(item.asOf)))
            if (item.reasons.isNotEmpty()) {
                Text(
                    stringResource(R.string.dispatch_readiness_reasons),
                    fontWeight = FontWeight.SemiBold
                )
                for (reason in item.reasons) Text("• ${reasonLabel(reason)}")
            }
            Text(
                stringResource(R.string.dispatch_readiness_lines, item.lines.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            for (line in item.lines) {
                Text(
                    stringResource(
                        R.string.dispatch_readiness_line_item,
                        line.catalogItemId,
                        line.skuId
                    )
                )
                Text(
                    stringResource(
                        R.string.dispatch_readiness_quantities,
                        line.allocatedQuantity.toPlainString(),
                        line.physicallyAllocatedQuantity.toPlainString(),
                        line.pickedQuantity.toPlainString(),
                        line.evidencedPickedQuantity.toPlainString()
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (!line.allocationComplete || !line.pickingComplete || !line.evidenceComplete) {
                    Text(
                        stringResource(R.string.dispatch_readiness_line_incomplete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
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

private fun formatInstant(value: Instant): String = DateTimeFormatter.ISO_INSTANT.format(value)
