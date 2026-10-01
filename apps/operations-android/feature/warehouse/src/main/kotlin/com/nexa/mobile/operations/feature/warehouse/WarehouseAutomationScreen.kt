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

/** Automation remains unavailable until its Product outcome and observation trust policy are defined. */
@Composable
fun WarehouseAutomationScreen(
    state: StockConditionUiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onSelectLot: (String) -> Unit, onManualTemperature: () -> Unit, canRecordTemperature: Boolean
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text("Observaciones de almacén", style = MaterialTheme.typography.headlineSmall)
        Text("Automatización no habilitada: faltan resultado de negocio y política de confianza para fuentes automatizadas. No hay proveedor RFID, sensor, robot ni telemetría seleccionados.")
        Text("Una lectura de dispositivo no autoriza recibir, corregir, liberar ni mover stock. Las acciones manuales requieren hechos y permisos vigentes.")
        Button(onClick = onRefresh, enabled = state.status != StockConditionStatus.Loading) { Text("Consultar stock autorizado") }
        Text("Consulta: ${state.status}; recibida en dispositivo: ${state.listObservedAt ?: "no disponible"}")
        if (state.status == StockConditionStatus.Current) state.lots.forEach { lot ->
            TextButton(onClick = { onSelectLot(lot.id) }) { Text("Revisar ${lot.batchNumber} · almacén ${lot.warehouseId}") }
        }
        if (state.detailStatus == StockConditionDetailStatus.Current) state.selectedLot?.let { lot ->
            Text("Hecho del servidor: lote ${lot.id}; producto ${lot.catalogItemId ?: "no informado"}; SKU ${lot.skuId ?: "no informado"}.")
            Text("Almacén ${lot.warehouseId}; zona ${lot.zoneId}; estado ${lot.status}; versión ${lot.version}.")
            Text("Físico ${lot.onHand}; reservado ${lot.reserved}; unidad ${lot.unit}. No se interpreta como aprobación de venta o automatización.")
        }
        Button(onClick = onManualTemperature, enabled = canRecordTemperature) { Text("Registrar observación manual de temperatura") }
        Text("El registro manual conserva persona, tiempo y decisión del servidor. No sustituye una integración industrial pendiente de definición.")
    }
}
