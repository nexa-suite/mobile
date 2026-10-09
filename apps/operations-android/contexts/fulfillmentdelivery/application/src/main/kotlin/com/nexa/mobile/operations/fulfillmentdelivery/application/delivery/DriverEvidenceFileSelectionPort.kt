package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

/** Platform boundary for preparing and discarding one temporary, privately copied image. */
interface DriverEvidenceFileSelectionPort {
    suspend fun prepare(sourceUri: String, scopeKey: String): DriverProofFileCandidate?
    fun discard(candidate: DriverProofFileCandidate)
}
