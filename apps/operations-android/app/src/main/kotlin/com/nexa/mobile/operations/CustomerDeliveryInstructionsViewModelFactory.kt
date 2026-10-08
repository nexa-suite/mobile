package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.AppCustomerInstructionStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsCustomerInstructionGateway
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.commercial.CustomerDeliveryInstructionsViewModel
import javax.inject.Inject
internal class CustomerDeliveryInstructionsViewModelFactory @Inject constructor(
    private val gateway: OperationsCustomerInstructionGateway,
    private val store: AppCustomerInstructionStore
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CustomerDeliveryInstructionsViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return CustomerDeliveryInstructionsViewModel(gateway, store) as T
    }
}
