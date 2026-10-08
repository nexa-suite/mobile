package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyViewModel
import com.nexa.mobile.operations.feature.warehouse.application.InboundDiscrepancyDraftStore
import com.nexa.mobile.operations.feature.warehouse.application.InboundDiscrepancyEvidenceArtifactStore
import com.nexa.mobile.operations.feature.warehouse.application.InboundDiscrepancyGateway

internal object InboundDiscrepancyViewModelBindings {
    fun viewModelFactory(
        gateway: InboundDiscrepancyGateway,
        drafts: InboundDiscrepancyDraftStore,
        artifacts: InboundDiscrepancyEvidenceArtifactStore
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(InboundDiscrepancyViewModel::class.java))
            return InboundDiscrepancyViewModel(gateway, drafts, artifacts) as T
        }
    }
}
