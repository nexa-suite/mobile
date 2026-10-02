package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMutationStatus as TemperatureMutationStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperaturePhotoStatus as TemperaturePhotoStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun DispatchTemperatureScreen(
    state: DispatchTemperatureUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onValueChanged: (lotId: String, valueCelsius: String) -> Unit,
    onRecord: (lotId: String) -> Unit,
    onSelectExcursionEvidence: (lotId: String) -> Unit = {},
    onRefreshExcursionEvidence: (lotId: String) -> Unit = {},
    onRetryUnknownOutcome: () -> Unit = {},
    onRouteClosed: () -> Unit
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) { onDispose { closeAction.value() } }

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
                    Text(stringResource(R.string.dispatch_temperature_back))
                }
                Text(
                    stringResource(R.string.dispatch_temperature_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.dispatch_temperature_authority_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { TemperatureStatusPanel(state.status, state.observedAt) }
            item {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = state.status != DispatchTemperatureStatus.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dispatch_temperature_refresh)) }
            }
            item {
                MutationStatusPanel(
                    state.mutationStatus,
                    state.canRetryUnknownOutcome,
                    onRetryUnknownOutcome
                )
            }
            if (state.status == DispatchTemperatureStatus.Loading) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.dispatch_temperature_loading))
                    }
                }
            }
            val readiness = state.readiness
            if (readiness != null && state.status == DispatchTemperatureStatus.Current) {
                item { FulfillmentTemperatureNote() }
                if (!state.canRecord) {
                    item { InfoCard(stringResource(R.string.dispatch_temperature_read_only)) }
                }
                if (readiness.lots.isEmpty()) {
                    item {
                        InfoCard(stringResource(R.string.dispatch_temperature_no_allocated_lots))
                    }
                }
                items(readiness.lots, key = { it.lotId ?: it.skuId }) { lot ->
                    TemperatureLotCard(
                        lot = lot,
                        value = lot.lotId?.let { state.valuesCelsius[it] }.orEmpty(),
                        canRecord =
                            state.canRecord && state.metadataReady && !state.hasPendingCommand &&
                                state.mutationStatus != TemperatureMutationStatus.Submitting,
                        canUploadPhoto = state.canUploadPhoto && !state.hasPendingCommand &&
                            state.mutationStatus != TemperatureMutationStatus.Submitting,
                        onValueChanged = { lotId, value -> onValueChanged(lotId, value) },
                        onRecord = onRecord,
                        photo = lot.lotId?.let { state.photoByLotId[it] },
                        onSelectExcursionEvidence = onSelectExcursionEvidence,
                        onRefreshExcursionEvidence = onRefreshExcursionEvidence
                    )
                }
            }
        }
    }
}

