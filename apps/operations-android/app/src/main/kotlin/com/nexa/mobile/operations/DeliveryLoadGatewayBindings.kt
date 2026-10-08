package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsDeliveryLoadGateway
import com.nexa.mobile.operations.feature.dispatch.DeliveryLoadViewModel
import com.nexa.mobile.operations.feature.dispatch.application.DeliveryLoadCommandMetadataStore as LoadCommandMetadataStore
import javax.inject.Inject

internal class DeliveryLoadViewModelFactory @Inject constructor(
    private val gateway: OperationsDeliveryLoadGateway,
    private val metadata: LoadCommandMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DeliveryLoadViewModel::class.java))
        return DeliveryLoadViewModel(gateway, metadata) as T
    }
}

internal class DeliveryLoadViewModelBindings @Inject constructor(
    private val factory: DeliveryLoadViewModelFactory
) {
    fun viewModelFactory(): ViewModelProvider.Factory = factory
}
