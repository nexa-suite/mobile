package com.nexa.mobile.operations

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

internal object FirebaseMessagingRuntime {
    fun initialize(context: Context): Boolean {
        if (!BuildConfig.FIREBASE_MESSAGING_CONFIGURED) return false
        return try {
            val configured = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_MESSAGING_SENDER_ID)
                .build()
            val defaultApp = FirebaseApp.getApps(context)
                .singleOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            if (defaultApp != null) {
                val existing = defaultApp.options
                existing.applicationId == configured.applicationId &&
                    existing.apiKey == configured.apiKey &&
                    existing.projectId == configured.projectId &&
                    existing.gcmSenderId == configured.gcmSenderId
            } else {
                FirebaseApp.initializeApp(context, configured)
                true
            }
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            context.getString(R.string.nexa_notification_channel_id),
            context.getString(R.string.nexa_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.nexa_notification_channel_description)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(
            context.getString(R.string.nexa_notification_channel_id)
        ) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        return true
    }
}
