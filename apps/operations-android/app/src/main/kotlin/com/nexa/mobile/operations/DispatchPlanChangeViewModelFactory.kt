package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeMetadataStore as PlanChangeMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDispatchPlanChangeGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchPlanChangeViewModel as PlanChangeViewModel
import javax.inject.Inject

internal class DispatchPlanChangeViewModelFactory @Inject constructor(
    private val gateway: OperationsDispatchPlanChangeGateway,
    private val metadata: PlanChangeMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlanChangeViewModel::class.java))
        return PlanChangeViewModel(
            gateway,
            metadata,
            requestBodyCodec = JsonDispatchRequestBodyCodec()
        ) as T
    }
}
