package com.nexa.mobile.operations.feature.delivery.application

import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentCurrentDeliveryResult
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceAttachCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceResult
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceUploadCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentResult
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentSelectionContext
import com.nexa.mobile.operations.feature.delivery.model.DriverProofFileCandidate

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
