package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    onRetryIntentCleanup: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text("Recepción de traslado", style = MaterialTheme.typography.headlineSmall)
        Text("La solicitud y la salida de origen no confirman que el stock llegó al destino.")
        Button(onClick = onReloadWarehouses, enabled = state.canLookUp && !state.isFrozen) {
            Text("Actualizar almacenes autorizados")
        }
        state.warehouses.forEach { warehouse ->
            TextButton(onClick = { onSelectDestinationWarehouse(warehouse.id) },
                enabled = state.canLookUp && !state.isFrozen) { Text("Destino: ${warehouse.name}") }
        }
        if (state.transferLookup == TransferLookupStatus.Empty) Text("Sin traslados para este destino.")
        state.transfers.forEach { transfer ->
            TextButton(onClick = { onSelectTransfer(transfer.id) }, enabled = !state.isFrozen) {
                Text("${transfer.id} · ${transfer.status} · ${transfer.transferredQuantityText} ${transfer.unit}")
            }
        }
        if (state.hasMoreTransfers) Button(onClick = onLoadMoreTransfers, enabled = !state.isFrozen) { Text("Ver más") }
        state.selectedTransfer?.let { transfer ->
            Text("Traslado ${transfer.id}; versión ${transfer.version}")
            Text("Origen: ${transfer.sourceWarehouseId} / ${transfer.sourceZoneId}")
            Text("Destino: ${transfer.destinationWarehouseId} / ${transfer.destinationZoneId}")
            Text("Lote de origen: ${transfer.sourceLotId}; lote físico: ${transfer.batchNumber ?: "no informado"}")
            Text("Vencimiento: ${transfer.expirationDate ?: "no informado"}")
            Text("Solicitado: ${transfer.requestedQuantityText} ${transfer.unit}; despachado: ${transfer.transferredQuantityText} ${transfer.unit}")
            transfer.dispatchedAt?.let { Text("Salida registrada: $it") }
            Text("Solo confirma si recibiste ese lote y toda la cantidad despachada. Una diferencia requiere registro y resolución separados.")
            Button(onClick = onReceiveExpectedQuantity,
                enabled = state.canReceive && !state.isFrozen &&
                    state.metadata == TransferMetadataStatus.Available && transfer.canReceiveExpectedQuantity) {
                Text("Confirmar recepción completa del lote y cantidad indicados")
            }
        }
        Text(when (state.command) {
            StockTransferReceiptCommandStatus.Editing -> "Sin recepción pendiente."
            StockTransferReceiptCommandStatus.PersistingIntent -> "Guardando intención protegida antes de enviar."
            StockTransferReceiptCommandStatus.Pending -> "Esperando confirmación del servidor."
            StockTransferReceiptCommandStatus.UnknownOutcome -> "Resultado incierto. No confirmes otra recepción; recupera el mismo intento."
            StockTransferReceiptCommandStatus.Confirmed -> "Recepción confirmada por el servidor."
            StockTransferReceiptCommandStatus.PreconditionFailed -> "El traslado cambió. Actualiza y decide nuevamente."
            StockTransferReceiptCommandStatus.Conflict -> "Conflicto: revisa el estado vigente antes de una nueva decisión."
            StockTransferReceiptCommandStatus.PermissionDenied -> "No tienes autorización vigente para recibir este traslado."
            StockTransferReceiptCommandStatus.ContextInvalidated, StockTransferReceiptCommandStatus.SessionInvalidated -> "Confirma nuevamente sesión y contexto."
            StockTransferReceiptCommandStatus.Rejected -> "Recepción rechazada por el servidor."
        })
        state.confirmed?.let { transfer ->
            Text("Resultado: ${transfer.status}; recibido: ${transfer.receivedAt ?: "no informado"}; lote destino: ${transfer.destinationLotId ?: "no informado"}; versión ${transfer.version}")
        }
        if (state.command == StockTransferReceiptCommandStatus.UnknownOutcome) {
            Button(onClick = onRetryUnknownOutcome, enabled = state.canReceive) { Text("Recuperar mismo intento") }
        }
        if (state.intentCleanupPending) Button(onClick = onRetryIntentCleanup) { Text("Completar conservación local del resultado") }
        if (state.metadata == TransferMetadataStatus.Unavailable) Text("Almacenamiento protegido no disponible. No se enviará una nueva recepción.")
        state.rejectionCode?.let { Text("Motivo del servidor: $it") }
        state.notice?.let { Text("Información pendiente: $it") }
    }
}
