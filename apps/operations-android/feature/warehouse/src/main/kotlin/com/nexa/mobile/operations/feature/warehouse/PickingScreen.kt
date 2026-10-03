package com.nexa.mobile.operations.feature.warehouse

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PickingScreen(
    state: PickingUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onSelectOffer: (String) -> Unit,
    onLotIdentifierChanged: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onStartPicking: () -> Unit,
    onConfirmPick: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onRetryIntentCleanup: () -> Unit,
    onProposeLotSubstitution: ((String) -> Unit)? = null
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
            TextButton(onClick = onBack) { Text("Volver") }
            Text(
                text = stringResource(R.string.picking_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.picking_authority_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!state.canRead) {
                Notice(stringResource(R.string.picking_permission_read), isError = true)
            }
            if (!state.canPick) {
                Notice(stringResource(R.string.picking_permission_write), isError = true)
            }
            if (state.metadata == PickingMetadataStatus.Unavailable) {
                Notice(stringResource(R.string.picking_metadata_unavailable), isError = true)
            }
            state.notice?.let { notice -> Notice(notice.label(), isError = true) }

            when (state.loadStatus) {
                PickingLoadStatus.Loading -> Notice(stringResource(R.string.picking_load_loading))

                PickingLoadStatus.AllocationUnavailable ->
                    Notice(stringResource(R.string.picking_load_unavailable), isError = true)

                PickingLoadStatus.NotFound ->
                    Notice(stringResource(R.string.picking_load_not_found), isError = true)

                PickingLoadStatus.NetworkUnavailable,
                PickingLoadStatus.ServiceUnavailable ->
                    Notice(stringResource(R.string.picking_load_error), isError = true)

                PickingLoadStatus.PermissionDenied ->
                    Notice(stringResource(R.string.picking_permission_read), isError = true)

                PickingLoadStatus.ContextInvalidated ->
                    Notice(stringResource(R.string.warehouse_context_invalid_body), isError = true)

                PickingLoadStatus.SessionInvalidated ->
                    Notice(stringResource(R.string.warehouse_session_invalid_body), isError = true)

                PickingLoadStatus.NotRequested,
                PickingLoadStatus.Ready -> Unit
            }

            state.fulfillment?.let { fulfillment ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.picking_fulfillment_title),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(
                                R.string.picking_fulfillment_status,
                                fulfillment.status,
                                fulfillment.version
                            )
                        )
                        Text(
                            "Fulfillment ID: ${fulfillment.id}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        fulfillment.lines.forEach { line ->
                            Text(
                                stringResource(
                                    R.string.picking_line,
                                    line.skuId,
                                    line.allocatedQuantity.toPlainString(),
                                    line.pickedQuantity.toPlainString(),
                                    line.remainingQuantity.toPlainString(),
                                    line.unit
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            state.allocation?.let { allocation ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            stringResource(R.string.picking_allocation_title),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(
                                R.string.picking_allocation_header,
                                allocation.status,
                                allocation.version,
                                allocation.asOf.toString()
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        allocation.lines.forEach { line ->
                            val offer = state.offers.singleOrNull {
                                it.allocationLine.physicalAllocationLineId ==
                                    line.physicalAllocationLineId
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = if (state.selectedAllocationLineId ==
                                        line.physicalAllocationLineId
                                    ) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.picking_offer,
                                            line.lotId,
                                            line.warehouseId,
                                            line.zoneId ?: "—"
                                        ),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        stringResource(
                                            R.string.picking_offer_quantities,
                                            line.quantity.toPlainString(),
                                            line.releasedQuantity.toPlainString(),
                                            line.consumedQuantity.toPlainString(),
                                            line.remainingQuantity.toPlainString(),
                                            line.unit
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    line.expirationDate?.let {
                                        Text(
                                            stringResource(
                                                R.string.picking_offer_expiry,
                                                it.toString()
                                            ),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (offer?.ambiguousFulfillmentMatch == true) {
                                        Text(
                                            stringResource(R.string.picking_offer_ambiguous),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onSelectOffer(line.physicalAllocationLineId) },
                                        enabled = canEdit(state) && offer?.isPickable == true
                                    ) {
                                        Text(stringResource(R.string.picking_offer_select))
                                    }
                                    if (onProposeLotSubstitution != null &&
                                        offer?.isPickable == true
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                onProposeLotSubstitution(
                                                    line.physicalAllocationLineId
                                                )
                                            },
                                            enabled = canEdit(state)
                                        ) {
                                            Text("Proponer sustitución razonada de este lote")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } ?: if (state.loadStatus == PickingLoadStatus.AllocationUnavailable) {
                Notice(stringResource(R.string.picking_no_allocation), isError = true)
            } else {
                Unit
            }

            state.selectedOffer?.let { selected ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Línea ${selected.fulfillmentLine?.id.orEmpty()}",
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedTextField(
                            value = state.lotIdentifierText,
                            onValueChange = onLotIdentifierChanged,
                            enabled = canEdit(state),
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.picking_lot_label)) },
                            supportingText = { Text(stringResource(R.string.picking_lot_support)) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = state.quantityText,
                            onValueChange = onQuantityChanged,
                            enabled = canEdit(state),
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.picking_quantity_label)) },
                            singleLine = true
                        )
                    }
                }
            }

            state.validationError?.let { Notice(it.label(), isError = true) }
            state.rejectionCode?.let {
                Notice(stringResource(R.string.picking_rejected, it), isError = true)
            }

            when (state.command) {
                PickingCommandStatus.PersistingIntent,
                PickingCommandStatus.Pending -> Notice(stringResource(R.string.picking_pending))

                PickingCommandStatus.UnknownOutcome -> {
                    Notice(stringResource(R.string.picking_unknown_title), isError = true)
                    Notice(stringResource(R.string.picking_unknown_body), isError = true)
                    Button(
                        onClick = onRetryUnknownOutcome,
                        enabled = state.canPick &&
                            state.metadata == PickingMetadataStatus.Available,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.picking_retry_same))
                    }
                }

                PickingCommandStatus.Confirmed -> {
                    state.confirmedLotId?.let { lotId ->
                        Notice(
                            stringResource(
                                R.string.picking_confirmed,
                                lotId,
                                state.confirmedWarehouseId.orEmpty()
                            )
                        )
                    }
                    state.confirmedFulfillment?.lines?.forEach { line ->
                        Text(
                            stringResource(
                                R.string.picking_line,
                                line.skuId,
                                line.allocatedQuantity.toPlainString(),
                                line.pickedQuantity.toPlainString(),
                                line.remainingQuantity.toPlainString(),
                                line.unit
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (state.intentCleanupPending) {
                        OutlinedButton(onClick = onRetryIntentCleanup) {
                            Text("Reintentar limpieza local")
                        }
                    } else {
                        OutlinedButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.picking_reload))
                        }
                    }
                }

                PickingCommandStatus.Rejected,
                PickingCommandStatus.StaleVersion -> {
                    Notice(
                        stringResource(
                            if (state.command == PickingCommandStatus.StaleVersion) {
                                R.string.picking_stale
                            } else {
                                R.string.picking_load_error
                            }
                        ),
                        isError = true
                    )
                    if (state.intentCleanupPending) {
                        OutlinedButton(onClick = onRetryIntentCleanup) {
                            Text("Reintentar limpieza local")
                        }
                    } else {
                        OutlinedButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.picking_reload))
                        }
                    }
                }

                PickingCommandStatus.Editing -> {
                    val fulfillment = state.fulfillment
                    if (fulfillment != null &&
                        fulfillment.status.equals("PICKING", ignoreCase = true)
                    ) {
                        Button(
                            onClick = onConfirmPick,
                            enabled = canEdit(state) && state.canPick &&
                                state.loadStatus == PickingLoadStatus.Ready &&
                                state.selectedOffer?.isPickable == true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.picking_confirm))
                        }
                    } else if (fulfillment != null && state.canPick) {
                        Button(
                            onClick = onStartPicking,
                            enabled =
                                canEdit(state) && state.metadata == PickingMetadataStatus.Available,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.picking_start))
                        }
                    }
                    if (state.loadStatus != PickingLoadStatus.Loading) {
                        OutlinedButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.picking_reload))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun canEdit(state: PickingUiState): Boolean =
    !state.isIntentFrozen && state.command == PickingCommandStatus.Editing &&
        state.metadata == PickingMetadataStatus.Available

