package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsViewModel as ExceptionsViewModel
import com.nexa.mobile.operations.feature.delivery.application.DriverDeliveryOperationalExceptionMetadataStore as ExceptionMetadataStore
import com.nexa.mobile.operations.feature.delivery.application.DriverDeliveryOperationalExceptionsGateway as ExceptionsGateway
import javax.inject.Inject

internal class DriverDeliveryOperationalExceptionsBindings @Inject constructor(
    private val gateway: ExceptionsGateway,
    private val metadataStore: ExceptionMetadataStore
) {
    fun viewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(
                modelClass.isAssignableFrom(
                    ExceptionsViewModel::class.java
                )
            )
            return ExceptionsViewModel(gateway, metadataStore) as T
        }
    }
}
