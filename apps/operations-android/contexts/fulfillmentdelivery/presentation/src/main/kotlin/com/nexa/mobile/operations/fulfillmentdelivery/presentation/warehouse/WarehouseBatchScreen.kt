package com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaWorkSection
import com.nexa.mobile.operations.core.designsystem.NexaWorkSummary
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkItem
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R

@Composable
fun WarehouseBatchScreen(
    state: WarehouseBatchUiState,
    work: PickingWorkListUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onAdd: (PickingWorkItem) -> Unit,
    onMove: (String, Int) -> Unit,
    onOpen: (String) -> Unit,
    onReview: (String, String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Volver") }
        NexaWorkSummary(
            title = stringResource(R.string.batch_work_title),
            description = stringResource(R.string.batch_work_support)
        )
        Text(
            "Trabajo disponible: ${work.status}; hechos del servidor: ${work.asOf ?: "no disponibles"}"
        )
        Button(onClick = onRefresh, enabled = work.status != PickingWorkListStatus.Loading) {
            Text("Actualizar trabajo autorizado")
        }
        if (work.status == PickingWorkListStatus.Ready) {
            Text(
                stringResource(R.string.batch_work_available),
                style = MaterialTheme.typography.titleMedium
            )
            work.items.forEach { item ->
                TextButton(
                    onClick = { onAdd(item) },
                    enabled = state.items.none {
                        it.prepared.fulfillmentId ==
                            item.fulfillmentId
                    }
                ) {
                    Text(
                        "Agregar ${item.fulfillmentId} · ${item.status} · ${item.lineCount} líneas"
                    )
                }
            }
            TextButton(onClick = onPreviousPage, enabled = work.page > 0) {
                Text("Página anterior")
            }
            TextButton(
                onClick = onNextPage,
                enabled =
                    (work.page.toLong() + 1) * work.size < work.totalItems
            ) { Text("Página siguiente") }
        } else {
            Text(
                "Sin hechos actuales; no se agregan trabajos de otra sesión ni se supone disponibilidad."
            )
        }
        if (state.items.isEmpty()) {
            Text(
                "Grupo vacío. Selecciona trabajo confirmado por el servidor."
            )
        }
        if (state.items.isNotEmpty()) {
            Text(
                stringResource(R.string.batch_work_selected),
                style = MaterialTheme.typography.titleMedium
            )
        }
        state.items.forEachIndexed { index, item ->
            val id = item.prepared.fulfillmentId
            NexaWorkSection(stringResource(R.string.batch_work_position, index + 1)) {
                Text(
                    "$id · preparación v${item.prepared.version}, asignación física v${item.prepared.allocationVersion}"
                )
                Text(
                    "Revisar estado vigente, asignación, lote físico, FEFO, unidad y cantidad antes de cada acción."
                )
                item.confirmed?.let { result ->
                    Text(
                        "Último resultado confirmado: ${result.status} v${result.version}."
                    )
                    result.lines.forEach { line ->
                        Text(
                            "${line.id}: recogido ${line.pickedQuantity}, pendiente ${line.remainingQuantity} ${line.unit}"
                        )
                    }
                }
                item.reviewNote?.let { Text("Requiere revisión (nota local): $it") }
                TextButton(onClick = {
                    onMove(id, -1)
                }, enabled = index > 0) { Text("Mover antes") }
                TextButton(onClick = {
                    onMove(id, 1)
                }, enabled = index < state.items.lastIndex) { Text("Mover después") }
                Button(onClick = { onOpen(id) }) { Text("Revisar y trabajar este elemento") }
                var note by remember(state.authorityEpoch, id) { mutableStateOf("") }
                OutlinedTextField(note, {
                    note = it
                }, label = { Text("Diferencia que requiere revisión") })
                TextButton(onClick = {
                    onReview(id, note)
                    note = ""
                }, enabled = note.isNotBlank()) { Text("Separar nota local de revisión") }
            }
        }
    }
}
