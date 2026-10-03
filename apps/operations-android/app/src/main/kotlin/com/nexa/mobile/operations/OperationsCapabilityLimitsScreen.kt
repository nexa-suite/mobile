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

/** Coordination scope; these explanations do not record business results. */
@Composable
internal fun OperationsCapabilityLimitsScreen(driver: Boolean, onBack: () -> Unit) {
    val limitations = if (driver) DRIVER_LIMITATIONS else DISPATCH_LIMITATIONS
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("Volver") }
        Text(
            if (driver) {
                "Privacidad y coordinación de entrega"
            } else {
                "Identidad, cargas y responsabilidad"
            },
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "Consulta funciones conectadas para ejecutar trabajo autorizado. Estos límites explican privacidad, contacto y responsabilidad."
        )
        limitations.forEach { (title, missing) ->
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(missing)
        }
        Text(
            "Continúa el trabajo autorizado desde las funciones conectadas. Una limitación de coordinación no confirma recepción, evidencia, pago ni resultado de entrega."
        )
    }
}

private val DRIVER_LIMITATIONS = listOf(
    "Compartir ubicación de entrega (MOB-US-029)" to
        "Ubicación únicamente durante jornada activa. Cerrar jornada detiene captura y transmisión; coordenadas caducan como máximo a las 24 horas. Buyer solo accede al mapa durante su Delivery despachada y activa.",
    "Contactar al comprador (MOB-US-030)" to
        "El chat de Nexa está reservado exclusivamente a Sales y Buyer. Driver y Buyer no tienen un canal de chat autorizado en este alcance; no se habilitan WhatsApp, SMS ni datos personales inferidos.",
    "Alternativa al código de entrega (MOB-US-034)" to
        "La emisión usa el contrato vigente de Delivery y Attempt y requiere logistics:write. El código permanece solo en memoria; una respuesta repetida sin secreto no permite recuperarlo. La alternativa operativa aprobada aún no está proyectada. Verificar el código no registra recepción ni resultado de entrega.",
    "Instrucciones y contacto vigente (MOB-US-063)" to
        "Consulta instrucciones vigentes de la Delivery asignada; reconoce versión actual de instrucciones críticas antes de iniciar intento. Conservan origen y autor registrados. Dirección autorizada disponible; chat exclusivo Sales y Buyer."
)
private val DISPATCH_LIMITATIONS = listOf(
    "Alcance de identidad de traspaso (MOB-US-024)" to
        "La identidad requiere Delivery autoritativa y asignación vigente. No crea una Delivery cuando falta esa relación. Resolver un código no registra aceptación bilateral, salida de stock, resultado Driver ni recepción Buyer.",
    "Cargas agrupadas y paradas (MOB-US-059)" to
        "Cargas conectadas requieren origen común, ventanas y temperatura compatibles, sin HOLD. Dispatch declara capacidad, manipulación, zona y ausencia de transporte exclusivo; servidor valida restricciones conocidas. Sin ventana autoritativa no puede agruparse.",
    "Traspaso al transportista (MOB-US-060)" to
        "Responsabilidad requiere confirmación de Dispatch y aceptación explícita de carga completa por Driver asignado. Asignación, identidad y evidencia de salida no equivalen a aceptación bilateral. Acceso directo de 3PL externo diferido."
)
