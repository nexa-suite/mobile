package com.nexa.mobile.operations.commercial

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FieldVisitScreen(state: FieldVisitState,onBack: ()->Unit,viewModel: FieldVisitViewModel) {
    val editable=state.record.intent==null && state.status !in setOf("Loading","MetadataUnavailable","Pending")
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
        .verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick=onBack){Text("Volver")}
        Text("Visita al cliente",style=MaterialTheme.typography.headlineSmall)
        Text("Propósito y seguimiento vinculados al cliente. Registrar evidencia no confirma una compra ni registra ubicación.")
        Text("Estado: ${state.status}")
        OutlinedTextField(state.record.customerId,viewModel::customerChanged,enabled=editable,label={Text("Referencia del cliente")})
        OutlinedTextField(state.record.purpose,viewModel::purposeChanged,enabled=editable,label={Text("Propósito de visita")})
        OutlinedTextField(state.record.followUp,viewModel::followUpChanged,enabled=editable,label={Text("Resultado y seguimiento previsto")})
        Button(onClick=viewModel::reviewCustomer,enabled=editable){Text("Revisar relación vigente")}
        state.customer?.let { customer ->
            Text("${customer.name} · ${customer.code} · versión ${customer.version}")
            Text("Relación ${if(customer.active) "activa" else "suspendida"}; Buyer vinculado: ${customer.buyerLinked}")
            Text("Contexto recibido: ${state.receivedAt}")
        }
        if(state.status=="Reviewed") Button(onClick=viewModel::recordFollowUp){Text("Confirmar evidencia de visita")}
        if(state.status=="UnknownOutcome") {
            Text("Resultado desconocido. No crear otra visita para resolver este envío.")
            Button(onClick=viewModel::retryUnknownOutcome){Text("Resolver mismo envío")}
        }
        if(state.status=="Recorded") Text("Evidencia registrada: ${state.record.intent?.receiptId}")
        if(state.status=="Conflict") Text("Información rechazada o cambió. Revisa relación antes de una nueva decisión.")
        if(state.status=="MetadataUnavailable") Text("Almacenamiento protegido no disponible. Envío bloqueado.")
        if(state.status in setOf("Recorded","Conflict")) Button(onClick=viewModel::newDecision){Text("Nueva decisión")}
    }
}
