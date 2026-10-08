package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperaturePhotoCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperaturePhotoGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperaturePhotoUploadIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperaturePhotoUploadMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureScopeIdentity

/** Encrypted, all-identity-scoped exact Celsius command staging. */
interface DispatchTemperatureMetadataStore {
    suspend fun loadIntent(
        scope: DispatchTemperatureScopeIdentity,
        fulfillmentId: String
    ): DispatchTemperatureMetadataRead

    suspend fun saveIntent(intent: DispatchTemperatureIntent): DispatchTemperatureMetadataWrite

    suspend fun clearIntent(
        scope: DispatchTemperatureScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchTemperatureMetadataWrite

    suspend fun loadPhotoUploadIntent(
        scope: DispatchTemperatureScopeIdentity,
        fulfillmentId: String,
        lotId: String
    ): DispatchTemperaturePhotoUploadMetadataRead

    suspend fun savePhotoUploadIntent(
        intent: DispatchTemperaturePhotoUploadIntent
    ): DispatchTemperatureMetadataWrite

    suspend fun clearPhotoUploadIntent(
        scope: DispatchTemperatureScopeIdentity,
        fulfillmentId: String,
        lotId: String,
        idempotencyKey: String
    ): DispatchTemperatureMetadataWrite
}

/** Client port for the current fulfillment temperature view and its authorized manual evidence. */
interface DispatchTemperatureGateway {
    suspend fun current(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchTemperatureGatewayResult

    suspend fun record(
        command: DispatchTemperatureCommand,
        context: DispatchAuthorityContext
    ): DispatchTemperatureGatewayResult

    suspend fun uploadExcursionPhoto(
        warehouseId: String,
        candidate: DispatchTemperaturePhotoCandidate,
        idempotencyKey: String,
        context: DispatchAuthorityContext
    ): DispatchTemperaturePhotoGatewayResult

    suspend fun excursionPhotoStatus(
        evidenceObjectId: String,
        warehouseId: String,
        context: DispatchAuthorityContext
    ): DispatchTemperaturePhotoGatewayResult
}
