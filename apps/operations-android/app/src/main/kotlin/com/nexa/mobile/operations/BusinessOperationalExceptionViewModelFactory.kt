package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionMetadataStore as ExceptionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionsGateway as ExceptionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.BusinessOperationalExceptionsViewModel as ExceptionsViewModel
import javax.inject.Inject

internal class BusinessOperationalExceptionViewModelFactory @Inject constructor(
    private val gateway: ExceptionsGateway,
    private val metadata: ExceptionMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ExceptionsViewModel::class.java))
        return ExceptionsViewModel(
            gateway,
            metadata,
            requestBodyCodec = JsonDispatchRequestBodyCodec()
        ) as T
    }
}
