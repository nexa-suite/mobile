package com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaCatalogImage
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.core.designsystem.NexaPrimaryButton
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaStatePanel
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.NexaWorkSection
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingOffer
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.FulfillmentPickingLine
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyph
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyphKind
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun PickingScreen(
    state: PickingUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onSelectOffer: (String) -> Unit,
    onLotIdentifierChanged: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onStartPicking: () -> Unit,
    onConfirmPick: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onRetryIntentCleanup: () -> Unit,
    onProposeLotSubstitution: ((String) -> Unit)? = null
) {
    Surface(modifier = Modifier.fillMaxSize(), color = NexaColors.Canvas) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            NexaTopAppBar(title = stringResource(R.string.picking_visual_title), onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.loadStatus == PickingLoadStatus.ContextInvalidated ||
                    state.loadStatus == PickingLoadStatus.SessionInvalidated
                ) {
                    PickingLoadFeedback(state = state, onReload = onReload)
                } else if (!state.canRead) {
                    NexaStatePanel(
                        title = stringResource(R.string.picking_visual_no_access_title),
                        description = stringResource(R.string.picking_visual_no_access_body)
                    )
                } else {
                    state.fulfillment?.let { fulfillment ->
                        PickingProgressCard(
                            status = fulfillment.status,
                            lines = fulfillment.lines
                        )
                    }

                    if (!state.canPick && state.fulfillment != null) {
                        NexaFeedbackBanner(
                            message = stringResource(R.string.picking_visual_read_only),
                            tone = NexaFeedbackTone.Information
                        )
                    }

                    PickingLoadFeedback(state = state, onReload = onReload)

                    if (state.metadata == PickingMetadataStatus.Unavailable && state.canPick &&
                        state.notice != PickingNotice.IntentMetadataUnavailable
                    ) {
                        NexaFeedbackBanner(
                            message = stringResource(R.string.picking_visual_metadata_unavailable),
                            tone = NexaFeedbackTone.Error
                        )
                    }

                    state.notice?.let { notice ->
                        NexaFeedbackBanner(
                            message = notice.visualLabel(),
                            tone = if (notice == PickingNotice.NetworkUnavailable ||
                                notice == PickingNotice.ServiceUnavailable ||
                                notice == PickingNotice.IntentMetadataUnavailable
                            ) {
                                NexaFeedbackTone.Error
                            } else {
                                NexaFeedbackTone.Warning
                            }
                        )
                    }

                    state.fulfillment?.let { fulfillment ->
                        when {
                            state.loadStatus == PickingLoadStatus.Ready &&
                                state.allocation != null -> {
                                NexaWorkSection(
                                    title = stringResource(R.string.picking_visual_items_title)
                                ) {
                                    if (state.offers.isEmpty()) {
                                        Text(
                                            stringResource(R.string.picking_visual_no_items),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = NexaColors.TextSecondary
                                        )
                                    }
                                    state.offers.groupBy { offer ->
                                        offer.fulfillmentLine?.id
                                            ?: offer.allocationLine.physicalAllocationLineId
                                    }.values.forEach { offers ->
                                        PickingProductCard(
                                            offers = offers,
                                            selectedAllocationLineId =
                                                state.selectedAllocationLineId,
                                            state = state,
                                            onSelectOffer = onSelectOffer,
                                            onLotIdentifierChanged = onLotIdentifierChanged,
                                            onQuantityChanged = onQuantityChanged,
                                            onConfirmPick = onConfirmPick,
                                            onReload = onReload,
                                            onProposeLotSubstitution = onProposeLotSubstitution
                                        )
                                    }
                                }
                            }

                            state.loadStatus == PickingLoadStatus.Ready &&
                                state.allocation == null &&
                                state.command == PickingCommandStatus.Editing -> {
                                NexaStatePanel(
                                    title = stringResource(
                                        R.string.picking_visual_allocation_missing_title
                                    ),
                                    description = stringResource(
                                        R.string.picking_visual_allocation_missing_body
                                    ),
                                    actionLabel = stringResource(R.string.picking_visual_refresh),
                                    onAction = onReload
                                )
                            }
                        }

                        if (state.command == PickingCommandStatus.Editing &&
                            fulfillment.status.equals("ALLOCATED", ignoreCase = true)
                        ) {
                            NexaPrimaryButton(
                                label = stringResource(R.string.picking_visual_start),
                                onClick = onStartPicking,
                                enabled = canEdit(state) && state.canPick &&
                                    state.metadata == PickingMetadataStatus.Available
                            )
                        } else if (state.command == PickingCommandStatus.Editing &&
                            fulfillment.status.equals("READY_FOR_DISPATCH", ignoreCase = true)
                        ) {
                            NexaFeedbackBanner(
                                message = stringResource(
                                    R.string.picking_visual_ready_for_dispatch
                                ),
                                tone = NexaFeedbackTone.Success
                            )
                        }

                        if (state.command == PickingCommandStatus.Editing &&
                            state.loadStatus != PickingLoadStatus.Loading
                        ) {
                            TextButton(
                                onClick = onReload,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.picking_visual_refresh))
                            }
                        }
                    }

                    PickingCommandFeedback(
                        state = state,
                        onReload = onReload,
                        onRetryUnknownOutcome = onRetryUnknownOutcome,
                        onRetryIntentCleanup = onRetryIntentCleanup
                    )
                }
            }
        }
    }
}

