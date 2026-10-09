package com.nexa.mobile.operations.salescommitment.application.commercial

import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.CancellationException

sealed interface DirectOrderExecution {
    data object MetadataUnavailable : DirectOrderExecution
    data object ContextLost : DirectOrderExecution
    data class Resolved(val record: DirectOrderRecord, val persisted: Boolean) :
        DirectOrderExecution
}

/** Persists a frozen client intent before transport and retains its resolution after route loss. */
class DirectOrderSubmissionCoordinator(
    private val gateway: DirectOrderGateway,
    private val store: DirectOrderStore
) {
    suspend fun execute(
        authority: CommercialAuthority,
        record: DirectOrderRecord,
        contextIsCurrent: () -> Boolean
    ): DirectOrderExecution {
        val intent = requireNotNull(record.intent)
        if (!contextIsCurrent()) return DirectOrderExecution.ContextLost
        if (!store.save(authority, record)) return DirectOrderExecution.MetadataUnavailable
        if (!contextIsCurrent()) return DirectOrderExecution.ContextLost
        val result = try {
            gateway.submit(authority, intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DirectOrderSubmission.UnknownOutcome
        }
        val outcome = when (result) {
            is DirectOrderSubmission.Confirmed -> "Confirmed"
            is DirectOrderSubmission.PrepaidPending -> "PrepaidPending"
            DirectOrderSubmission.Conflict -> "Conflict"
            DirectOrderSubmission.PermissionDenied -> "PermissionDenied"
            DirectOrderSubmission.Rejected -> "Rejected"
            DirectOrderSubmission.Unavailable -> "Unavailable"
            DirectOrderSubmission.LegacyIntent -> "LegacyIntent"
            else -> "UnknownOutcome"
        }
        val receipt = when (result) {
            is DirectOrderSubmission.Confirmed -> result.receipt
            is DirectOrderSubmission.PrepaidPending -> result.receipt
            else -> null
        }
        val updated = record.copy(
            intent = intent.copy(
                outcome = outcome,
                receiptId = receipt?.id,
                receipt = receipt
            )
        )
        return DirectOrderExecution.Resolved(updated, store.save(authority, updated))
    }
}
