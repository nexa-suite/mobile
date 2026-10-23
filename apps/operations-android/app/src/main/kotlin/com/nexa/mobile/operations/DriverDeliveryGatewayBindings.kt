package com.nexa.mobile.operations

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.changefeed.OperationsChangeFeedGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDriverDeliveryGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverDeliveryViewModel
import java.math.BigDecimal
import javax.inject.Inject

private val driverDirectionsCoordinatesPattern =
    Regex("(?i)^geo:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)$")

/** Hands the server-authorized destination snapshot to an external navigation app. */
fun launchDriverDirections(context: Context, destination: String): Boolean {
    val address = destination.trim().takeIf(String::isNotEmpty) ?: return false
    val navigationUri = if (address.startsWith("geo:", ignoreCase = true)) {
        val match = driverDirectionsCoordinatesPattern.matchEntire(address) ?: return false
        val latitude = match.groupValues[1].toBigDecimalOrNull() ?: return false
        val longitude = match.groupValues[2].toBigDecimalOrNull() ?: return false
        if (latitude < BigDecimal.valueOf(-90) || latitude > BigDecimal.valueOf(90) ||
            longitude < BigDecimal.valueOf(-180) || longitude > BigDecimal.valueOf(180)
        ) {
            return false
        }
        "geo:${latitude.toPlainString()},${longitude.toPlainString()}".toUri()
    } else {
        "geo:0,0?q=${Uri.encode(address)}".toUri()
    }
    val navigationIntent = Intent(
        Intent.ACTION_VIEW,
        navigationUri
    )
    return try {
        if (navigationIntent.resolveActivity(context.packageManager) == null) return false
        val chooser = Intent.createChooser(navigationIntent, null)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
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
    private val changeFeedGateway: OperationsChangeFeedGateway,
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
                requestBodyCodec = JsonDeliveryRequestBodyCodec(),
                changeFeedGateway = changeFeedGateway
            ) as T
        }
    }
}
