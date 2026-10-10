package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.notifications.infrastructure.transport.OperationsNotificationsGateway
import com.nexa.mobile.operations.notifications.presentation.inbox.NotificationsViewModel
import javax.inject.Inject

internal class NotificationsViewModelFactory @Inject constructor(
    private val gateway: OperationsNotificationsGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NotificationsViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return NotificationsViewModel(gateway) as T
    }
}
