package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsDriverHandoffTokenGateway
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenViewModel as HandoffTokenViewModel
import com.nexa.mobile.operations.feature.delivery.application.DriverHandoffTokenMetadataStore as HandoffTokenMetadataStore
import javax.inject.Inject
import javax.inject.Singleton

/** Route/factory seam for Root navigation. Root owns MainActivity and DeliveryScreen. */
@Singleton
internal class DriverHandoffTokenGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverHandoffTokenGateway,
    private val metadataStore: HandoffTokenMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HandoffTokenViewModel::class.java))
            return HandoffTokenViewModel(gateway, metadataStore) as T
        }
    }
}
