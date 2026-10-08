package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.AppFieldRequestStore
import com.nexa.mobile.operations.data.OperationsFieldRequestGateway
import com.nexa.mobile.operations.feature.commercial.FieldRequestViewModel
import javax.inject.Inject
internal class FieldRequestViewModelFactory @Inject constructor(
    private val gateway: OperationsFieldRequestGateway,
    private val store: AppFieldRequestStore
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FieldRequestViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return FieldRequestViewModel(gateway, store) as T
    }
}
