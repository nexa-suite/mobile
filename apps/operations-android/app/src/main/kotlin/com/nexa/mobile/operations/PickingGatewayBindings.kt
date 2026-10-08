package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsPickingGateway
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse.PickingViewModel
import javax.inject.Inject

/** Factory entry for integration routing without feature dependencies on auth/network/Hilt. */
internal class PickingGatewayBindings @Inject constructor(
    private val gateway: OperationsPickingGateway,
    private val metadataStore: PickingMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(
                modelClass.isAssignableFrom(
                    PickingViewModel::class.java
                )
            )
            return PickingViewModel(
                gateway,
                metadataStore
            ) as T
        }
    }
}
