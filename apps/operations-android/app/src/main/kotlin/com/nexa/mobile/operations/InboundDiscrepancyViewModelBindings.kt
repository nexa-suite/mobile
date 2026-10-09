package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.InboundDiscrepancyDraftStore
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.InboundDiscrepancyEvidenceArtifactStore
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.InboundDiscrepancyGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceSelectionCoordinator
import com.nexa.mobile.operations.inventoryavailability.infrastructure.serialization.warehouse.CanonicalInboundDiscrepancyPayloadCodec
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.InboundDiscrepancyViewModel

internal object InboundDiscrepancyViewModelBindings {
    fun viewModelFactory(
        gateway: InboundDiscrepancyGateway,
        drafts: InboundDiscrepancyDraftStore,
        artifacts: InboundDiscrepancyEvidenceArtifactStore,
        evidenceSelection: WarehouseEvidenceSelectionCoordinator
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(InboundDiscrepancyViewModel::class.java))
            return InboundDiscrepancyViewModel(
                gateway,
                drafts,
                artifacts,
                CanonicalInboundDiscrepancyPayloadCodec(),
                evidenceSelection = evidenceSelection
            ) as T
        }
    }
}
