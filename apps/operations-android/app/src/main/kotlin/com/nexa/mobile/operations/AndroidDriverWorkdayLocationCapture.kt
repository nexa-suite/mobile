package com.nexa.mobile.operations

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationCapture
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationEventStream
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationEvent
import kotlinx.coroutines.flow.Flow

/** Starts the app-private location foreground service after an explicit visible opt-in. */
internal class AndroidDriverWorkdayLocationCapture(context: Context) :
    DriverWorkdayLocationCapture {
    private val appContext = context.applicationContext
    private var preparedGeneration: Long? = null
    private var preparedWorkdayId: String? = null
    override val events: Flow<DriverWorkdayLocationEvent> =
        AndroidDriverWorkdayLocationEventBus.stream.events

    override fun prepare(workdayId: String): Boolean {
        if (workdayId.isBlank() || !locationPermissionGranted() || !notificationsVisible()) {
            return false
        }
        AndroidDriverWorkdayLocationEventBus.clearUserStop(workdayId)
        return startForegroundService(DriverWorkdayLocationService.ACTION_PREPARE, workdayId)
    }

    override fun start(workdayId: String): Boolean {
        if (workdayId.isBlank()) return false
        if (AndroidDriverWorkdayLocationEventBus.wasUserStopped(workdayId)) return false
        if (!locationPermissionGranted()) {
            AndroidDriverWorkdayLocationEventBus.stream.publish(
                DriverWorkdayLocationEvent.PermissionUnavailable(workdayId)
            )
            return false
        }
        if (!notificationsVisible()) {
            AndroidDriverWorkdayLocationEventBus.stream.publish(
                DriverWorkdayLocationEvent.NotificationUnavailable(workdayId)
            )
            return false
        }
        val generation = preparedGeneration ?: return false
        if (preparedWorkdayId != workdayId ||
            !AndroidDriverLocationServiceStartup.canCapture(generation)
        ) {
            return false
        }
        return startRunningService(DriverWorkdayLocationService.ACTION_START, workdayId, generation)
    }

    override fun stop() {
        preparedGeneration = null
        preparedWorkdayId = null
        if (!AndroidDriverLocationServiceStartup.deferStopUntilForeground()) {
            appContext.stopService(serviceIntent(DriverWorkdayLocationService.ACTION_STOP))
        }
    }

    private fun startForegroundService(action: String, workdayId: String): Boolean {
        val generation = AndroidDriverLocationServiceStartup.begin()
        return try {
            val intent = serviceIntent(action).apply {
                putExtra(DriverWorkdayLocationService.EXTRA_WORKDAY_ID, workdayId)
                putExtra(DriverWorkdayLocationService.EXTRA_START_GENERATION, generation)
            }
            appContext.startForegroundService(intent)
            preparedGeneration = generation
            preparedWorkdayId = workdayId
            true
        } catch (_: IllegalStateException) {
            AndroidDriverLocationServiceStartup.failed(generation)
            false
        } catch (_: SecurityException) {
            AndroidDriverLocationServiceStartup.failed(generation)
            false
        }
    }

    private fun startRunningService(action: String, workdayId: String, generation: Long): Boolean =
        try {
            appContext.startService(
                serviceIntent(action)
                    .putExtra(DriverWorkdayLocationService.EXTRA_WORKDAY_ID, workdayId)
                    .putExtra(DriverWorkdayLocationService.EXTRA_START_GENERATION, generation)
            ) != null
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun serviceIntent(action: String) =
        Intent(appContext, DriverWorkdayLocationService::class.java).setAction(action)

    private fun locationPermissionGranted(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationsVisible(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return appContext.getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() == true
    }
}

/** A cancelled startup must still finish Android's foreground-service handshake. */
internal object AndroidDriverLocationServiceStartup {
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null
    private val pendingGenerations = mutableSetOf<Long>()

    @Synchronized
    fun begin(): Long {
        val generation = ++nextGeneration
        activeGeneration = generation
        pendingGenerations += generation
        return generation
    }

    @Synchronized
    fun deferStopUntilForeground(): Boolean {
        activeGeneration = null
        return pendingGenerations.isNotEmpty()
    }

    @Synchronized
    fun promoted(generation: Long): Boolean {
        pendingGenerations -= generation
        return activeGeneration != generation
    }

    @Synchronized
    fun canCapture(generation: Long): Boolean = activeGeneration == generation

    @Synchronized
    fun failed(generation: Long) {
        pendingGenerations -= generation
        if (activeGeneration == generation) activeGeneration = null
    }
}

/** Process-local event bridge; location samples are never persisted. */
internal object AndroidDriverWorkdayLocationEventBus {
    val stream = DriverWorkdayLocationEventStream()

    @Volatile private var userStoppedWorkdayId: String? = null

    fun userStopped(workdayId: String) {
        userStoppedWorkdayId = workdayId
        stream.publish(DriverWorkdayLocationEvent.UserStopped(workdayId))
    }

    fun wasUserStopped(workdayId: String): Boolean = userStoppedWorkdayId == workdayId

    fun clearUserStop(workdayId: String) {
        if (userStoppedWorkdayId == workdayId) userStoppedWorkdayId = null
    }
}
