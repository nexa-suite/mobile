package com.nexa.mobile.operations

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationEvent
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationSample
import java.time.Instant
import java.util.UUID

/** App-private GPS source. It holds only a workday id and emits volatile events to the VM. */
internal class DriverWorkdayLocationService : Service() {
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var workdayId: String? = null
    private var startGeneration: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent ?: return START_NOT_STICKY
        when (request.action) {
            ACTION_STOP -> {
                AndroidDriverLocationServiceStartup.deferStopUntilForeground()
                workdayId?.let {
                    AndroidDriverWorkdayLocationEventBus.userStopped(it)
                }
                stopLocationUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }

            ACTION_PREPARE, ACTION_START -> {
                val requestedWorkdayId = request.getStringExtra(EXTRA_WORKDAY_ID)
                if (requestedWorkdayId.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val generation = request.getLongExtra(EXTRA_START_GENERATION, -1L)
                workdayId = requestedWorkdayId
                startGeneration = generation
                if (!startForegroundSafely(generation, startId)) return START_NOT_STICKY
                if (request.action == ACTION_START) {
                    startLocationUpdates(requestedWorkdayId)
                }
            }

            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        startGeneration?.let(AndroidDriverLocationServiceStartup::failed)
        super.onDestroy()
    }

    private fun startForegroundSafely(generation: Long, startId: Int): Boolean = try {
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        if (AndroidDriverLocationServiceStartup.promoted(generation)) {
            stopLocationUpdates()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            false
        } else {
            true
        }
    } catch (_: SecurityException) {
        AndroidDriverLocationServiceStartup.failed(generation)
        workdayId?.let {
            AndroidDriverWorkdayLocationEventBus.stream.publish(
                DriverWorkdayLocationEvent.PermissionUnavailable(it)
            )
        }
        stopSelf()
        false
    } catch (_: IllegalArgumentException) {
        AndroidDriverLocationServiceStartup.failed(generation)
        workdayId?.let {
            AndroidDriverWorkdayLocationEventBus.stream.publish(
                DriverWorkdayLocationEvent.ProviderUnavailable(it)
            )
        }
        stopSelf()
        false
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.driver_location_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, DriverWorkdayLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location_notification)
            .setContentTitle(getString(R.string.driver_location_notification_title))
            .setContentText(getString(R.string.driver_location_notification_body))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                0,
                getString(R.string.driver_location_notification_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun startLocationUpdates(currentWorkdayId: String) {
        stopLocationUpdates()
        val manager = getSystemService(LocationManager::class.java)
        if (manager == null || !runCatching {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
        ) {
            publishAndStop(
                DriverWorkdayLocationEvent.ProviderUnavailable(currentWorkdayId)
            )
            return
        }
        if (!locationPermissionGranted()) {
            publishAndStop(
                DriverWorkdayLocationEvent.PermissionUnavailable(currentWorkdayId)
            )
            return
        }
        if (!notificationsVisible()) {
            publishAndStop(
                DriverWorkdayLocationEvent.NotificationUnavailable(currentWorkdayId)
            )
            return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!locationPermissionGranted()) {
                    publishAndStop(
                        DriverWorkdayLocationEvent.PermissionUnavailable(currentWorkdayId)
                    )
                    return
                }
                if (!notificationsVisible()) {
                    publishAndStop(
                        DriverWorkdayLocationEvent.NotificationUnavailable(currentWorkdayId)
                    )
                    return
                }
                val accuracy = location.accuracy
                if (!location.hasAccuracy() || !accuracy.isFinite() || accuracy < 0f) return
                AndroidDriverWorkdayLocationEventBus.stream.publish(
                    DriverWorkdayLocationEvent.Sample(
                        captureId = currentWorkdayId,
                        value = DriverWorkdayLocationSample(
                            sampleId = UUID.randomUUID().toString(),
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = accuracy.toDouble(),
                            capturedAt = Instant.ofEpochMilli(location.time).toString()
                        )
                    )
                )
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                publishAndStop(
                    DriverWorkdayLocationEvent.ProviderUnavailable(currentWorkdayId)
                )
            }

            @Suppress("OVERRIDE_DEPRECATION")
            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_SAMPLE_INTERVAL_MILLIS,
                0f,
                listener,
                Looper.getMainLooper()
            )
            locationManager = manager
            locationListener = listener
        } catch (_: SecurityException) {
            publishAndStop(
                DriverWorkdayLocationEvent.PermissionUnavailable(currentWorkdayId)
            )
        } catch (_: IllegalArgumentException) {
            publishAndStop(
                DriverWorkdayLocationEvent.ProviderUnavailable(currentWorkdayId)
            )
        }
    }

    private fun publishAndStop(event: DriverWorkdayLocationEvent) {
        startGeneration?.let(AndroidDriverLocationServiceStartup::failed)
        AndroidDriverWorkdayLocationEventBus.stream.publish(event)
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopLocationUpdates() {
        val manager = locationManager
        val listener = locationListener
        locationManager = null
        locationListener = null
        if (manager != null && listener != null) {
            runCatching { manager.removeUpdates(listener) }
        }
    }

    private fun locationPermissionGranted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationsVisible(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    companion object {
        const val ACTION_PREPARE = "com.nexa.operations.location.PREPARE"
        const val ACTION_START = "com.nexa.operations.location.START"
        const val ACTION_STOP = "com.nexa.operations.location.STOP"
        const val EXTRA_WORKDAY_ID = "workdayId"
        const val EXTRA_START_GENERATION = "startGeneration"
        private const val CHANNEL_ID = "driver_location_sharing"
        private const val NOTIFICATION_ID = 4201
        private const val STOP_REQUEST_CODE = 4202
        private const val LOCATION_SAMPLE_INTERVAL_MILLIS = 15_000L
    }
}
