package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchDeliveryInstructionsStatus as DeliveryInstructionsStatus
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstruction
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionKind as DeliveryInstructionKind

@Composable
fun DispatchDeliveryInstructionsScreen(
    state: DispatchDeliveryInstructionsUiState,
    onBack: () -> Unit,
    onDeliveryIdChanged: (String) -> Unit,
    onLoadDelivery: () -> Unit,
    onRefresh: () -> Unit,
    onNewInstruction: () -> Unit,
    onEditInstruction: (String) -> Unit,
    onKindChanged: (DeliveryInstructionKind) -> Unit,
    onContentChanged: (String) -> Unit,
    onPublish: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onRouteClosed: () -> Unit
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { closeAction.value() } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.safeDrawing
            ).imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.dispatch_delivery_instructions_back))
                }
                Text(
                    stringResource(R.string.dispatch_delivery_instructions_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.dispatch_delivery_instructions_authority_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { InstructionStatusCard(state) }
            item {
                OutlinedTextField(
                    value = state.deliveryIdInput,
                    onValueChange = onDeliveryIdChanged,
                    label = {
                        Text(stringResource(R.string.dispatch_delivery_instructions_delivery_id))
                    },
                    supportingText = {
                        Text(
                            stringResource(R.string.dispatch_delivery_instructions_delivery_id_help)
                        )
                    },
                    singleLine = true,
                    enabled = state.status != DeliveryInstructionsStatus.Saving,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = onLoadDelivery,
                    enabled = state.canRead && state.status !in setOf(
                        DeliveryInstructionsStatus.Loading,
                        DeliveryInstructionsStatus.Saving
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text(stringResource(R.string.dispatch_delivery_instructions_load)) }
            }
            if (state.status == DeliveryInstructionsStatus.Loading ||
                state.status == DeliveryInstructionsStatus.Saving
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.dispatch_delivery_instructions_loading))
                    }
                }
            }
            if (state.status == DeliveryInstructionsStatus.UnknownOutcome) {
                item {
                    OutlinedButton(
                        onClick = onRetryUnknownOutcome,
                        enabled = state.canRetryUnknownOutcome,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.dispatch_delivery_instructions_retry)) }
                }
            }
            val snapshot = state.snapshot
            if (snapshot != null && state.status in setOf(
                    DeliveryInstructionsStatus.Current,
                    DeliveryInstructionsStatus.UnknownOutcome,
                    DeliveryInstructionsStatus.Saving
                )
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.dispatch_delivery_instructions_revision,
                                snapshot.deliveryVersion,
                                snapshot.instructionSetVersion
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onRefresh,
                            enabled = state.status != DeliveryInstructionsStatus.Saving
                        ) { Text(stringResource(R.string.dispatch_delivery_instructions_refresh)) }
                    }
                    if (state.canPublish &&
                        state.status == DeliveryInstructionsStatus.Current
                    ) {
                        OutlinedButton(
                            onClick = onNewInstruction,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.dispatch_delivery_instructions_new))
                        }
                    }
                }
                if (snapshot.instructions.isEmpty()) {
                    item {
                        InstructionInfoCard(
                            stringResource(R.string.dispatch_delivery_instructions_empty)
                        )
                    }
                }
                items(snapshot.instructions, key = { it.id.lowercase() }) { instruction ->
                    InstructionCard(
                        instruction,
                        state.canPublish &&
                            state.status == DeliveryInstructionsStatus.Current
                    ) {
                        onEditInstruction(instruction.id)
                    }
                }
                if (state.canPublish &&
                    state.status == DeliveryInstructionsStatus.Current
                ) {
                    item {
                        InstructionEditor(
                            state = state,
                            onKindChanged = onKindChanged,
                            onContentChanged = onContentChanged,
                            onPublish = onPublish
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionStatusCard(state: DispatchDeliveryInstructionsUiState) {
    val message = when (state.status) {
        DeliveryInstructionsStatus.Initial -> R.string.dispatch_delivery_instructions_initial

        DeliveryInstructionsStatus.Loading -> R.string.dispatch_delivery_instructions_loading

        DeliveryInstructionsStatus.Current -> R.string.dispatch_delivery_instructions_current

        DeliveryInstructionsStatus.Saving -> R.string.dispatch_delivery_instructions_saving

        DeliveryInstructionsStatus.UnknownOutcome -> R.string.dispatch_delivery_instructions_unknown

        DeliveryInstructionsStatus.InvalidDeliveryId ->
            R.string.dispatch_delivery_instructions_invalid_id

        DeliveryInstructionsStatus.NotFound -> R.string.dispatch_delivery_instructions_not_found

        DeliveryInstructionsStatus.StaleVersion -> R.string.dispatch_delivery_instructions_stale

        DeliveryInstructionsStatus.Conflict -> R.string.dispatch_delivery_instructions_conflict

        DeliveryInstructionsStatus.PermissionDenied ->
            R.string.dispatch_delivery_instructions_permission

        DeliveryInstructionsStatus.NetworkUnavailable ->
            R.string.dispatch_delivery_instructions_network

        DeliveryInstructionsStatus.ServiceUnavailable ->
            R.string.dispatch_delivery_instructions_service

        DeliveryInstructionsStatus.ContextInvalidated ->
            R.string.dispatch_delivery_instructions_context

        DeliveryInstructionsStatus.SessionInvalidated ->
            R.string.dispatch_delivery_instructions_session
    }
    InstructionInfoCard(stringResource(message)) {
        state.errorCode?.let {
            Text(stringResource(R.string.dispatch_delivery_instructions_error_code, it))
        }
    }
}

@Composable
private fun InstructionCard(
    instruction: DispatchDeliveryInstruction,
    canEdit: Boolean,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(
                    R.string.dispatch_delivery_instructions_kind,
                    stringResource(instruction.kind.labelResource),
                    instruction.instructionVersion
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(instruction.content, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (instruction.critical) {
                        R.string.dispatch_delivery_instructions_critical
                    } else {
                        R.string.dispatch_delivery_instructions_normal
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(
                    R.string.dispatch_delivery_instructions_source,
                    stringResource(instruction.sourceKind.sourceResource)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (instruction.acknowledged) {
                Text(stringResource(R.string.dispatch_delivery_instructions_acknowledged))
            }
            if (instruction.sourceKind ==
                DispatchDeliveryInstructionsUiState.OPERATIONAL_DISPATCH_SOURCE &&
                canEdit
            ) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dispatch_delivery_instructions_edit))
                }
            } else if (instruction.sourceKind !=
                DispatchDeliveryInstructionsUiState.OPERATIONAL_DISPATCH_SOURCE
            ) {
                Text(stringResource(R.string.dispatch_delivery_instructions_read_only))
            }
        }
    }
}

@Composable
private fun InstructionEditor(
    state: DispatchDeliveryInstructionsUiState,
    onKindChanged: (DeliveryInstructionKind) -> Unit,
    onContentChanged: (String) -> Unit,
    onPublish: () -> Unit
) {
    val expanded = remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(
                    if (state.isEditingOperationalInstruction) {
                        R.string.dispatch_delivery_instructions_edit_title
                    } else {
                        R.string.dispatch_delivery_instructions_new_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Box {
                OutlinedButton(onClick = {
                    expanded.value = true
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(state.selectedKind.labelResource))
                }
                DropdownMenu(expanded = expanded.value, onDismissRequest = {
                    expanded.value = false
                }) {
                    DeliveryInstructionKind.entries.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(stringResource(kind.labelResource)) },
                            onClick = {
                                expanded.value = false
                                onKindChanged(kind)
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = state.content,
                onValueChange = onContentChanged,
                label = { Text(stringResource(R.string.dispatch_delivery_instructions_content)) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.dispatch_delivery_instructions_content_count,
                            state.content.length
                        )
                    )
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onPublish,
                enabled = state.canPublishCurrent,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.dispatch_delivery_instructions_publish)) }
        }
    }
}