@Composable
private fun PickingProgressCard(status: String, lines: List<FulfillmentPickingLine>) {
    NexaWorkSection(title = stringResource(R.string.picking_visual_progress_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OperationsGlyph(OperationsGlyphKind.Picking)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.picking_visual_status_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = NexaColors.TextSecondary
                )
                Text(
                    status.visualLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    color = NexaColors.TextPrimary
                )
            }
        }
        Text(
            pluralStringResource(R.plurals.picking_visual_line_count, lines.size, lines.size),
            style = MaterialTheme.typography.bodySmall,
            color = NexaColors.TextSecondary
        )
    }
}

@Composable
private fun PickingProductCard(
    offers: List<PickingOffer>,
    selectedAllocationLineId: String?,
    state: PickingUiState,
    onSelectOffer: (String) -> Unit,
    onLotIdentifierChanged: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onConfirmPick: () -> Unit,
    onReload: () -> Unit,
    onProposeLotSubstitution: ((String) -> Unit)?
) {
    val firstOffer = offers.firstOrNull() ?: return
    val line = firstOffer.fulfillmentLine
    val allocationLine = firstOffer.allocationLine
    val productName = allocationLine.productName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.picking_visual_product_fallback)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaShapes.card,
        colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
        border = BorderStroke(1.dp, NexaColors.Border)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(NexaColors.SurfaceInset, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    OperationsGlyph(OperationsGlyphKind.Picking)
                    NexaCatalogImage(
                        fileName = allocationLine.imageFileName,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = productName
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        productName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NexaColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    listOfNotNull(
                        allocationLine.brandName?.takeIf(String::isNotBlank),
                        allocationLine.presentation?.takeIf(String::isNotBlank)
                    ).takeIf { it.isNotEmpty() }?.let { attributes ->
                        Text(
                            attributes.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = NexaColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            line?.let { fulfillmentLine ->
                PickingQuantitySummary(fulfillmentLine)
            }

            offers.forEach { offer ->
                PickingBatchOption(
                    offer = offer,
                    selected = offer.allocationLine.physicalAllocationLineId ==
                        selectedAllocationLineId,
                    state = state,
                    onSelect = {
                        onSelectOffer(offer.allocationLine.physicalAllocationLineId)
                        onQuantityChanged(
                            offer.allocationLine.remainingQuantity.toDisplayQuantity()
                        )
                    },
                    onLotIdentifierChanged = onLotIdentifierChanged,
                    onQuantityChanged = onQuantityChanged,
                    onConfirmPick = onConfirmPick,
                    onReload = onReload,
                    onProposeLotSubstitution = onProposeLotSubstitution
                )
            }
        }
    }
}

@Composable
private fun PickingBatchOption(
    offer: PickingOffer,
    selected: Boolean,
    state: PickingUiState,
    onSelect: () -> Unit,
    onLotIdentifierChanged: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onConfirmPick: () -> Unit,
    onReload: () -> Unit,
    onProposeLotSubstitution: ((String) -> Unit)?
) {
    val line = offer.fulfillmentLine
    val allocationLine = offer.allocationLine
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val batchLabel = allocationLine.batchNumber?.takeIf(String::isNotBlank)
    val location = listOfNotNull(
        allocationLine.warehouseName?.takeIf(String::isNotBlank),
        allocationLine.zoneName?.takeIf(String::isNotBlank)
    ).joinToString(" · ").ifBlank { stringResource(R.string.picking_visual_location_assigned) }
    val canSelect = canEdit(state) && state.canPick &&
        state.loadStatus == PickingLoadStatus.Ready &&
        state.fulfillment?.status.equals("PICKING", ignoreCase = true) &&
        state.allocation?.status.equals("ALLOCATED", ignoreCase = true) &&
        offer.isPickable && batchLabel != null

    var showDetails by remember(allocationLine.physicalAllocationLineId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaShapes.row,
        color = if (selected) NexaColors.InfoSurface else NexaColors.SurfaceInset,
        border = BorderStroke(1.dp, if (selected) NexaColors.InfoBorder else NexaColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canSelect || selected || showDetails) {
                PickingFact(
                    label = stringResource(R.string.picking_visual_batch),
                    value = batchLabel ?: stringResource(R.string.picking_visual_batch_assigned)
                )
                PickingFact(
                    label = stringResource(R.string.picking_visual_location),
                    value = location
                )
                PickingFact(
                    label = stringResource(R.string.picking_visual_reserved_in_batch),
                    value = allocationLine.remainingQuantity.toDisplayQuantity() + " " +
                        pickingUnitLabel(
                            allocationLine.unit,
                            allocationLine.remainingQuantity
                        )
                )
                allocationLine.expirationDate?.let { date ->
                    PickingFact(
                        label = stringResource(R.string.picking_visual_expiry),
                        value = date.format(
                            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                                .withLocale(locale)
                        )
                    )
                }
            }
            if (!canSelect && !selected) {
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(
                        stringResource(
                            if (showDetails) {
                                R.string.picking_visual_hide_batch_details
                            } else {
                                R.string.picking_visual_batch_details
                            }
                        )
                    )
                }
            }

            if (offer.ambiguousFulfillmentMatch || line == null) {
                NexaFeedbackBanner(
                    message = stringResource(R.string.picking_visual_line_unavailable),
                    tone = NexaFeedbackTone.Warning
                )
            } else if (selected && canEdit(state)) {
                if (batchLabel == null) {
                    NexaFeedbackBanner(
                        message = stringResource(R.string.picking_visual_batch_missing),
                        tone = NexaFeedbackTone.Warning
                    )
                    OutlinedButton(
                        onClick = onReload,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.picking_visual_refresh))
                    }
                } else {
                    OutlinedTextField(
                        value = state.lotIdentifierText,
                        onValueChange = onLotIdentifierChanged,
                        enabled = canEdit(state),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.picking_visual_batch_input)) },
                        supportingText = {
                            Text(stringResource(R.string.picking_visual_batch_support))
                        },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.quantityText,
                        onValueChange = onQuantityChanged,
                        enabled = canEdit(state),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.picking_visual_quantity_input)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    NexaPrimaryButton(
                        label = stringResource(R.string.picking_visual_confirm),
                        onClick = onConfirmPick,
                        enabled = canSelect
                    )
                }
            } else if (batchLabel == null && canEdit(state) &&
                state.fulfillment?.status.equals("PICKING", ignoreCase = true)
            ) {
                NexaFeedbackBanner(
                    message = stringResource(R.string.picking_visual_batch_missing),
                    tone = NexaFeedbackTone.Warning
                )
                OutlinedButton(
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.picking_visual_refresh))
                }
            }

            if (!selected && canSelect) {
                OutlinedButton(
                    onClick = onSelect,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = NexaColors.Surface,
                        contentColor = NexaColors.PrimaryStrong
                    )
                ) {
                    Text(stringResource(R.string.picking_visual_select_batch))
                }
            }

            if (onProposeLotSubstitution != null && canSelect) {
                TextButton(
                    onClick = {
                        onProposeLotSubstitution(allocationLine.physicalAllocationLineId)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.picking_visual_propose_substitution))
                }
            }
        }
    }
}

