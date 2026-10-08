package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.feature.warehouse.model.ReceivedLotFacts

@Composable
fun ReceivingScreen(
    state: ReceivingUiState,
    onBack: () -> Unit,
    onChooseProduct: () -> Unit,
    onSelectWarehouse: (String) -> Unit,
    onSelectZone: (String) -> Unit,
    onBatchNumberChanged: (String) -> Unit,
    onExpirationDateChanged: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onUnitChanged: (String) -> Unit,
    onTemperatureReadingChanged: (String) -> Unit,
    onReloadWarehouses: () -> Unit,
    onReloadZones: () -> Unit,
    onSubmit: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onRetryIntentCleanup: () -> Unit,
    onStartAnotherReceipt: () -> Unit,
    onReportDiscrepancy: (() -> Unit)? = null,
    onChooseTemperatureEvidence: (() -> Unit)? = null,
    onRefreshTemperatureEvidence: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.receiving_back)) }
            Text(
                text = stringResource(R.string.receiving_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.receiving_draft_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StorageNotice(state.metadata)
            PermissionNotice(state)
            LookupNotice(state.warehouseLookup, zones = false, onReloadWarehouses)

            SectionCard(
                title = stringResource(
                    if (state.productVerifiedEpoch == state.authorityEpoch &&
                        state.product != null
                    ) {
                        R.string.receiving_product_title
                    } else {
                        R.string.receiving_product_draft_title
                    }
                )
            ) {
                val product = state.product
                if (product == null) {
                    Text(stringResource(R.string.receiving_product_missing))
                } else {
                    Text(product.displayName, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.receiving_product_sku, product.skuCode),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.productVerifiedEpoch != state.authorityEpoch &&
                        state.command != ReceivingCommandStatus.UnknownOutcome
                    ) {
                        Text(
                            stringResource(R.string.receiving_product_reconfirm),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                OutlinedButton(
                    onClick = onChooseProduct,
                    enabled = !state.isIntentFrozen &&
                        state.command !in setOf(
                            ReceivingCommandStatus.Confirmed,
                            ReceivingCommandStatus.Pending
                        )
                ) {
                    Text(stringResource(R.string.receiving_choose_product))
                }
            }

            SectionCard(title = stringResource(R.string.receiving_warehouse_title)) {
                if (state.warehouses.isEmpty()) {
                    Text(stringResource(R.string.receiving_warehouse_empty))
                }
                state.warehouses.forEach { warehouse ->
                    ChoiceRow(
                        label = "${warehouse.name} · ${warehouse.code}",
                        selected = state.selectedWarehouseId == warehouse.id,
                        enabled = !state.isIntentFrozen,
                        onClick = { onSelectWarehouse(warehouse.id) }
                    )
                }
                LookupNotice(state.zoneLookup, zones = true, onReloadZones)
                state.zones.forEach { zone ->
                    ChoiceRow(
                        label = "${zone.name} · ${zone.code}",
                        selected = state.selectedZoneId == zone.id,
                        enabled = !state.isIntentFrozen,
                        onClick = { onSelectZone(zone.id) }
                    )
                }
            }

            if (onReportDiscrepancy != null &&
                state.productVerifiedEpoch == state.authorityEpoch &&
                state.product?.skuId != null && state.selectedWarehouseId != null
            ) {
                OutlinedButton(
                    onClick = onReportDiscrepancy,
                    enabled = !state.isIntentFrozen && state.command !in setOf(
                        ReceivingCommandStatus.Confirmed,
                        ReceivingCommandStatus.Pending
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.receiving_report_discrepancy))
                }
            }

            SectionCard(title = stringResource(R.string.receiving_lot_title)) {
                OutlinedTextField(
                    value = state.batchNumber,
                    onValueChange = onBatchNumberChanged,
                    enabled =
                        !state.isIntentFrozen && state.command != ReceivingCommandStatus.Confirmed,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.receiving_batch_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.expirationDateText,
                    onValueChange = onExpirationDateChanged,
                    enabled =
                        !state.isIntentFrozen && state.command != ReceivingCommandStatus.Confirmed,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.receiving_expiry_label)) },
                    supportingText = { Text(stringResource(R.string.receiving_expiry_support)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.quantityText,
                    onValueChange = onQuantityChanged,
                    enabled =
                        !state.isIntentFrozen && state.command != ReceivingCommandStatus.Confirmed,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.receiving_quantity_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.unit,
                    onValueChange = onUnitChanged,
                    enabled =
                        !state.isIntentFrozen && state.command != ReceivingCommandStatus.Confirmed,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.receiving_unit_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.temperatureReadingText,
                    onValueChange = onTemperatureReadingChanged,
                    enabled =
                        !state.isIntentFrozen && state.command != ReceivingCommandStatus.Confirmed,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.receiving_temperature_label)) },
                    supportingText = {
                        Text(stringResource(R.string.receiving_temperature_support))
                    },
                    singleLine = true
                )
                if (onChooseTemperatureEvidence != null) {
                    OutlinedButton(
                        onClick = onChooseTemperatureEvidence,
                        enabled = !state.isIntentFrozen && state.canReceive &&
                            state.canUploadTemperatureEvidence &&
                            state.selectedWarehouseId != null &&
                            state.temperatureEvidenceStatus !in setOf(
                                ReceivingEvidenceStatus.Uploading,
                                ReceivingEvidenceStatus.Checking
                            ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (state.temperatureEvidenceObjectId == null) {
                                    R.string.receiving_temperature_evidence_add
                                } else {
                                    R.string.receiving_temperature_evidence_replace
                                }
                            )
                        )
                    }
                    Text(
                        stringResource(state.temperatureEvidenceStatus.stringResource()),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.temperatureEvidenceStatus in setOf(
                                ReceivingEvidenceStatus.Rejected,
                                ReceivingEvidenceStatus.Unavailable,
                                ReceivingEvidenceStatus.UnknownOutcome
                            )
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (state.temperatureEvidenceStatus ==
                        ReceivingEvidenceStatus.AwaitingAvailability &&
                        onRefreshTemperatureEvidence != null
                    ) {
                        TextButton(onClick = onRefreshTemperatureEvidence) {
                            Text(stringResource(R.string.receiving_temperature_evidence_check))
                        }
                    }
                }
                state.validationError?.let { error ->
                    Text(
                        stringResource(error.stringResource()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.rejectionCode != null) {
                    Text(
                        stringResource(R.string.receiving_rejected),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            CommandStatus(state)
            state.confirmedLot?.let { lot -> ConfirmedLotCard(lot) }

            when (state.command) {
                ReceivingCommandStatus.UnknownOutcome -> Button(
                    onClick = onRetryUnknownOutcome,
                    enabled =
                        state.canReceive && state.metadata == ReceivingMetadataStatus.Available,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.receiving_retry_same))
                }

                ReceivingCommandStatus.Confirmed -> if (state.intentCleanupPending) {
                    OutlinedButton(
                        onClick = onRetryIntentCleanup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.receiving_retry_storage_cleanup))
                    }
                } else {
                    Button(onClick = onStartAnotherReceipt, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.receiving_start_another))
                    }
                }

                ReceivingCommandStatus.Rejected -> if (state.intentCleanupPending) {
                    OutlinedButton(
                        onClick = onRetryIntentCleanup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.receiving_retry_storage_cleanup))
                    }
                } else {
                    Button(
                        onClick = onSubmit,
                        enabled = canSubmit(state),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.receiving_submit))
                    }
                }

                ReceivingCommandStatus.Editing -> Button(
                    onClick = onSubmit,
                    enabled = canSubmit(state),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.receiving_submit))
                }

                ReceivingCommandStatus.PersistingIntent,
                ReceivingCommandStatus.Pending -> Unit
            }
        }
    }
}

