package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * PANTALLA 1: CONDUCCIÓN Y RUTA EN CURSO (MOB-US-026, 027, 028, 031, 033, 034)
 * Despacho en Ruta · Cadena de Frío · Timeline interactivo con paradas · POD CTA
 */
@Composable
fun MockDriverRouteScreen(
    modifier: Modifier = Modifier,
    onConfirmDelivery: () -> Unit = {},
    onScanHandoff: () -> Unit = {}
) {
    var hasArrived by remember { mutableStateOf(false) }
    var currentTemp by remember { mutableFloatStateOf(3.8f) }
    var podSigned by remember { mutableStateOf(false) }
    var photoCount by remember { mutableIntStateOf(2) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Driver Profile Card
        NexaDriverProfileCard(
            driverName = "Diego Morales",
            roleTitle = "Conductor Flota C-250 · ID #4829",
            vehicleInfo = "Furgón Refrigerado · Placa WKN-892",
            statusText = if (hasArrived) "● EN DESTINO" else "● EN RUTA"
        )

        // Header with live telemetry
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DESPACHO · CADENA DE FRÍO",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            // Interactive Temp Calibrate Simulator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { currentTemp = (currentTemp - 0.2f).coerceAtLeast(1.0f) },
                    shape = CircleShape,
                    color = NexaColors.SurfaceInset
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "−",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                Text(
                    text = String.format(Locale.ROOT, "%.1f°C", currentTemp),
                    style = NexaTypography.identifier.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    ),
                    color = if (currentTemp in
                        2.0f..6.0f
                    ) {
                        NexaColors.ColdRefrigerated
                    } else {
                        NexaColors.Danger
                    }
                )
                Surface(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { currentTemp = (currentTemp + 0.2f).coerceAtMost(9.0f) },
                    shape = CircleShape,
                    color = NexaColors.SurfaceInset
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "+",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }

        // Dispatch Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SO-2026-0018",
                        style = NexaTypography.identifier.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaColors.TextPrimary
                    )
                    NexaColdChainBadge(
                        temperatureRange = String.format(
                            Locale.ROOT,
                            "%.1f°C (2°-6°C)",
                            currentTemp
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "ICISA Distribuciones S.A.C.",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaColors.TextPrimary
                    )
                    Text(
                        text = "24x Salmón Salar + 12x Merluza Austral (36 bultos)",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaColors.TextSecondary
                    )
                }

                // Interactive Timeline
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexaColors.SurfaceInset, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TimelineRow(
                        title = "Salida CD Callao",
                        time = "08:15 AM",
                        status = "Completado",
                        isActive = false,
                        isDone = true
                    )
                    TimelineRow(
                        title = "Peaje Ancón Km 48",
                        time = "09:40 AM",
                        status = "Verificado",
                        isActive = false,
                        isDone = true
                    )
                    TimelineRow(
                        title = "Destino: Almacén ICISA",
                        time = if (hasArrived) "10:25 AM (Llegada)" else "ETA 10:30 AM",
                        status = if (hasArrived) "En Bahía" else "En Tránsito",
                        isActive = !hasArrived,
                        isDone = hasArrived
                    )
                }

                // Interactive Actions & Check
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { hasArrived = !hasArrived },
                        shape = RoundedCornerShape(8.dp),
                        color = if (hasArrived) {
                            NexaColors.SuccessSurface
                        } else {
                            NexaColors.InfoSurface
                        },
                        border = BorderStroke(
                            1.dp,
                            if (hasArrived) NexaColors.SuccessBorder else NexaColors.InfoBorder
                        )
                    ) {
                        Text(
                            text = if (hasArrived) "✓ Llegada Confirmada" else "Registrar Llegada",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (hasArrived) NexaColors.Success else NexaColors.Info,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { photoCount++ },
                        shape = RoundedCornerShape(8.dp),
                        color = NexaColors.SurfaceInset
                    ) {
                        Text(
                            text = "📷 $photoCount fotos",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = NexaColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                NexaInteractiveToggle(
                    title = "Firma de Receptor Digital",
                    subtitle = if (podSigned) {
                        "Firmado por: Juan Perez (Almacén)"
                    } else {
                        "Pendiente de captura"
                    },
                    checked = podSigned,
                    onCheckedChange = { podSigned = it }
                )

                HorizontalDivider(color = NexaColors.Border)

                NexaPrimaryButton(
                    label = if (hasArrived &&
                        podSigned
                    ) {
                        "Finalizar Entrega (POD Completo)"
                    } else {
                        "Completar Entrega en Sitio"
                    },
                    onClick = onConfirmDelivery,
                    enabled = true
                )

                OutlinedButton(
                    onClick = onScanHandoff,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = NexaShapes.button,
                    border = BorderStroke(1.dp, NexaColors.BorderStrong)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_barcode),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = NexaColors.TextPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Escanear Precinto de Seguridad",
                        color = NexaColors.TextPrimary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

/**
 * PANTALLA 2: PICKING FEFO EN ALMACÉN (MOB-US-011, 014, 017, 019, 051)
 * Escaneo CameraX · Validación de Lote y Caducidad · Stepper de Cajas · Discrepancia
 */
@Composable
fun MockWarehousePickingScreen(
    modifier: Modifier = Modifier,
    onItemScanned: () -> Unit = {},
    onDeclareDiscrepancy: () -> Unit = {}
) {
    var pickedUnits by remember { mutableIntStateOf(18) }
    var selectedZone by remember { mutableStateOf("Cámara Frío #2") }
    var hasDiscrepancy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Warehouse Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ORDEN DE PICKING FEFO",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = NexaColors.TextSecondary
                )
                Text(
                    text = "PK-2026-0941",
                    style = NexaTypography.identifier.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = NexaColors.TextPrimary
                )
            }
            NexaStatusPill(
                text = "EN PROCESO",
                backgroundColor = NexaColors.WarningSurface,
                textColor = NexaColors.Warning,
                borderColor = NexaColors.WarningBorder
            )
        }

        // Zone filter chips
        NexaFilterChips(
            options = listOf("Cámara Frío #2", "Cámara Congelados", "Dry Storage A"),
            selectedOption = selectedZone,
            onOptionSelected = { selectedZone = it }
        )

        // Item Card to Pick
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NexaColdChainBadge("Cámara Frío: -18°C")
                    Text(
                        text = "Ubicación: RACK-B04-N2",
                        style = NexaTypography.identifier.copy(fontWeight = FontWeight.Bold),
                        color = NexaColors.PrimaryStrong
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Salmón Salar Filete Congelado (Caja 10kg)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaColors.TextPrimary
                    )
                    Text(
                        text = "SKU: SAL-CONG-010 · Lote Requerido: L-20260914-A",
                        style = NexaTypography.identifier.copy(fontSize = 12.sp),
                        color = NexaColors.TextSecondary
                    )
                    Text(
                        text = "Vencimiento FEFO: 14/10/2026 (Prioridad Alta)",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NexaColors.Warning
                    )
                }

                HorizontalDivider(color = NexaColors.Border)

                // Interactive Quantity Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Unidades a Pickear",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = NexaColors.TextSecondary
                        )
                        Text(
                            text = "Objetivo: 24 cajas",
                            style = MaterialTheme.typography.bodySmall,
                            color = NexaColors.TextMuted
                        )
                    }

                    NexaQuantityStepper(
                        quantity = pickedUnits,
                        onQuantityChanged = { pickedUnits = it },
                        min = 0,
                        max = 24,
                        unitLabel = "cajas"
                    )
                }

                // Discrepancy toggle
                NexaInteractiveToggle(
                    title = "Declarar Discrepancia en Lote",
                    subtitle = if (hasDiscrepancy) {
                        "Lote físico difiere o stock insuficiente"
                    } else {
                        "Lote físico verificado OK"
                    },
                    checked = hasDiscrepancy,
                    onCheckedChange = { hasDiscrepancy = it }
                )

                NexaPrimaryButton(
                    label = "Escanear Código de Barras (CameraX)",
                    onClick = onItemScanned
                )

                if (hasDiscrepancy) {
                    OutlinedButton(
                        onClick = onDeclareDiscrepancy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = NexaShapes.button,
                        border = BorderStroke(1.dp, NexaColors.DangerBorder)
                    ) {
                        Text(
                            text = "Reportar Faltante en Rack (-${24 - pickedUnits} cajas)",
                            color = NexaColors.Danger,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * PANTALLA 2B: ESCÁNER CAMERAX / ML KIT CON RETÍCULA (MOB-US-011, ADR-0018)
 * Visor de Cámara con Retícula · Detección GS1 · Fallback Manual Obligatorio
 */
@Composable
fun MockScannerScreen(
    modifier: Modifier = Modifier,
    onBarcodeDetected: (String) -> Unit = {},
    onManualCodeSubmit: (String) -> Unit = {}
) {
    var detectedSku by remember { mutableStateOf("ALM-LMO-982") }
    var detectedLot by remember { mutableStateOf("L-20260914-A") }
    var isFlashOn by remember { mutableStateOf(false) }
    var manualCodeInput by remember { mutableStateOf("SO-2026-0018-01") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "ALMACÉN · RECONOCIMIENTO GS1-128",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Escáner CameraX / ML Kit",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        // Viewfinder Camera Preview Box
        Surface(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            shape = NexaShapes.card,
            color = NexaColors.BrandNavy,
            border = BorderStroke(1.dp, NexaColors.Border)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Visor CameraX Activo (60 FPS)",
                        color = NexaColors.BrandCeleste,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Surface(
                        shape = NexaShapes.pill,
                        color = if (isFlashOn) {
                            NexaColors.WarningSurface
                        } else {
                            NexaColors.Surface.copy(
                                alpha = 0.2f
                            )
                        },
                        modifier = Modifier.clickable { isFlashOn = !isFlashOn }
                    ) {
                        Text(
                            text = if (isFlashOn) "Flash ON" else "Flash OFF",
                            color = if (isFlashOn) NexaColors.Warning else Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Laser scan line simulation
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.85f)
                        .height(2.dp)
                        .background(NexaColors.BrandCeleste)
                )

                Text(
                    text = "Alinee código GS1-128 o DataMatrix",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // Detected Asset Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "SKU $detectedSku",
                        style = NexaTypography.identifier.copy(fontWeight = FontWeight.Bold),
                        color = NexaColors.PrimaryStrong
                    )
                    NexaStatusPill(
                        "FEFO VALIDADO",
                        NexaColors.SuccessSurface,
                        NexaColors.Success,
                        NexaColors.SuccessBorder
                    )
                }
                Text(
                    "Salmón Salar Congelado 4-5kg · Lote $detectedLot",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Text(
                    "Temperatura registrada por sonda: 3.8°C (Rango 2°C – 6°C)",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )

                NexaPrimaryButton(
                    label = "Confirmar Reconocimiento GS1",
                    onClick = { onBarcodeDetected(detectedSku) }
                )
            }
        }

        // Manual Input Fallback (ADR-0018 Requirement)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.SurfaceInset,
            border = BorderStroke(1.dp, NexaColors.Border)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "¿Etiqueta húmeda o código ilegible? (Fallback Manual)",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = NexaColors.TextPrimary
                )
                Text(
                    text = "Código de bulto: $manualCodeInput",
                    style = NexaTypography.identifier.copy(fontSize = 13.sp),
                    color = NexaColors.TextSecondary
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().height(
                        48.dp
                    ).clip(NexaShapes.button).clickable {
                        onManualCodeSubmit(manualCodeInput)
                    },
                    color = NexaColors.Surface,
                    shape = NexaShapes.button,
                    border = BorderStroke(1.dp, NexaColors.Primary)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Validar Código Manual (ADR-0018)",
                            style = MaterialTheme.typography.labelLarge,
                            color = NexaColors.Primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * PANTALLA 3: MUELLE DE DESPACHO KANBAN (MOB-US-020, 022, 023, 025)
 * Asignación de Bahía · Verificación de Precinto · Pallets Estibados · Handoff
 */
@Composable
fun MockDispatchKanbanScreen(
    modifier: Modifier = Modifier,
    onAssignDriver: () -> Unit = {},
    onVerifyDispatch: () -> Unit = {}
) {
    var selectedBay by remember { mutableStateOf("Muelle 04 (Frío)") }
    var sealVerified by remember { mutableStateOf(true) }
    var palletCount by remember { mutableIntStateOf(4) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DESPACHO · STAGING & MUELLE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = NexaColors.TextSecondary
                )
                Text(
                    text = "Bahías Activas",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = NexaColors.TextPrimary
                )
            }
            NexaStatusPill(
                text = "4 LISTOS",
                backgroundColor = NexaColors.InfoSurface,
                textColor = NexaColors.Info,
                borderColor = NexaColors.InfoBorder
            )
        }

        // Dock Selector Chips
        NexaFilterChips(
            options = listOf("Muelle 04 (Frío)", "Muelle 02 (Seco)", "Muelle 07 (Export)"),
            selectedOption = selectedBay,
            onOptionSelected = { selectedBay = it }
        )

        // Dock Staging Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedBay,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaColors.TextPrimary
                    )
                    NexaStatusPill(
                        "EN CARGA",
                        NexaColors.WarningSurface,
                        NexaColors.Warning,
                        NexaColors.WarningBorder
                    )
                }

                Text(
                    text = "Vehículo Asignado: Furgón Isuzu FTR · Placa WKN-892",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = NexaColors.TextSecondary
                )
                Text(
                    text = "Chofer: Carlos Mendoza · Ruta Norte Express",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextMuted
                )

                HorizontalDivider(color = NexaColors.Border)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Pallets Estibados:",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    NexaQuantityStepper(
                        quantity = palletCount,
                        onQuantityChanged = { palletCount = it },
                        min = 1,
                        max = 12,
                        unitLabel = "pallets"
                    )
                }

                NexaInteractiveToggle(
                    title = "Precinto de Seguridad Verificado",
                    subtitle = if (sealVerified) {
                        "Precinto #NX-9921 registrado"
                    } else {
                        "Falta verificación física de precinto"
                    },
                    checked = sealVerified,
                    onCheckedChange = { sealVerified = it }
                )

                NexaPrimaryButton(
                    label = "Aprobar Despacho & Salida de Muelle",
                    onClick = onVerifyDispatch
                )

                OutlinedButton(
                    onClick = onAssignDriver,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = NexaShapes.button,
                    border = BorderStroke(1.dp, NexaColors.BorderStrong)
                ) {
                    Text(
                        "Reasignar Vehículo / Chofer",
                        color = NexaColors.TextPrimary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

/**
 * PANTALLA 4: INSPECCIÓN PRE-USO DE MAQUINARIA ISO 3691 (MOB-US-073)
 * Checklist Dinámico · Validación de Seguridad · Bloqueo Operacional
 */
@Composable
fun MockMachineryInspectionScreen(
    modifier: Modifier = Modifier,
    onSubmitChecklist: () -> Unit = {},
    onReportFailure: () -> Unit = {}
) {
    var brakesOk by remember { mutableStateOf(true) }
    var batteryOk by remember { mutableStateOf(true) }
    var forksOk by remember { mutableStateOf(true) }
    var hydraulicOk by remember { mutableStateOf(true) }

    val allApproved = brakesOk && batteryOk && forksOk && hydraulicOk

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "SEGURIDAD INDUSTRIAL · ISO 3691",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Inspección Pre-Uso de Maquinaria",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        // Machinery Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Apilador Eléctrico Crown 1.6T",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaColors.TextPrimary
                    )
                    NexaStatusPill(
                        text = if (allApproved) "OPERATIVO" else "BLOQUEADO",
                        backgroundColor = if (allApproved) {
                            NexaColors.SuccessSurface
                        } else {
                            NexaColors.DangerSurface
                        },
                        textColor = if (allApproved) NexaColors.Success else NexaColors.Danger,
                        borderColor = if (allApproved) {
                            NexaColors.SuccessBorder
                        } else {
                            NexaColors.DangerBorder
                        }
                    )
                }
                Text(
                    "Código de Activo: EQ-APIL-042 · Serie: CRW-99812",
                    style = NexaTypography.identifier.copy(fontSize = 12.sp),
                    color = NexaColors.TextSecondary
                )

                HorizontalDivider(color = NexaColors.Border)

                // Checklist Items
                NexaInteractiveToggle(
                    "1. Frenos de Servicio y Emergencia",
                    "Frenado progresivo sin desvío",
                    brakesOk
                ) {
                    brakesOk =
                        it
                }
                NexaInteractiveToggle(
                    "2. Nivel de Carga de Batería (>80%)",
                    "Voltaje y bornes limpios",
                    batteryOk
                ) {
                    batteryOk =
                        it
                }
                NexaInteractiveToggle(
                    "3. Estado de Mástil y Uñas",
                    "Sin fisuras ni deformación mecánica",
                    forksOk
                ) {
                    forksOk =
                        it
                }
                NexaInteractiveToggle(
                    "4. Sistema Hidráulico sin Fugas",
                    "Cilindro estanco y mangueras OK",
                    hydraulicOk
                ) {
                    hydraulicOk =
                        it
                }

                HorizontalDivider(color = NexaColors.Border)

                if (allApproved) {
                    NexaPrimaryButton(
                        label = "Aprobar y Desbloquear Maquinaria",
                        onClick = onSubmitChecklist
                    )
                } else {
                    OutlinedButton(
                        onClick = onReportFailure,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = NexaShapes.button,
                        border = BorderStroke(1.dp, NexaColors.DangerBorder)
                    ) {
                        Text(
                            "Bloquear Activo & Notificar a Mantenimiento",
                            color = NexaColors.Danger,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * PANTALLA 5: CATÁLOGO B2B MAYORISTA CON PRECIOS TIER (MOB-US-036, 037, 039)
 * Precios por Escala · Selector de Cajas · Subtotal Dinámico · Carrito B2B
 */
@Composable
fun MockWholesaleCatalogScreen(
    modifier: Modifier = Modifier,
    onAddToCart: () -> Unit = {},
    onViewCart: () -> Unit = {}
) {
    var quantityTier1 by remember { mutableIntStateOf(10) }
    var selectedCategory by remember { mutableStateOf("Congelados") }

    val unitPrice = if (quantityTier1 >= 20) {
        28.50
    } else if (quantityTier1 >= 10) {
        32.00
    } else {
        35.00
    }
    val totalAmount = quantityTier1 * unitPrice

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "PORTAL B2B MAYORISTA",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Catálogo de Productos",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        NexaFilterChips(
            options = listOf("Congelados", "Pescados Frescos", "Conservas", "Empaques"),
            selectedOption = selectedCategory,
            onOptionSelected = { selectedCategory = it }
        )

        // Wholesale Product Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NexaColdChainBadge("Cadena Frío: -18°C")
                    Text(
                        "Stock: 340 Cajas",
                        style = NexaTypography.identifier.copy(fontSize = 12.sp),
                        color = NexaColors.Success
                    )
                }

                Text(
                    text = "Filete Salmón Premium IQF (Caja 10kg)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = NexaColors.TextPrimary
                )

                // Price Tier Table
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexaColors.SurfaceInset, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Escala de Precios B2B:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1 – 9 cajas: $35.00/u", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "10 – 19 cajas: $32.00/u",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (quantityTier1 in
                                    10..19
                                ) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        )
                        Text(
                            "≥20 cajas: $28.50/u",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (quantityTier1 >=
                                    20
                                ) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Cantidad Pedida",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            String.format(Locale.ROOT, "Precio aplicable: $%.2f", unitPrice),
                            style = MaterialTheme.typography.bodySmall,
                            color = NexaColors.PrimaryStrong
                        )
                    }
                    NexaQuantityStepper(
                        quantity = quantityTier1,
                        onQuantityChanged = { quantityTier1 = it },
                        min = 1,
                        max = 100,
                        unitLabel = "cajas"
                    )
                }

                HorizontalDivider(color = NexaColors.Border)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Total Estimado B2B:",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = String.format(Locale.ROOT, "$%.2f USD", totalAmount),
                        style = NexaTypography.identifier.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaColors.PrimaryStrong
                    )
                }

                NexaPrimaryButton(
                    label = "Agregar al Carrito Mayorista",
                    onClick = onAddToCart
                )
            }
        }
    }
}

