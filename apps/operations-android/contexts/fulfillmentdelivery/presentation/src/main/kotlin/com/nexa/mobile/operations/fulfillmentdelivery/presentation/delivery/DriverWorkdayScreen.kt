package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverLiveLocation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyph
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyphKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun DriverWorkdayScreen(
    state: DriverWorkdayUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onEnableLocation: () -> Unit,
    onDisableLocation: () -> Unit,
    onEnd: () -> Unit,
    onRefresh: () -> Unit,
    onRetryPending: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.driver_workday_back)) }
        Text(
            stringResource(R.string.driver_workday_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            stringResource(R.string.driver_workday_privacy_note),
            style = MaterialTheme.typography.bodyMedium
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    state.loading -> Text(stringResource(R.string.driver_workday_loading))

                    state.workday == null -> Text(stringResource(R.string.driver_workday_no_active))

                    state.workday.status == DriverWorkdayStatus.CLOSED -> Text(
                        stringResource(
                            R.string.driver_workday_closed,
                            (state.workday.endedAt ?: state.workday.startedAt).displayTime()
                        )
                    )

                    state.workday.locationAvailable && state.captureRequested &&
                        state.locationReady -> Text(
                        stringResource(
                            R.string.driver_workday_active_tracking,
                            state.workday.startedAt.displayTime()
                        )
                    )

                    state.workday.locationAvailable && state.captureRequested -> Text(
                        stringResource(
                            R.string.driver_workday_active_starting,
                            state.workday.startedAt.displayTime()
                        )
                    )

                    else -> Text(stringResource(R.string.driver_workday_active_without_location))
                }
                state.lastSampleAt?.let {
                    Text(stringResource(R.string.driver_workday_last_sample, it.displayTime()))
                }
            }
        }
        if (state.pendingCommand == null ||
            state.notice == DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
        ) {
            noticeText(state.notice)?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
            }
        }
        if (state.commandPending) {
            Text(
                stringResource(
                    if (state.notice == DriverWorkdayNotice.LOCATION_ACQUIRING) {
                        R.string.driver_workday_location_acquiring
                    } else {
                        R.string.driver_workday_command_pending
                    }
                )
            )
        }
        if (state.pendingCommand != null) {
            Text(stringResource(R.string.driver_workday_unknown_command_pending))
            Button(onClick = onRetryPending, enabled = !state.commandPending) {
                Text(stringResource(R.string.driver_workday_retry_pending))
            }
        }
        val actionEnabled =
            !state.loading && !state.commandPending && state.pendingCommand == null &&
                state.notice != DriverWorkdayNotice.END_PENDING_CONFIRMATION &&
                state.notice != DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
        if (state.workday == null || state.workday.status == DriverWorkdayStatus.CLOSED) {
            Button(onClick = onStart, enabled = actionEnabled && !state.loading) {
                Text(stringResource(R.string.driver_workday_start))
            }
        } else {
            if (state.workday.locationAvailable) {
                OutlinedButton(onClick = onDisableLocation, enabled = actionEnabled) {
                    Text(stringResource(R.string.driver_workday_disable_location))
                }
            } else {
                Button(onClick = onEnableLocation, enabled = actionEnabled) {
                    Text(stringResource(R.string.driver_workday_enable_location))
                }
            }
            Button(onClick = onEnd, enabled = actionEnabled) {
                Text(stringResource(R.string.driver_workday_end))
            }
        }
        OutlinedButton(onClick = onRefresh, enabled = !state.loading && !state.commandPending) {
            Text(stringResource(R.string.driver_workday_refresh))
        }
    }
}

