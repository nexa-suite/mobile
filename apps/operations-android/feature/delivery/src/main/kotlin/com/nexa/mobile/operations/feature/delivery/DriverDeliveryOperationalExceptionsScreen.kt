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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DriverDeliveryOperationalExceptionsScreen(
    state: DriverDeliveryOperationalExceptionsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClaim: (String) -> Unit,
    onSendForReview: (String) -> Unit,
    onResolve: (String, String) -> Unit,
    onClose: (String) -> Unit,
    onRetrySameCommand: () -> Unit
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
                stringResource(R.string.driver_operational_exceptions_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.driver_operational_exceptions_scope),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (state.loadStatus) {
                DriverDeliveryOperationalExceptionsLoadStatus.Loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.driver_operational_exceptions_loading))
                }

                DriverDeliveryOperationalExceptionsLoadStatus.NotFound -> ExceptionNotice(
                    stringResource(R.string.driver_operational_exceptions_not_found), isError = true
                )

                DriverDeliveryOperationalExceptionsLoadStatus.NetworkUnavailable -> ExceptionNotice(
                    stringResource(R.string.driver_operational_exceptions_network), isError = true
                )

                DriverDeliveryOperationalExceptionsLoadStatus.ServiceUnavailable -> ExceptionNotice(
                    stringResource(R.string.driver_operational_exceptions_service), isError = true
                )

                DriverDeliveryOperationalExceptionsLoadStatus.PermissionDenied -> ExceptionNotice(
                    stringResource(R.string.driver_operational_exceptions_permission), isError = true
                )

                DriverDeliveryOperationalExceptionsLoadStatus.ContextInvalidated -> ExceptionNotice(
                    stringResource(R.string.driver_delivery_context), isError = true
                )

                DriverDeliveryOperationalExceptionsLoadStatus.SessionInvalidated -> ExceptionNotice(
                    stringResource(R.string.driver_delivery_session), isError = true
                )

                DriverDeliveryOperationalExceptionsLoadStatus.NotRequested,
                DriverDeliveryOperationalExceptionsLoadStatus.Ready -> Unit
            }

            OutlinedButton(onClick = onRefresh, enabled = state.canRead) {
                Text(stringResource(R.string.driver_operational_exceptions_refresh))
            }

            state.snapshot?.let { snapshot ->
                Text(
                    stringResource(R.string.driver_operational_exceptions_version, snapshot.deliveryVersion),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (snapshot.exceptions.isEmpty()) {
                    ExceptionNotice(stringResource(R.string.driver_operational_exceptions_empty))
                }
                snapshot.exceptions.forEach { exception ->
                    OperationalExceptionCard(
                        exception = exception,
                        canClaim = state.canClaim(exception),
                        canReview = state.canReview(exception),
                        canResolveWarning = state.canResolveWarning(exception),
                        canCloseWarning = state.canCloseWarning(exception),
                        onClaim = { onClaim(exception.id) },
                        onSendForReview = { onSendForReview(exception.id) },
                        onResolve = { resolution -> onResolve(exception.id, resolution) },
                        onClose = { onClose(exception.id) }
                    )
                }
            }

            if (!state.canRead) {
                ExceptionNotice(stringResource(R.string.driver_operational_exceptions_permission), isError = true)
            }
            if (state.unresolvedCommandForOtherDelivery) {
                ExceptionNotice(
                    stringResource(R.string.driver_operational_exceptions_other_pending), isError = true
                )
            }
            when (state.commandStatus) {
                DriverDeliveryOperationalExceptionCommandStatus.Idle -> Unit
                DriverDeliveryOperationalExceptionCommandStatus.PersistingIntent -> ExceptionNotice(
                    stringResource(R.string.driver_operational_exceptions_persisting)
                )

                DriverDeliveryOperationalExceptionCommandStatus.Pending -> ExceptionNotice(
                    stringResource(R.string.driver_operational_exceptions_pending)
                )

                DriverDeliveryOperationalExceptionCommandStatus.UnknownOutcome -> {
                    ExceptionNotice(stringResource(R.string.driver_operational_exceptions_unknown), isError = true)
                    if (state.hasRecoverableCommand) {
                        OutlinedButton(onClick = onRetrySameCommand, enabled = state.canRespond) {
                            Text(stringResource(R.string.driver_operational_exceptions_retry_same))
                        }
                    }
                }

                DriverDeliveryOperationalExceptionCommandStatus.PersistenceUnavailable -> ExceptionNotice(
                    stringResource(R.string.driver_operational_exceptions_storage_unavailable), isError = true
                )

                DriverDeliveryOperationalExceptionCommandStatus.Claimed -> {
                    ExceptionNotice(
                        if (state.replayed) {
                            stringResource(R.string.driver_operational_exceptions_claim_replayed)
                        } else {
                            stringResource(R.string.driver_operational_exceptions_claimed)
                        }
                    )
                    if (state.hasRecoverableCommand) RetrySameCommandButton(onRetrySameCommand, state.canRespond)
                }

                DriverDeliveryOperationalExceptionCommandStatus.UnderReview -> {
                    ExceptionNotice(
                        if (state.replayed) {
                            stringResource(R.string.driver_operational_exceptions_review_replayed)
                        } else {
                            stringResource(R.string.driver_operational_exceptions_under_review)
                        }
                    )
                    if (state.hasRecoverableCommand) RetrySameCommandButton(onRetrySameCommand, state.canRespond)
                }

                DriverDeliveryOperationalExceptionCommandStatus.Resolved -> ExceptionNotice(
                    if (state.replayed) {
                        stringResource(R.string.driver_operational_exceptions_resolution_replayed)
                    } else {
                        stringResource(R.string.driver_operational_exceptions_resolved)
                    }
                )

                DriverDeliveryOperationalExceptionCommandStatus.Closed -> ExceptionNotice(
                    if (state.replayed) {
                        stringResource(R.string.driver_operational_exceptions_closure_replayed)
                    } else {
                        stringResource(R.string.driver_operational_exceptions_closed)
                    }
                )

                DriverDeliveryOperationalExceptionCommandStatus.StaleVersion -> {
                    ExceptionNotice(stringResource(R.string.driver_operational_exceptions_stale), isError = true)
                    ExceptionNotice(stringResource(R.string.driver_operational_exceptions_fresh_decision))
                }

                DriverDeliveryOperationalExceptionCommandStatus.Rejected -> {
                    ExceptionNotice(
                        state.rejectionCode?.let {
                            stringResource(R.string.driver_operational_exceptions_rejected_code, it)
                        } ?: stringResource(R.string.driver_operational_exceptions_rejected),
                        isError = true
                    )
                    if (state.hasRecoverableCommand) RetrySameCommandButton(onRetrySameCommand, state.canRespond)
                }
            }
        }
    }
}

