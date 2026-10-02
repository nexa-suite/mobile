package com.nexa.mobile.operations.feature.delivery

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun DriverDeliveryScreen(
    state: DriverDeliveryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDelivery: (String) -> Unit,
    onBeginDelivery: () -> Unit,
    onRetryUnknownStart: () -> Unit,
    onOpenDirections: (String) -> Boolean = { false },
    onRecordOutcome: (
        DriverOutcomeKind,
        Map<String, String>,
        String?,
        String?
    ) -> Unit = { _, _, _, _ -> },
    onRetryUnknownOutcome: () -> Unit = {},
    onSignalArrival: () -> Unit = {},
    onRetryUnknownArrival: () -> Unit = {},
    onCreateProof: (String) -> Unit = {},
    onChooseProofFile: () -> Unit = {},
    onUploadSelectedProofEvidence: () -> Unit = {},
    onRetryUnknownProof: () -> Unit = {},
    onRefreshProofEvidence: () -> Unit = {},
    onAttachAvailableProofEvidence: () -> Unit = {},
    onOpenInstructions: ((String) -> Unit)? = null,
    onOpenOperationalExceptions: ((String) -> Unit)? = null,
    onOpenExecutionTemperature: ((String) -> Unit)? = null,
    onOpenIncident: ((String, String, Long, Boolean) -> Unit)? = null,
    onOpenHandoffCode: ((String, String, Long) -> Unit)? = null
) {
    var navigationUnavailable by remember { mutableStateOf(false) }
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
                text = stringResource(R.string.driver_delivery_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.driver_delivery_authority_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!state.canRead) {
                Notice(stringResource(R.string.driver_delivery_read_permission), isError = true)
            }
            if (!state.canStart) {
                Notice(stringResource(R.string.driver_delivery_start_permission), isError = true)
            }
            when (state.listStatus) {
                DriverDeliveryLoadStatus.Loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.driver_delivery_loading))
                }

                DriverDeliveryLoadStatus.NotFound -> Notice(
                    stringResource(R.string.driver_delivery_not_found),
                    isError = true
                )

                DriverDeliveryLoadStatus.NetworkUnavailable -> Notice(
                    stringResource(R.string.driver_delivery_network),
                    isError = true
                )

                DriverDeliveryLoadStatus.ServiceUnavailable -> Notice(
                    stringResource(R.string.driver_delivery_service),
                    isError = true
                )

                DriverDeliveryLoadStatus.PermissionDenied -> Notice(
                    stringResource(R.string.driver_delivery_read_permission),
                    isError = true
                )

                DriverDeliveryLoadStatus.ContextInvalidated -> Notice(
                    stringResource(R.string.driver_delivery_context),
                    isError = true
                )

                DriverDeliveryLoadStatus.SessionInvalidated -> Notice(
                    stringResource(R.string.driver_delivery_session),
                    isError = true
                )

                DriverDeliveryLoadStatus.NotRequested,
                DriverDeliveryLoadStatus.Ready -> Unit
            }

            OutlinedButton(onClick = onRefresh, enabled = state.canRead) {
                Text(stringResource(R.string.driver_delivery_refresh))
            }

            if (state.listStatus == DriverDeliveryLoadStatus.Ready && state.deliveries.isEmpty()) {
                Notice(stringResource(R.string.driver_delivery_empty))
            }
            state.deliveries.forEach { delivery ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state.commandStatus !in FROZEN_COMMANDS) {
                            onSelectDelivery(delivery.id)
                        }
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            delivery.destination
                                ?: stringResource(R.string.driver_delivery_destination_unknown)
                        )
                        Text(stringResource(R.string.driver_delivery_status, delivery.status))
                        delivery.activeAttempt?.let { attempt ->
                            Text(
                                stringResource(
                                    R.string.driver_delivery_active_attempt,
                                    attempt.attemptNumber
                                )
                            )
                        }
                    }
                }
            }

            when (state.commandStatus) {
                DriverDeliveryCommandStatus.PersistingIntent -> Notice(
                    stringResource(R.string.driver_delivery_persisting)
                )

                DriverDeliveryCommandStatus.UnknownOutcome -> {
                    Notice(stringResource(R.string.driver_delivery_unknown), isError = true)
                    if (state.hasRecoverableStart) {
                        OutlinedButton(
                            onClick = onRetryUnknownStart,
                            enabled = state.canStart
                        ) {
                            Text(stringResource(R.string.driver_delivery_retry_same))
                        }
                    }
                }

                DriverDeliveryCommandStatus.PersistenceUnavailable -> {
                    Notice(
                        stringResource(R.string.driver_delivery_storage_unavailable),
                        isError = true
                    )
                    if (state.hasRecoverableStart) {
                        OutlinedButton(
                            onClick = onRetryUnknownStart,
                            enabled = state.canStart
                        ) {
                            Text(stringResource(R.string.driver_delivery_retry_same))
                        }
                    }
                }

                else -> Unit
            }

            when (state.outcomeCommandStatus) {
                DriverOutcomeCommandStatus.CheckingCurrent -> Notice(
                    stringResource(R.string.driver_delivery_outcome_checking)
                )

                DriverOutcomeCommandStatus.PersistingIntent -> Notice(
                    stringResource(R.string.driver_delivery_outcome_persisting)
                )

                DriverOutcomeCommandStatus.Pending -> Notice(
                    stringResource(R.string.driver_delivery_outcome_pending)
                )

                DriverOutcomeCommandStatus.UnknownOutcome -> {
                    Notice(stringResource(R.string.driver_delivery_outcome_unknown), isError = true)
                    if (state.hasRecoverableOutcome) {
                        OutlinedButton(onClick = onRetryUnknownOutcome, enabled = state.canStart) {
                            Text(stringResource(R.string.driver_delivery_outcome_retry_same))
                        }
                    }
                }

                DriverOutcomeCommandStatus.PersistenceUnavailable -> {
                    Notice(
                        stringResource(R.string.driver_delivery_outcome_storage_unavailable),
                        isError = true
                    )
                    if (state.hasRecoverableOutcome) {
                        OutlinedButton(onClick = onRetryUnknownOutcome, enabled = state.canStart) {
                            Text(stringResource(R.string.driver_delivery_outcome_retry_same))
                        }
                    }
                }

                DriverOutcomeCommandStatus.Recorded -> state.outcomeSummary?.let { summary ->
                    Notice(
                        stringResource(
                            R.string.driver_delivery_outcome_recorded,
                            summary.outcome,
                            summary.attemptedAt,
                            summary.deliveryVersion
                        )
                    )
                }

                DriverOutcomeCommandStatus.Rejected -> Notice(
                    state.outcomeRejectionCode?.let {
                        stringResource(R.string.driver_delivery_outcome_rejected_code, it)
                    } ?: stringResource(R.string.driver_delivery_outcome_rejected),
                    isError = true
                )

                DriverOutcomeCommandStatus.StaleVersion -> Notice(
                    stringResource(R.string.driver_delivery_outcome_stale),
                    isError = true
                )

                DriverOutcomeCommandStatus.Idle -> Unit
            }

            state.selectedDelivery?.let { delivery ->
                Text(
                    stringResource(R.string.driver_delivery_detail_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    delivery.destination
                        ?: stringResource(R.string.driver_delivery_destination_unknown)
                )
                Text(stringResource(R.string.driver_delivery_status, delivery.status))
                delivery.scheduledAt?.let {
                    Text(stringResource(R.string.driver_delivery_scheduled, it))
                }
                if (onOpenInstructions != null &&
                    state.authorizedInstructionsDeliveryId == delivery.id
                ) {
                    OutlinedButton(
                        onClick = { onOpenInstructions(delivery.id) },
                        enabled =
                            state.canRead && state.detailStatus == DriverDeliveryLoadStatus.Ready
                    ) {
                        Text(stringResource(R.string.driver_delivery_instructions_open))
                    }
                }
                if (onOpenOperationalExceptions != null &&
                    state.authorizedOperationalExceptionsDeliveryId == delivery.id
                ) {
                    OutlinedButton(
                        onClick = { onOpenOperationalExceptions(delivery.id) },
                        enabled =
                            state.canRead && state.detailStatus == DriverDeliveryLoadStatus.Ready
                    ) {
                        Text(stringResource(R.string.driver_delivery_exceptions_open))
                    }
                }
                if (onOpenExecutionTemperature != null && state.canRead && state.canStart &&
                    state.detailStatus == DriverDeliveryLoadStatus.Ready
                ) {
                    OutlinedButton(
                        onClick = { onOpenExecutionTemperature(delivery.id) },
                        enabled = state.commandStatus !in FROZEN_COMMANDS
                    ) {
                        Text(stringResource(R.string.driver_delivery_execution_temperature_open))
                    }
                }
                delivery.activeAttempt?.let { attempt ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(stringResource(R.string.driver_delivery_attempt_active))
                            Text(
                                stringResource(
                                    R.string.driver_delivery_attempt_number,
                                    attempt.attemptNumber
                                )
                            )
                            Text(stringResource(R.string.driver_delivery_attempt_id, attempt.id))
                            attempt.startedAt?.let {
                                Text(stringResource(R.string.driver_delivery_attempt_started, it))
                            }
                        }
                    }
                    val destination = state.authorizedDirectionsDestination
                    if (destination == null) {
                        Notice(stringResource(R.string.driver_delivery_directions_missing))
                    } else {
                        OutlinedButton(
                            onClick = { navigationUnavailable = !onOpenDirections(destination) }
                        ) {
                            Text(stringResource(R.string.driver_delivery_directions_open))
                        }
                        if (navigationUnavailable) {
                            Notice(
                                stringResource(R.string.driver_delivery_directions_unavailable),
                                isError = true
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.driver_delivery_coordination_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    delivery.arrival?.let { arrival ->
                        if (arrival.attemptId == attempt.id) {
                            Notice(
                                stringResource(
                                    R.string.driver_delivery_arrival_confirmed,
                                    arrival.arrivedAt
                                )
                            )
                        }
                    }
                    when (state.arrivalCommandStatus) {
                        DriverArrivalCommandStatus.CheckingCurrent -> Notice(
                            stringResource(R.string.driver_delivery_arrival_checking)
                        )

                        DriverArrivalCommandStatus.PersistingIntent -> Notice(
                            stringResource(R.string.driver_delivery_arrival_persisting)
                        )

                        DriverArrivalCommandStatus.Pending -> Notice(
                            stringResource(R.string.driver_delivery_arrival_pending)
                        )

                        DriverArrivalCommandStatus.UnknownOutcome -> {
                            Notice(
                                stringResource(R.string.driver_delivery_arrival_unknown),
                                isError = true
                            )
                            if (state.hasRecoverableArrival) {
                                OutlinedButton(
                                    onClick = onRetryUnknownArrival,
                                    enabled = state.canStart
                                ) {
                                    Text(
                                        stringResource(R.string.driver_delivery_arrival_retry_same)
                                    )
                                }
                            }
                        }

                        DriverArrivalCommandStatus.PersistenceUnavailable -> {
                            Notice(
                                stringResource(
                                    R.string.driver_delivery_arrival_storage_unavailable
                                ),
                                isError = true
                            )
                            if (state.hasRecoverableArrival) {
                                OutlinedButton(
                                    onClick = onRetryUnknownArrival,
                                    enabled = state.canStart
                                ) {
                                    Text(
                                        stringResource(R.string.driver_delivery_arrival_retry_same)
                                    )
                                }
                            }
                        }

                        DriverArrivalCommandStatus.Recorded -> {
                            val arrival = state.arrivalSummary
                            if (arrival != null) {
                                Notice(
                                    stringResource(
                                        R.string.driver_delivery_arrival_confirmed,
                                        arrival.arrivedAt
                                    )
                                )
                            }
                        }

                        DriverArrivalCommandStatus.Rejected -> Notice(
                            state.arrivalRejectionCode?.let {
                                stringResource(R.string.driver_delivery_arrival_rejected_code, it)
                            } ?: stringResource(R.string.driver_delivery_arrival_rejected),
                            isError = true
                        )

                        DriverArrivalCommandStatus.StaleVersion -> Notice(
                            stringResource(R.string.driver_delivery_arrival_stale),
                            isError = true
                        )

                        DriverArrivalCommandStatus.Idle -> Unit
                    }
                    if (delivery.arrival?.attemptId != attempt.id &&
                        state.arrivalCommandStatus in setOf(
                            DriverArrivalCommandStatus.Idle,
                            DriverArrivalCommandStatus.Rejected,
                            DriverArrivalCommandStatus.StaleVersion
                        )
                    ) {
                        OutlinedButton(
                            onClick = onSignalArrival,
                            enabled = state.canRead && state.canStart &&
                                state.detailStatus == DriverDeliveryLoadStatus.Ready
                        ) {
                            Text(stringResource(R.string.driver_delivery_arrival_signal))
                        }
                    }
                    Notice(stringResource(R.string.driver_delivery_location_sharing_unavailable))
                    Notice(stringResource(R.string.driver_delivery_contact_unavailable))
                    Notice(stringResource(R.string.driver_delivery_handoff_code_unavailable))
                    if (delivery.outcomeLines.isNotEmpty()) {
                        Text(
                            stringResource(R.string.driver_delivery_quantities_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        delivery.outcomeLines.forEach { line ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${line.catalogItemId} · ${line.unit}")
                                Text(
                                    stringResource(
                                        R.string.driver_delivery_quantities,
                                        line.dispatchedQuantity.toPlainString(),
                                        line.deliveredQuantity.toPlainString(),
                                        line.rejectedQuantity.toPlainString(),
                                        line.cancelledQuantity.toPlainString(),
                                        line.remainingQuantity.toPlainString()
                                    )
                                )
                            }
                        }
                    }
                    OutcomeEntry(
                        delivery = delivery,
                        state = state,
                        onRecordOutcome = onRecordOutcome
                    )
                }
                if (onOpenHandoffCode != null && delivery.activeAttempt != null) {
                    OutlinedButton(
                        onClick = {
                            onOpenHandoffCode(
                                delivery.id,
                                delivery.activeAttempt.id,
                                delivery.version
                            )
                        },
                        enabled =
                            state.canRead && state.detailStatus == DriverDeliveryLoadStatus.Ready &&
                                state.outcomeCommandStatus !in
                                setOf(
                                    DriverOutcomeCommandStatus.Pending,
                                    DriverOutcomeCommandStatus.UnknownOutcome
                                )
                    ) {
                        Text("Presentar código de entrega")
                    }
                }
                val incidentAttemptId = delivery.activeAttempt?.id ?: state.outcomeSummary
                    ?.takeIf {
                        state.outcomeCommandStatus == DriverOutcomeCommandStatus.Recorded &&
                            it.deliveryVersion == delivery.version
                    }
                    ?.attemptId
                if (onOpenIncident != null && incidentAttemptId != null) {
                    OutlinedButton(
                        onClick = {
                            onOpenIncident(
                                delivery.id,
                                incidentAttemptId,
                                delivery.version,
                                delivery.activeAttempt == null
                            )
                        },
                        enabled =
                            state.canRead && state.canStart &&
                                state.detailStatus == DriverDeliveryLoadStatus.Ready &&
                                state.outcomeCommandStatus !in
                                setOf(
                                    DriverOutcomeCommandStatus.Pending,
                                    DriverOutcomeCommandStatus.UnknownOutcome
                                )
                    ) {
                        Text("Registrar incidente para revisión")
                    }
                }
                if (delivery.status == "DELIVERED" || state.proofId != null) {
                    ProofEntry(
                        state = state,
                        canCreate =
                            state.canCaptureProof &&
                                state.detailStatus == DriverDeliveryLoadStatus.Ready,
                        onCreateProof = onCreateProof,
                        onChooseProofFile = onChooseProofFile,
                        onUploadSelectedProofEvidence = onUploadSelectedProofEvidence,
                        onRetryUnknownProof = onRetryUnknownProof,
                        onRefreshProofEvidence = onRefreshProofEvidence,
                        onAttachAvailableProofEvidence = onAttachAvailableProofEvidence
                    )
                }
                when (state.detailStatus) {
                    DriverDeliveryLoadStatus.Loading -> Notice(
                        stringResource(R.string.driver_delivery_detail_loading)
                    )

                    DriverDeliveryLoadStatus.NetworkUnavailable -> Notice(
                        stringResource(R.string.driver_delivery_network),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.ServiceUnavailable -> Notice(
                        stringResource(R.string.driver_delivery_service),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.NotFound -> Notice(
                        stringResource(R.string.driver_delivery_not_found),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.PermissionDenied -> Notice(
                        stringResource(R.string.driver_delivery_read_permission),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.ContextInvalidated -> Notice(
                        stringResource(R.string.driver_delivery_context),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.SessionInvalidated -> Notice(
                        stringResource(R.string.driver_delivery_session),
                        isError = true
                    )

                    DriverDeliveryLoadStatus.NotRequested,
                    DriverDeliveryLoadStatus.Ready -> Unit
                }
                when (state.commandStatus) {
                    DriverDeliveryCommandStatus.CheckingCurrent -> Notice(
                        stringResource(R.string.driver_delivery_checking)
                    )

                    DriverDeliveryCommandStatus.Pending -> Notice(
                        stringResource(R.string.driver_delivery_start_pending)
                    )

                    DriverDeliveryCommandStatus.PersistingIntent,
                    DriverDeliveryCommandStatus.UnknownOutcome,
                    DriverDeliveryCommandStatus.PersistenceUnavailable -> Unit

                    DriverDeliveryCommandStatus.Started -> Notice(
                        stringResource(R.string.driver_delivery_started)
                    )

                    DriverDeliveryCommandStatus.Rejected -> Notice(
                        state.rejectionCode?.let {
                            stringResource(R.string.driver_delivery_rejected_code, it)
                        }
                            ?: stringResource(R.string.driver_delivery_rejected),
                        isError = true
                    )

                    DriverDeliveryCommandStatus.StaleVersion -> Notice(
                        stringResource(R.string.driver_delivery_stale),
                        isError = true
                    )

                    DriverDeliveryCommandStatus.Idle -> Unit
                }
                if (state.commandStatus == DriverDeliveryCommandStatus.Rejected &&
                    state.rejectionCode == "DELIVERY_CRITICAL_INSTRUCTION_ACK_REQUIRED" &&
                    onOpenInstructions != null &&
                    state.authorizedInstructionsDeliveryId == delivery.id
                ) {
                    OutlinedButton(
                        onClick = { onOpenInstructions(delivery.id) },
                        enabled =
                            state.canRead && state.detailStatus == DriverDeliveryLoadStatus.Ready
                    ) {
                        Text(stringResource(R.string.driver_delivery_instructions_required))
                    }
                }
                if (delivery.activeAttempt == null && state.commandStatus in setOf(
                        DriverDeliveryCommandStatus.Idle,
                        DriverDeliveryCommandStatus.Rejected,
                        DriverDeliveryCommandStatus.StaleVersion
                    )
                ) {
                    Button(
                        onClick = onBeginDelivery,
                        enabled =
                            state.canStart && state.detailStatus == DriverDeliveryLoadStatus.Ready
                    ) {
                        Text(stringResource(R.string.driver_delivery_begin))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProofEntry(
    state: DriverDeliveryUiState,
    canCreate: Boolean,
    onCreateProof: (String) -> Unit,
    onChooseProofFile: () -> Unit,
    onUploadSelectedProofEvidence: () -> Unit,
    onRetryUnknownProof: () -> Unit,
    onRefreshProofEvidence: () -> Unit,
    onAttachAvailableProofEvidence: () -> Unit
) {
    var receiverName by remember(state.proofId) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.driver_delivery_proof_title),
            style = MaterialTheme.typography.titleMedium
        )
        Notice(stringResource(R.string.driver_delivery_proof_policy_limit))
        if (state.proofId == null &&
            state.outcomeCommandStatus == DriverOutcomeCommandStatus.Recorded
        ) {
            OutlinedTextField(
                value = receiverName,
                onValueChange = { receiverName = it },
                label = { Text(stringResource(R.string.driver_delivery_proof_receiver)) },
                singleLine = true,
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onCreateProof(receiverName) },
                enabled =
                    canCreate && receiverName.isNotBlank()
            ) {
                Text(stringResource(R.string.driver_delivery_proof_begin))
            }
        }
        state.proofId?.let { id ->
            Text(stringResource(R.string.driver_delivery_proof_id, id))
            when (state.proofCommandStatus) {
                DriverProofCommandStatus.CheckingCurrent -> Notice(
                    stringResource(R.string.driver_delivery_proof_checking)
                )

                DriverProofCommandStatus.PersistingIntent -> Notice(
                    stringResource(R.string.driver_delivery_proof_persisting)
                )

                DriverProofCommandStatus.Pending -> Notice(
                    stringResource(R.string.driver_delivery_proof_pending)
                )

                DriverProofCommandStatus.UnknownOutcome -> {
                    Notice(stringResource(R.string.driver_delivery_proof_unknown), isError = true)
                    if (state.hasRecoverableProof) {
                        OutlinedButton(
                            onClick = onRetryUnknownProof,
                            enabled = state.canCaptureProof
                        ) {
                            Text(stringResource(R.string.driver_delivery_proof_retry_same))
                        }
                    }
                    if (state.proofEvidenceId != null && state.canReadProofEvidence) {
                        OutlinedButton(
                            onClick = onRefreshProofEvidence,
                            enabled = state.canCaptureProof
                        ) {
                            Text(stringResource(R.string.driver_delivery_proof_refresh_evidence))
                        }
                    }
                }

                DriverProofCommandStatus.PersistenceUnavailable -> Notice(
                    stringResource(R.string.driver_delivery_proof_storage_unavailable),
                    isError = true
                )

                DriverProofCommandStatus.AwaitingEvidenceSelection -> {
                    Notice(stringResource(R.string.driver_delivery_proof_pending_selection))
                    OutlinedButton(onClick = onChooseProofFile, enabled = state.canCaptureProof) {
                        Text(stringResource(R.string.driver_delivery_proof_choose_file))
                    }
                }

                DriverProofCommandStatus.ReadyToUploadReview -> {
                    Notice(stringResource(R.string.driver_delivery_proof_review))
                    OutlinedButton(
                        onClick = onUploadSelectedProofEvidence,
                        enabled = state.canCaptureProof
                    ) {
                        Text(stringResource(R.string.driver_delivery_proof_upload))
                    }
                }

                DriverProofCommandStatus.WaitingForScan -> {
                    Notice(stringResource(R.string.driver_delivery_proof_waiting_scan))
                    if (state.canReadProofEvidence) {
                        OutlinedButton(
                            onClick = onRefreshProofEvidence,
                            enabled = state.canCaptureProof
                        ) {
                            Text(stringResource(R.string.driver_delivery_proof_refresh_evidence))
                        }
                    }
                }

                DriverProofCommandStatus.EvidenceAvailable -> {
                    Notice(stringResource(R.string.driver_delivery_proof_available))
                    OutlinedButton(
                        onClick = onAttachAvailableProofEvidence,
                        enabled = state.canCaptureProof
                    ) {
                        Text(stringResource(R.string.driver_delivery_proof_attach))
                    }
                }

                DriverProofCommandStatus.Captured -> state.proofSummary?.let { proof ->
                    state.selectedDelivery?.let { delivery ->
                        Text(stringResource(R.string.driver_delivery_proof_delivery, delivery.id))
                    }
                    Text(stringResource(R.string.driver_delivery_proof_attempt, proof.attemptId))
                    Text(
                        stringResource(
                            R.string.driver_delivery_proof_actor,
                            proof.actorMembershipId
                        )
                    )
                    Text(
                        stringResource(
                            R.string.driver_delivery_proof_receiver_value,
                            proof.receiverName
                        )
                    )
                    Notice(
                        stringResource(R.string.driver_delivery_proof_captured, proof.capturedAt)
                    )
                    proof.photoEvidenceObjectId?.let { evidenceId ->
                        Text(
                            stringResource(
                                R.string.driver_delivery_proof_photo_reference,
                                evidenceId
                            )
                        )
                    }
                }

                DriverProofCommandStatus.Rejected -> Notice(
                    state.proofRejectionCode?.let {
                        stringResource(R.string.driver_delivery_proof_rejected_code, it)
                    } ?: stringResource(R.string.driver_delivery_proof_rejected),
                    isError = true
                )

                DriverProofCommandStatus.StaleVersion -> Notice(
                    stringResource(R.string.driver_delivery_proof_stale),
                    isError = true
                )

                DriverProofCommandStatus.Idle -> Unit
            }
        }
        if (!state.canCaptureProof) {
            Notice(stringResource(R.string.driver_delivery_proof_permission), isError = true)
        }
    }
}

@Composable
private fun OutcomeEntry(
    delivery: DriverDeliverySnapshot,
    state: DriverDeliveryUiState,
    onRecordOutcome: (DriverOutcomeKind, Map<String, String>, String?, String?) -> Unit
) {
    var selected by remember(delivery.id) { mutableStateOf(DriverOutcomeKind.DELIVERED) }
    var reason by remember(delivery.id) { mutableStateOf("") }
    var notes by remember(delivery.id) { mutableStateOf("") }
    val delivered = remember(delivery.id) { mutableStateMapOf<String, String>() }
    val canEdit = state.canStart && state.detailStatus == DriverDeliveryLoadStatus.Ready &&
        state.outcomeCommandStatus in setOf(
            DriverOutcomeCommandStatus.Idle,
            DriverOutcomeCommandStatus.Recorded,
            DriverOutcomeCommandStatus.Rejected,
            DriverOutcomeCommandStatus.StaleVersion
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.driver_delivery_outcome_title),
            style = MaterialTheme.typography.titleMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DriverOutcomeKind.entries.forEach { kind ->
                TextButton(onClick = { selected = kind }, enabled = canEdit) {
                    Text(
                        text = stringResource(kind.label()),
                        fontWeight = if (kind == selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        if (selected == DriverOutcomeKind.PARTIAL) {
            delivery.outcomeLines.filter { it.remainingQuantity.signum() > 0 }.forEach { line ->
                OutlinedTextField(
                    value = delivered[line.fulfillmentLineId].orEmpty(),
                    onValueChange = { delivered[line.fulfillmentLineId] = it },
                    label = {
                        Text(
                            stringResource(
                                R.string.driver_delivery_partial_quantity,
                                line.catalogItemId,
                                line.remainingQuantity.toPlainString(),
                                line.unit
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = canEdit,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (selected in
            setOf(DriverOutcomeKind.FAILED, DriverOutcomeKind.REFUSED, DriverOutcomeKind.ABSENT)
        ) {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.driver_delivery_outcome_reason)) },
                enabled = canEdit,
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(stringResource(R.string.driver_delivery_outcome_notes)) },
            enabled = canEdit,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onRecordOutcome(selected, delivered.toMap(), reason, notes) },
            enabled = canEdit
        ) {
            Text(stringResource(R.string.driver_delivery_outcome_submit))
        }
        if (!state.canStart) {
            Notice(
                stringResource(R.string.driver_delivery_start_permission),
                isError = true
            )
        }
    }
}

private fun DriverOutcomeKind.label(): Int = when (this) {
    DriverOutcomeKind.DELIVERED -> R.string.driver_delivery_outcome_delivered
    DriverOutcomeKind.PARTIAL -> R.string.driver_delivery_outcome_partial
    DriverOutcomeKind.FAILED -> R.string.driver_delivery_outcome_failed
    DriverOutcomeKind.REFUSED -> R.string.driver_delivery_outcome_refused
    DriverOutcomeKind.ABSENT -> R.string.driver_delivery_outcome_absent
}

@Composable
private fun Notice(text: String, isError: Boolean = false) {
    Text(
        text = text,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodyMedium
    )
}

private val FROZEN_COMMANDS = setOf(
    DriverDeliveryCommandStatus.CheckingCurrent,
    DriverDeliveryCommandStatus.PersistingIntent,
    DriverDeliveryCommandStatus.Pending,
    DriverDeliveryCommandStatus.UnknownOutcome,
    DriverDeliveryCommandStatus.PersistenceUnavailable
)