@Composable
fun DriverWorkdayPanel(
    state: DriverWorkdayUiState,
    onStart: () -> Unit,
    onEnableLocation: () -> Unit,
    onDisableLocation: () -> Unit,
    onEnd: () -> Unit,
    onRefresh: () -> Unit,
    onRetryPending: () -> Unit,
    onShowMap: @Composable (DriverLiveLocation) -> Unit
) {
    var privacyExpanded by rememberSaveable { mutableStateOf(false) }
    val day = state.workday
    val activeWorkday = day != null && day.status != DriverWorkdayStatus.CLOSED
    val sharingConfirmed = activeWorkday && day?.locationAvailable == true &&
        state.captureRequested && state.locationReady
    val waitingForLocation = activeWorkday && day?.locationAvailable == true &&
        state.captureRequested && !state.locationReady
    val statusText = when {
        state.loading -> R.string.workday_visual_updating

        day == null -> R.string.workday_visual_no_workday

        day.status == DriverWorkdayStatus.CLOSED -> R.string.workday_visual_ended

        sharingConfirmed -> R.string.workday_visual_sharing

        waitingForLocation -> R.string.workday_visual_waiting

        state.notice == DriverWorkdayNotice.LOCATION_UNAVAILABLE ->
            R.string.workday_visual_unavailable

        else -> R.string.workday_visual_stopped
    }
    val statusColor = when {
        sharingConfirmed -> NexaColors.Success

        waitingForLocation -> NexaColors.Warning

        state.notice == DriverWorkdayNotice.LOCATION_UNAVAILABLE ->
            MaterialTheme.colorScheme.error

        else -> NexaColors.TextSecondary
    }
    val statusDetail = when {
        sharingConfirmed -> state.lastSampleAt?.humanDisplayTime()?.let {
            stringResource(R.string.workday_visual_last_accepted, it)
        }

        waitingForLocation -> stringResource(R.string.workday_visual_waiting_detail)

        day != null && day.status == DriverWorkdayStatus.CLOSED -> {
            (day.endedAt ?: day.startedAt).humanDisplayTime()?.let {
                stringResource(R.string.workday_visual_ended_at, it)
            }
        }

        activeWorkday && state.eligibleDeliveryId == null ->
            stringResource(R.string.workday_visual_select_delivery)

        activeWorkday -> stringResource(R.string.workday_visual_delivery_ready)

        else -> stringResource(R.string.workday_visual_start_hint)
    }
    val actionEnabled = !state.loading && !state.commandPending &&
        state.pendingCommand == null &&
        state.notice != DriverWorkdayNotice.END_PENDING_CONFIRMATION &&
        state.notice != DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
    val canStart = day == null || day.status == DriverWorkdayStatus.CLOSED

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.workday_visual_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = NexaColors.TextPrimary
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
            border = BorderStroke(1.dp, NexaColors.Border),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OperationsGlyph(
                        kind = OperationsGlyphKind.Driver,
                        modifier = Modifier.size(44.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(statusText),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                        statusDetail?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexaColors.TextSecondary
                            )
                        }
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val horizontalActions =
                        maxWidth >= 430.dp && LocalDensity.current.fontScale < 1.3f
                    if (canStart) {
                        Button(
                            onClick = onStart,
                            enabled = actionEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.workday_visual_start))
                        }
                    } else if (horizontalActions) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (day?.locationAvailable == true) {
                                OutlinedButton(
                                    onClick = onDisableLocation,
                                    enabled = actionEnabled,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.workday_visual_stop))
                                }
                            } else {
                                Button(
                                    onClick = onEnableLocation,
                                    enabled = actionEnabled && state.eligibleDeliveryId != null,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.workday_visual_share))
                                }
                            }
                            TextButton(
                                onClick = onEnd,
                                enabled = actionEnabled,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.workday_visual_end))
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (day?.locationAvailable == true) {
                                OutlinedButton(
                                    onClick = onDisableLocation,
                                    enabled = actionEnabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.workday_visual_stop))
                                }
                            } else {
                                Button(
                                    onClick = onEnableLocation,
                                    enabled = actionEnabled && state.eligibleDeliveryId != null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.workday_visual_share))
                                }
                            }
                            TextButton(
                                onClick = onEnd,
                                enabled = actionEnabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.workday_visual_end))
                            }
                        }
                    }
                }
            }
        }

        if (state.pendingCommand == null ||
            state.notice == DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
        ) {
            noticeText(state.notice)?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
            }
        }
        if (state.commandPending) {
            Text(
                stringResource(
                    if (state.notice == DriverWorkdayNotice.LOCATION_ACQUIRING) {
                        R.string.driver_workday_location_acquiring
                    } else {
                        R.string.driver_workday_command_pending
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = NexaColors.TextSecondary
            )
        }
        if (state.pendingCommand != null) {
            Text(
                stringResource(R.string.driver_workday_unknown_command_pending),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onRetryPending,
                enabled = !state.commandPending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.driver_workday_retry_pending))
            }
        }
        state.liveLocation?.let { location ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
                border = BorderStroke(1.dp, NexaColors.Border),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.workday_visual_map_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        onShowMap(location)
                    }
                }
            }
        }
        TextButton(
            onClick = onRefresh,
            enabled = !state.loading && !state.commandPending,
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.workday_visual_refresh))
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
            border = BorderStroke(1.dp, NexaColors.Border),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                TextButton(
                    onClick = { privacyExpanded = !privacyExpanded },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(
                        stringResource(
                            if (privacyExpanded) {
                                R.string.workday_visual_privacy_hide
                            } else {
                                R.string.workday_visual_privacy_show
                            }
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (privacyExpanded) "⌃" else "⌄")
                }
                if (privacyExpanded) {
                    Text(
                        stringResource(R.string.driver_workday_privacy_note),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaColors.TextSecondary
                    )
                }
            }
        }
    }
}

