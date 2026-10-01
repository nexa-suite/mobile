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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LotSubstitutionScreen(
    state: LotSubstitutionUiState, onBack: () -> Unit, onLoadAlternatives: () -> Unit,
    onSelectAlternative: (String) -> Unit, onReasonChanged: (String) -> Unit,
    onRequest: () -> Unit, onRecoverSameIntent: () -> Unit, onRefreshCurrentAllocation: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text("Proponer sustitución de lote", style = MaterialTheme.typography.headlineSmall)
        Text("FEFO sigue siendo la regla. Una propuesta conserva la asignación original; no confirma picking ni sustituye stock automáticamente.")
        state.work?.let { work ->
            Text("Trabajo ${work.fulfillmentId}; asignación ${work.allocationId}; versión ${work.allocationVersion}.")
            Text("Línea ${work.allocationLineId}; SKU ${work.skuId}; lote esperado ${work.expectedLotId}.")
            Text("Cantidad preparada: ${work.preparedQuantityText} ${work.unit}.")
        }
        Text("Estado: ${state.status}")
        Button(onClick = onLoadAlternatives, enabled = state.status.toString() == "Editing") {
            Text(if (state.hasMoreAlternatives) "Consultar más alternativas autorizadas" else "Consultar alternativas autorizadas")
        }
        state.alternatives.forEach { lot ->
            TextButton(onClick = { onSelectAlternative(lot.id) }, enabled = state.status.toString() == "Editing") {
                Text("Lote ${lot.id}; almacén ${lot.warehouseId}; zona ${lot.zoneId}; ${lot.availableText} ${lot.unit}; ${lot.status}; versión ${lot.version}${if (lot.id == state.selectedAlternativeId) " · seleccionado" else ""}")
            }
        }
        OutlinedTextField(state.reasonText, onReasonChanged,
            label = { Text("Motivo por el que el lote esperado no puede atender el trabajo") },
            enabled = state.status.toString() == "Editing")
        Button(onClick = onRequest, enabled = state.canRequest) { Text("Enviar propuesta para decisión autorizada") }
        state.request?.let { request ->
            Text("Solicitud ${request.id}: ${request.status}.")
            Text("Esperado ${request.expectedLotId}; alternativa ${request.alternativeLotId}; asignación actual ${request.currentAllocationVersion}.")
            Text("La solicitud no equivale a aprobación. Consulta el trabajo vigente antes de continuar.")
        }
        if (state.status.toString() == "UnknownOutcome") {
            Text("Resultado incierto. Conserva propuesta, cuerpo, clave y versión originales.")
            Button(onClick = onRecoverSameIntent) { Text("Recuperar la misma propuesta") }
        }
        if (state.status.toString() in setOf("Stale", "Rejected", "Conflict")) {
            Text("La asignación original permanece. Lee hechos actuales y revisa una nueva decisión; no se aplica una alternativa obsoleta.")
            Button(onClick = onRefreshCurrentAllocation) { Text("Consultar asignación actual") }
        }
        state.notice?.let { Text(it) }
    }
}
