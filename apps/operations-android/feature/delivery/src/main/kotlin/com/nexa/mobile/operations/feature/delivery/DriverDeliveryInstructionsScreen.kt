package com.nexa.mobile.operations.feature.delivery

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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

@Composable
fun DriverDeliveryInstructionsScreen(
    state: DriverDeliveryInstructionsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectInstruction: (String, Boolean) -> Unit,
    onAcknowledgeSelected: () -> Unit,
    onRetryUnknownAcknowledgement: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.driver_delivery_back)) }
            Text(
                text = stringResource(R.string.driver_instruction_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.driver_instruction_authority_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (state.loadStatus) {
                DriverDeliveryInstructionsLoadStatus.Loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.driver_instruction_loading))
                }

                DriverDeliveryInstructionsLoadStatus.NotFound -> InstructionNotice(
                    stringResource(R.string.driver_instruction_not_found), isError = true
                )

                DriverDeliveryInstructionsLoadStatus.NetworkUnavailable -> InstructionNotice(
                    stringResource(R.string.driver_instruction_network), isError = true
                )

                DriverDeliveryInstructionsLoadStatus.ServiceUnavailable -> InstructionNotice(
                    stringResource(R.string.driver_instruction_service), isError = true
                )

                DriverDeliveryInstructionsLoadStatus.PermissionDenied -> InstructionNotice(
                    stringResource(R.string.driver_instruction_permission), isError = true
                )

                DriverDeliveryInstructionsLoadStatus.ContextInvalidated -> InstructionNotice(
                    stringResource(R.string.driver_delivery_context), isError = true
                )

                DriverDeliveryInstructionsLoadStatus.SessionInvalidated -> InstructionNotice(
                    stringResource(R.string.driver_delivery_session), isError = true
                )

                DriverDeliveryInstructionsLoadStatus.NotRequested,
                DriverDeliveryInstructionsLoadStatus.Ready -> Unit
            }

            OutlinedButton(onClick = onRefresh, enabled = state.canRead) {
                Text(stringResource(R.string.driver_instruction_refresh))
            }

            state.snapshot?.let { snapshot ->
                Text(
                    stringResource(
                        R.string.driver_instruction_versions,
                        snapshot.deliveryVersion,
                        snapshot.instructionSetVersion
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (snapshot.instructions.isEmpty()) {
                    InstructionNotice(stringResource(R.string.driver_instruction_empty))
                }
                snapshot.instructions.forEach { instruction ->
                    InstructionCard(
                        instruction = instruction,
                        selected = instruction.id in state.selectedInstructionIds,
                        enabled = state.canAcknowledge &&
                            state.loadStatus == DriverDeliveryInstructionsLoadStatus.Ready &&
                            !state.hasRecoverableAcknowledgement &&
                            !state.unresolvedAcknowledgementForOtherDelivery &&
                            state.acknowledgementStatus !in setOf(
                                DriverDeliveryInstructionAcknowledgementStatus.Pending,
                                DriverDeliveryInstructionAcknowledgementStatus.PersistingIntent,
                                DriverDeliveryInstructionAcknowledgementStatus.UnknownOutcome,
                                DriverDeliveryInstructionAcknowledgementStatus.PersistenceUnavailable
                            ),
                        onSelected = { onSelectInstruction(instruction.id, it) }
                    )
                }
            }

            if (!state.canRead) {
                InstructionNotice(stringResource(R.string.driver_instruction_permission), isError = true)
            }
            if (state.unresolvedAcknowledgementForOtherDelivery) {
                InstructionNotice(stringResource(R.string.driver_instruction_other_pending), isError = true)
            }
            when (state.acknowledgementStatus) {
                DriverDeliveryInstructionAcknowledgementStatus.PersistingIntent -> InstructionNotice(
                    stringResource(R.string.driver_instruction_persisting)
                )

                DriverDeliveryInstructionAcknowledgementStatus.Pending -> InstructionNotice(
                    stringResource(R.string.driver_instruction_pending)
                )

                DriverDeliveryInstructionAcknowledgementStatus.UnknownOutcome -> {
                    InstructionNotice(stringResource(R.string.driver_instruction_unknown), isError = true)
                    if (state.hasRecoverableAcknowledgement) {
                        OutlinedButton(onClick = onRetryUnknownAcknowledgement, enabled = state.canAcknowledge) {
                            Text(stringResource(R.string.driver_instruction_retry_same))
                        }
                    }
                }

                DriverDeliveryInstructionAcknowledgementStatus.PersistenceUnavailable -> InstructionNotice(
                    stringResource(R.string.driver_instruction_storage_unavailable), isError = true
                )

                DriverDeliveryInstructionAcknowledgementStatus.Acknowledged -> {
                    InstructionNotice(
                        if (state.acknowledgementReplayed) {
                            stringResource(R.string.driver_instruction_replayed)
                        } else {
                            stringResource(R.string.driver_instruction_acknowledged)
                        }
                    )
                    if (state.hasRecoverableAcknowledgement) {
                        OutlinedButton(onClick = onRetryUnknownAcknowledgement, enabled = state.canAcknowledge) {
                            Text(stringResource(R.string.driver_instruction_retry_same))
                        }
                    }
                }

                DriverDeliveryInstructionAcknowledgementStatus.Rejected -> InstructionNotice(
                    state.rejectionCode?.let {
                        stringResource(R.string.driver_instruction_rejected_code, it)
                    } ?: stringResource(R.string.driver_instruction_rejected),
                    isError = true
                )

                DriverDeliveryInstructionAcknowledgementStatus.StaleVersion -> InstructionNotice(
                    stringResource(R.string.driver_instruction_stale), isError = true
                )

                DriverDeliveryInstructionAcknowledgementStatus.Idle -> Unit
            }

            if (state.acknowledgementStatus == DriverDeliveryInstructionAcknowledgementStatus.StaleVersion) {
                InstructionNotice(stringResource(R.string.driver_instruction_fresh_decision))
            }
            Button(onClick = onAcknowledgeSelected, enabled = state.canAcknowledgeSelected) {
                Text(stringResource(R.string.driver_instruction_acknowledge_selected))
            }
        }
    }
}