@Composable
private fun StorageNotice(status: ReceivingMetadataStatus) {
    when (status) {
        ReceivingMetadataStatus.Loading,
        ReceivingMetadataStatus.Saving -> Text(stringResource(R.string.receiving_storage_saving))

        ReceivingMetadataStatus.Available -> Text(stringResource(R.string.receiving_storage_ready))

        ReceivingMetadataStatus.Unavailable -> Text(
            stringResource(R.string.receiving_storage_unavailable),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun PermissionNotice(state: ReceivingUiState) {
    when {
        !state.canReceive -> Text(
            stringResource(R.string.receiving_permission_required),
            color = MaterialTheme.colorScheme.error
        )

        !state.canLookUpWarehouses -> Text(
            stringResource(R.string.receiving_lookup_permission_required),
            color = MaterialTheme.colorScheme.error
        )
    }
    state.notice?.let { notice ->
        val id = when (notice) {
            ReceivingSubmitNotice.NetworkUnavailable -> R.string.receiving_network_unknown

            ReceivingSubmitNotice.ServiceUnavailable -> R.string.receiving_service_unknown

            ReceivingSubmitNotice.PermissionDenied -> R.string.receiving_permission_required

            ReceivingSubmitNotice.ContextInvalidated -> R.string.receiving_context_changed

            ReceivingSubmitNotice.SessionInvalidated -> R.string.receiving_session_changed

            ReceivingSubmitNotice.IntentMetadataUnavailable ->
                R.string.receiving_storage_unavailable
        }
        Text(stringResource(id), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LookupNotice(
    status: ReceivingLookupStatus,
    zones: Boolean,
    onReloadWarehouses: () -> Unit
) {
    when (status) {
        ReceivingLookupStatus.Loading -> Text(
            stringResource(
                if (zones) {
                    R.string.receiving_zones_loading
                } else {
                    R.string.receiving_warehouses_loading
                }
            )
        )

        ReceivingLookupStatus.NetworkUnavailable -> Text(
            stringResource(R.string.receiving_lookup_network)
        )

        ReceivingLookupStatus.ServiceUnavailable -> Text(
            stringResource(R.string.receiving_lookup_service)
        )

        ReceivingLookupStatus.PermissionDenied -> Text(
            stringResource(R.string.receiving_lookup_permission_required)
        )

        ReceivingLookupStatus.ContextInvalidated -> Text(
            stringResource(R.string.receiving_context_changed)
        )

        ReceivingLookupStatus.SessionInvalidated -> Text(
            stringResource(R.string.receiving_session_changed)
        )

        ReceivingLookupStatus.Empty -> Text(
            stringResource(
                if (zones) R.string.receiving_zones_empty else R.string.receiving_warehouses_empty
            )
        )

        ReceivingLookupStatus.NotRequested,
        ReceivingLookupStatus.Ready -> Unit
    }
    if (status in setOf(
            ReceivingLookupStatus.NetworkUnavailable,
            ReceivingLookupStatus.ServiceUnavailable
        )
    ) {
        TextButton(onClick = onReloadWarehouses) {
            Text(
                stringResource(
                    if (zones) {
                        R.string.receiving_reload_zones
                    } else {
                        R.string.receiving_reload_warehouses
                    }
                )
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                content()
            }
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (selected) {
            Text(stringResource(R.string.receiving_selected), maxLines = 1)
        }
    }
}

@Composable
private fun CommandStatus(state: ReceivingUiState) {
    val (textId, emphasis) = when (state.command) {
        ReceivingCommandStatus.Editing -> R.string.receiving_status_draft to false
        ReceivingCommandStatus.PersistingIntent -> R.string.receiving_status_preparing to false
        ReceivingCommandStatus.Pending -> R.string.receiving_status_pending to false
        ReceivingCommandStatus.UnknownOutcome -> R.string.receiving_status_unknown to true
        ReceivingCommandStatus.Confirmed -> R.string.receiving_status_confirmed to false
        ReceivingCommandStatus.Rejected -> R.string.receiving_status_rejected to true
    }
    Text(
        stringResource(textId),
        color = if (emphasis) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        fontWeight = FontWeight.Medium
    )
    if (state.command == ReceivingCommandStatus.Pending ||
        state.command == ReceivingCommandStatus.UnknownOutcome
    ) {
        Text(
            stringResource(R.string.receiving_status_not_stock),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ConfirmedLotCard(lot: ReceivedLotFacts) {
    SectionCard(title = stringResource(R.string.receiving_confirmed_lot_title)) {
        Fact(stringResource(R.string.receiving_lot_id), lot.id)
        Fact(stringResource(R.string.receiving_batch_label), lot.batchNumber)
        Fact(stringResource(R.string.receiving_expiry_label), lot.expirationDate.toString())
        Fact(
            stringResource(R.string.receiving_quantity_label),
            "${lot.onHand.toPlainString()} ${lot.unit}"
        )
        Fact(
            stringResource(R.string.receiving_available_label),
            "${lot.available.toPlainString()} ${lot.unit}"
        )
        Fact(stringResource(R.string.receiving_status_label), lot.status)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun canSubmit(state: ReceivingUiState): Boolean =
    state.canReceive && state.metadata == ReceivingMetadataStatus.Available &&
        (
            state.temperatureEvidenceObjectId == null ||
                state.temperatureEvidenceStatus == ReceivingEvidenceStatus.Available
            ) &&
        state.command !in setOf(
            ReceivingCommandStatus.PersistingIntent,
            ReceivingCommandStatus.Pending,
            ReceivingCommandStatus.UnknownOutcome
        ) && !state.intentCleanupPending

private fun ReceivingValidationError.stringResource(): Int = when (this) {
    ReceivingValidationError.ProductRequired -> R.string.receiving_error_product

    ReceivingValidationError.ProductMustBeReconfirmed -> R.string.receiving_product_reconfirm

    ReceivingValidationError.WarehouseRequired -> R.string.receiving_error_warehouse

    ReceivingValidationError.ZoneRequired -> R.string.receiving_error_zone

    ReceivingValidationError.BatchRequired -> R.string.receiving_error_batch

    ReceivingValidationError.ExpiryRequired -> R.string.receiving_error_expiry

    ReceivingValidationError.ExpiryMalformed -> R.string.receiving_error_expiry_format

    ReceivingValidationError.QuantityRequired -> R.string.receiving_error_quantity

    ReceivingValidationError.QuantityInvalid -> R.string.receiving_error_quantity_format

    ReceivingValidationError.QuantityMustBePositive -> R.string.receiving_error_quantity_positive

    ReceivingValidationError.TemperatureInvalid -> R.string.receiving_error_temperature

    ReceivingValidationError.TemperatureEvidenceNotAvailable ->
        R.string.receiving_error_temperature_evidence_unavailable

    ReceivingValidationError.UnitRequired -> R.string.receiving_error_unit

    ReceivingValidationError.MetadataUnavailable -> R.string.receiving_storage_unavailable
}

private fun ReceivingEvidenceStatus.stringResource(): Int = when (this) {
    ReceivingEvidenceStatus.None -> R.string.receiving_temperature_evidence_none
    ReceivingEvidenceStatus.Uploading -> R.string.receiving_temperature_evidence_uploading
    ReceivingEvidenceStatus.Checking -> R.string.receiving_temperature_evidence_checking
    ReceivingEvidenceStatus.AwaitingAvailability -> R.string.receiving_temperature_evidence_pending
    ReceivingEvidenceStatus.Available -> R.string.receiving_temperature_evidence_available
    ReceivingEvidenceStatus.UnknownOutcome -> R.string.receiving_temperature_evidence_unknown
    ReceivingEvidenceStatus.Rejected -> R.string.receiving_temperature_evidence_rejected
    ReceivingEvidenceStatus.Unavailable -> R.string.receiving_temperature_evidence_unavailable
}
