package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun StockTransferScreen(
    state: StockTransferUiState,
    onBack: () -> Unit,
    onSelectSourceLot: (String) -> Unit,
    onSelectDestinationWarehouse: (String) -> Unit,
    onSelectDestinationZone: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onReasonChanged: (String) -> Unit,
    onReloadSourceLots: () -> Unit,
    onReloadWarehouses: () -> Unit,
    onReloadZones: () -> Unit,
    onStartTransfer: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onRetryIntentCleanup: () -> Unit,
    onStartAnotherTransfer: () -> Unit
) {
    val editable =
        state.command in setOf(TransferCommandStatus.Editing, TransferCommandStatus.Rejected) &&
            !state.intentCleanupPending
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
            TextButton(onClick = onBack) { Text(stringResource(R.string.transfer_back)) }
            Text(
                text = stringResource(R.string.transfer_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.transfer_authority_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!state.canCreate) {
                Notice(stringResource(R.string.transfer_permission_missing), isError = true)
            }
            Notice(
                text = metadataText(state.metadata),
                isError = state.metadata == TransferMetadataStatus.Unavailable
            )
            state.notice?.let { notice ->
                Notice(
                    text = noticeText(notice),
                    isError = notice != TransferSubmitNotice.PreconditionFailed
                )
            }
            state.validationError?.let { error ->
                Notice(validationText(error), isError = true)
            }
            state.rejectionCode?.let { code ->
                Notice(stringResource(R.string.transfer_rejected, code), isError = true)
            }

            Section(title = stringResource(R.string.transfer_source_title)) {
                LookupMessage(state.sourceLotLookup, onReloadSourceLots)
                if (state.sourceLots.isEmpty() &&
                    state.sourceLotLookup == TransferLookupStatus.Ready
                ) {
                    Text(stringResource(R.string.transfer_lot_empty))
                }
                state.sourceLots.filter(TransferSourceLotChoice::isSelectable).forEach { lot ->
                    val selected = lot.id == state.selectedSourceLotId
                    ChoiceCard(selected = selected, onClick = {
                        onSelectSourceLot(lot.id)
                    }, label = {
                        val warehouse = state.warehouses.firstOrNull { it.id == lot.warehouseId }
                            ?.let { "${it.name} · ${it.code}" } ?: lot.warehouseId
                        Text(lot.batchNumber, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(
                                R.string.transfer_lot_product_reference,
                                lot.skuId ?: lot.catalogItemId.orEmpty()
                            )
                        )
                        Text(stringResource(R.string.transfer_lot_location, warehouse, lot.zoneId))
                        Text(
                            stringResource(
                                R.string.transfer_lot_remaining,
                                lot.physicalRemaining.toPlainString(),
                                lot.unit
                            )
                        )
                    })
                }
            }

            Section(title = stringResource(R.string.transfer_destination_title)) {
                LookupMessage(state.warehouseLookup, onReloadWarehouses)
                state.warehouses.filter(
                    TransferWarehouseChoice::isSelectable
                ).forEach { warehouse ->
                    ChoiceCard(
                        selected = warehouse.id == state.selectedDestinationWarehouseId,
                        onClick = { onSelectDestinationWarehouse(warehouse.id) },
                        label = { Text("${warehouse.name} · ${warehouse.code}") }
                    )
                }
                if (state.selectedDestinationWarehouseId != null) {
                    LookupMessage(state.zoneLookup, onReloadZones)
                    state.zones.filter(TransferZoneChoice::isSelectable).forEach { zone ->
                        ChoiceCard(
                            selected = zone.id == state.selectedDestinationZoneId,
                            onClick = { onSelectDestinationZone(zone.id) },
                            label = { Text("${zone.code} · ${zone.name}") }
                        )
                    }
                }
            }

            Section(title = stringResource(R.string.transfer_details_title)) {
                OutlinedTextField(
                    value = state.quantityText,
                    onValueChange = onQuantityChanged,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.transfer_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                state.selectedSourceLotId?.let { lotId ->
                    state.sourceLots.firstOrNull { it.id == lotId }?.let { lot ->
                        Text(stringResource(R.string.transfer_unit_label, lot.unit))
                    }
                }
                OutlinedTextField(
                    value = state.reason,
                    onValueChange = onReasonChanged,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.transfer_reason_label)) },
                    minLines = 2,
                    maxLines = 4
                )
            }

            when (state.command) {
                TransferCommandStatus.Pending,
                TransferCommandStatus.PersistingIntent -> Notice(
                    stringResource(R.string.transfer_pending),
                    isError = false
                )

                TransferCommandStatus.UnknownOutcome -> {
                    Notice(stringResource(R.string.transfer_unknown), isError = true)
                    Button(
                        onClick = onRetryUnknownOutcome,
                        enabled =
                            state.canCreate && state.metadata == TransferMetadataStatus.Available,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.transfer_retry_same)) }
                }

                TransferCommandStatus.Confirmed -> {
                    state.confirmed?.let { transfer ->
                        Notice(
                            stringResource(
                                R.string.transfer_confirmed,
                                transfer.id,
                                transfer.status,
                                transfer.requestedQuantity.toPlainString(),
                                transfer.unit
                            ),
                            isError = false
                        )
                    }
                    if (state.intentCleanupPending) {
                        OutlinedButton(onClick = onRetryIntentCleanup) {
                            Text(stringResource(R.string.transfer_retry_cleanup))
                        }
                    } else {
                        OutlinedButton(onClick = onStartAnotherTransfer) {
                            Text(stringResource(R.string.transfer_another))
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = onStartTransfer,
                        enabled = state.command == TransferCommandStatus.Editing && editable &&
                            state.canCreate && state.metadata == TransferMetadataStatus.Available,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.transfer_submit)) }
                    if (state.command == TransferCommandStatus.Rejected) {
                        OutlinedButton(onClick = onStartAnotherTransfer) {
                            Text(stringResource(R.string.transfer_another))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LookupMessage(status: TransferLookupStatus, retry: () -> Unit) {
    when (status) {
        TransferLookupStatus.Loading -> Text(stringResource(R.string.transfer_loading))

        TransferLookupStatus.Empty -> Text(stringResource(R.string.transfer_lookup_empty))

        TransferLookupStatus.NetworkUnavailable,
        TransferLookupStatus.ServiceUnavailable,
        TransferLookupStatus.SessionInvalidated,
        TransferLookupStatus.ContextInvalidated -> {
            Text(stringResource(R.string.transfer_lookup_unavailable))
            TextButton(onClick = retry) { Text(stringResource(R.string.transfer_retry_lookup)) }
        }

        TransferLookupStatus.PermissionDenied -> Text(
            stringResource(R.string.transfer_lookup_denied)
        )

        TransferLookupStatus.NotRequested,
        TransferLookupStatus.Ready -> Unit
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun ChoiceCard(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (selected) "Selected" else "Choose" },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            label()
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun metadataText(status: TransferMetadataStatus): String = when (status) {
    TransferMetadataStatus.Loading -> stringResource(R.string.transfer_metadata_loading)
    TransferMetadataStatus.Available -> stringResource(R.string.transfer_metadata_available)
    TransferMetadataStatus.Unavailable -> stringResource(R.string.transfer_metadata_unavailable)
}

@Composable
private fun noticeText(notice: TransferSubmitNotice): String = when (notice) {
    TransferSubmitNotice.NetworkUnavailable -> stringResource(R.string.transfer_network_unknown)

    TransferSubmitNotice.ServiceUnavailable -> stringResource(R.string.transfer_service_unknown)

    TransferSubmitNotice.PermissionDenied -> stringResource(R.string.transfer_permission_missing)

    TransferSubmitNotice.ContextInvalidated -> stringResource(R.string.transfer_context_invalid)

    TransferSubmitNotice.SessionInvalidated -> stringResource(R.string.transfer_session_invalid)

    TransferSubmitNotice.PreconditionFailed -> stringResource(R.string.transfer_stale_source)

    TransferSubmitNotice.Conflict -> stringResource(R.string.transfer_conflict)

    TransferSubmitNotice.IntentMetadataUnavailable -> stringResource(
        R.string.transfer_metadata_unavailable
    )
}

@Composable
private fun validationText(error: TransferValidationError): String = when (error) {
    TransferValidationError.SourceLotRequired -> stringResource(R.string.transfer_source_required)

    TransferValidationError.DestinationWarehouseRequired -> stringResource(
        R.string.transfer_destination_required
    )

    TransferValidationError.DestinationZoneRequired -> stringResource(
        R.string.transfer_zone_required
    )

    TransferValidationError.SameLocation -> stringResource(R.string.transfer_same_location)

    TransferValidationError.QuantityRequired -> stringResource(R.string.transfer_quantity_required)

    TransferValidationError.QuantityInvalid -> stringResource(R.string.transfer_quantity_invalid)

    TransferValidationError.QuantityMustBePositive -> stringResource(
        R.string.transfer_quantity_positive
    )

    TransferValidationError.QuantityExceedsSource -> stringResource(
        R.string.transfer_quantity_too_large
    )

    TransferValidationError.ReasonRequired -> stringResource(R.string.transfer_reason_required)

    TransferValidationError.MetadataUnavailable -> stringResource(
        R.string.transfer_metadata_unavailable
    )
}