@Composable
private fun TemperatureStatusPanel(status: DispatchTemperatureStatus, observedAt: Instant?) {
    val message = when (status) {
        DispatchTemperatureStatus.Initial -> R.string.dispatch_temperature_initial

        DispatchTemperatureStatus.Loading -> R.string.dispatch_temperature_loading

        DispatchTemperatureStatus.Current -> R.string.dispatch_temperature_current

        DispatchTemperatureStatus.PermissionUnknown ->
            R.string.dispatch_temperature_permission_unknown

        DispatchTemperatureStatus.PermissionDenied ->
            R.string.dispatch_temperature_permission_denied

        DispatchTemperatureStatus.NetworkUnavailable -> R.string.dispatch_temperature_network

        DispatchTemperatureStatus.ServiceUnavailable -> R.string.dispatch_temperature_service

        DispatchTemperatureStatus.ContextInvalidated ->
            R.string.dispatch_temperature_context_invalidated

        DispatchTemperatureStatus.SessionInvalidated ->
            R.string.dispatch_temperature_session_invalidated
    }
    InfoCard(stringResource(message)) {
        if (status == DispatchTemperatureStatus.Current && observedAt != null) {
            Text(
                stringResource(
                    R.string.dispatch_temperature_as_of,
                    formatTemperatureInstant(observedAt)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FulfillmentTemperatureNote() {
    InfoCard(stringResource(R.string.dispatch_temperature_optional_note))
}

@Composable
private fun TemperatureLotCard(
    lot: DispatchTemperatureLot,
    value: String,
    canRecord: Boolean,
    canUploadPhoto: Boolean,
    onValueChanged: (lotId: String, valueCelsius: String) -> Unit,
    onRecord: (lotId: String) -> Unit,
    photo: DispatchTemperaturePhotoState?,
    onSelectExcursionEvidence: (lotId: String) -> Unit,
    onRefreshExcursionEvidence: (lotId: String) -> Unit
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.dispatch_temperature_lot_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.dispatch_temperature_sku, lot.skuId))
            Text(
                stringResource(
                    R.string.dispatch_temperature_lot,
                    lot.lotId ?: stringResource(R.string.dispatch_temperature_unavailable)
                )
            )
            Text(stringResource(R.string.dispatch_temperature_lot_status, lot.status))
            Text(stringResource(R.string.dispatch_temperature_range, rangeDescription(lot)))
            Text(
                stringResource(
                    R.string.dispatch_temperature_policy,
                    if (lot.skuColdChainRequired) {
                        stringResource(R.string.dispatch_temperature_policy_applies)
                    } else {
                        stringResource(R.string.dispatch_temperature_policy_not_required)
                    }
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            lot.latestEvidence?.let { evidence ->
                Text(
                    stringResource(
                        R.string.dispatch_temperature_latest,
                        evidence.valueCelsius.stripTrailingZeros().toPlainString(),
                        formatTemperatureInstant(evidence.occurredAt),
                        evidence.status
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                evidence.inventoryLotStatus?.let { status ->
                    Text(stringResource(R.string.dispatch_temperature_server_lot_status, status))
                }
                evidence.affectedQuantity?.let { quantity ->
                    Text(
                        stringResource(
                            R.string.dispatch_temperature_affected_quantity,
                            quantity.stripTrailingZeros().toPlainString()
                        )
                    )
                }
            }
            if (lot.supportsInRangeEvidence && lot.lotId != null) {
                val readingDescription = stringResource(R.string.dispatch_temperature_entry)
                OutlinedTextField(
                    value = value,
                    onValueChange = { onValueChanged(lot.lotId, it) },
                    enabled = canRecord,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "$readingDescription ${lot.lotId}"
                        },
                    label = { Text(stringResource(R.string.dispatch_temperature_celsius_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                val numericValue = value.trim().toBigDecimalOrNull()
                val outOfRange = numericValue != null && !lot.isWithinRange(numericValue)
                if (outOfRange) {
                    Text(
                        stringResource(R.string.dispatch_temperature_out_of_range_note),
                        color = MaterialTheme.colorScheme.error
                    )
                    ExcursionPhotoPanel(
                        lotId = lot.lotId,
                        photo = photo?.takeIf { it.warehouseId == lot.warehouseId },
                        canUpload = canUploadPhoto,
                        onSelect = onSelectExcursionEvidence,
                        onRefresh = onRefreshExcursionEvidence
                    )
                }
                Button(
                    onClick = { onRecord(lot.lotId) },
                    enabled = canRecord && numericValue != null && (
                        !outOfRange || photo?.let {
                            it.warehouseId == lot.warehouseId && it.isAvailable &&
                                it.evidence?.subjectType == "WAREHOUSE" &&
                                it.evidence.subjectId == lot.warehouseId
                        } == true
                        ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (outOfRange) {
                                R.string.dispatch_temperature_record_excursion
                            } else {
                                R.string.dispatch_temperature_record
                            }
                        )
                    )
                }
            } else {
                Text(
                    stringResource(R.string.dispatch_temperature_evidence_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ExcursionPhotoPanel(
    lotId: String,
    photo: DispatchTemperaturePhotoState?,
    canUpload: Boolean,
    onSelect: (lotId: String) -> Unit,
    onRefresh: (lotId: String) -> Unit
) {
    val message = when (photo?.status ?: TemperaturePhotoStatus.None) {
        TemperaturePhotoStatus.None -> R.string.dispatch_temperature_photo_required
        TemperaturePhotoStatus.Uploading -> R.string.dispatch_temperature_photo_uploading
        TemperaturePhotoStatus.Checking -> R.string.dispatch_temperature_photo_checking
        TemperaturePhotoStatus.AwaitingAvailability -> R.string.dispatch_temperature_photo_awaiting
        TemperaturePhotoStatus.Available -> R.string.dispatch_temperature_photo_available
        TemperaturePhotoStatus.UnknownOutcome -> R.string.dispatch_temperature_photo_unknown
        TemperaturePhotoStatus.NetworkUnavailable -> R.string.dispatch_temperature_photo_network
        TemperaturePhotoStatus.ServiceUnavailable -> R.string.dispatch_temperature_photo_service
        TemperaturePhotoStatus.Rejected -> R.string.dispatch_temperature_photo_rejected
        TemperaturePhotoStatus.PermissionDenied -> R.string.dispatch_temperature_photo_permission
    }
    InfoCard(stringResource(message)) {
        if (photo?.status == TemperaturePhotoStatus.Uploading ||
            photo?.status == TemperaturePhotoStatus.Checking
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.dispatch_temperature_photo_working))
            }
        } else if (photo?.status in setOf(
                TemperaturePhotoStatus.AwaitingAvailability,
                TemperaturePhotoStatus.ServiceUnavailable,
                TemperaturePhotoStatus.NetworkUnavailable
            ) && photo?.evidence != null
        ) {
            OutlinedButton(
                onClick = { onRefresh(lotId) },
                enabled = canUpload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.dispatch_temperature_photo_check_again))
            }
        }
        if (photo?.status != TemperaturePhotoStatus.Available) {
            Button(
                onClick = { onSelect(lotId) },
                enabled = canUpload && photo?.status !in setOf(
                    TemperaturePhotoStatus.Uploading,
                    TemperaturePhotoStatus.Checking
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (photo?.status == TemperaturePhotoStatus.UnknownOutcome) {
                            R.string.dispatch_temperature_photo_retry_same
                        } else {
                            R.string.dispatch_temperature_photo_attach
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun MutationStatusPanel(
    status: TemperatureMutationStatus,
    canRetry: Boolean,
    onRetry: () -> Unit
) {
    val message = when (status) {
        TemperatureMutationStatus.Idle -> null

        TemperatureMutationStatus.Submitting -> R.string.dispatch_temperature_submitting

        TemperatureMutationStatus.Recorded -> R.string.dispatch_temperature_recorded

        TemperatureMutationStatus.ExcursionPhotoRequired ->
            R.string.dispatch_temperature_photo_required

        TemperatureMutationStatus.OutsideRangeBackendContractGap ->
            R.string.dispatch_temperature_out_of_range_gap

        TemperatureMutationStatus.UnknownOutcome -> R.string.dispatch_temperature_unknown_outcome

        TemperatureMutationStatus.NetworkUnavailable -> R.string.dispatch_temperature_network

        TemperatureMutationStatus.ServiceUnavailable -> R.string.dispatch_temperature_service

        TemperatureMutationStatus.PermissionDenied ->
            R.string.dispatch_temperature_permission_denied

        TemperatureMutationStatus.Stale -> R.string.dispatch_temperature_stale

        TemperatureMutationStatus.Conflict -> R.string.dispatch_temperature_conflict
    } ?: if (canRetry) R.string.dispatch_temperature_retry_unknown else return
    InfoCard(stringResource(message)) {
        if (canRetry) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dispatch_temperature_retry_unknown))
            }
        }
    }
}

@Composable
private fun InfoCard(message: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

private fun rangeDescription(lot: DispatchTemperatureLot): String {
    val min = lot.minimumCelsius?.stripTrailingZeros()?.toPlainString()
    val max = lot.maximumCelsius?.stripTrailingZeros()?.toPlainString()
    return when {
        min != null && max != null -> "$min °C to $max °C"
        min != null -> "at least $min °C"
        max != null -> "at most $max °C"
        else -> "Unavailable"
    }
}

private fun formatTemperatureInstant(value: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(value)
