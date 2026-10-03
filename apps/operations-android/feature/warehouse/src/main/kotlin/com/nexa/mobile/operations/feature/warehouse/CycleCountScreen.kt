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

@Composable
fun CycleCountScreen(
    state: CycleCountUiState,
    canCorrect: Boolean,
    onBack: () -> Unit,
    onReloadLots: () -> Unit,
    onSelectLot: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onRecord: () -> Unit,
    onRetryCount: () -> Unit,
    onApplyCorrection: () -> Unit,
    onRetryCorrection: () -> Unit,
    onLoadMoreLots: () -> Unit = {},
    hasMoreLots: Boolean = false,
    onRefreshStaleCount: () -> Unit = {}
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text("Conteo físico y corrección", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Contar no modifica stock. Una diferencia queda como solicitud; aplicar requiere autorización vigente y una decisión explícita."
        )
        Text("Consulta de lotes: ${state.lotLookup}")
        Button(
            onClick = onReloadLots,
            enabled =
                !state.isFrozen && state.lotLookup != CycleCountLookupStatus.Loading
        ) {
            Text("Actualizar lotes autorizados")
        }
        state.lots.forEach { lot ->
            TextButton(onClick = { onSelectLot(lot.id) }, enabled = !state.isFrozen) {
                Text("${lot.batchNumber} · ${lot.onHandText} ${lot.unit} · ${lot.status}")
            }
        }
        if (hasMoreLots) {
            Button(onClick = onLoadMoreLots, enabled = !state.isFrozen) {
                Text("Ver más lotes")
            }
        }
        state.selectedLot?.let { lot ->
            Text(
                "Lote ${lot.id}; producto ${lot.catalogItemId ?: "no informado"}; versión ${lot.version}."
            )
            Text(
                "Almacén ${lot.warehouseId}; zona ${lot.zoneId}; vencimiento ${lot.expirationDate}."
            )
            Text(
                "Registro físico: ${lot.onHandText}; reservado: ${lot.reservedText}; unidad ${lot.unit}. La cantidad física no equivale a stock vendible."
            )
            OutlinedTextField(
                state.observedQuantityText,
                onQuantityChanged,
                label = {
                    Text("Cantidad física observada (${lot.unit})")
                },
                enabled = !state.isFrozen
            )
            Button(onClick = onRecord, enabled = state.canRecord) {
                Text("Registrar conteo con el servidor")
            }
        }
        Text("Conteo: ${state.countCommand}; corrección: ${state.correctionCommand}")
        if (!state.metadataAvailable) {
            Text(
                "Almacenamiento protegido no disponible. No se enviarán nuevas decisiones."
            )
        }
        state.recordedCount?.let { count ->
            Text(
                "Conteo ${count.id}: ${count.status}; lote ${count.lotId}, versión observada ${count.lotVersion}."
            )
            Text(
                "Esperado ${count.expectedQuantityText}; observado ${count.observedQuantityText} ${count.unit}."
            )
            Text(
                "Almacén ${count.warehouseId}; zona ${count.zoneId}; persona ${count.actorMembershipId}; servidor ${count.recordedAt}."
            )
            if (count.status == "REQUESTED") {
                var confirmed by remember(state.authorityEpoch, count.id) { mutableStateOf(false) }
                Text(
                    "Aplicar cambia la cantidad física al conteo confirmado. El servidor calcula el movimiento y conserva el historial; un cambio concurrente impide aplicar este conteo."
                )
                if (canCorrect && state.canApplyCorrection) {
                    TextButton(onClick = {
                        confirmed = !confirmed
                    }) {
                        Text(
                            if (confirmed) {
                                "Quitar confirmación de esta corrección"
                            } else {
                                "He revisado la diferencia y autorizo esta corrección"
                            }
                        )
                    }
                    Button(onClick = {
                        confirmed = false
                        onApplyCorrection()
                    }, enabled = confirmed) { Text("Aplicar corrección autorizada") }
                } else if (!canCorrect) {
                    Text(
                        "Tu permiso actual permite registrar el conteo; la corrección requiere autoridad de ajuste."
                    )
                }
            }
        }
        state.appliedCorrection?.let { correction ->
            Text(
                "Corrección confirmada ${correction.id}: ${correction.quantityBeforeText} a ${correction.quantityAfterText} ${correction.unit}; movimiento ${correction.quantityDeltaText}."
            )
            Text(
                "Versiones ${correction.lotVersionBefore} a ${correction.lotVersionAfter}; persona ${correction.actorMembershipId}; servidor ${correction.recordedAt}. Los movimientos anteriores permanecen conservados."
            )
        }
        if (state.countCommand == CycleCountCommandStatus.UnknownOutcome) {
            Text("Resultado de conteo incierto; no generes otro intento.")
            Button(onClick = onRetryCount) { Text("Recuperar mismo conteo") }
        }
        if (state.correctionCommand == CycleCountCommandStatus.UnknownOutcome) {
            Text(
                "Resultado de corrección incierto; no vuelvas a decidir ni cambies clave o versión."
            )
            Button(onClick = onRetryCorrection, enabled = canCorrect) {
                Text("Recuperar misma corrección")
            }
        }
        if (state.countCommand == CycleCountCommandStatus.PreconditionFailed ||
            state.correctionCommand == CycleCountCommandStatus.PreconditionFailed
        ) {
            Text(
                "El stock cambió desde el conteo. Actualiza los hechos y realiza un nuevo conteo; no se aplica la cantidad anterior automáticamente."
            )
            if (state.correctionCommand == CycleCountCommandStatus.PreconditionFailed) {
                Button(onClick = onRefreshStaleCount) {
                    Text("Descartar decisión obsoleta y consultar hechos actuales")
                }
            }
        }
        state.notice?.let { Text("Información: $it") }
    }
}