private fun PickingValidationError.label(): String = when (this) {
    PickingValidationError.LotIdentifierRequired -> "Escribe el identificador del lote asignado."

    PickingValidationError.LotIdentifierMismatch -> "El lote no coincide con la asignación actual."

    PickingValidationError.QuantityRequired -> "Escribe la cantidad que vas a recoger."

    PickingValidationError.QuantityInvalid -> "Escribe una cantidad decimal válida."

    PickingValidationError.QuantityMustBePositive -> "La cantidad debe ser mayor que cero."

    PickingValidationError.QuantityExceedsRemaining ->
        "La cantidad supera el restante actual de la asignación o de la línea."

    PickingValidationError.OfferRequired -> "Selecciona una línea con asignación física actual."

    PickingValidationError.AmbiguousFulfillmentLine ->
        "No se pudo asociar el lote a una única línea de la preparación."

    PickingValidationError.MetadataUnavailable -> stringResourceValueNotAvailable()

    PickingValidationError.RefreshRequired -> "Vuelve a consultar la preparación y asignación."

    PickingValidationError.FulfillmentUnavailable,
    PickingValidationError.AllocationUnavailable,
    PickingValidationError.AllocationNotReady ->
        "No hay una asignación física vigente para confirmar."
}

private fun PickingNotice.label(): String = when (this) {
    PickingNotice.NetworkUnavailable -> "Sin conexión; no se confirmó el resultado del comando."

    PickingNotice.ServiceUnavailable -> "El servicio no confirmó el resultado del comando."

    PickingNotice.PermissionDenied -> "El permiso actual no permite esta operación."

    PickingNotice.ContextInvalidated ->
        "El contexto cambió; vuelve a verificarlo antes de continuar."

    PickingNotice.SessionInvalidated -> "La sesión cambió; vuelve a iniciar sesión."

    PickingNotice.IntentMetadataUnavailable -> stringResourceValueNotAvailable()

    PickingNotice.RefreshRequired -> "La preparación cambió; vuelve a consultarla antes de decidir."
}

private fun stringResourceValueNotAvailable(): String =
    "No se pudo guardar la identidad del comando; no se envió una mutación."
