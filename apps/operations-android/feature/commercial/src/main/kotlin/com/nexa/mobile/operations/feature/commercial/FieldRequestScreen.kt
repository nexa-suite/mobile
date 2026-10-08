package com.nexa.mobile.operations.feature.commercial

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FieldRequestScreen(
    state: FieldRequestState,
    onBack: () -> Unit,
    viewModel: FieldRequestViewModel
) {
    val draft = state.record.draft
    val editable = state.record.intent == null && state.status !in setOf(
        FieldRequestStatus.Loading,
        FieldRequestStatus.MetadataUnavailable,
        FieldRequestStatus.Reviewing,
        FieldRequestStatus.Pending
    )
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text("Solicitud del cliente", style = MaterialTheme.typography.headlineSmall)
        Text(
            when (state.status) {
                FieldRequestStatus.Confirmed ->
                    "Registrada por Nexa: ${state.record.intent?.receiptId}"

                FieldRequestStatus.UnknownOutcome ->
                    "Resultado desconocido. Resolver el mismo envío; no crear otro."

                FieldRequestStatus.Conflict ->
                    "Solicitud rechazada o información cambió. Revisar antes de una nueva decisión."

                FieldRequestStatus.Changed ->
                    "Información comercial cambió. Revisar precios, unidades y cliente antes de aceptar."

                FieldRequestStatus.Reviewed ->
                    "Información revisada. Confirma el envío; backend decide compromiso y disponibilidad."

                FieldRequestStatus.MetadataUnavailable ->
                    "Almacenamiento protegido no disponible. Envío bloqueado."

                FieldRequestStatus.PermissionDenied -> "Autoridad o relación no vigente."

                FieldRequestStatus.Unavailable ->
                    "Información vigente no disponible. Borrador sigue sin confirmar."

                FieldRequestStatus.Pending -> "Envío pendiente de respuesta."

                FieldRequestStatus.Reviewing -> "Consultando información vigente…"

                FieldRequestStatus.Loading -> "Cargando borrador protegido…"

                FieldRequestStatus.Draft ->
                    "Borrador sin confirmar. No reserva existencias ni crea compromiso."
            }
        )
        OutlinedTextField(
            draft.customerId,
            viewModel::customerChanged,
            enabled = editable,
            label = {
                Text("Referencia de cliente")
            }
        )
        OutlinedTextField(state.productId, viewModel::productChanged, enabled = editable, label = {
            Text("Producto del catálogo (CAT-…)")
        })
        OutlinedTextField(state.quantity, viewModel::quantityChanged, enabled = editable, label = {
            Text("Cantidad")
        })
        Button(
            onClick = viewModel::addProduct,
            enabled =
                editable && draft.customerId.isNotBlank() && state.productId.isNotBlank()
        ) {
            Text("Consultar producto y agregar")
        }
        draft.lines.forEach { line ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(line.name)
                    Text(
                        "${line.catalogItemId} · ${line.price} ${line.currency} / ${line.unit ?: "unidad no disponible"}"
                    )
                    Text("Referencia recibida: ${line.asOf}. Vigencia se consulta antes del envío.")
                    OutlinedTextField(
                        line.quantity,
                        {
                            viewModel.lineQuantityChanged(line.catalogItemId, it)
                        },
                        enabled = editable,
                        label = { Text("Cantidad prevista") }
                    )
                    TextButton(onClick = {
                        viewModel.removeLine(line.catalogItemId)
                    }, enabled = editable) { Text("Quitar") }
                }
            }
        }
        OutlinedTextField(
            draft.deliveryDate,
            viewModel::deliveryDateChanged,
            enabled = editable,
            label = {
                Text("Fecha solicitada YYYY-MM-DD")
            }
        )
        OutlinedTextField(
            draft.deliveryProfile,
            viewModel::deliveryProfileChanged,
            enabled = editable,
            label = {
                Text("Instrucciones de entrega")
            }
        )
        Text("Forma de pago: ${draft.paymentOption}")
        listOf("CASH_ON_DELIVERY", "CREDIT_LINE", "BANK_TRANSFER", "PREPAID").forEach { option ->
            TextButton(onClick = {
                viewModel.paymentChanged(option)
            }, enabled = editable) { Text(option) }
        }
        OutlinedTextField(draft.comment, viewModel::commentChanged, enabled = editable, label = {
            Text("Comentario")
        })
        Button(onClick = viewModel::review, enabled = editable && draft.valid()) {
            Text("Revisar información vigente")
        }
        if (state.status == FieldRequestStatus.Changed) {
            Button(onClick = viewModel::acceptChangedInformation) {
                Text("Aceptar información revisada")
            }
        }
        if (state.status == FieldRequestStatus.Reviewed) {
            Button(onClick = viewModel::submit) { Text("Confirmar y enviar solicitud") }
        }
        if (state.status == FieldRequestStatus.UnknownOutcome) {
            Button(onClick = viewModel::retryUnknownOutcome) { Text("Resolver mismo envío") }
        }
        if (state.status in setOf(FieldRequestStatus.Confirmed, FieldRequestStatus.Conflict)) {
            Button(onClick = viewModel::startNewDecision) { Text("Iniciar nueva decisión") }
        }
    }
}