/**
 * PANTALLA 6: RECEPCIÓN Y CONFORMIDAD EN DESTINO (MOB-US-047, 048, 049)
 * Validación de Código OTP · Checklist de Bultos · Declaración de Discrepancia
 */
@Composable
fun MockBuyerReceiptScreen(
    modifier: Modifier = Modifier,
    onConfirmReceipt: () -> Unit = {},
    onReportDiscrepancy: () -> Unit = {}
) {
    var box1Ok by remember { mutableStateOf(true) }
    var box2Ok by remember { mutableStateOf(true) }
    var box3Ok by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "RECEPCIÓN DE MERCADERÍA",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Revisión Física de Pedido",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Código OTP Handoff:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Surface(
                        color = NexaColors.InfoSurface,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, NexaColors.InfoBorder)
                    ) {
                        Text(
                            "NX-8842",
                            style = NexaTypography.identifier.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = NexaColors.Info,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = "Orden de Compra: PO-2026-0042 · Proveedor: Pesquera Mar Azul",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = NexaColors.TextPrimary
                )

                HorizontalDivider(color = NexaColors.Border)

                Text(
                    "Checklist de Bultos Físicos:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
                NexaInteractiveToggle(
                    "Bulto #1 - Salmón Salar (24 Cajas)",
                    "Precinto intacto · T° 3.4°C",
                    box1Ok
                ) {
                    box1Ok =
                        it
                }
                NexaInteractiveToggle(
                    "Bulto #2 - Merluza Austral (12 Cajas)",
                    "Precinto intacto · T° 3.6°C",
                    box2Ok
                ) {
                    box2Ok =
                        it
                }
                NexaInteractiveToggle(
                    "Bulto #3 - Calamar Gigante (8 Cajas)",
                    "Caja abierta / daño visible",
                    box3Ok
                ) {
                    box3Ok =
                        it
                }

                HorizontalDivider(color = NexaColors.Border)

                NexaPrimaryButton(
                    label = "Confirmar Recepción de Bultos",
                    onClick = onConfirmReceipt
                )

                if (!box3Ok) {
                    OutlinedButton(
                        onClick = onReportDiscrepancy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = NexaShapes.button,
                        border = BorderStroke(1.dp, NexaColors.WarningBorder)
                    ) {
                        Text(
                            "Registrar Acta de Discrepancia / Merma",
                            color = NexaColors.Warning,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * PANTALLA 7: SINCRONIZACIÓN OFFLINE & COLA DE MUTACIONES (MOB-US-041, 042, 043)
 * Modo Sin Red · Cola Local SQLite · Reintentos y Progreso de Sincronización
 */
@Composable
fun MockOfflineSyncScreen(
    modifier: Modifier = Modifier,
    onSyncAll: () -> Unit = {},
    onRetryItem: (String) -> Unit = {}
) {
    var isOfflineMode by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableFloatStateOf(0.75f) }
    var failedRetried by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "OFFLINE ENGINE · RESILIENCIA DE RED",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Cola de Sincronización Local",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        // Network Status Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = if (isOfflineMode) NexaColors.WarningSurface else NexaColors.Surface,
            border = BorderStroke(
                1.dp,
                if (isOfflineMode) NexaColors.WarningBorder else NexaColors.Border
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_sync),
                            contentDescription = null,
                            tint = if (isOfflineMode) NexaColors.Warning else NexaColors.Success,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isOfflineMode) {
                                "Modo Offline (Sin Conexión)"
                            } else {
                                "Conectado al Gateway Central"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    NexaStatusPill(
                        text = if (isOfflineMode) "OFFLINE" else "ONLINE",
                        backgroundColor = if (isOfflineMode) {
                            NexaColors.WarningSurface
                        } else {
                            NexaColors.SuccessSurface
                        },
                        textColor = if (isOfflineMode) NexaColors.Warning else NexaColors.Success,
                        borderColor = if (isOfflineMode) {
                            NexaColors.WarningBorder
                        } else {
                            NexaColors.SuccessBorder
                        }
                    )
                }

                NexaInteractiveToggle(
                    title = "Simular Desconexión / Modo Avión",
                    subtitle = "Las mutaciones se encolan en Room DB local",
                    checked = isOfflineMode,
                    onCheckedChange = { isOfflineMode = it }
                )
            }
        }

        // Progress bar
        NexaSyncProgressBar(
            progress = syncProgress,
            syncStatus = "3 de 4 transacciones sincronizadas"
        )

        // Queue list
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Transacciones Pendientes en Dispositivo:",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
            )

            SyncMutationCard(
                mutationId = "MUT-0041",
                description = "POD Firma & Geotag SO-2026-0018",
                timestamp = "10:24 AM",
                status = "Sincronizado",
                isError = false
            )

            SyncMutationCard(
                mutationId = "MUT-0042",
                description = "Confirmación Picking Lote L-9921",
                timestamp = "10:31 AM",
                status = "Sincronizado",
                isError = false
            )

            SyncMutationCard(
                mutationId = "MUT-0043",
                description = "Checklist Pre-Uso Apilador Crown",
                timestamp = "10:45 AM",
                status = if (failedRetried) "Reintentando..." else "Error de Red (504 Timeout)",
                isError = !failedRetried,
                onRetry = {
                    failedRetried = true
                    syncProgress = 1.0f
                    onRetryItem("MUT-0043")
                }
            )

            SyncMutationCard(
                mutationId = "MUT-0044",
                description = "Cobro COD $1,840 Recibo Digital",
                timestamp = "11:02 AM",
                status = "En Cola Local",
                isError = false
            )
        }

        NexaPrimaryButton(
            label = "Forzar Sincronización Inmediata",
            onClick = onSyncAll
        )
    }
}

