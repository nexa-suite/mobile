package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.payments.infrastructure.adapters.AppWalletRefundIntentStore
import com.nexa.mobile.operations.payments.infrastructure.transport.NexaWalletRefundGateway
import com.nexa.mobile.operations.payments.presentation.refund.WalletRefundViewModel
import javax.inject.Inject

internal class WalletRefundViewModelFactory @Inject constructor(
    private val gateway: NexaWalletRefundGateway,
    private val store: AppWalletRefundIntentStore
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(WalletRefundViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return WalletRefundViewModel(gateway, store) as T
    }
}
