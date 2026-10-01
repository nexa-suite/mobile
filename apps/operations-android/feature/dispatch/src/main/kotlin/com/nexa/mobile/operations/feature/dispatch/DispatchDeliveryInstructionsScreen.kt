package com.nexa.mobile.operations.feature.dispatch

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

@Composable
fun DispatchDeliveryInstructionsScreen(
    state: DispatchDeliveryInstructionsUiState,
    onBack: () -> Unit,
    onDeliveryIdChanged: (String) -> Unit,
    onLoadDelivery: () -> Unit,
    onRefresh: () -> Unit,
    onNewInstruction: () -> Unit,
    onEditInstruction: (String) -> Unit,
    onKindChanged: (DispatchDeliveryInstructionKind) -> Unit,
    onContentChanged: (String) -> Unit,
    onPublish: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onRouteClosed: () -> Unit
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { closeAction.value() } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack) { Text(stringResource(R.string.dispatch_delivery_instructions_back)) }
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
                    label = { Text(stringResource(R.string.dispatch_delivery_instructions_delivery_id)) },
                    supportingText = { Text(stringResource(R.string.dispatch_delivery_instructions_delivery_id_help)) },
                    singleLine = true,
                    enabled = state.status != DispatchDeliveryInstructionsStatus.Saving,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = onLoadDelivery,
                    enabled = state.canRead && state.status !in setOf(
                        DispatchDeliveryInstructionsStatus.Loading,
                        DispatchDeliveryInstructionsStatus.Saving
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text(stringResource(R.string.dispatch_delivery_instructions_load)) }
            }
            if (state.status == DispatchDeliveryInstructionsStatus.Loading ||
                state.status == DispatchDeliveryInstructionsStatus.Saving
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.dispatch_delivery_instructions_loading))
                    }
                }
            }
            if (state.status == DispatchDeliveryInstructionsStatus.UnknownOutcome) {
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
                    DispatchDeliveryInstructionsStatus.Current,
                    DispatchDeliveryInstructionsStatus.UnknownOutcome,
                    DispatchDeliveryInstructionsStatus.Saving
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
                            enabled = state.status != DispatchDeliveryInstructionsStatus.Saving
                        ) { Text(stringResource(R.string.dispatch_delivery_instructions_refresh)) }
                    }
                    if (state.canPublish && state.status == DispatchDeliveryInstructionsStatus.Current) {
                        OutlinedButton(onClick = onNewInstruction, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.dispatch_delivery_instructions_new))
                        }
                    }
                }
                if (snapshot.instructions.isEmpty()) {
                    item { InstructionInfoCard(stringResource(R.string.dispatch_delivery_instructions_empty)) }
                }
                items(snapshot.instructions, key = { it.id.lowercase() }) { instruction ->
                    InstructionCard(instruction, state.canPublish && state.status == DispatchDeliveryInstructionsStatus.Current) {
                        onEditInstruction(instruction.id)
                    }
                }
                if (state.canPublish && state.status == DispatchDeliveryInstructionsStatus.Current) {
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
        DispatchDeliveryInstructionsStatus.Initial -> R.string.dispatch_delivery_instructions_initial
        DispatchDeliveryInstructionsStatus.Loading -> R.string.dispatch_delivery_instructions_loading
        DispatchDeliveryInstructionsStatus.Current -> R.string.dispatch_delivery_instructions_current
        DispatchDeliveryInstructionsStatus.Saving -> R.string.dispatch_delivery_instructions_saving
        DispatchDeliveryInstructionsStatus.UnknownOutcome -> R.string.dispatch_delivery_instructions_unknown
        DispatchDeliveryInstructionsStatus.InvalidDeliveryId -> R.string.dispatch_delivery_instructions_invalid_id
        DispatchDeliveryInstructionsStatus.NotFound -> R.string.dispatch_delivery_instructions_not_found
        DispatchDeliveryInstructionsStatus.StaleVersion -> R.string.dispatch_delivery_instructions_stale
        DispatchDeliveryInstructionsStatus.Conflict -> R.string.dispatch_delivery_instructions_conflict
        DispatchDeliveryInstructionsStatus.PermissionDenied -> R.string.dispatch_delivery_instructions_permission
        DispatchDeliveryInstructionsStatus.NetworkUnavailable -> R.string.dispatch_delivery_instructions_network
        DispatchDeliveryInstructionsStatus.ServiceUnavailable -> R.string.dispatch_delivery_instructions_service
        DispatchDeliveryInstructionsStatus.ContextInvalidated -> R.string.dispatch_delivery_instructions_context
        DispatchDeliveryInstructionsStatus.SessionInvalidated -> R.string.dispatch_delivery_instructions_session
    }
    InstructionInfoCard(stringResource(message)) {
        state.errorCode?.let { Text(stringResource(R.string.dispatch_delivery_instructions_error_code, it)) }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (instruction.critical) R.string.dispatch_delivery_instructions_critical
                    else R.string.dispatch_delivery_instructions_normal
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
            if (instruction.sourceKind == DispatchDeliveryInstructionsUiState.OPERATIONAL_DISPATCH_SOURCE && canEdit) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dispatch_delivery_instructions_edit))
                }
            } else if (instruction.sourceKind != DispatchDeliveryInstructionsUiState.OPERATIONAL_DISPATCH_SOURCE) {
                Text(stringResource(R.string.dispatch_delivery_instructions_read_only))
            }
        }
    }
}

@Composable
private fun InstructionEditor(
    state: DispatchDeliveryInstructionsUiState,
    onKindChanged: (DispatchDeliveryInstructionKind) -> Unit,
    onContentChanged: (String) -> Unit,
    onPublish: () -> Unit
) {
    val expanded = remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(
                    if (state.isEditingOperationalInstruction) R.string.dispatch_delivery_instructions_edit_title
                    else R.string.dispatch_delivery_instructions_new_title
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Box {
                OutlinedButton(onClick = { expanded.value = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(state.selectedKind.labelResource))
                }
                DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
                    DispatchDeliveryInstructionKind.entries.forEach { kind ->
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
                    Text(stringResource(R.string.dispatch_delivery_instructions_content_count, state.content.length))
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message)
            content()
        }
    }
}

private val DispatchDeliveryInstructionKind.labelResource: Int
    get() = when (this) {
        DispatchDeliveryInstructionKind.NORMAL -> R.string.dispatch_delivery_instructions_kind_normal
        DispatchDeliveryInstructionKind.COLD_CHAIN -> R.string.dispatch_delivery_instructions_kind_cold_chain
        DispatchDeliveryInstructionKind.ACCESS_RESTRICTION -> R.string.dispatch_delivery_instructions_kind_access
        DispatchDeliveryInstructionKind.SPECIAL_UNLOADING -> R.string.dispatch_delivery_instructions_kind_unloading
        DispatchDeliveryInstructionKind.CUSTOMER_SAFETY -> R.string.dispatch_delivery_instructions_kind_safety
        DispatchDeliveryInstructionKind.GOODS_HANDLING -> R.string.dispatch_delivery_instructions_kind_handling
    }

private val String?.sourceResource: Int
    get() = when (this) {
        "BUYER" -> R.string.dispatch_delivery_instructions_source_buyer
        "CUSTOMER_REPORTED_BY_SALES" -> R.string.dispatch_delivery_instructions_source_sales
        DispatchDeliveryInstructionsUiState.OPERATIONAL_DISPATCH_SOURCE ->
            R.string.dispatch_delivery_instructions_source_dispatch

        else -> R.string.dispatch_delivery_instructions_source_unknown
    }