@Composable
private fun PickingQuantitySummary(line: FulfillmentPickingLine) {
    val denominator = line.allocatedQuantity
    val pickedWithinPlanned = line.pickedQuantity.signum() >= 0 &&
        line.pickedQuantity <= denominator
    val progress = if (!pickedWithinPlanned || denominator.signum() <= 0) {
        null
    } else {
        line.pickedQuantity.divide(denominator, 4, RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuantityValue(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.picking_visual_planned),
                value = line.allocatedQuantity.toDisplayQuantity(),
                unit = line.unit
            )
            QuantityValue(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.picking_visual_picked),
                value = line.pickedQuantity.toDisplayQuantity(),
                unit = line.unit
            )
        }
        progress?.let { value ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        R.string.picking_visual_picked_of_planned,
                        line.pickedQuantity.toDisplayQuantity(),
                        line.allocatedQuantity.toDisplayQuantity(),
                        pickingUnitLabel(line.unit, line.allocatedQuantity)
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )
                Text(
                    stringResource(R.string.picking_visual_progress_percent, (value * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = NexaColors.PrimaryStrong
                )
            }
            LinearProgressIndicator(
                progress = { value },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = NexaColors.PrimaryStrong,
                trackColor = NexaColors.SurfaceInset
            )
        }
    }
}