@Composable
private fun InstructionCard(
    instruction: DriverDeliveryInstruction,
    selected: Boolean,
    enabled: Boolean,
    onSelected: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = instruction.kind.localizedName(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(instruction.content)
            Text(
                stringResource(R.string.driver_instruction_revision, instruction.instructionVersion),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (instruction.critical) {
                if (instruction.acknowledged) {
                    InstructionNotice(
                        stringResource(
                            R.string.driver_instruction_acknowledged_fact,
                            instruction.acknowledgedByMembershipId.orEmpty(),
                            instruction.acknowledgedAt.orEmpty()
                        )
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = selected, onCheckedChange = onSelected, enabled = enabled)
                        Text(
                            stringResource(R.string.driver_instruction_read_confirmation),
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriverDeliveryInstructionKind.localizedName(): String = when (this) {
    DriverDeliveryInstructionKind.NORMAL -> stringResource(R.string.driver_instruction_kind_normal)
    DriverDeliveryInstructionKind.COLD_CHAIN -> stringResource(R.string.driver_instruction_kind_cold_chain)
    DriverDeliveryInstructionKind.ACCESS_RESTRICTION -> stringResource(R.string.driver_instruction_kind_access)
    DriverDeliveryInstructionKind.SPECIAL_UNLOADING -> stringResource(R.string.driver_instruction_kind_unloading)
    DriverDeliveryInstructionKind.CUSTOMER_SAFETY -> stringResource(R.string.driver_instruction_kind_safety)
    DriverDeliveryInstructionKind.GOODS_HANDLING -> stringResource(R.string.driver_instruction_kind_goods)
}

@Composable
private fun InstructionNotice(message: String, isError: Boolean = false) {
    Text(
        message,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}
