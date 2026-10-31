package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.adapters.OperationsOwnProfileGateway
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.profile.OwnProfileViewModel
import javax.inject.Inject

internal class OwnProfileViewModelFactory @Inject constructor(
    private val gateway: OperationsOwnProfileGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(OwnProfileViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return OwnProfileViewModel(gateway) as T
    }
}
