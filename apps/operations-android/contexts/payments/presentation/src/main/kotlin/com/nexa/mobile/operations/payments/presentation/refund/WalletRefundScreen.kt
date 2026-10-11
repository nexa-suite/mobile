package com.nexa.mobile.operations.payments.presentation.refund

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaSizes
import com.nexa.mobile.operations.core.designsystem.NexaSpacing
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntent
import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidate

@Composable
fun WalletRefundScreen(
    state: WalletRefundUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectOrder: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onUseRemaining: () -> Unit,
    onReasonChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: (WalletRefundIntent) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NexaSpacing.screenHorizontal)
            .padding(bottom = NexaSpacing.section),
        verticalArrangement = Arrangement.spacedBy(NexaSpacing.standard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = NexaSpacing.micro),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaSpacing.base)
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = NexaSizes.controlMinHeight)
            ) {
                Text("Volver")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Devolución a billetera", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Operaciones autorizadas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
            }
            TextButton(
                onClick = onRefresh,
                enabled = !state.isLoading && !state.isSubmitting,
                modifier = Modifier.heightIn(min = NexaSizes.controlMinHeight)
            ) {
                Text("Actualizar")
            }
        }

        WalletRefundNoticeCard(state.notice)

        if (state.pendingIntents.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(NexaSpacing.base)) {
                Text(
                    "Solicitudes con resultado pendiente",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Reintentar enviará exactamente la misma solicitud y clave.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
                state.pendingIntents.forEach { intent ->
                    WalletRefundPendingCard(
                        intent = intent,
                        enabled = !state.isSubmitting && !state.isLoading,
                        onRetry = { onRetry(intent) }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(NexaSpacing.base)) {
            Text("Órdenes con saldo reembolsable", style = MaterialTheme.typography.titleMedium)
            if (state.isLoading && state.candidates.isEmpty()) {
                Text("Cargando órdenes…", style = MaterialTheme.typography.bodyMedium)
            } else if (state.candidates.isEmpty()) {
                Text("No hay órdenes en esta página.", style = MaterialTheme.typography.bodyMedium)
            } else {
                state.candidates.forEach { candidate ->
                    WalletRefundCandidateCard(
                        candidate = candidate,
                        selected = candidate.salesOrderId == state.selectedSalesOrderId,
                        enabled = !state.isSubmitting && !state.isLoading,
                        onSelect = { onSelectOrder(candidate.salesOrderId) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPreviousPage,
                    enabled = state.page > 0 && !state.isLoading && !state.isSubmitting,
                    modifier = Modifier.heightIn(min = NexaSizes.controlMinHeight)
                ) {
                    Text("Anterior")
                }
                Text(
                    "Página ${state.page + 1} · ${state.total} órdenes",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )
                OutlinedButton(
                    onClick = onNextPage,
                    enabled = (state.page + 1L) * PAGE_SIZE < state.total &&
                        !state.isLoading && !state.isSubmitting,
                    modifier = Modifier.heightIn(min = NexaSizes.controlMinHeight)
                ) {
                    Text("Siguiente")
                }
            }
        }

        val selected = state.candidates.singleOrNull {
            it.salesOrderId == state.selectedSalesOrderId
        }
        if (selected != null) {
            Column(verticalArrangement = Arrangement.spacedBy(NexaSpacing.base)) {
                Text("Monto y motivo", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Disponible según servidor: PEN ${selected.refundablePEN.toPlainString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NexaSpacing.base),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.amountInput,
                        onValueChange = onAmountChanged,
                        modifier = Modifier.weight(1f),
                        label = { Text("Monto PEN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedButton(
                        onClick = onUseRemaining,
                        enabled = !state.isSubmitting && !state.isLoading,
                        modifier = Modifier.heightIn(min = NexaSizes.controlMinHeight)
                    ) {
                        Text("Usar saldo")
                    }
                }
                OutlinedTextField(
                    value = state.reasonInput,
                    onValueChange = onReasonChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Motivo requerido") },
                    minLines = 2
                )
                Button(
                    onClick = onSubmit,
                    enabled = state.storageAvailable && !state.isSubmitting && !state.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = NexaSizes.primaryButtonMinHeight),
                    shape = NexaShapes.button
                ) {
                    Text(if (state.isSubmitting) "Enviando…" else "Registrar devolución")
                }
            }
        }

        state.lastPosted?.let { posted ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NexaColors.SuccessSurface),
                shape = NexaShapes.card
            ) {
                Column(
                    modifier = Modifier.padding(NexaSpacing.surfaceInset),
                    verticalArrangement = Arrangement.spacedBy(NexaSpacing.micro)
                ) {
                    Text("Devolución registrada", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "PEN ${posted.amountPEN.toPlainString()} · ${posted.status}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletRefundCandidateCard(
    candidate: WalletRefundCandidate,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = NexaSizes.candidateRowMinHeight),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) NexaColors.InfoSurface else NexaColors.Surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            NexaSizes.borderWidth,
            if (selected) NexaColors.InfoBorder else NexaColors.Border
        ),
        shape = NexaShapes.card
    ) {
        Column(
            modifier = Modifier.padding(NexaSpacing.surfaceInset),
            verticalArrangement = Arrangement.spacedBy(NexaSpacing.micro)
        ) {
            Text(candidate.orderNumber, style = MaterialTheme.typography.titleSmall)
            Text(
                "Consumido PEN ${candidate.consumedPEN.toPlainString()} · " +
                    "Devuelto PEN ${candidate.refundedPEN.toPlainString()}",
                style = MaterialTheme.typography.bodySmall,
                color = NexaColors.TextSecondary
            )
            Text(
                "Disponible PEN ${candidate.refundablePEN.toPlainString()}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun WalletRefundPendingCard(
    intent: WalletRefundIntent,
    enabled: Boolean,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NexaColors.WarningSurface),
        shape = NexaShapes.card
    ) {
        Column(
            modifier = Modifier.padding(NexaSpacing.surfaceInset),
            verticalArrangement = Arrangement.spacedBy(NexaSpacing.base)
        ) {
            Text("Orden ${intent.salesOrderId}", style = MaterialTheme.typography.titleSmall)
            Text(
                "PEN ${intent.amountPEN} · ${intent.reason}",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NexaSizes.primaryButtonMinHeight),
                shape = NexaShapes.button
            ) {
                Text("Reintentar misma solicitud")
            }
        }
    }
}

@Composable
private fun WalletRefundNoticeCard(notice: WalletRefundNotice) {
    val message = when (notice) {
        WalletRefundNotice.None -> return

        WalletRefundNotice.CandidatesUnavailable -> "No se cargaron órdenes. Vuelve a actualizar."

        WalletRefundNotice.AccessDenied -> "La sesión ya no autoriza esta operación."

        WalletRefundNotice.MetadataUnavailable -> "No se pudo abrir o guardar la solicitud."

        WalletRefundNotice.MissingReason -> "Escribe un motivo para continuar."

        WalletRefundNotice.ReasonTooLong -> "El motivo no puede superar 500 caracteres."

        WalletRefundNotice.InvalidAmount -> "Ingresa un monto positivo con hasta cuatro decimales."

        WalletRefundNotice.AmountExceedsServerBalance ->
            "El monto supera el saldo disponible según el servidor."

        WalletRefundNotice.PendingIntent ->
            "Hay una solicitud pendiente para esta orden. Reinténtala con la misma clave."

        WalletRefundNotice.UnknownOutcome ->
            "Resultado sin confirmar. Reintenta la solicitud guardada con la misma clave."

        WalletRefundNotice.Posted -> "La API confirmó la devolución."

        WalletRefundNotice.PostedIntentRetained ->
            "La API confirmó la devolución; la solicitud local sigue protegida."

        WalletRefundNotice.Rejected -> "La API rechazó la solicitud. Revisa monto, motivo y saldo."

        WalletRefundNotice.Conflict ->
            "La API informó un conflicto. Actualiza antes de preparar otra solicitud."

        WalletRefundNotice.IntentUnavailable -> "Solicitud guardada no disponible en este contexto."
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (notice == WalletRefundNotice.AccessDenied ||
                notice == WalletRefundNotice.MetadataUnavailable
            ) {
                NexaColors.DangerSurface
            } else {
                NexaColors.InfoSurface
            }
        ),
        shape = NexaShapes.card
    ) {
        Text(
            message,
            modifier = Modifier.padding(NexaSpacing.surfaceInset),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private const val PAGE_SIZE = 25L
