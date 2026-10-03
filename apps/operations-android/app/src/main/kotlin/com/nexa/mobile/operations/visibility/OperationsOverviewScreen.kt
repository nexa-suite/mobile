package com.nexa.mobile.operations.visibility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessUiState

/** Shows only the authoritative prepared-work projection; it never constructs enterprise totals or severity. */
@Composable
fun OperationsOverviewScreen(
    state: DispatchReadinessUiState,
    tenantId: String,
    workspaceId: String,
    exceptionsOnly: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOwningWork: (String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(
            rememberScrollState()
        ).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text(
            if (exceptionsOnly) "Trabajo bloqueado" else "Vista operativa",
            style = MaterialTheme.typography.headlineSmall
        )
        Text("Tenant: $tenantId\nWorkspace: $workspaceId")
        Text(
            "Cobertura: preparación de Fulfillment visible para tus permisos y almacenes. No representa todos los procesos de Nexa."
        )
        Text("Estado: ${state.status}")
        state.asOf?.let { Text("Información del servidor: $it") }
        state.observedAt?.let { Text("Recibida en dispositivo: $it") }
        Button(onClick = onRefresh, enabled = state.status != DispatchReadinessStatus.Loading) {
            Text("Actualizar hechos")
        }
        if (state.status !in
            setOf(DispatchReadinessStatus.Current, DispatchReadinessStatus.Empty)
        ) {
            Text("Hechos vigentes no disponibles. No se calculan totales ni decisiones.")
            return@Column
        }
        if (exceptionsOnly) {
            Text(
                "Product define WARNING, BLOCKING y CRITICAL. Esta proyección no incluye clasificación, responsable ni lifecycle de Operational Exception; muestra bloqueos del trabajo sin inventar esas decisiones."
            )
        }
        val items = if (exceptionsOnly) {
            state.items.filter {
                !it.ready || it.reasons.isNotEmpty()
            }
        } else {
            state.items
        }
        if (items.isEmpty()) {
            Text(
                "Sin elementos en esta proyección. No implica ausencia de excepciones en otros procesos."
            )
        }
        items.forEach { item ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Fulfillment ${item.fulfillmentId}")
                    Text("Contexto de este trabajo: Tenant $tenantId; Workspace $workspaceId.")
                    Text("Estado servidor: ${item.fulfillmentStatus}; listo: ${item.ready}")
                    Text(
                        "Hechos: ${item.asOf}; versión ${item.fulfillmentVersion}; asignación física ${item.physicalAllocationVersion}"
                    )
                    if (item.reasons.isNotEmpty()) {
                        Text(
                            "Bloqueos del servidor: ${item.reasons.joinToString()}"
                        )
                    }
                    if (exceptionsOnly) {
                        Text(
                            "Clasificación de excepción no proyectada. Propietario del trabajo: Fulfillment & Delivery."
                        )
                    }
                    TextButton(onClick = {
                        onOpenOwningWork(item.fulfillmentId)
                    }) { Text("Abrir preparación autorizada") }
                }
            }
        }
    }
}