@Composable
private fun SyncMutationCard(
    mutationId: String,
    description: String,
    timestamp: String,
    status: String,
    isError: Boolean,
    onRetry: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaShapes.row,
        color = if (isError) NexaColors.DangerSurface else NexaColors.Surface,
        border = BorderStroke(1.dp, if (isError) NexaColors.DangerBorder else NexaColors.Border)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = mutationId,
                        style = NexaTypography.identifier.copy(fontSize = 11.sp),
                        color = NexaColors.TextMuted
                    )
                    Text(
                        text = "· $timestamp",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = NexaColors.TextMuted
                    )
                }
            }
            if (isError && onRetry != null) {
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onRetry() },
                    color = NexaColors.Danger,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "Reintentar",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else {
                NexaStatusPill(
                    text = status,
                    backgroundColor = if (isError) {
                        NexaColors.DangerSurface
                    } else {
                        NexaColors.SuccessSurface
                    },
                    textColor = if (isError) NexaColors.Danger else NexaColors.Success,
                    borderColor = if (isError) {
                        NexaColors.DangerBorder
                    } else {
                        NexaColors.SuccessBorder
                    }
                )
            }
        }
    }
}

/**
 * PANTALLA 8: REPORTE DE INCIDENTES Y DAÑOS CON EVIDENCIA (MOB-US-056, 057, 058)
 * Severidad · Captura Fotográfica CameraX · Nota de Voz · Geotag
 */
