package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.LegalAcceptanceGateway
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.legal.LegalAcceptanceViewModel

internal class LegalAcceptanceViewModelFactory(private val gateway: LegalAcceptanceGateway) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LegalAcceptanceViewModel::class.java))
        return LegalAcceptanceViewModel(gateway) as T
    }
}
