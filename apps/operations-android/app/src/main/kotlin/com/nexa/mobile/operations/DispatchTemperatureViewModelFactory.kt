package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureViewModel as TemperatureViewModel
import com.nexa.mobile.operations.feature.dispatch.application.DispatchTemperatureGateway
import com.nexa.mobile.operations.feature.dispatch.application.DispatchTemperatureMetadataStore as TemperatureMetadataStore
import javax.inject.Inject

internal class DispatchTemperatureViewModelFactory @Inject constructor(
    private val gateway: DispatchTemperatureGateway,
    private val metadata: TemperatureMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TemperatureViewModel::class.java))
        return TemperatureViewModel(gateway, metadata) as T
    }
}
