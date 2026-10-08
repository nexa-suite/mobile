package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsBusinessDocumentsGateway
import com.nexa.mobile.operations.feature.commercial.BusinessDocumentsViewModel
import javax.inject.Inject
internal class BusinessDocumentsViewModelFactory @Inject constructor(
    private val gateway: OperationsBusinessDocumentsGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BusinessDocumentsViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return BusinessDocumentsViewModel(gateway) as T
    }
}
