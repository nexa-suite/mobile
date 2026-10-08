package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofFileCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofMetadataWrite

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
