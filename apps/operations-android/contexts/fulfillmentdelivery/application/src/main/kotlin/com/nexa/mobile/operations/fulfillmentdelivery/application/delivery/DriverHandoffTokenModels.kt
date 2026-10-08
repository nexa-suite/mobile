package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffCurrentDeliveryResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffIssueCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffIssueResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffMetadataWrite

interface DriverHandoffTokenMetadataStore {
    suspend fun load(
        scope: DriverAttemptScopeIdentity,
        deliveryId: String,
        attemptId: String
    ): DriverHandoffMetadataRead

    suspend fun persistIntent(
        scope: DriverAttemptScopeIdentity,
        command: DriverHandoffIssueCommand
    ): DriverHandoffMetadataWrite

    suspend fun clearKnownRejection(
        scope: DriverAttemptScopeIdentity,
        deliveryId: String,
        attemptId: String,
        idempotencyKey: String
    ): DriverHandoffMetadataWrite
}

interface DriverHandoffTokenGateway {
    suspend fun currentDelivery(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverHandoffCurrentDeliveryResult

    suspend fun issue(
        command: DriverHandoffIssueCommand,
        authority: DriverDeliveryAuthority
    ): DriverHandoffIssueResult
}
