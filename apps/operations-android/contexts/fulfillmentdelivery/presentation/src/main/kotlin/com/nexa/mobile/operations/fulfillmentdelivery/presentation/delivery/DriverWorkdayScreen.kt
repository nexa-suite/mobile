package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DriverWorkdayScreen(
    state: DriverWorkdayUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onEnableLocation: () -> Unit,
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

                    state.workday.locationAvailable && state.captureRequested -> Text(
                        stringResource(
                            R.string.driver_workday_active_tracking,
                            state.workday.startedAt.displayTime()
                        )
                    )

                    state.workday.locationAvailable -> Text(
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
        if (state.commandPending) Text(stringResource(R.string.driver_workday_command_pending))
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
            if (state.workday.status == DriverWorkdayStatus.LOCATION_UNAVAILABLE ||
                !state.workday.locationAvailable
            ) {
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

private fun noticeText(notice: DriverWorkdayNotice): Int? = when (notice) {
    DriverWorkdayNotice.NONE -> null
    DriverWorkdayNotice.LOCATION_PERMISSION_REQUIRED -> R.string.driver_workday_permission_required
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
