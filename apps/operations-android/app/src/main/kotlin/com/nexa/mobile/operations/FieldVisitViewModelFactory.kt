package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.AppFieldVisitStore
import com.nexa.mobile.operations.data.OperationsFieldVisitGateway
import com.nexa.mobile.operations.feature.commercial.FieldVisitViewModel
import javax.inject.Inject
internal class FieldVisitViewModelFactory @Inject constructor(
    private val gateway: OperationsFieldVisitGateway,
    private val store: AppFieldVisitStore
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FieldVisitViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return FieldVisitViewModel(gateway, store) as T
    }
}
