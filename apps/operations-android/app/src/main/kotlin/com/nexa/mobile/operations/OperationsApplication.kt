package com.nexa.mobile.operations

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OperationsApplication : Application() {
    @Inject lateinit var fcmPushRegistrationCoordinator: FcmPushRegistrationCoordinator

    override fun onCreate() {
        super.onCreate()
        FirebaseMessagingRuntime.createNotificationChannel(this)
        if (FirebaseMessagingRuntime.initialize(this)) {
            fcmPushRegistrationCoordinator.start()
        }
    }
}
