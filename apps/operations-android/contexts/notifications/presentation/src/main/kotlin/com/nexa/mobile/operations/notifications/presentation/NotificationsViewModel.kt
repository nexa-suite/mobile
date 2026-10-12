package com.nexa.mobile.operations.notifications.presentation.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.notifications.application.inbox.NotificationInbox
import com.nexa.mobile.operations.notifications.application.inbox.NotificationInboxItem
import com.nexa.mobile.operations.notifications.application.inbox.NotificationPreference
import com.nexa.mobile.operations.notifications.application.inbox.NotificationPreferences
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsAuthority
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsGateway
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NotificationsTab { Inbox, Preferences }

enum class NotificationRequestStatus { Idle, Loading, Ready, Denied, Conflict, Unavailable }

enum class NotificationActionStatus { Idle, Loading, Success, Denied, Conflict, Unavailable }

data class NotificationsState(
    val tab: NotificationsTab = NotificationsTab.Inbox,
    val unreadOnly: Boolean = false,
    val inbox: NotificationInbox? = null,
    val inboxStatus: NotificationRequestStatus = NotificationRequestStatus.Idle,
    val preferences: NotificationPreferences? = null,
    val preferencesStatus: NotificationRequestStatus = NotificationRequestStatus.Idle,
    val canManagePreferences: Boolean = false,
    val actionStatus: NotificationActionStatus = NotificationActionStatus.Idle
)

