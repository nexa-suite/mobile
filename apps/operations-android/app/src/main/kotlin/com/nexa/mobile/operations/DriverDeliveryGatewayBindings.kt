package com.nexa.mobile.operations

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDriverDeliveryGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverDeliveryViewModel
import javax.inject.Inject

/** Hands the server-authorized destination snapshot to an external navigation app. */
fun launchDriverDirections(context: Context, destination: String): Boolean {
    val address = destination.trim().takeIf(String::isNotEmpty) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(address)}".toUri())
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Integration entry for Root without feature dependencies on auth, network, or Hilt. */
internal class DriverDeliveryGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverDeliveryGateway,
    private val metadataStore: DriverAttemptMetadataStore,
    private val outcomeMetadataStore: DriverOutcomeMetadataStore,
    private val arrivalMetadataStore: DriverArrivalMetadataStore,
    private val proofMetadataStore: DriverProofMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriverDeliveryViewModel::class.java))
            return DriverDeliveryViewModel(
                gateway,
                metadataStore,
                outcomeMetadataStore = outcomeMetadataStore,
                arrivalMetadataStore = arrivalMetadataStore,
                proofMetadataStore = proofMetadataStore,
                requestBodyCodec = JsonDeliveryRequestBodyCodec()
            ) as T
        }
    }
}
