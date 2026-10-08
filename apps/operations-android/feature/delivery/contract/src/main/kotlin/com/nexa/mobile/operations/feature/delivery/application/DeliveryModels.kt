package com.nexa.mobile.operations.feature.delivery.application

import com.nexa.mobile.operations.feature.delivery.model.DriverArrivalCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverArrivalIntentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverArrivalMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverArrivalMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverArrivalResult
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptIntentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptStartCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptStartResult
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryLoadResult
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeIntentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeResult
import com.nexa.mobile.operations.feature.delivery.model.DriverProofAttachCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverProofAttachResult
import com.nexa.mobile.operations.feature.delivery.model.DriverProofCreateCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverProofCreateResult
import com.nexa.mobile.operations.feature.delivery.model.DriverProofEvidenceStatusResult
import com.nexa.mobile.operations.feature.delivery.model.DriverProofUploadCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverProofUploadResult

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
