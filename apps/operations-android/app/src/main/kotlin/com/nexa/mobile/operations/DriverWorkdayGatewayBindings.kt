package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDriverWorkdayCommandStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDriverWorkdayGateway
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverWorkdayViewModel
import javax.inject.Inject

internal class DriverWorkdayGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverWorkdayGateway,
    private val commandStore: OperationsDriverWorkdayCommandStore
) {
    fun viewModelFactory(capture: AndroidDriverWorkdayLocationCapture): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(DriverWorkdayViewModel::class.java))
                return DriverWorkdayViewModel(gateway, capture, commandStore) as T
            }
        }
}
