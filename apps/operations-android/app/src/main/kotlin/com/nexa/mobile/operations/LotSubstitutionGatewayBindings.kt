package com.nexa.mobile.operations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.LotSubstitutionGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.LotSubstitutionMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.AndroidLotSubstitutionMetadataBackend
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.AppLotSubstitutionMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.LotSubstitutionMetadataBackend
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.OperationsLotSubstitutionGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.serialization.warehouse.CanonicalWarehouseFrozenPayloadCodec
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaLotSubstitutionGateway
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.LotSubstitutionViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object LotSubstitutionGatewayBindings {
    @Provides
    @Singleton
    fun nexLotSubstitutionGateway(calls: ProtectedCallExecutor): NexaLotSubstitutionGateway =
        NexaLotSubstitutionGateway(calls)

    @Provides
    @Singleton
    fun metadataBackend(@ApplicationContext context: Context): LotSubstitutionMetadataBackend =
        AndroidLotSubstitutionMetadataBackend(context)

    @Provides
    @Singleton
    fun metadataStore(backend: LotSubstitutionMetadataBackend): LotSubstitutionMetadataStore =
        AppLotSubstitutionMetadataStore(backend)

    @Provides
    @Singleton
    fun gateway(implementation: OperationsLotSubstitutionGateway): LotSubstitutionGateway =
        implementation

    @Provides
    fun viewModelFactory(
        gateway: LotSubstitutionGateway,
        store: LotSubstitutionMetadataStore
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LotSubstitutionViewModel::class.java))
            return LotSubstitutionViewModel(
                gateway,
                store,
                payloadCodec = CanonicalWarehouseFrozenPayloadCodec()
            ) as T
        }
    }
}
