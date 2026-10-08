package com.nexa.mobile.operations.feature.delivery.application

import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryOperationalExceptionCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryOperationalExceptionIntent
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryOperationalExceptionMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryOperationalExceptionMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryOperationalExceptionMutationResult
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryOperationalExceptionsLoadResult

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
