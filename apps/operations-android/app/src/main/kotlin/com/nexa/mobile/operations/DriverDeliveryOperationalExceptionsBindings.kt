package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionMetadataStore as ExceptionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionsGateway as ExceptionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverDeliveryOperationalExceptionsViewModel as ExceptionsViewModel
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
            return ExceptionsViewModel(
                gateway,
                metadataStore,
                requestBodyCodec = JsonDeliveryRequestBodyCodec()
            ) as T
        }
    }
}