@Composable
private fun QuantityValue(modifier: Modifier, label: String, value: String, unit: String) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = NexaColors.TextSecondary)
        Text(
            "$value ${pickingUnitLabel(unit, value.toBigDecimalOrNull() ?: BigDecimal.ZERO)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = NexaColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PickingFact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.38f),
            style = MaterialTheme.typography.labelMedium,
            color = NexaColors.TextSecondary
        )
        Text(
            value,
            modifier = Modifier.weight(0.62f),
            style = MaterialTheme.typography.bodyMedium,
            color = NexaColors.TextPrimary
        )
    }
}

@Composable
private fun PickingLoadFeedback(state: PickingUiState, onReload: () -> Unit) {
    val panel = when (state.loadStatus) {
        PickingLoadStatus.Loading -> R.string.picking_visual_loading

        PickingLoadStatus.AllocationUnavailable -> R.string.picking_visual_allocation_missing_body

        PickingLoadStatus.NotFound -> R.string.picking_visual_not_found

        PickingLoadStatus.NetworkUnavailable -> R.string.picking_visual_network

        PickingLoadStatus.ServiceUnavailable -> R.string.picking_visual_service

        PickingLoadStatus.PermissionDenied -> R.string.picking_visual_no_access_body

        PickingLoadStatus.ContextInvalidated -> R.string.picking_visual_context_changed

        PickingLoadStatus.SessionInvalidated -> R.string.picking_visual_session_changed

        PickingLoadStatus.NotRequested,
        PickingLoadStatus.Ready -> null
    } ?: return
    val loading = state.loadStatus == PickingLoadStatus.Loading
    val canRefresh = state.loadStatus in setOf(
        PickingLoadStatus.AllocationUnavailable,
        PickingLoadStatus.NotFound,
        PickingLoadStatus.NetworkUnavailable,
        PickingLoadStatus.ServiceUnavailable
    )
    NexaStatePanel(
        title = stringResource(
            if (loading) {
                R.string.picking_visual_loading_title
            } else {
                R.string.picking_visual_unavailable_title
            }
        ),
        description = stringResource(panel),
        actionLabel = if (canRefresh) stringResource(R.string.picking_visual_refresh) else null,
        onAction = if (canRefresh) onReload else null
    )
}

@Composable
private fun PickingCommandFeedback(
    state: PickingUiState,
    onReload: () -> Unit,
    onRetryUnknownOutcome: () -> Unit,
    onRetryIntentCleanup: () -> Unit
) {
    when (state.command) {
        PickingCommandStatus.PersistingIntent,
        PickingCommandStatus.Pending -> NexaFeedbackBanner(
            message = stringResource(R.string.picking_visual_pending),
            tone = NexaFeedbackTone.Information
        )

        PickingCommandStatus.UnknownOutcome -> {
            val canRetry = state.canPick && state.metadata == PickingMetadataStatus.Available
            NexaStatePanel(
                title = stringResource(R.string.picking_visual_unknown_title),
                description = stringResource(R.string.picking_visual_unknown_body),
                actionLabel = if (canRetry) {
                    stringResource(R.string.picking_visual_retry_same)
                } else {
                    null
                },
                onAction = onRetryUnknownOutcome.takeIf { canRetry }
            )
        }

        PickingCommandStatus.Confirmed -> {
            NexaFeedbackBanner(
                message = stringResource(R.string.picking_visual_confirmed),
                tone = NexaFeedbackTone.Success
            )
            if (state.intentCleanupPending) {
                OutlinedButton(
                    onClick = onRetryIntentCleanup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.picking_visual_retry_cleanup))
                }
            } else {
                NexaPrimaryButton(
                    label = stringResource(R.string.picking_visual_refresh),
                    onClick = onReload
                )
            }
        }

        PickingCommandStatus.Rejected,
        PickingCommandStatus.StaleVersion -> {
            NexaFeedbackBanner(
                message = stringResource(
                    if (state.command == PickingCommandStatus.StaleVersion) {
                        R.string.picking_visual_stale
                    } else {
                        R.string.picking_visual_rejected
                    }
                ),
                tone = NexaFeedbackTone.Warning
            )
            if (state.intentCleanupPending) {
                OutlinedButton(
                    onClick = onRetryIntentCleanup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.picking_visual_retry_cleanup))
                }
            } else {
                OutlinedButton(
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.picking_visual_refresh))
                }
            }
        }

        PickingCommandStatus.Editing -> {
            state.validationError?.let { error ->
                NexaFeedbackBanner(
                    message = error.visualLabel(),
                    tone = NexaFeedbackTone.Warning
                )
            }
            if (state.rejectionCode != null) {
                NexaFeedbackBanner(
                    message = stringResource(R.string.picking_visual_rejected),
                    tone = NexaFeedbackTone.Warning
                )
            }
        }
    }
}

