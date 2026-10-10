package com.nexa.mobile.operations.notifications.application.inbox

data class NotificationsAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    override fun toString(): String = "NotificationsAuthority(REDACTED)"
}

data class NotificationInboxItem(
    val id: String,
    val category: String,
    val title: String,
    val message: String,
    val subjectType: String?,
    val subjectId: String?,
    val createdAt: String,
    val readAt: String?
)

data class NotificationInbox(
    val items: List<NotificationInboxItem>,
    val unreadCount: Long,
    val limit: Int
)

data class NotificationPreference(
    val eventCategory: String,
    val channel: String,
    val enabled: Boolean,
    val version: Long
)

data class NotificationPreferences(val preferences: List<NotificationPreference>, val version: Long)

sealed interface NotificationsResult<out T> {
    data class Success<T>(val value: T) : NotificationsResult<T>
    data object PermissionDenied : NotificationsResult<Nothing>
    data object Conflict : NotificationsResult<Nothing>
    data object Unavailable : NotificationsResult<Nothing>
}

interface NotificationsGateway {
    suspend fun inbox(
        authority: NotificationsAuthority,
        unreadOnly: Boolean
    ): NotificationsResult<NotificationInbox>

    suspend fun setRead(
        authority: NotificationsAuthority,
        notificationId: String,
        read: Boolean
    ): NotificationsResult<Unit>

    suspend fun markAllRead(authority: NotificationsAuthority): NotificationsResult<Unit>

    suspend fun preferences(
        authority: NotificationsAuthority
    ): NotificationsResult<NotificationPreferences>

    suspend fun updatePreferences(
        authority: NotificationsAuthority,
        preferences: NotificationPreferences
    ): NotificationsResult<NotificationPreferences>
}
