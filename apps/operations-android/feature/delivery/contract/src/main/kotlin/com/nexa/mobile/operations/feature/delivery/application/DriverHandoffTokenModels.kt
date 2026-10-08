package com.nexa.mobile.operations.feature.delivery.application

import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffCurrentDeliveryResult
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffIssueCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffIssueResult
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffMetadataWrite

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