@Composable
private fun InstructionInfoCard(message: String, content: @Composable () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(message)
            content()
        }
    }
}

private val DeliveryInstructionKind.labelResource: Int
    get() = when (this) {
        DeliveryInstructionKind.NORMAL -> R.string.dispatch_delivery_instructions_kind_normal

        DeliveryInstructionKind.COLD_CHAIN ->
            R.string.dispatch_delivery_instructions_kind_cold_chain

        DeliveryInstructionKind.ACCESS_RESTRICTION ->
            R.string.dispatch_delivery_instructions_kind_access

        DeliveryInstructionKind.SPECIAL_UNLOADING ->
            R.string.dispatch_delivery_instructions_kind_unloading

        DeliveryInstructionKind.CUSTOMER_SAFETY ->
            R.string.dispatch_delivery_instructions_kind_safety

        DeliveryInstructionKind.GOODS_HANDLING ->
            R.string.dispatch_delivery_instructions_kind_handling
    }

private val String?.sourceResource: Int
    get() = when (this) {
        "BUYER" -> R.string.dispatch_delivery_instructions_source_buyer

        "CUSTOMER_REPORTED_BY_SALES" -> R.string.dispatch_delivery_instructions_source_sales

        DispatchDeliveryInstructionsUiState.OPERATIONAL_DISPATCH_SOURCE ->
            R.string.dispatch_delivery_instructions_source_dispatch

        else -> R.string.dispatch_delivery_instructions_source_unknown
    }
