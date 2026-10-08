package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.OperationsReceivingGateway
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.ReceivingViewModel
import javax.inject.Inject

/** Factory entry for routing without coupling the feature to auth/network/Hilt. */
internal class ReceivingGatewayBindings @Inject constructor(
    private val gateway: OperationsReceivingGateway,
    private val metadataStore: ReceivingMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReceivingViewModel::class.java))
            return ReceivingViewModel(gateway, metadataStore) as T
        }
    }
}