@Composable
private fun PickingValidationError.visualLabel(): String = stringResource(
    when (this) {
        PickingValidationError.FulfillmentUnavailable,
        PickingValidationError.AllocationUnavailable,
        PickingValidationError.AllocationNotReady,
        PickingValidationError.RefreshRequired -> R.string.picking_visual_refresh_required

        PickingValidationError.OfferRequired -> R.string.picking_visual_select_offer

        PickingValidationError.AmbiguousFulfillmentLine -> R.string.picking_visual_line_unavailable

        PickingValidationError.LotIdentifierRequired -> R.string.picking_visual_batch_required

        PickingValidationError.LotIdentifierMismatch -> R.string.picking_visual_batch_mismatch

        PickingValidationError.QuantityRequired -> R.string.picking_visual_quantity_required

        PickingValidationError.QuantityInvalid -> R.string.picking_visual_quantity_invalid

        PickingValidationError.QuantityMustBePositive -> R.string.picking_visual_quantity_positive

        PickingValidationError.QuantityExceedsRemaining -> R.string.picking_visual_quantity_exceeds

        PickingValidationError.MetadataUnavailable -> R.string.picking_visual_metadata_unavailable
    }
)

@Composable
private fun PickingNotice.visualLabel(): String = stringResource(
    when (this) {
        PickingNotice.NetworkUnavailable -> R.string.picking_visual_network
        PickingNotice.ServiceUnavailable -> R.string.picking_visual_service
        PickingNotice.PermissionDenied -> R.string.picking_visual_no_access_body
        PickingNotice.ContextInvalidated -> R.string.picking_visual_context_changed
        PickingNotice.SessionInvalidated -> R.string.picking_visual_session_changed
        PickingNotice.IntentMetadataUnavailable -> R.string.picking_visual_metadata_unavailable
        PickingNotice.RefreshRequired -> R.string.picking_visual_refresh_required
    }
)

@Composable
private fun String.visualLabel(): String = stringResource(
    when (uppercase(Locale.ROOT)) {
        "ALLOCATED" -> R.string.picking_visual_status_assigned
        "PICKING" -> R.string.picking_visual_status_picking
        "READY_FOR_DISPATCH" -> R.string.picking_visual_status_ready
        else -> R.string.picking_visual_status_current
    }
)

private fun BigDecimal.toDisplayQuantity(): String =
    stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

private fun canEdit(state: PickingUiState): Boolean =
    !state.isIntentFrozen && state.command == PickingCommandStatus.Editing &&
        state.metadata == PickingMetadataStatus.Available

@Composable
private fun pickingUnitLabel(unit: String, quantity: BigDecimal): String = when (unit.uppercase()) {
    "UNIT", "UNITS", "EA", "EACH" -> stringResource(
        if (quantity.compareTo(BigDecimal.ONE) == 0) {
            R.string.picking_visual_unit_one
        } else {
            R.string.picking_visual_unit_many
        }
    )

    "KG", "KILOGRAM" -> "kg"

    "G", "GRAM" -> "g"

    "L", "LITER" -> "L"

    "ML", "MILLILITER" -> "mL"

    else -> unit
}
