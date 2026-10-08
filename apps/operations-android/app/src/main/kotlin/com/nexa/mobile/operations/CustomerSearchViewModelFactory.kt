package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsCustomerGateway
import com.nexa.mobile.operations.feature.commercial.CustomerSearchViewModel
import javax.inject.Inject

internal class CustomerSearchViewModelFactory @Inject constructor(
    private val gateway: OperationsCustomerGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CustomerSearchViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return CustomerSearchViewModel(gateway) as T
    }
}