@Composable
private fun OperationalExceptionCard(
    exception: DriverDeliveryOperationalException,
    canClaim: Boolean,
    canReview: Boolean,
    canResolveWarning: Boolean,
    canCloseWarning: Boolean,
    onClaim: () -> Unit,
    onSendForReview: () -> Unit,
    onResolve: (String) -> Unit,
    onClose: () -> Unit
) {
    var resolution by remember(exception.id) { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.driver_operational_exceptions_type_severity, exception.type, exception.severity),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.driver_operational_exceptions_status, exception.status))
            Text(exception.description)
            Text(stringResource(
                R.string.driver_operational_exceptions_source, exception.sourceKind, exception.sourceIncidentId
            ))
            Text(stringResource(
                R.string.driver_operational_exceptions_affected_object,
                exception.affectedObjectType,
                exception.affectedObjectId
            ))
            exception.reason?.let { Text(stringResource(R.string.driver_operational_exceptions_reason, it)) }
            Text(stringResource(R.string.driver_operational_exceptions_occurred_at, exception.occurredAt))
            Text(
                stringResource(
                    R.string.driver_operational_exceptions_reported_by,
                    exception.reportedByMembershipId,
                    exception.reportedAt
                )
            )
            exception.responsibleMembershipId?.let {
                Text(
                    exception.claimedAt?.let { claimedAt ->
                        stringResource(R.string.driver_operational_exceptions_responsible_at, it, claimedAt)
                    } ?: stringResource(R.string.driver_operational_exceptions_responsible, it)
                )
            }
            exception.place?.let { Text(stringResource(R.string.driver_operational_exceptions_place, it)) }
            exception.resolution?.let { Text(stringResource(R.string.driver_operational_exceptions_resolution, it)) }
            exception.outcome?.let { Text(stringResource(R.string.driver_operational_exceptions_outcome, it)) }
            if (exception.evidenceObjectIds.isNotEmpty()) {
                Text(stringResource(
                    R.string.driver_operational_exceptions_evidence,
                    exception.evidenceObjectIds.joinToString()
                ))
            }
            exception.underReviewByMembershipId?.let { reviewer ->
                Text(
                    exception.underReviewAt?.let { reviewedAt ->
                        stringResource(R.string.driver_operational_exceptions_reviewed_at, reviewer, reviewedAt)
                    } ?: stringResource(R.string.driver_operational_exceptions_reviewed_by, reviewer)
                )
            }
            if (canClaim) {
                Button(onClick = onClaim) {
                    Text(stringResource(R.string.driver_operational_exceptions_claim))
                }
            }
            if (canReview) {
                OutlinedButton(onClick = onSendForReview) {
                    Text(stringResource(R.string.driver_operational_exceptions_send_for_review))
                }
            }
            if (canResolveWarning) {
                OutlinedTextField(
                    value = resolution,
                    onValueChange = { candidate ->
                        if (candidate.length <= DRIVER_WARNING_RESOLUTION_MAX_CHARS) resolution = candidate
                    },
                    label = { Text(stringResource(R.string.driver_operational_exceptions_resolution_input)) },
                    supportingText = {
                        Text(stringResource(
                            R.string.driver_operational_exceptions_resolution_length,
                            resolution.length,
                            DRIVER_WARNING_RESOLUTION_MAX_CHARS
                        ))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { onResolve(resolution) }, enabled = resolution.isNotBlank()) {
                    Text(stringResource(R.string.driver_operational_exceptions_resolve_warning))
                }
            }
            if (canCloseWarning) {
                OutlinedButton(onClick = onClose) {
                    Text(stringResource(R.string.driver_operational_exceptions_close_warning))
                }
            }
        }
    }
}

@Composable
private fun RetrySameCommandButton(onRetry: () -> Unit, enabled: Boolean) {
    OutlinedButton(onClick = onRetry, enabled = enabled) {
        Text(stringResource(R.string.driver_operational_exceptions_retry_same))
    }
}

@Composable
private fun ExceptionNotice(message: String, isError: Boolean = false) {
    Text(
        message,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}
