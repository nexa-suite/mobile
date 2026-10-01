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
        Text("Las decisiones de Product están registradas. Esta pantalla explica funciones excluidas o contratos API pendientes; no registra resultados empresariales.")
        limitations.forEach { (title, missing) ->
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("No disponible: $missing")
        }
        Text("Continúa el trabajo autorizado desde las funciones conectadas. Una limitación de coordinación no confirma recepción, evidencia, pago ni resultado de entrega.")
    }
}

private val DRIVER_LIMITATIONS = listOf(
    "Compartir ubicación de entrega (MOB-US-029)" to
        "Driver requiere ubicación durante su jornada operativa, nunca fuera de ella. Faltan contratos de jornada, ubicación vigente y acceso limitado; la duración exacta de retención sigue pendiente de Privacy/Security/Data Governance. Esta versión no simula seguimiento.",
    "Contactar al comprador (MOB-US-030)" to
        "El chat de Nexa está reservado exclusivamente a Sales y Buyer. Driver y Buyer no tienen un canal de chat autorizado en este alcance; no se habilitan WhatsApp, SMS ni datos personales inferidos.",
    "Alternativa al código de entrega (MOB-US-034)" to
        "La emisión usa el contrato vigente de Delivery y Attempt y requiere logistics:write. El código permanece solo en memoria; una respuesta repetida sin secreto no permite recuperarlo. La alternativa operativa aprobada aún no está proyectada. Verificar el código no registra recepción ni resultado de entrega.",
    "Instrucciones y contacto vigente (MOB-US-063)" to
        "Product acepta instrucciones actuales durante asignación y entrega activa, con reconocimiento versionado obligatorio para instrucciones críticas. Falta esa proyección y contrato de reconocimiento. La dirección autorizada sigue disponible; chat solo Sales y Buyer."
)
private val DISPATCH_LIMITATIONS = listOf(
    "Alcance de identidad de traspaso (MOB-US-024)" to
        "La identidad requiere Delivery autoritativa y asignación vigente. No crea una Delivery cuando falta esa relación. Resolver un código no registra aceptación bilateral, salida de stock, resultado Driver ni recepción Buyer.",
    "Cargas agrupadas y paradas (MOB-US-059)" to
        "Product acepta origen común, ventanas compatibles, mismo rango térmico, handling/capacidad compatibles y restricciones explícitas. Falta contrato API de carga y esos hechos de compatibilidad; no se presenta agrupación local como plan del servidor.",
    "Traspaso al transportista (MOB-US-060)" to
        "Product requiere confirmación de Dispatch y aceptación explícita de la carga completa por Driver con Membership autorizada. Falta contrato API para ambos hechos; asignación y evidencia de salida no equivalen a aceptación bilateral. Acceso directo de 3PL externo está diferido."
)
