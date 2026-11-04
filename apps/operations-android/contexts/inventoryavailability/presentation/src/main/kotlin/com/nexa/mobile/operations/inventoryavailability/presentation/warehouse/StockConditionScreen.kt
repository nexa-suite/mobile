package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nexa.mobile.operations.core.designsystem.CatalogImageRegistry
import com.nexa.mobile.operations.core.designsystem.NexaCatalogImage
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as SharedR
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionLot
import com.nexa.mobile.operations.inventoryavailability.presentation.R
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

@Composable
private fun StockConditionAutoRefresh(state: StockConditionUiState, onRefresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true

                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> isResumed = false

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val latestState = rememberUpdatedState(state)
    val refreshAction = rememberUpdatedState(onRefresh)
    val authorityEpoch = state.authorityEpoch
    val permitted = state.status !in STOCK_CONDITION_REFRESH_BLOCKED_STATUSES
    val changeSignals = state.stockConditionChangeSignals
    LaunchedEffect(lifecycleOwner, isResumed, authorityEpoch, permitted, changeSignals) {
        if (!isResumed || !permitted || authorityEpoch <= 0) return@LaunchedEffect
        val pollingSignals = flow {
            while (true) {
                delay(STOCK_CONDITION_POLL_INTERVAL_MILLIS)
                emit(Unit)
            }
        }
        merge(changeSignals, pollingSignals).collect {
            val current = latestState.value
            if (current.authorityEpoch != authorityEpoch ||
                current.status in STOCK_CONDITION_REFRESH_BLOCKED_STATUSES
            ) {
                return@collect
            }
            refreshAction.value()
        }
    }
}

