package com.nexa.mobile.operations.salescommitment.application.commercial

import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestRecord
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.CancellationException

sealed interface FieldRequestExecution {
    data object MetadataUnavailable : FieldRequestExecution
    data object ContextLost : FieldRequestExecution
    data class Resolved(val record: FieldRequestRecord, val persisted: Boolean) :
        FieldRequestExecution
}

/** Persists a frozen client intent before transport and retains its resolution after route loss. */
class FieldRequestSubmissionCoordinator(
    private val gateway: FieldRequestGateway,
    private val store: FieldRequestStore
) {
    suspend fun execute(
        authority: CommercialAuthority,
        record: FieldRequestRecord,
        contextIsCurrent: () -> Boolean
    ): FieldRequestExecution {
        val intent = requireNotNull(record.intent)
        if (!contextIsCurrent()) return FieldRequestExecution.ContextLost
        if (!store.save(authority, record)) return FieldRequestExecution.MetadataUnavailable
        if (!contextIsCurrent()) return FieldRequestExecution.ContextLost
        val result = try {
            gateway.submit(authority, intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            FieldRequestSubmission.UnknownOutcome
        }
        val outcome = when (result) {
            is FieldRequestSubmission.Confirmed -> "Confirmed"
            is FieldRequestSubmission.PrepaidPending -> "PrepaidPending"
            FieldRequestSubmission.Conflict -> "Conflict"
            FieldRequestSubmission.PermissionDenied -> "PermissionDenied"
            FieldRequestSubmission.Rejected -> "Rejected"
            FieldRequestSubmission.Unavailable -> "Unavailable"
            FieldRequestSubmission.LegacyIntent -> "LegacyIntent"
            else -> "UnknownOutcome"
        }
        val receipt = when (result) {
            is FieldRequestSubmission.Confirmed -> result.receipt
            is FieldRequestSubmission.PrepaidPending -> result.receipt
            else -> null
        }
        val updated = record.copy(
            intent = intent.copy(
                outcome = outcome,
                receiptId = receipt?.id,
                receipt = receipt
            )
        )
        return FieldRequestExecution.Resolved(updated, store.save(authority, updated))
    }
}
