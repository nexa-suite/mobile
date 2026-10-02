package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptCommandStatus as ReceiptCommandStatus

/** Destination facts remain distinct from requested and dispatched quantities. */
@Composable
fun StockTransferReceiptScreen(
    state: StockTransferReceiptUiState,
    onBack: () -> Unit,
    onReloadWarehouses: () -> Unit,
    onSelectDestinationWarehouse: (String) -> Unit,
    onLoadMoreTransfers: () -> Unit,
    onSelectTransfer: (String) -> Unit,
    onReceiveExpectedQuantity: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onRetryIntentCleanup: () -> Unit,
    onObserveArrival: (String, String?, String, String) -> Unit,
    onRetryObservation: () -> Unit,
    onCleanupObservation: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text("Recepción de traslado", style = MaterialTheme.typography.headlineSmall)
        Text("La solicitud y la salida de origen no confirman que el stock llegó al destino.")
        Button(onClick = onReloadWarehouses, enabled = state.canLookUp && !state.isFrozen) {
            Text("Actualizar almacenes autorizados")
        }
        state.warehouses.forEach { warehouse ->
            TextButton(
                onClick = { onSelectDestinationWarehouse(warehouse.id) },
                enabled = state.canLookUp && !state.isFrozen
            ) { Text("Destino: ${warehouse.name}") }
        }
        if (state.transferLookup ==
            TransferLookupStatus.Empty
        ) {
            Text("Sin traslados para este destino.")
        }
        state.transfers.forEach { transfer ->
            TextButton(onClick = { onSelectTransfer(transfer.id) }, enabled = !state.isFrozen) {
                Text(
                    "${transfer.id} · ${transfer.status} · ${transfer.transferredQuantityText} ${transfer.unit}"
                )
            }
        }
        if (state.hasMoreTransfers) {
            Button(
                onClick = onLoadMoreTransfers,
                enabled = !state.isFrozen
            ) {
                Text("Ver más")
            }
        }
        state.selectedTransfer?.let { transfer ->
            Text("Traslado ${transfer.id}; versión ${transfer.version}")
            Text("Origen: ${transfer.sourceWarehouseId} / ${transfer.sourceZoneId}")
            Text("Destino: ${transfer.destinationWarehouseId} / ${transfer.destinationZoneId}")
            Text(
                "Lote de origen: ${transfer.sourceLotId}; lote físico: ${transfer.batchNumber ?: "no informado"}"
            )
            Text("Vencimiento: ${transfer.expirationDate ?: "no informado"}")
            Text(
                "Solicitado: ${transfer.requestedQuantityText} ${transfer.unit}; despachado: ${transfer.transferredQuantityText} ${transfer.unit}"
            )
            transfer.dispatchedAt?.let { Text("Salida registrada: $it") }
            var observedBatch by remember(state.authorityEpoch, transfer.id) {
                mutableStateOf(transfer.batchNumber.orEmpty())
            }
            var observedExpiry by remember(state.authorityEpoch, transfer.id) {
                mutableStateOf(transfer.expirationDate.orEmpty())
            }
            var observedQuantity by remember(state.authorityEpoch, transfer.id) {
                mutableStateOf(transfer.transferredQuantityText)
            }
            Text("Registrar diferencia física de llegada; no recibe ni modifica stock.")
            OutlinedTextField(observedBatch, {
                observedBatch = it
            }, label = { Text("Lote físico observado") }, enabled = !state.isFrozen)
            OutlinedTextField(observedExpiry, {
                observedExpiry = it
            }, label = {
                Text("Vencimiento observado AAAA-MM-DD (opcional)")
            }, enabled = !state.isFrozen)
            OutlinedTextField(observedQuantity, {
                observedQuantity = it
            }, label = { Text("Cantidad observada (${transfer.unit})") }, enabled = !state.isFrozen)
            Button(
                onClick = {
                    onObserveArrival(
                        observedBatch,
                        observedExpiry.takeIf {
                            it.isNotBlank()
                        },
                        observedQuantity,
                        transfer.unit
                    )
                },
                enabled =
                    state.canReceive && !state.isFrozen && transfer.canReceiveExpectedQuantity &&
                        state.observationMetadata == TransferMetadataStatus.Available
            ) {
                Text("Registrar hechos observados de llegada")
            }
            Text(
                "Solo confirma si recibiste ese lote y toda la cantidad despachada. Una diferencia requiere registro y resolución separados."
            )
            Button(
                onClick = onReceiveExpectedQuantity,
                enabled = state.canReceive && !state.isFrozen &&
                    state.metadata == TransferMetadataStatus.Available &&
                    transfer.canReceiveExpectedQuantity
            ) {
                Text("Confirmar recepción completa del lote y cantidad indicados")
            }
        }
        Text(
            when (state.command) {
                ReceiptCommandStatus.Editing -> "Sin recepción pendiente."

                ReceiptCommandStatus.PersistingIntent ->
                    "Guardando intención protegida antes de enviar."

                ReceiptCommandStatus.Pending -> "Esperando confirmación del servidor."

                ReceiptCommandStatus.UnknownOutcome ->
                    "Resultado incierto. No confirmes otra recepción; recupera el mismo intento."

                ReceiptCommandStatus.Confirmed -> "Recepción confirmada por el servidor."

                ReceiptCommandStatus.PreconditionFailed ->
                    "El traslado cambió. Actualiza y decide nuevamente."

                ReceiptCommandStatus.Conflict ->
                    "Conflicto: revisa el estado vigente antes de una nueva decisión."

                ReceiptCommandStatus.PermissionDenied ->
                    "No tienes autorización vigente para recibir este traslado."

                ReceiptCommandStatus.ContextInvalidated,
                ReceiptCommandStatus.SessionInvalidated ->
                    "Confirma nuevamente sesión y contexto."

                ReceiptCommandStatus.Rejected -> "Recepción rechazada por el servidor."
            }
        )
        if (state.observationCommand != StockTransferReceiptObservationCommandStatus.Editing) {
            Text("Registro de observación: ${state.observationCommand}")
        }
        state.recordedObservation?.let { observation ->
            Text(
                "Observación ${observation.observationId}: ${observation.observedBatchNumber}, ${observation.observedQuantityText} ${observation.observedUnit}; vencimiento ${observation.observedExpirationDate ?: "no informado"}."
            )
            Text("Servidor: ${observation.recordedAt}; persona: ${observation.actorMembershipId}.")
            if (observation.hasDifference) {
                Text(
                    "Diferencia registrada. La recepción requiere resolución autorizada; este registro no mueve stock."
                )
            }
        }
        if (state.observationCommand ==
            StockTransferReceiptObservationCommandStatus.UnknownOutcome
        ) {
            Text(
                "Resultado de observación incierto. Recupera el mismo intento sin cambiar hechos ni clave."
            )
            Button(onClick = onRetryObservation, enabled = state.canReceive) {
                Text("Recuperar observación pendiente")
            }
        }
        if (state.observationCleanupPending) {
            Button(onClick = onCleanupObservation) {
                Text("Conservar resultado de observación")
            }
        }
        state.observationNotice?.let { Text("Observación: $it") }
        if (state.observationMetadata ==
            TransferMetadataStatus.Unavailable
        ) {
            Text("Registro de diferencias bloqueado: almacenamiento protegido no disponible.")
        }
        state.confirmed?.let { transfer ->
            Text(
                "Resultado: ${transfer.status}; recibido: ${transfer.receivedAt ?: "no informado"}; lote destino: ${transfer.destinationLotId ?: "no informado"}; versión ${transfer.version}"
            )
        }
        if (state.command == ReceiptCommandStatus.UnknownOutcome) {
            Button(onClick = onRetryUnknownOutcome, enabled = state.canReceive) {
                Text("Recuperar mismo intento")
            }
        }
        if (state.intentCleanupPending) {
            Button(onClick = onRetryIntentCleanup) {
                Text("Completar conservación local del resultado")
            }
        }
        if (state.metadata ==
            TransferMetadataStatus.Unavailable
        ) {
            Text("Almacenamiento protegido no disponible. No se enviará una nueva recepción.")
        }
        state.rejectionCode?.let { Text("Motivo del servidor: $it") }
        state.notice?.let { Text("Información pendiente: $it") }
    }
}
