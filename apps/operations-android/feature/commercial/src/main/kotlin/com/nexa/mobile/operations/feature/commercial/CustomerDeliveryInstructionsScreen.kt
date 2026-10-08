package com.nexa.mobile.operations.feature.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CustomerDeliveryInstructionsScreen(
    state: CustomerInstructionsState,
    onBack: () -> Unit,
    viewModel: CustomerDeliveryInstructionsViewModel
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text("Instrucciones de entrega Customer", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sales registra instrucciones recibidas del cliente. Origen, autor, versión y ventana de edición son verificados por servidor."
        )
        OutlinedTextField(
            state.orderId,
            viewModel::orderChanged,
            label = { Text("SalesOrder ID") },
            enabled = !state.pending && state.recoverable == null
        )
        OutlinedButton(viewModel::refresh, enabled = !state.pending) {
            Text("Consultar instrucciones")
        }
        if (state.pending) CircularProgressIndicator()
        state.message?.let { Text(it) }
        state.recoverable?.let {
            Text(
                "Comando protegido pendiente. No crear otra instrucción hasta recuperar resultado."
            )
            Button(viewModel::retry, enabled = !state.pending) { Text("Reintentar mismo comando") }
        }
        state.snapshot?.let { snapshot ->
            Text(
                "Versión ${snapshot.version} · ${if (snapshot.editable) "Edición disponible" else "Edición cerrada"}"
            )
            snapshot.rows.forEach { row ->
                Card {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("${row.kind} · Revisión ${row.version}")
                        Text(row.content)
                        Text("${row.source} · ${row.recordedBy} · ${row.recordedAt}")
                        if (snapshot.editable) {
                            TextButton(
                                { viewModel.edit(row) },
                                enabled =
                                    !state.pending && state.recoverable == null
                            ) { Text("Editar") }
                        }
                    }
                }
            }
            if (snapshot.editable && state.recoverable == null) {
                OutlinedButton(viewModel::newInstruction, enabled = !state.pending) {
                    Text("Nueva instrucción")
                }
                CustomerDeliveryInstructionsViewModel.kinds.forEach { kind ->
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = {
                            viewModel.kindChanged(kind)
                        },
                        label = { Text(kind) },
                        enabled = !state.pending
                    )
                }
                OutlinedTextField(state.content, viewModel::contentChanged, label = {
                    Text("Instrucción del cliente")
                }, enabled = !state.pending)
                OutlinedTextField(state.source, viewModel::sourceChanged, label = {
                    Text("Referencia de recepción del cliente")
                }, enabled = !state.pending)
                Text(
                    "No atribuir a Buyer una instrucción recibida por Sales. Instrucciones críticas requerirán reconocimiento Driver."
                )
                Button(viewModel::publish, enabled = state.canPublish) {
                    Text("Registrar instrucción")
                }
            }
        }
    }
}
