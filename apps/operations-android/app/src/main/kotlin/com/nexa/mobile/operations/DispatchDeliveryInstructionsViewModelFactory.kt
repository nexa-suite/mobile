package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionsViewModel as InstructionsViewModel
import com.nexa.mobile.operations.feature.dispatch.application.DispatchDeliveryInstructionMetadataStore as InstructionMetadataStore
import com.nexa.mobile.operations.feature.dispatch.application.DispatchDeliveryInstructionsGateway as InstructionsGateway
import javax.inject.Inject

internal class DispatchDeliveryInstructionsViewModelFactory @Inject constructor(
    private val gateway: InstructionsGateway,
    private val metadata: InstructionMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(InstructionsViewModel::class.java))
        return InstructionsViewModel(gateway, metadata) as T
    }
}
