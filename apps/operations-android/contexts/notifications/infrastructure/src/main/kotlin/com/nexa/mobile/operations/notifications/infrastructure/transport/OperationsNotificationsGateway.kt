package com.nexa.mobile.operations.notifications.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.notifications.application.inbox.NotificationInbox
import com.nexa.mobile.operations.notifications.application.inbox.NotificationInboxItem
import com.nexa.mobile.operations.notifications.application.inbox.NotificationPreference
import com.nexa.mobile.operations.notifications.application.inbox.NotificationPreferences
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsAuthority
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsGateway
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class OperationsNotificationsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val calls: ProtectedCallExecutor
) : NotificationsGateway {
    override suspend fun inbox(
        authority: NotificationsAuthority,
        unreadOnly: Boolean
    ): NotificationsResult<NotificationInbox> = execute(
        authority,
        READ_PERMISSION,
        ProtectedRequest(
            ProtectedMethod.GET,
            "/api/v1/notifications?unread=$unreadOnly&limit=$INBOX_LIMIT"
        ),
        ::decodeInbox
    )

    override suspend fun setRead(
        authority: NotificationsAuthority,
        notificationId: String,
        read: Boolean
    ): NotificationsResult<Unit> {
        if (!safeId.matches(notificationId)) return NotificationsResult.Unavailable
        val method = if (read) ProtectedMethod.POST else ProtectedMethod.DELETE
        return execute(
            authority,
            READ_PERMISSION,
            ProtectedRequest(method, "/api/v1/notifications/$notificationId/read"),
            { Unit }
        )
    }

    override suspend fun markAllRead(authority: NotificationsAuthority): NotificationsResult<Unit> =
        execute(
            authority,
            READ_PERMISSION,
            ProtectedRequest(ProtectedMethod.POST, "/api/v1/notifications/read-all"),
            { Unit }
        )

    override suspend fun preferences(
        authority: NotificationsAuthority
    ): NotificationsResult<NotificationPreferences> = execute(
        authority,
        READ_PERMISSION,
        ProtectedRequest(ProtectedMethod.GET, "/api/v1/notifications/preferences"),
        ::decodePreferences
    )

    override suspend fun updatePreferences(
        authority: NotificationsAuthority,
        preferences: NotificationPreferences
    ): NotificationsResult<NotificationPreferences> {
        if (MANAGE_PERMISSION !in authority.permissions || preferences.version < 0) {
            return NotificationsResult.PermissionDenied
        }
        val accepted = preferences.preferences.filter { it.channel in ACCEPTED_CHANNELS }
        if (accepted.any { it.eventCategory.isBlank() || it.version < 0 }) {
            return NotificationsResult.Unavailable
        }
        val payload = buildJsonObject {
            put("version", preferences.version)
            put(
                "preferences",
                JsonArray(
                    accepted.map { preference ->
                        buildJsonObject {
                            put("eventCategory", preference.eventCategory)
                            put("channel", preference.channel)
                            put("enabled", preference.enabled)
                            put("version", preference.version)
                        }
                    }
                )
            )
        }.toString()
        return execute(
            authority,
            MANAGE_PERMISSION,
            ProtectedRequest(
                ProtectedMethod.PATCH,
                "/api/v1/notifications/preferences",
                payload
            ),
            ::decodePreferences
        )
    }

    private suspend fun <T> execute(
        authority: NotificationsAuthority,
        permission: String,
        request: ProtectedRequest,
        decode: (String?) -> T?
    ): NotificationsResult<T> {
        if (!current(authority, permission)) return NotificationsResult.PermissionDenied
        val lease = sessions.currentAccess() ?: return NotificationsResult.PermissionDenied
        val result = try {
            calls.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return NotificationsResult.Unavailable
        }
        if (!sessions.isEpochCurrent(lease.epoch) || !current(authority, permission)) {
            return NotificationsResult.PermissionDenied
        }
        return when (result) {
            is ProtectedResult.Success -> runCatching { decode(result.body) }
                .getOrNull()
                ?.let { NotificationsResult.Success(it) }
                ?: NotificationsResult.Unavailable

            is ProtectedResult.Failure -> when (result.error.kind) {
                FailureKind.AuthenticationRequired,
                FailureKind.AuthorizationFailure -> NotificationsResult.PermissionDenied

                FailureKind.BusinessConflict,
                FailureKind.StaleState,
                FailureKind.PreconditionRequired -> NotificationsResult.Conflict

                else -> NotificationsResult.Unavailable
            }
        }
    }

    private fun current(authority: NotificationsAuthority, permission: String): Boolean {
        if (authority.authorityEpoch <= 0 || permission !in authority.permissions) return false
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active &&
            verified.hasAuthorizedContext &&
            verified.userId == authority.userId && verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId &&
            verified.membershipId == authority.membershipId &&
            verified.permissions == authority.permissions
    }

    private fun decodeInbox(body: String?): NotificationInbox? = runCatching {
        val value = json.parseToJsonElement(body ?: "").jsonObject
        val items = value.getValue("items").jsonArray.map { raw ->
            val item = raw.jsonObject
            val id = item.requiredText("id")
            require(safeId.matches(id))
            NotificationInboxItem(
                id = id,
                category = item.requiredText("category"),
                title = item.requiredText("title"),
                message = item.requiredText("message"),
                subjectType = item.optionalText("subjectType"),
                subjectId = item.optionalText("subjectId"),
                createdAt = item.requiredText("createdAt"),
                readAt = item.optionalText("readAt")
            )
        }
        val unreadCount = value.getValue("unreadCount").jsonPrimitive.long
        val limit = value.getValue("limit").jsonPrimitive.content.toInt()
        require(unreadCount >= 0 && limit in 1..100)
        NotificationInbox(items, unreadCount, limit)
    }.getOrNull()

    private fun decodePreferences(body: String?): NotificationPreferences? = runCatching {
        val value = json.parseToJsonElement(body ?: "").jsonObject
        val preferences = value.getValue("preferences").jsonArray.map { raw ->
            val preference = raw.jsonObject
            NotificationPreference(
                eventCategory = preference.requiredText("eventCategory"),
                channel = preference.requiredText("channel"),
                enabled = preference.getValue("enabled").jsonPrimitive.content.toBooleanStrict(),
                version = preference.getValue("version").jsonPrimitive.long
            ).also { require(it.version >= 0) }
        }
        val version = value.getValue("version").jsonPrimitive.long
        require(version >= 0)
        NotificationPreferences(preferences, version)
    }.getOrNull()

    private fun JsonObject.requiredText(key: String): String =
        getValue(key).jsonPrimitive.content.also { require(it.isNotBlank()) }

    private fun JsonObject.optionalText(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    private companion object {
        const val READ_PERMISSION = "notification.read"
        const val MANAGE_PERMISSION = "notification.manage_preferences"
        const val INBOX_LIMIT = 50
        val ACCEPTED_CHANNELS = setOf("IN_APP", "EMAIL")
        val safeId = Regex("[A-Za-z0-9._~-]{1,128}")
        val json = Json { ignoreUnknownKeys = true }
    }
}
