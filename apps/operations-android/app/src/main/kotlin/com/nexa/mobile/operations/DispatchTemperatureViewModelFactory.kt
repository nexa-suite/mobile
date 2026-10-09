package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureEvidenceSelectionCoordinator
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureMetadataStore as TemperatureMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchTemperatureViewModel as TemperatureViewModel
import javax.inject.Inject

internal class DispatchTemperatureViewModelFactory @Inject constructor(
    private val gateway: DispatchTemperatureGateway,
    private val metadata: TemperatureMetadataStore,
    private val evidenceSelection: DispatchTemperatureEvidenceSelectionCoordinator
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TemperatureViewModel::class.java))
        return TemperatureViewModel(
            gateway,
            metadata,
            requestBodyCodec = JsonDispatchRequestBodyCodec(),
            evidenceSelection = evidenceSelection
        ) as T
    }
}