class NotificationsViewModel(private val gateway: NotificationsGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(NotificationsState())
    val state = mutableState.asStateFlow()

    private var authority: NotificationsAuthority? = null
    private var scopeGeneration = 0L
    private var inboxGeneration = 0L
    private var preferencesGeneration = 0L
    private var actionGeneration = 0L

    fun activate(value: NotificationsAuthority) {
        deactivate()
        authority = value.copy(permissions = value.permissions.toSet())
        mutableState.value = NotificationsState(
            canManagePreferences = MANAGE_PERMISSION in value.permissions
        )
        refreshInbox()
    }

    fun deactivate() {
        scopeGeneration++
        inboxGeneration++
        preferencesGeneration++
        actionGeneration++
        authority = null
        mutableState.value = NotificationsState()
    }

    fun selectTab(tab: NotificationsTab) {
        mutableState.value = mutableState.value.copy(tab = tab)
        if (tab == NotificationsTab.Preferences &&
            mutableState.value.preferencesStatus == NotificationRequestStatus.Idle
        ) {
            refreshPreferences()
        }
    }

    fun setUnreadOnly(value: Boolean) {
        if (mutableState.value.unreadOnly == value) return
        mutableState.value = mutableState.value.copy(unreadOnly = value, inbox = null)
        refreshInbox()
    }

    fun refreshInbox() {
        val scope = authority ?: return
        if (READ_PERMISSION !in scope.permissions) {
            denyCurrentScope()
            return
        }
        val scopeToken = scopeGeneration
        val requestToken = ++inboxGeneration
        val unreadOnly = mutableState.value.unreadOnly
        mutableState.value =
            mutableState.value.copy(inboxStatus = NotificationRequestStatus.Loading)
        viewModelScope.launch {
            val result = try {
                gateway.inbox(scope, unreadOnly)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NotificationsResult.Unavailable
            }
            if (!isCurrent(scope, scopeToken) || inboxGeneration != requestToken) return@launch
            mutableState.value = when (result) {
                is NotificationsResult.Success -> mutableState.value.copy(
                    inbox = result.value,
                    inboxStatus = NotificationRequestStatus.Ready
                )

                NotificationsResult.PermissionDenied -> {
                    denyCurrentScope()
                    mutableState.value
                }

                NotificationsResult.Conflict -> mutableState.value.copy(
                    inbox = null,
                    inboxStatus = NotificationRequestStatus.Conflict
                )

                NotificationsResult.Unavailable -> mutableState.value.copy(
                    inbox = null,
                    inboxStatus = NotificationRequestStatus.Unavailable
                )
            }
        }
    }

    fun refreshPreferences() {
        val scope = authority ?: return
        if (READ_PERMISSION !in scope.permissions) {
            denyCurrentScope()
            return
        }
        val scopeToken = scopeGeneration
        val requestToken = ++preferencesGeneration
        mutableState.value = mutableState.value.copy(
            preferencesStatus = NotificationRequestStatus.Loading
        )
        viewModelScope.launch {
            val result = try {
                gateway.preferences(scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NotificationsResult.Unavailable
            }
            if (!isCurrent(scope, scopeToken) ||
                preferencesGeneration != requestToken
            ) {
                return@launch
            }
            mutableState.value = when (result) {
                is NotificationsResult.Success -> mutableState.value.copy(
                    preferences = result.value,
                    preferencesStatus = NotificationRequestStatus.Ready
                )

                NotificationsResult.PermissionDenied -> {
                    denyCurrentScope()
                    mutableState.value
                }

                NotificationsResult.Conflict -> mutableState.value.copy(
                    preferences = null,
                    preferencesStatus = NotificationRequestStatus.Conflict
                )

                NotificationsResult.Unavailable -> mutableState.value.copy(
                    preferences = null,
                    preferencesStatus = NotificationRequestStatus.Unavailable
                )
            }
        }
    }

    fun setRead(item: NotificationInboxItem, read: Boolean) {
        if (mutableState.value.inbox?.items?.any { it.id == item.id } != true) return
        performAction { gateway.setRead(it, item.id, read) }
    }

    fun markAllRead() {
        if (mutableState.value.inbox?.unreadCount?.let { it > 0 } != true) return
        performAction { gateway.markAllRead(it) }
    }

    fun setPreference(preference: NotificationPreference, enabled: Boolean) {
        val scope = authority ?: return
        val current = mutableState.value.preferences ?: return
        if (!mutableState.value.canManagePreferences ||
            preference.channel !in ACCEPTED_CHANNELS
        ) {
            return
        }
        val accepted = current.preferences.filter { it.channel in ACCEPTED_CHANNELS }
        if (accepted.none {
                it.eventCategory == preference.eventCategory && it.channel == preference.channel
            }
        ) {
            return
        }
        val updated = current.copy(
            preferences = accepted.map {
                if (it.eventCategory == preference.eventCategory &&
                    it.channel == preference.channel
                ) {
                    it.copy(enabled = enabled)
                } else {
                    it
                }
            }
        )
        val scopeToken = scopeGeneration
        val requestToken = ++preferencesGeneration
        val actionToken = ++actionGeneration
        mutableState.value = mutableState.value.copy(
            preferencesStatus = NotificationRequestStatus.Loading,
            actionStatus = NotificationActionStatus.Loading
        )
        viewModelScope.launch {
            val result = try {
                gateway.updatePreferences(scope, updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NotificationsResult.Unavailable
            }
            if (!isCurrent(scope, scopeToken) ||
                preferencesGeneration != requestToken || actionGeneration != actionToken
            ) {
                return@launch
            }
            when (result) {
                is NotificationsResult.Success -> mutableState.value = mutableState.value.copy(
                    preferences = result.value,
                    preferencesStatus = NotificationRequestStatus.Ready,
                    actionStatus = NotificationActionStatus.Success
                )

                NotificationsResult.PermissionDenied -> denyCurrentScope()

                NotificationsResult.Conflict -> {
                    mutableState.value = mutableState.value.copy(
                        preferences = null,
                        preferencesStatus = NotificationRequestStatus.Conflict,
                        actionStatus = NotificationActionStatus.Conflict
                    )
                    refreshPreferences()
                }

                NotificationsResult.Unavailable -> mutableState.value = mutableState.value.copy(
                    preferencesStatus = NotificationRequestStatus.Unavailable,
                    actionStatus = NotificationActionStatus.Unavailable
                )
            }
        }
    }

    private fun performAction(
        action: suspend (NotificationsAuthority) -> NotificationsResult<Unit>
    ) {
        val scope = authority ?: return
        val scopeToken = scopeGeneration
        val actionToken = ++actionGeneration
        mutableState.value =
            mutableState.value.copy(actionStatus = NotificationActionStatus.Loading)
        viewModelScope.launch {
            val result = try {
                action(scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NotificationsResult.Unavailable
            }
            if (!isCurrent(scope, scopeToken) || actionGeneration != actionToken) return@launch
            when (result) {
                is NotificationsResult.Success -> {
                    mutableState.value = mutableState.value.copy(
                        actionStatus = NotificationActionStatus.Success
                    )
                    refreshInbox()
                }

                NotificationsResult.PermissionDenied -> denyCurrentScope()

                NotificationsResult.Conflict -> mutableState.value = mutableState.value.copy(
                    actionStatus = NotificationActionStatus.Conflict
                )

                NotificationsResult.Unavailable -> mutableState.value = mutableState.value.copy(
                    actionStatus = NotificationActionStatus.Unavailable
                )
            }
        }
    }

    private fun isCurrent(scope: NotificationsAuthority, token: Long): Boolean =
        authority == scope && scopeGeneration == token

    private fun denyCurrentScope() {
        scopeGeneration++
        inboxGeneration++
        preferencesGeneration++
        actionGeneration++
        authority = null
        mutableState.value = deniedState()
    }

    private fun deniedState(): NotificationsState = NotificationsState(
        inboxStatus = NotificationRequestStatus.Denied,
        preferencesStatus = NotificationRequestStatus.Denied,
        actionStatus = NotificationActionStatus.Denied
    )

    private companion object {
        const val READ_PERMISSION = "notification.read"
        const val MANAGE_PERMISSION = "notification.manage_preferences"
        val ACCEPTED_CHANNELS = setOf("IN_APP", "EMAIL")
    }
}
