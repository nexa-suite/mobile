package com.nexa.mobile.operations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationCapture
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationEventStream
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationEvent
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationSample
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Foreground-only GPS source. Samples travel through a volatile flow and are never persisted. */
internal class AndroidDriverWorkdayLocationCapture(context: Context) :
    DriverWorkdayLocationCapture {
    private val appContext = context.applicationContext
    private val eventStream = DriverWorkdayLocationEventStream()
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null

    override val events: Flow<DriverWorkdayLocationEvent> = eventStream.events

    override fun start(workdayId: String): Boolean {
        stop()
        if (
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            eventStream.publish(DriverWorkdayLocationEvent.PermissionUnavailable(workdayId))
            return false
        }
        val locationManager = appContext.getSystemService(LocationManager::class.java)
        if (locationManager == null || !runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
        ) {
            eventStream.publish(DriverWorkdayLocationEvent.ProviderUnavailable(workdayId))
            return false
        }
        val newListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val accuracy = location.accuracy
                if (!location.hasAccuracy() || !accuracy.isFinite() || accuracy < 0f) return
                eventStream.publish(
                    DriverWorkdayLocationEvent.Sample(
                        captureId = workdayId,
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
                eventStream.publish(DriverWorkdayLocationEvent.ProviderUnavailable(workdayId))
            }

            @Suppress("OVERRIDE_DEPRECATION")
            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        return try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_SAMPLE_INTERVAL_MILLIS,
                0f,
                newListener,
                Looper.getMainLooper()
            )
            manager = locationManager
            listener = newListener
            true
        } catch (_: SecurityException) {
            eventStream.publish(DriverWorkdayLocationEvent.PermissionUnavailable(workdayId))
            false
        } catch (_: IllegalArgumentException) {
            eventStream.publish(DriverWorkdayLocationEvent.ProviderUnavailable(workdayId))
            false
        }
    }

    override fun stop() {
        val activeManager = manager
        val activeListener = listener
        manager = null
        listener = null
        if (activeManager != null && activeListener != null) {
            runCatching { activeManager.removeUpdates(activeListener) }
        }
    }

    private companion object {
        const val LOCATION_SAMPLE_INTERVAL_MILLIS = 15_000L
    }
}
