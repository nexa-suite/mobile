package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Explicit unavailable states. No capability here records an authoritative business result. */
@Composable
internal fun OperationsCapabilityLimitsScreen(driver: Boolean, onBack: () -> Unit) {
    val limitations = if (driver) DRIVER_LIMITATIONS else DISPATCH_LIMITATIONS
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text(if (driver) "Privacidad y coordinación de entrega" else "Identidad, cargas y responsabilidad",
            style = MaterialTheme.typography.headlineSmall)
        Text("Estas acciones requieren una política aceptada. No se comparte ubicación, inicia contacto, emite código ni cambia responsabilidad desde esta pantalla.")
        limitations.forEach { (title, missing) ->
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("No disponible: $missing")
        }
        Text("Continúa el trabajo autorizado desde las funciones conectadas. Una limitación de coordinación no confirma recepción, evidencia, pago ni resultado de entrega.")
    }
}

private val DRIVER_LIMITATIONS = listOf(
    "Compartir ubicación de entrega (MOB-US-029)" to
        "Falta política aceptada de consentimiento, destinatarios, frecuencia y retención. No se solicita ubicación ni se ejecuta seguimiento en segundo plano.",
    "Contactar al comprador (MOB-US-030)" to
        "Faltan canal autorizado, consentimiento y registro de uso vinculados a la asignación actual. No se muestran datos personales inferidos desde otros clientes.",
    "Código de entrega (MOB-US-034)" to
        "Faltan duración, uso único, autoridad de verificación y alternativa aprobada vinculados a Delivery y Attempt. Un código de recibo de Buyer no sustituye este mecanismo.",
    "Instrucciones y contacto vigente (MOB-US-063)" to
        "Faltan instrucciones y contactos permitidos proyectados para el conductor, con versión y confirmación de vigencia. La dirección autorizada sigue disponible en la entrega."
)
private val DISPATCH_LIMITATIONS = listOf(
    "Identificar traspaso (MOB-US-024)" to
        "Faltan mecanismo de identidad acotada, vigencia y verificación de Delivery y asignación. La evidencia de salida existente conserva esas referencias, pero no emite una identidad temporal.",
    "Cargas agrupadas y paradas (MOB-US-059)" to
        "Faltan reglas aceptadas de compatibilidad entre clientes y cadena de frío, formación de carga y orden de paradas. No se presenta agrupación local como plan del servidor.",
    "Traspaso al transportista (MOB-US-060)" to
        "Faltan identidad y autoridad del transportista, requisitos de evidencia y transición de responsabilidad sobre la carga. Asignar un conductor no confirma este traspaso."
)