private const val STOCK_CONDITION_POLL_INTERVAL_MILLIS = 15_000L
private val STOCK_CONDITION_REFRESH_BLOCKED_STATUSES = setOf(
    StockConditionStatus.PermissionUnknown,
    StockConditionStatus.PermissionDenied,
    StockConditionStatus.ContextInvalidated,
    StockConditionStatus.SessionInvalidated
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockConditionScreen(
    state: StockConditionUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectLot: (String) -> Unit,
    onRouteClosed: () -> Unit,
    onDisposition: ((String) -> Unit)? = null,
    identifiedSkuId: String? = null,
    onReceiveStock: (() -> Unit)? = null
) {
    StockConditionAutoRefresh(state, onRefresh)
    var query by remember(state.authorityEpoch, identifiedSkuId) { mutableStateOf("") }
    var showLotDetail by remember(state.authorityEpoch) { mutableStateOf(false) }
    var detailLotId by remember(state.authorityEpoch) { mutableStateOf<String?>(null) }
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val baseLots = remember(state.lots, identifiedSkuId) {
        if (identifiedSkuId.isNullOrBlank()) {
            state.lots
        } else {
            state.lots.filter { it.skuId.equals(identifiedSkuId, ignoreCase = true) }
        }
    }
    val visibleLots = remember(baseLots, query) {
        val term = query.trim()
        if (term.isBlank()) {
            baseLots
        } else {
            baseLots.filter { lot ->
                lot.productName.orEmpty().contains(term, ignoreCase = true) ||
                    lot.skuCode.orEmpty().contains(term, ignoreCase = true) ||
                    lot.batchNumber.contains(term, ignoreCase = true)
            }
        }
    }
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) {
        onDispose { closeAction.value() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        verticalArrangement = Arrangement.Top
    ) {
        NexaTopAppBar(
            title = stringResource(R.string.inventory_visual_title),
            onBack = onBack,
            actions = {
                onReceiveStock?.let { receive ->
                    IconButton(onClick = receive, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painterResource(SharedR.drawable.ic_add),
                            contentDescription = stringResource(R.string.inventory_visual_receive),
                            tint = NexaColors.PrimaryStrong
                        )
                    }
                }
            }
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (maxWidth >= 840.dp && !largeText) 2 else 1
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.status != StockConditionStatus.Current) {
                    item { StockConditionStatusSummary(state.status) }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = onRefresh,
                            enabled = state.status != StockConditionStatus.Loading &&
                                state.status !in STOCK_CONDITION_REFRESH_BLOCKED_STATUSES,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = NexaShapes.button,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = NexaColors.Surface,
                                contentColor = NexaColors.PrimaryStrong
                            )
                        ) {
                            Icon(
                                painter = painterResource(SharedR.drawable.ic_sync),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.inventory_visual_refresh))
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        leadingIcon = {
                            Icon(
                                painter = painterResource(SharedR.drawable.ic_search),
                                contentDescription = null,
                                tint = NexaColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { Text(stringResource(R.string.inventory_visual_search)) }
                    )
                }
                if (!identifiedSkuId.isNullOrBlank()) {
                    item {
                        InventoryContextLabel(
                            text = stringResource(R.string.inventory_visual_scan_filter)
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.inventory_visual_lot_count, visibleLots.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NexaColors.TextPrimary
                    )
                }
                if (state.status == StockConditionStatus.Loading && state.lots.isEmpty()) {
                    item { InventoryLoadingRow() }
                }
                if (visibleLots.isNotEmpty()) {
                    items(
                        items = visibleLots.chunked(columns),
                        key = { row -> row.first().id.lowercase() }
                    ) { rowLots ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowLots.forEach { lot ->
                                LotChoiceCard(
                                    lot = lot,
                                    selected = state.selectedLotId.equals(
                                        lot.id,
                                        ignoreCase = true
                                    ),
                                    onClick = {
                                        detailLotId = lot.id
                                        showLotDetail = true
                                        onSelectLot(lot.id)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowLots.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                } else if (state.status in
                    setOf(StockConditionStatus.Current, StockConditionStatus.Empty)
                ) {
                    item {
                        InventoryEmptyState(
                            text = stringResource(
                                if (query.isBlank() && identifiedSkuId.isNullOrBlank()) {
                                    R.string.inventory_visual_empty
                                } else {
                                    R.string.inventory_visual_no_matches
                                }
                            )
                        )
                    }
                }
            }
        }
        if (showLotDetail) {
            ModalBottomSheet(
                onDismissRequest = { showLotDetail = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = NexaColors.Canvas
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SelectedLotPanel(state, detailLotId, largeText)
                    val confirmedLot = state.selectedLot?.takeIf { lot ->
                        state.selectedLotId.equals(detailLotId, ignoreCase = true) &&
                            lot.id.equals(detailLotId, ignoreCase = true) &&
                            state.detailStatus == StockConditionDetailStatus.Current
                    }
                    if (confirmedLot != null && onDisposition != null) {
                        OutlinedButton(
                            onClick = { onDisposition(confirmedLot.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.stock_condition_record_disposition))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StockConditionStatusSummary(status: StockConditionStatus) {
    val (label, background, foreground) = when (status) {
        StockConditionStatus.Initial -> Triple(
            R.string.inventory_visual_status_initial,
            NexaColors.InfoSurface,
            NexaColors.Info
        )

        StockConditionStatus.Loading -> Triple(
            R.string.inventory_visual_status_loading,
            NexaColors.InfoSurface,
            NexaColors.Info
        )

        StockConditionStatus.Current -> Triple(
            R.string.inventory_visual_status_current,
            NexaColors.SuccessSurface,
            NexaColors.Success
        )

        StockConditionStatus.Empty -> Triple(
            R.string.inventory_visual_status_empty,
            NexaColors.SurfaceInset,
            NexaColors.TextSecondary
        )

        StockConditionStatus.PermissionUnknown,
        StockConditionStatus.PermissionDenied -> Triple(
            R.string.inventory_visual_status_access,
            NexaColors.WarningSurface,
            NexaColors.Warning
        )

        StockConditionStatus.NetworkUnavailable -> Triple(
            R.string.inventory_visual_status_offline,
            NexaColors.WarningSurface,
            NexaColors.Warning
        )

        StockConditionStatus.ServiceUnavailable -> Triple(
            R.string.inventory_visual_status_service,
            NexaColors.DangerSurface,
            NexaColors.Danger
        )

        StockConditionStatus.ContextInvalidated -> Triple(
            R.string.inventory_visual_status_context,
            NexaColors.SurfaceInset,
            NexaColors.TextSecondary
        )

        StockConditionStatus.SessionInvalidated -> Triple(
            R.string.inventory_visual_status_session,
            NexaColors.SurfaceInset,
            NexaColors.TextSecondary
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = background,
        contentColor = foreground
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (status == StockConditionStatus.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = foreground
                )
            }
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InventoryLoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.inventory_visual_loading), color = NexaColors.TextSecondary)
    }
}

@Composable
private fun InventoryContextLabel(text: String) {
    Surface(
        shape = RoundedCornerShape(100),
        color = NexaColors.InfoSurface,
        contentColor = NexaColors.Info
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun InventoryEmptyState(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = NexaColors.CardSurface,
        border = null
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InventoryGlyph(InventoryGlyphKind.Product)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = NexaColors.TextSecondary
            )
        }
    }
}

@Composable
private fun LotChoiceCard(
    lot: StockConditionLot,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = NexaShapes.card,
        colors = CardDefaults.cardColors(containerColor = NexaColors.CardSurface),
        border = if (selected) BorderStroke(2.dp, NexaColors.PrimaryStrong) else null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProductPhoto(lot, Modifier.size(80.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    productName(lot),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NexaColors.TextPrimary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "${formatQuantity(
                            lot.physicalRemaining
                        )} ${unitLabel(lot.unit, lot.physicalRemaining)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NexaColors.TextPrimary
                    )
                    LotStatusBadge(lot.status)
                }
                Text(
                    stringResource(
                        R.string.inventory_visual_expiry,
                        formatDate(lot.expirationDate)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )
                lot.warehouseName?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductPhoto(lot: StockConditionLot, modifier: Modifier) {
    if (CatalogImageRegistry.resolve(lot.imageFileName) != null) {
        NexaCatalogImage(
            fileName = lot.imageFileName,
            modifier = modifier,
            targetSize = 200.dp,
            contentDescription = productName(lot)
        )
    } else {
        InventoryGlyph(InventoryGlyphKind.Product)
    }
}

@Composable
private fun SelectedLotPanel(
    state: StockConditionUiState,
    requestedLotId: String?,
    largeText: Boolean
) {
    val selectionMatchesRequest = !requestedLotId.isNullOrBlank() &&
        state.selectedLotId.equals(requestedLotId, ignoreCase = true)
    val lot = state.selectedLot?.takeIf {
        selectionMatchesRequest && it.id.equals(requestedLotId, ignoreCase = true)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NexaColors.CardSurface),
        border = null,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.inventory_visual_detail_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (lot != null && state.detailStatus == StockConditionDetailStatus.Current) {
                ProductPhoto(lot, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 200.dp))
            }
            when {
                state.detailStatus == StockConditionDetailStatus.NetworkUnavailable -> Text(
                    stringResource(R.string.inventory_visual_status_offline),
                    color = NexaColors.Warning
                )

                state.detailStatus == StockConditionDetailStatus.ServiceUnavailable -> Text(
                    stringResource(R.string.inventory_visual_status_service),
                    color = NexaColors.Danger
                )

                state.detailStatus == StockConditionDetailStatus.PermissionDenied -> Text(
                    stringResource(R.string.inventory_visual_status_access),
                    color = NexaColors.Warning
                )

                state.detailStatus == StockConditionDetailStatus.ContextInvalidated -> Text(
                    stringResource(R.string.inventory_visual_status_context),
                    color = NexaColors.TextSecondary
                )

                state.detailStatus == StockConditionDetailStatus.SessionInvalidated -> Text(
                    stringResource(R.string.inventory_visual_status_session),
                    color = NexaColors.TextSecondary
                )

                lot != null && state.detailStatus == StockConditionDetailStatus.Current ->
                    LotDetailContent(lot, largeText)

                !selectionMatchesRequest -> Text(
                    stringResource(R.string.inventory_visual_detail_loading),
                    color = NexaColors.TextSecondary
                )

                state.detailStatus == StockConditionDetailStatus.Loading ||
                    state.detailStatus == StockConditionDetailStatus.NotRequested -> Text(
                    stringResource(R.string.inventory_visual_detail_loading),
                    color = NexaColors.TextSecondary
                )

                else -> Text(
                    stringResource(R.string.inventory_visual_detail_missing),
                    color = NexaColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun LotDetailContent(lot: StockConditionLot, largeText: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        InventoryGlyph(InventoryGlyphKind.Product)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                productName(lot),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NexaColors.TextPrimary
            )
            lot.skuCode?.takeIf(String::isNotBlank)?.let { code ->
                Text(
                    stringResource(R.string.inventory_visual_sku, code),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )
            }
            Text(
                stringResource(R.string.inventory_visual_batch_value, lot.batchNumber),
                style = MaterialTheme.typography.bodySmall,
                color = NexaColors.TextSecondary
            )
        }
        LotStatusBadge(lot.status)
    }
    HorizontalDivider(color = NexaColors.Border)
    val quantities = listOf(
        stringResource(R.string.inventory_visual_stock) to lot.onHand,
        stringResource(R.string.inventory_visual_reserved) to lot.reserved,
        stringResource(R.string.inventory_visual_remaining) to lot.physicalRemaining
    )
    if (largeText) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quantities.forEach { (label, value) ->
                QuantityTile(label, value, lot.unit, modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quantities.forEach { (label, value) ->
                QuantityTile(label, value, lot.unit, modifier = Modifier.weight(1f))
            }
        }
    }
    HorizontalDivider(color = NexaColors.Border)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(locationLabel(lot), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.inventory_visual_expiry, formatDate(lot.expirationDate)),
            style = MaterialTheme.typography.bodyMedium,
            color = NexaColors.TextSecondary
        )
    }
}

@Composable
private fun QuantityTile(
    label: String,
    value: BigDecimal,
    unit: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 82.dp),
        shape = RoundedCornerShape(14.dp),
        color = NexaColors.SurfaceInset
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = NexaColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${formatQuantity(value)} ${unitLabel(unit, value)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = NexaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LotStatusBadge(status: String) {
    val (label, background, foreground, border) = when (status.trim().uppercase(Locale.ROOT)) {
        "AVAILABLE" -> StatusBadgeStyle(
            R.string.inventory_visual_lot_available,
            NexaColors.SuccessSurface,
            NexaColors.Success,
            NexaColors.SuccessBorder
        )

        "HOLD" -> StatusBadgeStyle(
            R.string.inventory_visual_lot_held,
            NexaColors.WarningSurface,
            NexaColors.Warning,
            NexaColors.WarningBorder
        )

        "QUARANTINED" -> StatusBadgeStyle(
            R.string.inventory_visual_lot_quarantined,
            NexaColors.WarningSurface,
            NexaColors.Warning,
            NexaColors.WarningBorder
        )

        "EXPIRED" -> StatusBadgeStyle(
            R.string.inventory_visual_lot_expired,
            NexaColors.DangerSurface,
            NexaColors.Danger,
            NexaColors.DangerBorder
        )

        "DEPLETED" -> StatusBadgeStyle(
            R.string.inventory_visual_lot_depleted,
            NexaColors.SurfaceInset,
            NexaColors.TextSecondary,
            NexaColors.Border
        )

        "BLOCKED" -> StatusBadgeStyle(
            R.string.inventory_visual_lot_blocked,
            NexaColors.DangerSurface,
            NexaColors.Danger,
            NexaColors.DangerBorder
        )

        else -> StatusBadgeStyle(
            R.string.inventory_visual_lot_other,
            NexaColors.InfoSurface,
            NexaColors.Info,
            NexaColors.InfoBorder
        )
    }
    Surface(
        shape = RoundedCornerShape(100),
        color = background,
        contentColor = foreground,
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            stringResource(label),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

private data class StatusBadgeStyle(
    val label: Int,
    val background: androidx.compose.ui.graphics.Color,
    val foreground: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color
)

@Composable
private fun productName(lot: StockConditionLot): String =
    lot.productName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.inventory_visual_product_unknown)

@Composable
private fun locationLabel(lot: StockConditionLot): String {
    val warehouse = lot.warehouseName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.inventory_visual_warehouse_unknown)
    val zone = lot.zoneName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.inventory_visual_zone_unknown)
    return stringResource(R.string.inventory_visual_location, warehouse, zone)
}

@Composable
private fun formatDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    return remember(date, locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
    }
}

@Composable
private fun unitLabel(unit: String, quantity: BigDecimal): String {
    val singular = quantity.compareTo(BigDecimal.ONE) == 0
    return when (unit.trim().uppercase(Locale.ROOT)) {
        "EA", "EACH", "UNIT", "UNITS" -> stringResource(
            if (singular) {
                R.string.inventory_visual_unit_each_one
            } else {
                R.string.inventory_visual_unit_each_many
            }
        )

        "KG", "KILOGRAM", "KILOGRAMS" -> stringResource(R.string.inventory_visual_unit_kg)

        "G", "GRAM", "GRAMS" -> stringResource(R.string.inventory_visual_unit_g)

        "L", "LITER", "LITRE", "LITERS", "LITRES" ->
            stringResource(R.string.inventory_visual_unit_l)

        "ML", "MILLILITER", "MILLILITRE" -> stringResource(R.string.inventory_visual_unit_ml)

        "CASE", "CASES" -> stringResource(
            if (singular) {
                R.string.inventory_visual_unit_case_one
            } else {
                R.string.inventory_visual_unit_case_many
            }
        )

        "BOX", "BOXES" -> stringResource(
            if (singular) {
                R.string.inventory_visual_unit_box_one
            } else {
                R.string.inventory_visual_unit_box_many
            }
        )

        else -> unit
    }
}

private fun formatQuantity(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
