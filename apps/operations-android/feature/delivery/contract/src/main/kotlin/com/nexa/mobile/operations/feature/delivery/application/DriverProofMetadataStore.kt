package com.nexa.mobile.operations.feature.delivery.application

import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverProofFileCandidate
import com.nexa.mobile.operations.feature.delivery.model.DriverProofIntentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverProofMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverProofMetadataWrite

interface DriverProofMetadataStore {
    suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverProofMetadataRead
    suspend fun saveIntent(intent: DriverProofIntentMetadata): DriverProofMetadataWrite
    suspend fun stageCandidate(
        intent: DriverProofIntentMetadata,
        candidate: DriverProofFileCandidate
    ): DriverProofMetadataWrite
    suspend fun loadCandidate(intent: DriverProofIntentMetadata): DriverProofFileCandidate?
    suspend fun clearCandidate(intent: DriverProofIntentMetadata): Boolean
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        createIdempotencyKey: String
    ): DriverProofMetadataWrite
}
