package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.feature.dispatch.BusinessOperationalExceptionsViewModel as ExceptionsViewModel
import com.nexa.mobile.operations.feature.dispatch.application.BusinessOperationalExceptionMetadataStore as ExceptionMetadataStore
import com.nexa.mobile.operations.feature.dispatch.application.BusinessOperationalExceptionsGateway as ExceptionsGateway
import javax.inject.Inject

internal class BusinessOperationalExceptionViewModelFactory @Inject constructor(
    private val gateway: ExceptionsGateway,
    private val metadata: ExceptionMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ExceptionsViewModel::class.java))
        return ExceptionsViewModel(gateway, metadata) as T
    }
}