@Composable
fun MockIncidentReportScreen(
    modifier: Modifier = Modifier,
    onCapturePhoto: () -> Unit = {},
    onSubmitReport: () -> Unit = {}
) {
    var selectedCategory by remember { mutableStateOf("Cadena de Frío Rota") }
    var selectedSeverity by remember { mutableStateOf("Crítica") }
    var photoCount by remember { mutableIntStateOf(2) }
    var isVoiceRecorded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "GESTIÓN DE INCIDENCIAS & CALIDAD",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Reporte de Daños o Siniestro",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        // Category Chips
        NexaFilterChips(
            options = listOf("Cadena de Frío Rota", "Embalaje Roto", "Faltante", "Accidente Vial"),
            selectedOption = selectedCategory,
            onOptionSelected = { selectedCategory = it }
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Nivel de Severidad:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
                NexaSeveritySelector(selectedSeverity = selectedSeverity, onSeveritySelected = {
                    selectedSeverity =
                        it
                })

                HorizontalDivider(color = NexaColors.Border)

                Text(
                    "Evidencia Fotográfica Obligatoria:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    repeat(photoCount) { index ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(80.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = NexaColors.SurfaceInset,
                            border = BorderStroke(1.dp, NexaColors.Border)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_camera),
                                    contentDescription = null,
                                    tint = NexaColors.PrimaryStrong
                                )
                                Text(
                                    "Foto #${index + 1}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                photoCount = (photoCount + 1).coerceAtMost(4)
                                onCapturePhoto()
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = NexaColors.Surface,
                        border = BorderStroke(1.dp, NexaColors.PrimaryStrong)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "+",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = NexaColors.PrimaryStrong
                                )
                            )
                            Text(
                                "Añadir",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    color = NexaColors.PrimaryStrong
                                )
                            )
                        }
                    }
                }

                NexaInteractiveToggle(
                    title = "Nota de Audio de Evidencia (Voz)",
                    subtitle = if (isVoiceRecorded) {
                        "Grabación adjunta (0:24 seg)"
                    } else {
                        "Presiona para grabar audio descriptivo"
                    },
                    checked = isVoiceRecorded,
                    onCheckedChange = { isVoiceRecorded = it }
                )

                HorizontalDivider(color = NexaColors.Border)

                NexaPrimaryButton(
                    label = "Transmitir Reporte con Geotag & Hora",
                    onClick = onSubmitReport
                )
            }
        }
    }
}

