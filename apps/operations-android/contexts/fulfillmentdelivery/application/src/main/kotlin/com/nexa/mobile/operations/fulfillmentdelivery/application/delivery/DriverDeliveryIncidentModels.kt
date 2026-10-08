package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentCurrentDeliveryResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceAttachCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceUploadCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentSelectionContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofFileCandidate

interface DriverIncidentMetadataStore {
    suspend fun load(scope: DriverAttemptScopeIdentity): DriverIncidentMetadataRead
    suspend fun saveDraft(metadata: DriverIncidentMetadata): DriverIncidentMetadataWrite
    suspend fun persistIntent(metadata: DriverIncidentMetadata): DriverIncidentMetadataWrite
    suspend fun persistRecorded(metadata: DriverIncidentMetadata): DriverIncidentMetadataWrite
    suspend fun stageReturnedEvidence(
        context: DriverIncidentSelectionContext,
        candidate: DriverProofFileCandidate
    ): DriverIncidentMetadataWrite
    suspend fun updateRecordedEvidence(
        metadata: DriverIncidentMetadata
    ): DriverIncidentMetadataWrite
    suspend fun loadCandidate(metadata: DriverIncidentMetadata): DriverProofFileCandidate?
    suspend fun clearCandidate(metadata: DriverIncidentMetadata): Boolean
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        deliveryId: String,
        idempotencyKey: String
    ): DriverIncidentMetadataWrite
}

interface DriverIncidentGateway {
    suspend fun currentDelivery(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverIncidentCurrentDeliveryResult

    suspend fun recordIncident(
        command: DriverIncidentCommand,
        authority: DriverDeliveryAuthority
    ): DriverIncidentResult

    suspend fun uploadEvidence(
        command: DriverIncidentEvidenceUploadCommand,
        authority: DriverDeliveryAuthority
    ): DriverIncidentEvidenceResult

    suspend fun evidenceStatus(
        evidenceId: String,
        authority: DriverDeliveryAuthority
    ): DriverIncidentEvidenceResult

    suspend fun attachEvidence(
        command: DriverIncidentEvidenceAttachCommand,
        authority: DriverDeliveryAuthority
    ): DriverIncidentResult
}
