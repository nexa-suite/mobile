package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundReceiptRequest
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingDraftMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadataStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingSubmitResult
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivedLotFacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates one durable receipt intent from freeze through its authoritative response. */
class ReceivingIntentCoordinator(
    private val gateway: ReceivingGateway,
    private val metadataStore: ReceivingMetadataStore,
    private val metadataMutex: Mutex
) {
    suspend fun execute(
        command: ReceivingIntentMetadata,
        draft: ReceivingDraftMetadata?,
        authority: ReceivingAuthority,
        isCurrent: () -> Boolean,
        onIntentPersisted: () -> Unit = {}
    ): ReceivingIntentExecution {
        if (command.scope != authority.scope ||
            command.idempotencyKey.isBlank() ||
            command.status != ReceivingIntentMetadataStatus.Pending
        ) {
            return ReceivingIntentExecution.MetadataUnavailable
        }

        val persisted = metadataMutex.withLock {
            if (!isCurrent()) return@withLock false
            val draftSaved = draft == null || safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                metadataStore.saveDraft(authority.scope, draft)
            } == ReceivingMetadataWrite.Saved
            if (draftSaved) {
                safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                    metadataStore.saveIntent(command)
                } == ReceivingMetadataWrite.Saved
            } else {
                false
            }
        }
        if (!isCurrent()) return ReceivingIntentExecution.Stale
        if (!persisted) return ReceivingIntentExecution.MetadataUnavailable

        onIntentPersisted()
        if (!isCurrent()) return ReceivingIntentExecution.Stale

        val rawResult = try {
            gateway.receive(command.request, command.idempotencyKey, authority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceivingSubmitResult.UnknownOutcome
        }
        if (!isCurrent()) return ReceivingIntentExecution.Stale

        val result = if (rawResult is ReceivingSubmitResult.Confirmed &&
            !rawResult.facts.matches(command.request)
        ) {
            ReceivingSubmitResult.UnknownOutcome
        } else {
            rawResult
        }

        return when (result) {
            is ReceivingSubmitResult.Confirmed,
            is ReceivingSubmitResult.Rejected -> {
                val cleared = clearTerminalIntent(command, isCurrent)
                if (!isCurrent()) {
                    ReceivingIntentExecution.Stale
                } else {
                    ReceivingIntentExecution.Terminal(result, cleared)
                }
            }

            ReceivingSubmitResult.UnknownOutcome,
            ReceivingSubmitResult.ServiceUnavailable,
            ReceivingSubmitResult.PermissionDenied,
            ReceivingSubmitResult.ContextInvalidated,
            ReceivingSubmitResult.SessionInvalidated -> {
                val frozen = command.copy(status = ReceivingIntentMetadataStatus.UnknownOutcome)
                val saved = metadataMutex.withLock {
                    safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                        metadataStore.saveIntent(frozen)
                    } == ReceivingMetadataWrite.Saved
                }
                if (!isCurrent()) {
                    ReceivingIntentExecution.Stale
                } else {
                    ReceivingIntentExecution.UnknownOutcome(result, saved)
                }
            }
        }
    }

    /** A late successful clear must restore uncertainty for the original scoped command. */
    private suspend fun clearTerminalIntent(
        command: ReceivingIntentMetadata,
        isCurrent: () -> Boolean
    ): Boolean = metadataMutex.withLock {
        if (!isCurrent()) return@withLock false
        val cleared = safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
            metadataStore.clearIntent(command.scope, command.idempotencyKey)
        } == ReceivingMetadataWrite.Saved
        if (!isCurrent()) {
            if (cleared) {
                safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                    metadataStore.saveIntent(
                        command.copy(status = ReceivingIntentMetadataStatus.UnknownOutcome)
                    )
                }
            }
            false
        } else {
            cleared
        }
    }

    private suspend fun <T> safeMetadataCall(fallback: T, operation: suspend () -> T): T = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback
    }

    private fun ReceivedLotFacts.matches(request: InboundReceiptRequest): Boolean =
        id.isNotBlank() && warehouseId == request.warehouseId && zoneId == request.zoneId &&
            (request.catalogItemId == null || catalogItemId == request.catalogItemId) &&
            (request.skuId == null || skuId == request.skuId) &&
            batchNumber == request.batchNumber && expirationDate == request.expirationDate &&
            onHand.compareTo(request.quantity) == 0 && unit.equals(request.unit, ignoreCase = true)
}

sealed interface ReceivingIntentExecution {
    data object MetadataUnavailable : ReceivingIntentExecution

    data object Stale : ReceivingIntentExecution

    data class Terminal(val result: ReceivingSubmitResult, val intentCleared: Boolean) :
        ReceivingIntentExecution

    data class UnknownOutcome(val reason: ReceivingSubmitResult, val intentPersisted: Boolean) :
        ReceivingIntentExecution
}
