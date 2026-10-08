package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptStartCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptStartResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofAttachCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofAttachResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofCreateCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofCreateResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofEvidenceStatusResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofUploadCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofUploadResult

interface DriverArrivalMetadataStore {
    suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverArrivalMetadataRead
    suspend fun saveIntent(intent: DriverArrivalIntentMetadata): DriverArrivalMetadataWrite
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverArrivalMetadataWrite
}

interface DriverOutcomeMetadataStore {
    suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverOutcomeMetadataRead
    suspend fun saveIntent(intent: DriverOutcomeIntentMetadata): DriverOutcomeMetadataWrite
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverOutcomeMetadataWrite
}

interface DriverAttemptMetadataStore {
    suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverAttemptMetadataRead
    suspend fun saveIntent(intent: DriverAttemptIntentMetadata): DriverAttemptMetadataWrite
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverAttemptMetadataWrite
}

/** Feature boundary for server-authorized driver delivery reads and start command. */
interface DriverDeliveryGateway {
    suspend fun assignedDeliveries(authority: DriverDeliveryAuthority): DriverDeliveryLoadResult

    suspend fun delivery(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryLoadResult

    suspend fun startAttempt(
        command: DriverAttemptStartCommand,
        authority: DriverDeliveryAuthority
    ): DriverAttemptStartResult

    suspend fun recordOutcome(
        command: DriverOutcomeCommand,
        authority: DriverDeliveryAuthority
    ): DriverOutcomeResult

    suspend fun signalArrival(
        command: DriverArrivalCommand,
        authority: DriverDeliveryAuthority
    ): DriverArrivalResult

    suspend fun createProof(
        command: DriverProofCreateCommand,
        authority: DriverDeliveryAuthority
    ): DriverProofCreateResult = DriverProofCreateResult.ServiceUnavailable

    suspend fun uploadProofEvidence(
        command: DriverProofUploadCommand,
        authority: DriverDeliveryAuthority
    ): DriverProofUploadResult = DriverProofUploadResult.ServiceUnavailable

    suspend fun proofEvidenceStatus(
        evidenceId: String,
        proofId: String,
        authority: DriverDeliveryAuthority
    ): DriverProofEvidenceStatusResult = DriverProofEvidenceStatusResult.ServiceUnavailable

    suspend fun attachProofEvidence(
        command: DriverProofAttachCommand,
        authority: DriverDeliveryAuthority
    ): DriverProofAttachResult = DriverProofAttachResult.ServiceUnavailable
}
