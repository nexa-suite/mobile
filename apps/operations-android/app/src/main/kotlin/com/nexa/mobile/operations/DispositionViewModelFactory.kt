package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsDispositionGateway
import com.nexa.mobile.operations.feature.warehouse.DispositionViewModel
import com.nexa.mobile.operations.feature.warehouse.application.DispositionMetadataStore
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
