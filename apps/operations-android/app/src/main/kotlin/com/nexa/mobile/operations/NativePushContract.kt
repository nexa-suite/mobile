package com.nexa.mobile.operations

internal const val ACTION_OPEN_NOTIFICATIONS_FROM_PUSH =
    "com.nexa.mobile.operations.action.OPEN_NOTIFICATIONS_FROM_PUSH"
internal const val EXTRA_OPEN_NOTIFICATIONS_FROM_PUSH =
    "com.nexa.mobile.operations.extra.OPEN_NOTIFICATIONS"
internal const val EXTRA_PUSH_EVENT_ID =
    "com.nexa.mobile.operations.extra.PUSH_EVENT_ID"
internal const val FCM_EVENT_ID_FIELD = "eventId"
internal const val FCM_MESSAGE_ID_FIELD = "google.message_id"
internal const val ANDROID_MAIN_ACTION = "android.intent.action.MAIN"
internal const val ANDROID_LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"

private val SAFE_PUSH_EVENT_ID = Regex("[A-Za-z0-9_-]{1,128}")

internal fun isSafePushEventId(value: String?): Boolean =
    value != null && SAFE_PUSH_EVENT_ID.matches(value)

internal fun isSafeFcmMessageId(value: String?): Boolean =
    value != null && value.isNotBlank() && value.length <= 256 &&
        value.none(Char::isISOControl)

internal fun isFcmNotificationTap(
    action: String?,
    hasLauncherCategory: Boolean,
    explicitPushMarker: Boolean,
    messageId: String?,
    eventId: String?
): Boolean {
    if (!isSafePushEventId(eventId)) return false
    val foregroundNotificationTap =
        action == ACTION_OPEN_NOTIFICATIONS_FROM_PUSH && explicitPushMarker
    val systemNotificationTap = action == ANDROID_MAIN_ACTION && hasLauncherCategory &&
        isSafeFcmMessageId(messageId)
    return foregroundNotificationTap || systemNotificationTap
}
