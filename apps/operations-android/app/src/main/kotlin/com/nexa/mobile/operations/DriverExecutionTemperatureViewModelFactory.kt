package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsDriverIncidentGateway
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureViewModel as TemperatureViewModel
import com.nexa.mobile.operations.feature.delivery.application.DriverExecutionTemperatureGateway as TemperatureGateway
import com.nexa.mobile.operations.feature.delivery.application.DriverExecutionTemperatureMetadataStore as TemperatureMetadataStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DriverExecutionTemperatureViewModelFactory @Inject constructor(
    private val gateway: TemperatureGateway,
    private val incidentGateway: OperationsDriverIncidentGateway,
    private val metadata: TemperatureMetadataStore
) {
    fun viewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TemperatureViewModel::class.java))
            return TemperatureViewModel(gateway, incidentGateway, metadata) as T
        }
    }
}
