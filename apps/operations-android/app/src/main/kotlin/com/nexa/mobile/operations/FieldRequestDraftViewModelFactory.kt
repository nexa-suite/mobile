package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.salescommitment.infrastructure.adapters.AppFieldRequestDraftStore
import com.nexa.mobile.operations.salescommitment.presentation.commercial.FieldRequestDraftViewModel
import javax.inject.Inject

internal class FieldRequestDraftViewModelFactory @Inject constructor(
    private val store: AppFieldRequestDraftStore,
    private val gateway: OperationsFieldRequestDraftGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FieldRequestDraftViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return FieldRequestDraftViewModel(store, gateway) as T
    }
}
