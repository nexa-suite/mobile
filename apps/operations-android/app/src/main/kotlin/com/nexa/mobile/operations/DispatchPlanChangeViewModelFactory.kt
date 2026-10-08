package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsDispatchPlanChangeGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeViewModel as PlanChangeViewModel
import com.nexa.mobile.operations.feature.dispatch.application.DispatchPlanChangeMetadataStore as PlanChangeMetadataStore
import javax.inject.Inject

internal class DispatchPlanChangeViewModelFactory @Inject constructor(
    private val gateway: OperationsDispatchPlanChangeGateway,
    private val metadata: PlanChangeMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlanChangeViewModel::class.java))
        return PlanChangeViewModel(gateway, metadata) as T
    }
}
