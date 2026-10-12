package com.nexa.mobile.operations.notifications.presentation.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.notifications.application.inbox.NotificationPreference
import com.nexa.mobile.operations.notifications.presentation.R

@Composable
fun NotificationsScreen(
    state: NotificationsState,
    onBack: () -> Unit,
    onTabSelected: (NotificationsTab) -> Unit,
    onRefreshInbox: () -> Unit,
    onUnreadOnlyChanged: (Boolean) -> Unit,
    onMarkRead: (String, Boolean) -> Unit,
    onMarkAllRead: () -> Unit,
    onRefreshPreferences: () -> Unit,
    onPreferenceChanged: (NotificationPreference, Boolean) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        NexaTopAppBar(
            title = stringResource(R.string.notifications_title),
            onBack = onBack
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { onTabSelected(NotificationsTab.Inbox) }) {
                Text(stringResource(R.string.notifications_inbox_tab))
            }
            TextButton(onClick = { onTabSelected(NotificationsTab.Preferences) }) {
                Text(stringResource(R.string.notifications_preferences_tab))
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state.tab) {
                NotificationsTab.Inbox -> InboxContent(
                    state = state,
                    onRefresh = onRefreshInbox,
                    onUnreadOnlyChanged = onUnreadOnlyChanged,
                    onMarkRead = onMarkRead,
                    onMarkAllRead = onMarkAllRead
                )

                NotificationsTab.Preferences -> PreferencesContent(
                    state = state,
                    onRefresh = onRefreshPreferences,
                    onPreferenceChanged = onPreferenceChanged
                )
            }
        }
    }
}

@Composable
private fun InboxContent(
    state: NotificationsState,
    onRefresh: () -> Unit,
    onUnreadOnlyChanged: (Boolean) -> Unit,
    onMarkRead: (String, Boolean) -> Unit,
    onMarkAllRead: () -> Unit
) {
    Text(
        stringResource(R.string.notifications_inbox_heading),
        style = MaterialTheme.typography.headlineSmall
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onUnreadOnlyChanged(!state.unreadOnly) }) {
            Text(
                stringResource(
                    if (state.unreadOnly) {
                        R.string.notifications_show_all
                    } else {
                        R.string.notifications_unread_only
                    }
                )
            )
        }
        Button(
            onClick = onMarkAllRead,
            enabled = state.inboxStatus == NotificationRequestStatus.Ready &&
                (state.inbox?.unreadCount ?: 0) > 0 &&
                state.actionStatus != NotificationActionStatus.Loading
        ) {
            Text(stringResource(R.string.notifications_mark_all_read))
        }
    }
    Button(
        onClick = onRefresh,
        enabled = state.inboxStatus != NotificationRequestStatus.Loading
    ) {
        Text(stringResource(R.string.notifications_refresh))
    }
    RequestStatus(state.inboxStatus, preferences = false)
    ActionStatus(state.actionStatus)
    val inbox = state.inbox
    if (state.inboxStatus == NotificationRequestStatus.Ready && inbox?.items.isNullOrEmpty()) {
        Text(stringResource(R.string.notifications_empty))
    }
    inbox?.items.orEmpty().forEach { item ->
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(item.message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        R.string.notifications_category_and_time,
                        item.category,
                        item.createdAt
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { onMarkRead(item.id, item.readAt == null) },
                    enabled = state.actionStatus != NotificationActionStatus.Loading
                ) {
                    Text(
                        stringResource(
                            if (item.readAt == null) {
                                R.string.notifications_mark_read
                            } else {
                                R.string.notifications_mark_unread
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PreferencesContent(
    state: NotificationsState,
    onRefresh: () -> Unit,
    onPreferenceChanged: (NotificationPreference, Boolean) -> Unit
) {
    Text(
        stringResource(R.string.notifications_preferences_heading),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(stringResource(R.string.notifications_accepted_channels))
    if (!state.canManagePreferences) {
        Text(stringResource(R.string.notifications_preferences_read_only))
    }
    Button(
        onClick = onRefresh,
        enabled = state.preferencesStatus != NotificationRequestStatus.Loading
    ) {
        Text(stringResource(R.string.notifications_refresh))
    }
    RequestStatus(state.preferencesStatus, preferences = true)
    val visible = state.preferences?.preferences.orEmpty()
        .filter { it.channel == "IN_APP" || it.channel == "EMAIL" }
        .sortedWith(
            compareBy(NotificationPreference::eventCategory, NotificationPreference::channel)
        )
    if (state.preferencesStatus == NotificationRequestStatus.Ready && visible.isEmpty()) {
        Text(stringResource(R.string.notifications_preferences_empty))
    }
    visible.forEach { preference ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(preference.eventCategory, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            if (preference.channel == "EMAIL") {
                                R.string.notifications_email_channel
                            } else {
                                R.string.notifications_in_app_channel
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = preference.enabled,
                    onCheckedChange = { enabled -> onPreferenceChanged(preference, enabled) },
                    enabled = state.canManagePreferences &&
                        state.preferencesStatus == NotificationRequestStatus.Ready &&
                        state.actionStatus != NotificationActionStatus.Loading
                )
            }
        }
    }
    ActionStatus(state.actionStatus)
}

@Composable
private fun RequestStatus(status: NotificationRequestStatus, preferences: Boolean) {
    val text = when (status) {
        NotificationRequestStatus.Idle -> null

        NotificationRequestStatus.Loading -> R.string.notifications_loading

        NotificationRequestStatus.Ready -> null

        NotificationRequestStatus.Denied -> R.string.notifications_denied

        NotificationRequestStatus.Conflict -> R.string.notifications_conflict

        NotificationRequestStatus.Unavailable -> if (preferences) {
            R.string.notifications_preferences_unavailable
        } else {
            R.string.notifications_inbox_unavailable
        }
    }
    if (text != null) Text(stringResource(text))
}

@Composable
private fun ActionStatus(status: NotificationActionStatus) {
    val text = when (status) {
        NotificationActionStatus.Idle -> null
        NotificationActionStatus.Loading -> R.string.notifications_action_loading
        NotificationActionStatus.Success -> R.string.notifications_action_success
        NotificationActionStatus.Denied -> R.string.notifications_denied
        NotificationActionStatus.Conflict -> R.string.notifications_conflict
        NotificationActionStatus.Unavailable -> R.string.notifications_action_unavailable
    }
    if (text != null) Text(stringResource(text))
}