/**
 * PANTALLA 9: CONTEO CÍCLICO E INVENTARIO CIEGO (MOB-US-061, 062, 065)
 * Conteo Físico Ciego · Verificación de Tolerancia · Ajuste de Lote
 */
@Composable
fun MockCycleCountScreen(
    modifier: Modifier = Modifier,
    onScanBarcode: () -> Unit = {},
    onConfirmCount: () -> Unit = {}
) {
    var blindMode by remember { mutableStateOf(true) }
    var countedQuantity by remember { mutableIntStateOf(45) }
    val expectedQuantity = 48
    val difference = countedQuantity - expectedQuantity

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "GESTIÓN DE INVENTARIOS & WMS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Conteo Cíclico en Rack",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Pasillo B · Rack 04 · Nivel 2",
                        style = NexaTypography.identifier.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        ),
                        color = NexaColors.PrimaryStrong
                    )
                    NexaStatusPill(
                        "EN PROGRESO",
                        NexaColors.InfoSurface,
                        NexaColors.Info,
                        NexaColors.InfoBorder
                    )
                }

                Text(
                    text = "Atún en Trozos en Aceite Vegetal (Master Box 48u)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = NexaColors.TextPrimary
                )
                Text(
                    "EAN: 7751234567890 · Lote Activo: L-2026-T82",
                    style = NexaTypography.identifier.copy(fontSize = 12.sp),
                    color = NexaColors.TextSecondary
                )

                NexaInteractiveToggle(
                    title = "Modo de Conteo Ciego (Blind Count)",
                    subtitle = if (blindMode) {
                        "Stock teórico oculto para evitar sesgo"
                    } else {
                        "Stock teórico visible: 48 cajas"
                    },
                    checked = blindMode,
                    onCheckedChange = { blindMode = it }
                )

                HorizontalDivider(color = NexaColors.Border)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Unidades Físicas Contadas",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        if (!blindMode) {
                            Text(
                                "Diferencia: $difference cajas",
                                color = if (difference ==
                                    0
                                ) {
                                    NexaColors.Success
                                } else {
                                    NexaColors.Danger
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                    NexaQuantityStepper(
                        quantity = countedQuantity,
                        onQuantityChanged = { countedQuantity = it },
                        min = 0,
                        max = 200,
                        unitLabel = "cajas"
                    )
                }

                NexaPrimaryButton(
                    label = "Confirmar Conteo & Registrar",
                    onClick = onConfirmCount
                )

                OutlinedButton(
                    onClick = onScanBarcode,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = NexaShapes.button,
                    border = BorderStroke(1.dp, NexaColors.BorderStrong)
                ) {
                    Text(
                        "Escanear Código de Siguiente Bulto",
                        color = NexaColors.TextPrimary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

/**
 * PANTALLA 10: COBRO CONTRA ENTREGA (COD) & LIQUIDACIÓN (MOB-US-066, 067, 069)
 * Cobro de Factura en Efectivo / QR · Cálculo de Vuelto · Recibo Digital
 */
@Composable
fun MockCashOnDeliveryScreen(
    modifier: Modifier = Modifier,
    onCollectPayment: () -> Unit = {},
    onPrintReceipt: () -> Unit = {}
) {
    var selectedMethod by remember { mutableStateOf("Efectivo") }
    var cashReceived by remember { mutableFloatStateOf(2000.0f) }
    val totalToCollect = 1840.0f
    val changeAmount = (cashReceived - totalToCollect).coerceAtLeast(0.0f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "FINANZAS · COBRO CONTRA ENTREGA",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Liquidación y Cobro (COD)",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        NexaFilterChips(
            options = listOf("Efectivo", "QR Transferencia", "Cheque Certificado"),
            selectedOption = selectedMethod,
            onOptionSelected = { selectedMethod = it }
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Total a Cobrar:",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = String.format(Locale.ROOT, "$%.2f USD", totalToCollect),
                        style = NexaTypography.identifier.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = NexaColors.PrimaryStrong
                    )
                }
                Text(
                    "Cliente: Supermercados Wong · RUC 20100128491",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )

                HorizontalDivider(color = NexaColors.Border)

                if (selectedMethod == "Efectivo") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Monto Recibido:", style = MaterialTheme.typography.bodyMedium)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(32.dp).clip(CircleShape).clickable {
                                    cashReceived =
                                        (cashReceived - 100.0f).coerceAtLeast(totalToCollect)
                                },
                                shape = CircleShape,
                                color = NexaColors.SurfaceInset
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("−", fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(
                                String.format(Locale.ROOT, "$%.2f", cashReceived),
                                style = NexaTypography.identifier.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            )
                            Surface(
                                modifier = Modifier.size(32.dp).clip(CircleShape).clickable {
                                    cashReceived +=
                                        100.0f
                                },
                                shape = CircleShape,
                                color = NexaColors.PrimaryStrong
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("+", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Change banner
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = NexaColors.SuccessSurface,
                        border = BorderStroke(1.dp, NexaColors.SuccessBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Vuelto a Entregar al Cliente:",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = NexaColors.Success
                            )
                            Text(
                                String.format(Locale.ROOT, "$%.2f USD", changeAmount),
                                style = NexaTypography.identifier.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                color = NexaColors.Success
                            )
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = NexaColors.SurfaceInset,
                        border = BorderStroke(1.dp, NexaColors.Border)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Código QR Dinámico B2B Generado",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                "Monto exacto: $1,840.00 USD · Válido por 10 min",
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaColors.TextSecondary
                            )
                        }
                    }
                }

                HorizontalDivider(color = NexaColors.Border)

                NexaPrimaryButton(
                    label = "Emitir Recibo Digital & Liquidar Cobro",
                    onClick = onCollectPayment
                )

                OutlinedButton(
                    onClick = onPrintReceipt,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = NexaShapes.button,
                    border = BorderStroke(1.dp, NexaColors.BorderStrong)
                ) {
                    Text(
                        "Imprimir Recibo Térmico Bluetooth",
                        color = NexaColors.TextPrimary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

/**
 * PANTALLA 11: DEVOLUCIONES Y LOGÍSTICA INVERSA RMA (MOB-US-070, 071)
 * Retorno de Mercadería · Motivo · Evaluación Técnica de Cadena de Frío
 */
@Composable
fun MockReturnsRmaScreen(
    modifier: Modifier = Modifier,
    onScanRma: () -> Unit = {},
    onConfirmReturn: () -> Unit = {}
) {
    var selectedReason by remember { mutableStateOf("Rechazo por Calidad") }
    var sealsIntact by remember { mutableStateOf(true) }
    var tempCompliant by remember { mutableStateOf(true) }
    var returnBoxes by remember { mutableIntStateOf(4) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "LOGÍSTICA INVERSA & CALIDAD",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Autorización de Devolución (RMA)",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        NexaFilterChips(
            options = listOf("Rechazo por Calidad", "Caducidad Corta (<15d)", "Error en Pedido"),
            selectedOption = selectedReason,
            onOptionSelected = { selectedReason = it }
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "RMA-2026-0914",
                        style = NexaTypography.identifier.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        color = NexaColors.PrimaryStrong
                    )
                    NexaStatusPill(
                        "EN EVALUACIÓN",
                        NexaColors.WarningSurface,
                        NexaColors.Warning,
                        NexaColors.WarningBorder
                    )
                }

                Text(
                    "Producto: Salmón Salar Filete · Lote: L-20260914-A",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cajas a Devolver:", style = MaterialTheme.typography.bodyMedium)
                    NexaQuantityStepper(
                        quantity = returnBoxes,
                        onQuantityChanged = { returnBoxes = it },
                        min = 1,
                        max = 24,
                        unitLabel = "cajas"
                    )
                }

                HorizontalDivider(color = NexaColors.Border)

                Text(
                    "Inspección de Condición al Retorno:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
                NexaInteractiveToggle(
                    "1. Sellos y Precintos de Fábrica Intactos",
                    "Sin alteración ni empaque roto",
                    sealsIntact
                ) {
                    sealsIntact =
                        it
                }
                NexaInteractiveToggle(
                    "2. Cadena de Frío Conservada (<4°C)",
                    "Termómetro de sonda: 3.2°C OK",
                    tempCompliant
                ) {
                    tempCompliant =
                        it
                }

                HorizontalDivider(color = NexaColors.Border)

                NexaPrimaryButton(
                    label = "Aprobar Retorno & Generar Etiqueta RMA",
                    onClick = onConfirmReturn
                )

                OutlinedButton(
                    onClick = onScanRma,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = NexaShapes.button,
                    border = BorderStroke(1.dp, NexaColors.BorderStrong)
                ) {
                    Text(
                        "Escanear Código de Orden Original",
                        color = NexaColors.TextPrimary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

/**
 * PANTALLA 12: AUTENTICACIÓN BIOMÉTRICA & SELECCIÓN DE SEDE (MOB-US-001, 002, 003)
 * Biometría / PIN Offline · Selector de Planta Logística · Inicio de Turno
 */
@Composable
fun MockAuthWorkspaceScreen(
    modifier: Modifier = Modifier,
    onBiometricLogin: () -> Unit = {},
    onSelectWorkspace: (String) -> Unit = {}
) {
    var selectedFacility by remember { mutableStateOf("Planta Frío Callao") }
    var biometricVerified by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "ACCESO & GESTIÓN DE TURNOS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = NexaColors.TextSecondary
            )
            Text(
                text = "Inicio de Turno Operativo",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NexaColors.TextPrimary
            )
        }

        // Operator ID Card
        NexaDriverProfileCard(
            driverName = "Diego Morales",
            roleTitle = "Operador Logístico Senior · ID #4829",
            vehicleInfo = "Licencia A-IIIc · Certificado Cadena Frío",
            statusText = if (biometricVerified) "● AUTENTICADO" else "● PENDIENTE"
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaShapes.card,
            color = NexaColors.Surface,
            border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Seleccionar Centro Logístico / Almacén:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
                NexaFilterChips(
                    options = listOf("Planta Frío Callao", "Hub Lurín", "Cross-dock Huachipa"),
                    selectedOption = selectedFacility,
                    onOptionSelected = {
                        selectedFacility = it
                        onSelectWorkspace(it)
                    }
                )

                HorizontalDivider(color = NexaColors.Border)

                Text(
                    "Autenticación Segura en Dispositivo:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            biometricVerified = !biometricVerified
                            onBiometricLogin()
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = if (biometricVerified) {
                        NexaColors.SuccessSurface
                    } else {
                        NexaColors.InfoSurface
                    },
                    border = BorderStroke(
                        1.dp,
                        if (biometricVerified) NexaColors.SuccessBorder else NexaColors.InfoBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            tint = if (biometricVerified) NexaColors.Success else NexaColors.Info,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (biometricVerified) {
                                    "Biometría Verificada con Éxito"
                                } else {
                                    "Validar Huella Dactilar o FaceID"
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (biometricVerified) {
                                    NexaColors.Success
                                } else {
                                    NexaColors.TextPrimary
                                }
                            )
                            Text(
                                text = if (biometricVerified) {
                                    "Token operativo activo por 8 horas"
                                } else {
                                    "Toca aquí para simular sensor biométrico"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaColors.TextSecondary
                            )
                        }
                    }
                }

                HorizontalDivider(color = NexaColors.Border)

                NexaPrimaryButton(
                    label = "Comenzar Turno en $selectedFacility",
                    onClick = onBiometricLogin,
                    enabled = biometricVerified
                )
            }
        }
    }
}

/**
 * Helper row for Route Timeline.
 */
@Composable
private fun TimelineRow(
    title: String,
    time: String,
    status: String,
    isActive: Boolean,
    isDone: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isDone -> NexaColors.Success
                            isActive -> NexaColors.Info
                            else -> NexaColors.TextMuted
                        }
                    )
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (isActive || isDone) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = NexaColors.TextPrimary
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = NexaColors.TextMuted
                )
            }
        }

        NexaStatusPill(
            text = status,
            backgroundColor = when {
                isDone -> NexaColors.SuccessSurface
                isActive -> NexaColors.InfoSurface
                else -> NexaColors.SurfaceInset
            },
            textColor = when {
                isDone -> NexaColors.Success
                isActive -> NexaColors.Info
                else -> NexaColors.TextSecondary
            },
            borderColor = when {
                isDone -> NexaColors.SuccessBorder
                isActive -> NexaColors.InfoBorder
                else -> NexaColors.Border
            }
        )
    }
}
