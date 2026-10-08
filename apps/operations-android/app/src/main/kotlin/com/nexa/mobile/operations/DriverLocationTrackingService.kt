package com.nexa.mobile.operations

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.nexa.mobile.operations.feature.delivery.R as DeliveryR
import com.nexa.mobile.operations.feature.delivery.application.DriverWorkdayLocationEventStream as WorkdayLocationEventStream
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayLocationEvent
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayLocationSample as WorkdayLocationSample
import java.time.Instant
import java.util.UUID

internal object DriverLocationEvents {
    private val stream = WorkdayLocationEventStream()
    val events = stream.events

    fun publish(event: DriverWorkdayLocationEvent) {
        // One volatile newest event may wait for an active collector; events before subscription
        // are discarded because replay remains zero.
        stream.publish(event)
    }
}

/** Foreground-only OS location reader. Coordinates live only in callback memory. */
class DriverLocationTrackingService : Service() {
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private var activeWorkdayId: String? = null
    private var receiverRegistered = false

    private val providerChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (enabledProviders().isEmpty()) {
                DriverLocationEvents.publish(DriverWorkdayLocationEvent.ProviderUnavailable)
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val workdayId = intent?.getStringExtra(EXTRA_WORKDAY_ID)
        if (intent?.action != ACTION_START || workdayId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (activeWorkdayId == workdayId && listener != null) return START_NOT_STICKY
        stopLocationUpdates()
        activeWorkdayId = workdayId
        startForegroundLocationNotification()
        beginLocationUpdates()
        return START_NOT_STICKY
    }

    private fun startForegroundLocationNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(DeliveryR.string.driver_workday_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(DeliveryR.string.driver_workday_notification_title))
            .setContentText(getString(DeliveryR.string.driver_workday_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun beginLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            DriverLocationEvents.publish(DriverWorkdayLocationEvent.PermissionUnavailable)
            stopSelf()
            return
        }
        val locationManager = getSystemService(LocationManager::class.java)
        manager = locationManager
        val providers = enabledProviders()
        if (providers.isEmpty()) {
            DriverLocationEvents.publish(DriverWorkdayLocationEvent.ProviderUnavailable)
            stopSelf()
            return
        }
        val activeListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                publishLocation(location)
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (enabledProviders().isEmpty()) {
                    DriverLocationEvents.publish(DriverWorkdayLocationEvent.ProviderUnavailable)
                    stopSelf()
                }
            }
        }
        listener = activeListener
        try {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_INTERVAL_MILLIS,
                    LOCATION_DISTANCE_METERS,
                    activeListener,
                    Looper.getMainLooper()
                )
            }
            val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(providerChanges, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(providerChanges, filter)
            }
            receiverRegistered = true
        } catch (_: SecurityException) {
            DriverLocationEvents.publish(DriverWorkdayLocationEvent.PermissionUnavailable)
            stopSelf()
        } catch (_: IllegalArgumentException) {
            DriverLocationEvents.publish(DriverWorkdayLocationEvent.ProviderUnavailable)
            stopSelf()
        }
    }

    private fun publishLocation(location: Location) {
        val ageMillis = System.currentTimeMillis() - location.time
        if (activeWorkdayId == null || !location.hasAccuracy() ||
            !location.latitude.isFinite() || !location.longitude.isFinite() ||
            !location.accuracy.isFinite() || location.accuracy < 0f ||
            location.time <= 0L || ageMillis !in 0..MAX_SAMPLE_AGE_MILLIS
        ) {
            return
        }
        DriverLocationEvents.publish(
            DriverWorkdayLocationEvent.Sample(
                WorkdayLocationSample(
                    sampleId = UUID.randomUUID().toString(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.toDouble(),
                    capturedAt = Instant.ofEpochMilli(location.time).toString()
                )
            )
        )
    }

    private fun enabledProviders(): List<String> = try {
        val locationManager = manager ?: getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter(locationManager::isProviderEnabled)
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun stopLocationUpdates() {
        val activeListener = listener
        if (activeListener != null) manager?.removeUpdates(activeListener)
        listener = null
        manager = null
        activeWorkdayId = null
        if (receiverRegistered) {
            unregisterReceiver(providerChanges)
            receiverRegistered = false
        }
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.nexa.mobile.operations.driver.location.START"
        const val ACTION_STOP = "com.nexa.mobile.operations.driver.location.STOP"
        const val EXTRA_WORKDAY_ID = "workdayId"
        private const val CHANNEL_ID = "driver-workday-location"
        private const val NOTIFICATION_ID = 4702
        private const val LOCATION_INTERVAL_MILLIS = 15_000L
        private const val LOCATION_DISTANCE_METERS = 25f
        private const val MAX_SAMPLE_AGE_MILLIS = 120_000L
    }
}
