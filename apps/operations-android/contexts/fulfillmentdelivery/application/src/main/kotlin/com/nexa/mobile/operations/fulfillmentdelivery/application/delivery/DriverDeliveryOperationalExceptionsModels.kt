package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionMutationResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionsLoadResult

interface DriverDeliveryOperationalExceptionMetadataStore {
    suspend fun loadIntent(
        scope: DriverAttemptScopeIdentity
    ): DriverDeliveryOperationalExceptionMetadataRead

    suspend fun saveIntent(
        intent: DriverDeliveryOperationalExceptionIntent
    ): DriverDeliveryOperationalExceptionMetadataWrite

    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverDeliveryOperationalExceptionMetadataWrite
}

interface DriverDeliveryOperationalExceptionsGateway {
    suspend fun currentExceptions(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryOperationalExceptionsLoadResult

    suspend fun mutate(
        command: DriverDeliveryOperationalExceptionCommand,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryOperationalExceptionMutationResult
}
