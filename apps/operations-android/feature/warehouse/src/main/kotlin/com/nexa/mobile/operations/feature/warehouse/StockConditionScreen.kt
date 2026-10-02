package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant

@Composable
fun StockConditionScreen(
    state: StockConditionUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectLot: (String) -> Unit,
    onRouteClosed: () -> Unit,
    onDisposition: ((String) -> Unit)? = null,
    identifiedSkuId: String? = null
) {
    var storageReference by remember(state.authorityEpoch) { mutableStateOf("") }
    val visibleLots = if (identifiedSkuId == null) {
        state.lots
    } else {
        state.lots.filter { it.skuId.equals(identifiedSkuId, ignoreCase = true) }
    }
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) {
        onDispose { closeAction.value() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack) { Text(stringResource(R.string.stock_condition_back)) }
                Text(
                    text = stringResource(R.string.stock_condition_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.stock_condition_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                StatusPanel(state.status, state.listObservedAt)
            }
            item {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = state.status != StockConditionStatus.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.stock_condition_refresh))
                }
            }
            item {
                identifiedSkuId?.let {
                    Text(
                        "Identidad de Catálogo confirmada: SKU $it. Consulta de lotes actuales autorizados; ningún código confirma cantidades físicas."
                    )
                }
                OutlinedTextField(storageReference, {
                    storageReference = it
                }, label = { Text("Referencia manual de lote") }, singleLine = true)
                OutlinedButton(
                    onClick = {
                        onSelectLot(storageReference.trim())
                    },
                    enabled = runCatching {
                        java.util.UUID.fromString(storageReference.trim())
                    }.isSuccess
                ) {
                    Text("Confirmar lote y ubicación con el servidor")
                }
                if (identifiedSkuId != null && state.status == StockConditionStatus.Current &&
                    visibleLots.isEmpty()
                ) {
                    Text(
                        "No hay lotes autorizados de este SKU en la consulta actual. Usa una referencia de lote para confirmación; no se infiere una ubicación desde el código."
                    )
                }
            }
            if (state.status == StockConditionStatus.Loading) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.stock_condition_loading))
                    }
                }
            }
            if (visibleLots.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.stock_condition_lots, visibleLots.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(visibleLots, key = { it.id.lowercase() }) { lot ->
                    LotChoiceCard(
                        lot = lot,
                        selected = state.selectedLotId.equals(lot.id, ignoreCase = true),
                        onClick = { onSelectLot(lot.id) }
                    )
                }
            }
            if (state.selectedLotId != null) {
                item {
                    if (identifiedSkuId != null &&
                        state.detailStatus == StockConditionDetailStatus.Current &&
                        state.selectedLot?.skuId?.equals(identifiedSkuId, ignoreCase = true) != true
                    ) {
                        Text(
                            "El lote consultado no corresponde al SKU escaneado. Confirma la identidad antes de realizar trabajo físico."
                        )
                    }
                    SelectedLotPanel(state)
                }
                val confirmedLot = state.selectedLot
                if (confirmedLot != null &&
                    state.detailStatus == StockConditionDetailStatus.Current &&
                    onDisposition != null
                ) {
                    item {
                        OutlinedButton(onClick = { onDisposition(confirmedLot.id) }) {
                            Text(stringResource(R.string.stock_condition_record_disposition))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(status: StockConditionStatus, observedAt: Instant?) {
    val (title, body) = when (status) {
        StockConditionStatus.Initial ->
            R.string.stock_condition_initial_title to R.string.stock_condition_initial_body

        StockConditionStatus.Loading ->
            R.string.stock_condition_loading_title to R.string.stock_condition_loading

        StockConditionStatus.Current ->
            R.string.stock_condition_current_title to R.string.stock_condition_current_body

        StockConditionStatus.Empty ->
            R.string.stock_condition_empty_title to R.string.stock_condition_empty_body

        StockConditionStatus.PermissionUnknown ->
            R.string.stock_condition_permission_unknown_title to
                R.string.stock_condition_permission_unknown_body

        StockConditionStatus.PermissionDenied ->
            R.string.stock_condition_permission_denied_title to
                R.string.stock_condition_permission_denied_body

        StockConditionStatus.NetworkUnavailable ->
            R.string.stock_condition_network_title to R.string.stock_condition_network_body

        StockConditionStatus.ServiceUnavailable ->
            R.string.stock_condition_service_title to R.string.stock_condition_service_body

        StockConditionStatus.ContextInvalidated ->
            R.string.stock_condition_context_title to R.string.stock_condition_context_body

        StockConditionStatus.SessionInvalidated ->
            R.string.stock_condition_session_title to R.string.stock_condition_session_body
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            if (observedAt != null && status in setOf(
                    StockConditionStatus.Current,
                    StockConditionStatus.Empty
                )
            ) {
                Text(
                    stringResource(R.string.stock_condition_observed_at, observedAt.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LotChoiceCard(lot: StockConditionLot, selected: Boolean, onClick: () -> Unit) {
    val accessibilityLabel = stringResource(
        R.string.stock_condition_lot_accessibility,
        lot.batchNumber,
        lot.warehouseId,
        lot.physicalRemaining.toPlainString(),
        lot.unit
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = accessibilityLabel },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(lot.batchNumber, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.stock_condition_lot_status, lot.status))
            Text(
                stringResource(
                    R.string.stock_condition_physical_remaining,
                    lot.physicalRemaining.toPlainString(),
                    lot.unit
                )
            )
            Text(
                stringResource(
                    R.string.stock_condition_lot_location,
                    lot.warehouseId,
                    lot.zoneId
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SelectedLotPanel(state: StockConditionUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.stock_condition_selected_lot),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            when (state.detailStatus) {
                StockConditionDetailStatus.NotRequested -> Unit

                StockConditionDetailStatus.Loading -> Text(
                    stringResource(R.string.stock_condition_detail_loading)
                )

                StockConditionDetailStatus.Current -> state.selectedLot?.let { lot ->
                    Text(stringResource(R.string.stock_condition_lot_id, lot.id))
                    Text(stringResource(R.string.stock_condition_warehouse_id, lot.warehouseId))
                    Text(stringResource(R.string.stock_condition_zone_id, lot.zoneId))
                    lot.catalogItemId?.let {
                        Text(stringResource(R.string.stock_condition_catalog_item_id, it))
                    }
                    lot.skuId?.let { Text(stringResource(R.string.stock_condition_sku_id, it)) }
                    Text(stringResource(R.string.stock_condition_batch, lot.batchNumber))
                    Text(stringResource(R.string.stock_condition_expiry, lot.expirationDate))
                    Text(stringResource(R.string.stock_condition_lot_status, lot.status))
                    QuantityValue(
                        stringResource(R.string.stock_condition_on_hand),
                        lot.onHand.toPlainString(),
                        lot.unit
                    )
                    QuantityValue(
                        stringResource(R.string.stock_condition_reserved),
                        lot.reserved.toPlainString(),
                        lot.unit
                    )
                    QuantityValue(
                        stringResource(R.string.stock_condition_physical_remaining_label),
                        lot.physicalRemaining.toPlainString(),
                        lot.unit
                    )
                    state.detailObservedAt?.let {
                        Text(
                            stringResource(R.string.stock_condition_observed_at, it.toString()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                StockConditionDetailStatus.NetworkUnavailable -> Text(
                    stringResource(R.string.stock_condition_detail_network)
                )

                StockConditionDetailStatus.ServiceUnavailable -> Text(
                    stringResource(R.string.stock_condition_detail_service)
                )

                StockConditionDetailStatus.PermissionDenied -> Text(
                    stringResource(R.string.stock_condition_permission_denied_body)
                )

                StockConditionDetailStatus.ContextInvalidated -> Text(
                    stringResource(R.string.stock_condition_context_body)
                )

                StockConditionDetailStatus.SessionInvalidated -> Text(
                    stringResource(R.string.stock_condition_session_body)
                )
            }
            AvailabilityPanel(state)
        }
    }
}

@Composable
private fun AvailabilityPanel(state: StockConditionUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.stock_condition_sku_availability_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        when (state.availabilityStatus) {
            StockConditionAvailabilityStatus.NotRequested -> Text(
                stringResource(R.string.stock_condition_availability_not_requested)
            )

            StockConditionAvailabilityStatus.Loading -> Text(
                stringResource(R.string.stock_condition_availability_loading)
            )

            StockConditionAvailabilityStatus.Current -> state.availability?.let { availability ->
                QuantityValue(
                    stringResource(R.string.stock_condition_sku_physical),
                    availability.physicalQuantity?.toPlainString()
                        ?: stringResource(R.string.stock_condition_value_unavailable),
                    ""
                )
                QuantityValue(
                    stringResource(R.string.stock_condition_sellable),
                    availability.sellableQuantity?.toPlainString()
                        ?: stringResource(R.string.stock_condition_value_unavailable),
                    ""
                )
                Text(
                    stringResource(
                        R.string.stock_condition_availability_status,
                        availability.status
                    )
                )
                Text(
                    stringResource(
                        R.string.stock_condition_server_as_of,
                        availability.asOf.toString()
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            } ?: Text(stringResource(R.string.stock_condition_value_unavailable))

            StockConditionAvailabilityStatus.Unavailable -> Text(
                stringResource(R.string.stock_condition_availability_missing)
            )

            StockConditionAvailabilityStatus.NetworkUnavailable -> Text(
                stringResource(R.string.stock_condition_availability_network)
            )

            StockConditionAvailabilityStatus.ServiceUnavailable -> Text(
                stringResource(R.string.stock_condition_availability_service)
            )

            StockConditionAvailabilityStatus.PermissionDenied -> Text(
                stringResource(R.string.stock_condition_permission_denied_body)
            )

            StockConditionAvailabilityStatus.ContextInvalidated -> Text(
                stringResource(R.string.stock_condition_context_body)
            )

            StockConditionAvailabilityStatus.SessionInvalidated -> Text(
                stringResource(R.string.stock_condition_session_body)
            )
        }
        Text(
            stringResource(R.string.stock_condition_availability_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuantityValue(label: String, value: String, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text("$value${if (unit.isBlank()) "" else " $unit"}", fontWeight = FontWeight.Medium)
    }
}
