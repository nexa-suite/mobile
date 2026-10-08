package com.nexa.mobile.operations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.network.NexaLotSubstitutionGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.data.AndroidLotSubstitutionMetadataBackend
import com.nexa.mobile.operations.data.AppLotSubstitutionMetadataStore
import com.nexa.mobile.operations.data.LotSubstitutionMetadataBackend
import com.nexa.mobile.operations.data.OperationsLotSubstitutionGateway
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionViewModel
import com.nexa.mobile.operations.feature.warehouse.application.LotSubstitutionGateway
import com.nexa.mobile.operations.feature.warehouse.application.LotSubstitutionMetadataStore
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
            return LotSubstitutionViewModel(gateway, store) as T
        }
    }
}
