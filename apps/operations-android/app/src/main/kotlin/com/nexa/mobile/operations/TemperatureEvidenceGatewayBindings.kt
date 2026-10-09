package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.TemperatureEvidenceMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceSelectionCoordinator
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.OperationsTemperatureEvidenceGateway
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.TemperatureEvidenceViewModel
import javax.inject.Inject

internal class TemperatureEvidenceGatewayBindings @Inject constructor(
    private val gateway: OperationsTemperatureEvidenceGateway,
    private val metadataStore: TemperatureEvidenceMetadataStore,
    private val evidenceSelection: WarehouseEvidenceSelectionCoordinator
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TemperatureEvidenceViewModel::class.java))
            return TemperatureEvidenceViewModel(
                gateway,
                metadataStore,
                evidenceSelection = evidenceSelection
            ) as T
        }
    }
}
