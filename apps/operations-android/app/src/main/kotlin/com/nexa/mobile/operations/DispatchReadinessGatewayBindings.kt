package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataStore as AssignmentMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDispatchAssignmentGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDispatchReadinessGateway
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchAssignmentViewModel as AssignmentViewModel
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchReadinessViewModel
import javax.inject.Inject

internal class DispatchReadinessViewModelFactory @Inject constructor(
    private val gateway: OperationsDispatchReadinessGateway
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchReadinessViewModel::class.java))
        return DispatchReadinessViewModel(gateway) as T
    }
}

internal class DispatchAssignmentViewModelFactory @Inject constructor(
    private val gateway: OperationsDispatchAssignmentGateway,
    private val metadata: AssignmentMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AssignmentViewModel::class.java))
        return AssignmentViewModel(gateway, metadata) as T
    }
}
