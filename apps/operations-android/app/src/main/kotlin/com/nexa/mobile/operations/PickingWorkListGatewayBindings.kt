package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsPickingWorkListGateway
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse.PickingWorkListViewModel
import javax.inject.Inject

/** ViewModel factory consumed by the root entry point without leaking app types into the feature. */
internal class PickingWorkListGatewayBindings @Inject constructor(
    private val gateway: OperationsPickingWorkListGateway
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PickingWorkListViewModel::class.java))
            return PickingWorkListViewModel(gateway) as T
        }
    }
}