private fun noticeText(notice: DriverWorkdayNotice): Int? = when (notice) {
    DriverWorkdayNotice.NONE -> null

    DriverWorkdayNotice.LOCATION_PERMISSION_REQUIRED -> R.string.driver_workday_permission_required

    DriverWorkdayNotice.NOTIFICATION_PERMISSION_REQUIRED ->
        R.string.driver_workday_notification_permission_required

    DriverWorkdayNotice.ACTIVE_DELIVERY_REQUIRED -> R.string.driver_workday_active_delivery_required

    DriverWorkdayNotice.LOCATION_ACQUIRING -> null

    DriverWorkdayNotice.CURRENT_UNAVAILABLE -> R.string.driver_workday_current_unavailable

    DriverWorkdayNotice.START_UNKNOWN -> R.string.driver_workday_start_unknown

    DriverWorkdayNotice.START_REJECTED -> R.string.driver_workday_start_rejected

    DriverWorkdayNotice.END_PENDING_CONFIRMATION -> R.string.driver_workday_end_confirmation

    DriverWorkdayNotice.AVAILABILITY_UNKNOWN -> R.string.driver_workday_availability_unknown

    DriverWorkdayNotice.LOCATION_UNAVAILABLE -> R.string.driver_workday_location_unavailable

    DriverWorkdayNotice.STALE_WORKDAY -> R.string.driver_workday_stale

    DriverWorkdayNotice.PERMISSION_DENIED -> R.string.driver_workday_denied

    DriverWorkdayNotice.CONTEXT_INVALIDATED -> R.string.driver_workday_context

    DriverWorkdayNotice.SESSION_INVALIDATED -> R.string.driver_workday_session

    DriverWorkdayNotice.COMMAND_REJECTED -> R.string.driver_workday_command_rejected

    DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE -> R.string.driver_workday_storage_unavailable

    DriverWorkdayNotice.UNKNOWN_COMMAND_PENDING -> R.string.driver_workday_unknown_command_pending
}

private fun String.displayTime(): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(this))
}.getOrDefault(this)

private fun String.humanDisplayTime(): String? = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(this))
}.getOrNull()
