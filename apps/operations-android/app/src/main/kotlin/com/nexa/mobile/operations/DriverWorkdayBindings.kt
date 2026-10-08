package com.nexa.mobile.operations

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsDriverWorkdayCommandStore
import com.nexa.mobile.operations.data.OperationsDriverWorkdayGateway
import com.nexa.mobile.operations.feature.delivery.DriverWorkdayViewModel
import com.nexa.mobile.operations.feature.delivery.application.DriverWorkdayLocationCapture as WorkdayLocationCapture
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayLocationEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
internal class OperationsDriverLocationCapture @Inject constructor(
    @ApplicationContext private val context: Context
) : WorkdayLocationCapture {
    override val events: Flow<DriverWorkdayLocationEvent> = DriverLocationEvents.events
    private var activeWorkdayId: String? = null

    @Synchronized
    override fun start(workdayId: String): Boolean {
        if (activeWorkdayId == workdayId) return true
        return try {
            val intent = Intent(context, DriverLocationTrackingService::class.java)
                .setAction(DriverLocationTrackingService.ACTION_START)
                .putExtra(DriverLocationTrackingService.EXTRA_WORKDAY_ID, workdayId)
            context.startForegroundService(intent)
            activeWorkdayId = workdayId
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    @Synchronized
    override fun stop() {
        activeWorkdayId = null
        context.stopService(Intent(context, DriverLocationTrackingService::class.java))
    }
}

internal class DriverWorkdayBindings @Inject constructor(
    private val gateway: OperationsDriverWorkdayGateway,
    private val capture: OperationsDriverLocationCapture,
    private val commands: OperationsDriverWorkdayCommandStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriverWorkdayViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return DriverWorkdayViewModel(gateway, capture, commands) as T
        }
    }
}
