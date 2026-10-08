package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsDriverIncidentGateway
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryIncidentViewModel as IncidentViewModel
import com.nexa.mobile.operations.feature.delivery.application.DriverIncidentMetadataStore as IncidentMetadataStore
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentMetadataWrite as IncidentMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentSelectionContext as IncidentSelectionContext
import com.nexa.mobile.operations.feature.delivery.model.DriverProofFileCandidate
import javax.inject.Inject
import javax.inject.Singleton

/** Factory seam for Root navigation; Root owns route construction and lifecycle. */
@Singleton
internal class DriverDeliveryIncidentGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverIncidentGateway,
    private val metadataStore: IncidentMetadataStore
) {
    suspend fun stageReturnedEvidence(
        context: IncidentSelectionContext,
        candidate: DriverProofFileCandidate
    ): IncidentMetadataWrite = metadataStore.stageReturnedEvidence(context, candidate)

    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(IncidentViewModel::class.java))
            return IncidentViewModel(gateway, metadataStore) as T
        }
    }
}
