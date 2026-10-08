package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.DispositionMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.OperationsDispositionGateway
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.DispositionViewModel
import javax.inject.Inject

internal class DispositionViewModelFactory @Inject constructor(
    private val gateway: OperationsDispositionGateway,
    private val metadataStore: DispositionMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispositionViewModel::class.java))
        return DispositionViewModel(gateway, metadataStore) as T
    }
}
