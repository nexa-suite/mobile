package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsTemperatureEvidenceGateway
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceViewModel
import com.nexa.mobile.operations.feature.warehouse.application.TemperatureEvidenceMetadataStore
import javax.inject.Inject

internal class TemperatureEvidenceGatewayBindings @Inject constructor(
    private val gateway: OperationsTemperatureEvidenceGateway,
    private val metadataStore: TemperatureEvidenceMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TemperatureEvidenceViewModel::class.java))
            return TemperatureEvidenceViewModel(gateway, metadataStore) as T
        }
    }
}
