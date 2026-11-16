package com.nexa.mobile.operations

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@AndroidEntryPoint
class OperationsFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var registrationCoordinator: FcmPushRegistrationCoordinator

    @Deprecated("Use onRegistered() to receive the Firebase Installation ID.")
    @Suppress("UNUSED_PARAMETER")
    override fun onNewToken(token: String) {
        FirebaseInstallations.getInstance().id.addOnSuccessListener(
            registrationCoordinator::onFirebaseRegistered
        )
    }

    override fun onRegistered(installationId: String) {
        registrationCoordinator.onFirebaseRegistered(installationId)
    }

    override fun onUnregistered(installationId: String) {
        registrationCoordinator.onFirebaseUnregistered(installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val eventId = message.data[FCM_EVENT_ID_FIELD]
        if (message.data.keys != setOf(FCM_EVENT_ID_FIELD) ||
            !isSafePushEventId(eventId)
        ) {
            return
        }
        if (!FirebaseMessagingRuntime.notificationsAllowed(this)) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val title = getString(R.string.nexa_notification_title)
        val body = getString(R.string.nexa_notification_body)
        val channelId = getString(R.string.nexa_notification_channel_id)
        val openInbox = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_NOTIFICATIONS_FROM_PUSH
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_NOTIFICATIONS_FROM_PUSH, true)
            putExtra(EXTRA_PUSH_EVENT_ID, eventId)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openInbox,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val publicVersion = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_nexa_notification)
            .setContentTitle(title)
            .setContentText(body)
            .build()
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_nexa_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_IDS.incrementAndGet(), notification)
    }

    private companion object {
        val NOTIFICATION_IDS = AtomicInteger(10_000)
    }
}
