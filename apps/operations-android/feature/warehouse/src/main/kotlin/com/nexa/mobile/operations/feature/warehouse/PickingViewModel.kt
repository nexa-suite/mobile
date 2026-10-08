package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.feature.warehouse.application.PickingGateway
import com.nexa.mobile.operations.feature.warehouse.application.PickingMetadataStore
import com.nexa.mobile.operations.feature.warehouse.model.FulfillmentPickingSnapshot
import com.nexa.mobile.operations.feature.warehouse.model.PickingAllocationProjection
import com.nexa.mobile.operations.feature.warehouse.model.PickingAuthority
import com.nexa.mobile.operations.feature.warehouse.model.PickingConfirmationCommand
import com.nexa.mobile.operations.feature.warehouse.model.PickingFulfillmentSnapshot
import com.nexa.mobile.operations.feature.warehouse.model.PickingIntentCommand
import com.nexa.mobile.operations.feature.warehouse.model.PickingIntentMetadata
import com.nexa.mobile.operations.feature.warehouse.model.PickingIntentMetadataStatus
import com.nexa.mobile.operations.feature.warehouse.model.PickingLoadResult
import com.nexa.mobile.operations.feature.warehouse.model.PickingMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.PickingMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.PickingMutationResult
import com.nexa.mobile.operations.feature.warehouse.model.PickingOffer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PickingLoadStatus {
    NotRequested,
    Loading,
    Ready,
    AllocationUnavailable,
    NotFound,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class PickingMetadataStatus { Loading, Available, Unavailable }

enum class PickingCommandStatus {
    Editing,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Confirmed,
    Rejected,
    StaleVersion
}

enum class PickingValidationError {
    FulfillmentUnavailable,
    AllocationUnavailable,
    AllocationNotReady,
    OfferRequired,
    AmbiguousFulfillmentLine,
    LotIdentifierRequired,
    LotIdentifierMismatch,
    QuantityRequired,
    QuantityInvalid,
    QuantityMustBePositive,
    QuantityExceedsRemaining,
    MetadataUnavailable,
    RefreshRequired
}

data class PickingUiState(
    val authorityEpoch: Long = 0,
    val canRead: Boolean = false,
    val canPick: Boolean = false,
    val fulfillmentId: String = "",
    val loadStatus: PickingLoadStatus = PickingLoadStatus.NotRequested,
    val fulfillment: FulfillmentPickingSnapshot? = null,
    val allocation: PickingAllocationProjection? = null,
    val selectedAllocationLineId: String? = null,
    val lotIdentifierText: String = "",
    val quantityText: String = "",
    val metadata: PickingMetadataStatus = PickingMetadataStatus.Loading,
    val command: PickingCommandStatus = PickingCommandStatus.Editing,
    val validationError: PickingValidationError? = null,
    val rejectionCode: String? = null,
    val confirmedLotId: String? = null,
    val confirmedWarehouseId: String? = null,
    val confirmedFulfillment: FulfillmentPickingSnapshot? = null,
    val intentCleanupPending: Boolean = false,
    val notice: PickingNotice? = null
) {
    val offers: List<PickingOffer>
        get() {
            val currentFulfillment = fulfillment ?: return emptyList()
            val currentAllocation = allocation ?: return emptyList()
            return PickingFulfillmentSnapshot(currentFulfillment, currentAllocation).offers()
        }

    val selectedOffer: PickingOffer?
        get() = offers.singleOrNull {
            it.allocationLine.physicalAllocationLineId ==
                selectedAllocationLineId
        }

    val isIntentFrozen: Boolean
        get() = command in setOf(
            PickingCommandStatus.PersistingIntent,
            PickingCommandStatus.Pending,
            PickingCommandStatus.UnknownOutcome
        ) || (
            (
                command == PickingCommandStatus.Confirmed ||
                    command == PickingCommandStatus.Rejected ||
                    command == PickingCommandStatus.StaleVersion
                ) && intentCleanupPending
            )

    override fun toString(): String =
        "PickingUiState(epoch=$authorityEpoch, load=$loadStatus, command=$command, " +
            "offers=${offers.size}, metadata=$metadata)"
}

enum class PickingNotice {
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    IntentMetadataUnavailable,
    RefreshRequired
}

/** Picking controller. FEFO, lot eligibility, and consumed stock remain server-owned. */
class PickingViewModel(
    private val gateway: PickingGateway,
    private val metadataStore: PickingMetadataStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(PickingUiState())
    val state = mutableState.asStateFlow()

    private var authority: PickingAuthority? = null
    private var generation = 0L
    private var intent: PickingIntentMetadata? = null
    private val metadataMutex = Mutex()

    fun activate(currentAuthority: PickingAuthority, fulfillmentId: String) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        intent = null
        mutableState.value = PickingUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canRead = currentAuthority.canRead,
            canPick = currentAuthority.canPick,
            fulfillmentId = fulfillmentId,
            loadStatus = if (currentAuthority.canRead) {
                PickingLoadStatus.Loading
            } else {
                PickingLoadStatus.PermissionDenied
            },
            metadata = PickingMetadataStatus.Loading
        )
        viewModelScope.launch {
            val read = metadataMutex.withLock {
                safeMetadataCall(PickingMetadataRead.Unavailable) {
                    metadataStore.loadIntent(currentAuthority.scope)
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val restored = (read as? PickingMetadataRead.Available)
                ?.value
                ?.takeIf {
                    it.scope == currentAuthority.scope &&
                        it.idempotencyKey.isNotBlank() &&
                        it.command.fulfillmentId() != null
                }
            intent = restored?.copy(status = PickingIntentMetadataStatus.UnknownOutcome)
            val metadataAvailable = read is PickingMetadataRead.Available
            mutableState.update { current ->
                current.copy(
                    fulfillmentId = restored?.command?.fulfillmentId() ?: fulfillmentId,
                    metadata = if (metadataAvailable) {
                        PickingMetadataStatus.Available
                    } else {
                        PickingMetadataStatus.Unavailable
                    },
                    command = if (restored != null) {
                        PickingCommandStatus.UnknownOutcome
                    } else {
                        PickingCommandStatus.Editing
                    },
                    lotIdentifierText = (restored?.command as? PickingIntentCommand.Confirm)
                        ?.request?.lotId.orEmpty(),
                    quantityText = (restored?.command as? PickingIntentCommand.Confirm)
                        ?.request?.quantity?.toPlainString().orEmpty(),
                    validationError = if (metadataAvailable) {
                        null
                    } else {
                        PickingValidationError.MetadataUnavailable
                    },
                    notice = if (metadataAvailable) {
                        null
                    } else {
                        PickingNotice.IntentMetadataUnavailable
                    }
                )
            }
            if (metadataAvailable && restored?.status == PickingIntentMetadataStatus.Pending) {
                val saved = metadataMutex.withLock {
                    if (!isCurrent(requestGeneration, currentAuthority)) {
                        PickingMetadataWrite.Unavailable
                    } else {
                        safeMetadataCall(PickingMetadataWrite.Unavailable) {
                            metadataStore.saveIntent(
                                restored.copy(status = PickingIntentMetadataStatus.UnknownOutcome)
                            )
                        }
                    }
                }
                if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                if (saved != PickingMetadataWrite.Saved) {
                    mutableState.update {
                        it.copy(
                            metadata = PickingMetadataStatus.Unavailable,
                            notice = PickingNotice.IntentMetadataUnavailable
                        )
                    }
                }
            }
            if (currentAuthority.canRead) load(requestGeneration, currentAuthority)
        }
    }

    fun invalidate() {
        generation++
        authority = null
        intent = null
        mutableState.value = PickingUiState(
            authorityEpoch = mutableState.value.authorityEpoch + 1,
            loadStatus = PickingLoadStatus.SessionInvalidated,
            metadata = PickingMetadataStatus.Loading
        )
    }

    fun reload() {
        val currentAuthority = authority ?: return
        load(generation, currentAuthority)
    }

    fun selectOffer(physicalAllocationLineId: String) {
        if (!canEdit()) return
        val offer = mutableState.value.offers.singleOrNull {
            it.allocationLine.physicalAllocationLineId == physicalAllocationLineId
        } ?: return
        mutableState.update {
            it.copy(
                selectedAllocationLineId = physicalAllocationLineId,
                lotIdentifierText = "",
                quantityText = offer.allocationLine.remainingQuantity.toPlainString(),
                validationError = null,
                rejectionCode = null
            )
        }
    }

    fun lotIdentifierChanged(value: String) {
        if (!canEdit()) return
        mutableState.update { it.copy(lotIdentifierText = value, validationError = null) }
    }

    fun quantityChanged(value: String) {
        if (!canEdit()) return
        mutableState.update { it.copy(quantityText = value, validationError = null) }
    }

    fun startPicking() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!canEdit() || !currentAuthority.canPick) return
        val fulfillment =
            current.fulfillment ?: return showError(PickingValidationError.FulfillmentUnavailable)
        if (current.metadata != PickingMetadataStatus.Available) {
            return showError(PickingValidationError.MetadataUnavailable)
        }
        beginIntent(
            PickingIntentCommand.Start(fulfillment.id, fulfillment.version),
            currentAuthority
        )
    }

    fun confirmPick() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!canEdit() || !currentAuthority.canPick) return
        if (current.metadata != PickingMetadataStatus.Available) {
            return showError(PickingValidationError.MetadataUnavailable)
        }
        val fulfillment = current.fulfillment
            ?: return showError(PickingValidationError.FulfillmentUnavailable)
        val allocation = current.allocation
            ?: return showError(PickingValidationError.AllocationUnavailable)
        if (!allocation.status.equals("ALLOCATED", ignoreCase = true)) {
            return showError(PickingValidationError.AllocationNotReady)
        }
        if (!fulfillment.status.equals("PICKING", ignoreCase = true)) {
            return showError(PickingValidationError.RefreshRequired)
        }
        val offer = current.selectedOffer ?: return showError(PickingValidationError.OfferRequired)
        if (offer.ambiguousFulfillmentMatch) {
            return showError(PickingValidationError.AmbiguousFulfillmentLine)
        }
        if (!offer.isPickable) return showError(PickingValidationError.AllocationNotReady)
        if (current.lotIdentifierText.isBlank()) {
            return showError(PickingValidationError.LotIdentifierRequired)
        }
        if (!current.lotIdentifierText.trim().equals(
                offer.allocationLine.lotId,
                ignoreCase = true
            )
        ) {
            return showError(PickingValidationError.LotIdentifierMismatch)
        }
        if (current.quantityText.isBlank()) {
            return showError(PickingValidationError.QuantityRequired)
        }
        val quantity = current.quantityText.trim().toBigDecimalOrNull()
            ?: return showError(PickingValidationError.QuantityInvalid)
        if (quantity.signum() <= 0) return showError(PickingValidationError.QuantityMustBePositive)
        val line = offer.fulfillmentLine
            ?: return showError(PickingValidationError.AmbiguousFulfillmentLine)
        val allocationLine = offer.allocationLine
        if (quantity > allocationLine.remainingQuantity || quantity > line.remainingQuantity) {
            return showError(PickingValidationError.QuantityExceedsRemaining)
        }
        val request = PickingConfirmationCommand(
            fulfillmentId = fulfillment.id,
            expectedFulfillmentVersion = fulfillment.version,
            allocationVersion = allocation.version,
            fulfillmentLineId = line.id,
            skuId = allocationLine.skuId,
            physicalAllocationLineId = allocationLine.physicalAllocationLineId,
            lotId = allocationLine.lotId,
            warehouseId = allocationLine.warehouseId,
            quantity = quantity,
            unit = allocationLine.unit
        )
        beginIntent(PickingIntentCommand.Confirm(request), currentAuthority)
    }

    /** Explicitly replays only the exact persisted key and command after an unknown outcome. */
    fun retryUnknownOutcome() {
        if (mutableState.value.command != PickingCommandStatus.UnknownOutcome) return
        val currentAuthority = authority ?: return
        val frozen = intent ?: return showError(PickingValidationError.MetadataUnavailable)
        if (frozen.scope != currentAuthority.scope || !currentAuthority.canPick) return
        mutableState.update {
            it.copy(command = PickingCommandStatus.PersistingIntent, validationError = null)
        }
        val requestGeneration = generation
        val pending = frozen.copy(status = PickingIntentMetadataStatus.Pending)
        intent = pending
        viewModelScope.launch {
            val saved = metadataMutex.withLock {
                if (!isCurrent(requestGeneration, currentAuthority)) {
                    PickingMetadataWrite.Unavailable
                } else {
                    safeMetadataCall(PickingMetadataWrite.Unavailable) {
                        metadataStore.saveIntent(pending)
                    }
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != PickingMetadataWrite.Saved) {
                intent = frozen.copy(status = PickingIntentMetadataStatus.UnknownOutcome)
                mutableState.update {
                    it.copy(
                        command = PickingCommandStatus.UnknownOutcome,
                        metadata = PickingMetadataStatus.Unavailable,
                        notice = PickingNotice.IntentMetadataUnavailable
                    )
                }
                return@launch
            }
            mutableState.update { it.copy(command = PickingCommandStatus.Pending) }
            val result = execute(pending, currentAuthority)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            handleResult(pending, result, requestGeneration, currentAuthority)
        }
    }

    fun retryIntentCleanup() {
        val frozen = intent ?: return
        val currentAuthority = authority ?: return
        if (!mutableState.value.intentCleanupPending ||
            frozen.scope != currentAuthority.scope
        ) {
            return
        }
        val requestGeneration = generation
        viewModelScope.launch {
            val cleared = clearIntent(frozen, requestGeneration, currentAuthority)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (cleared) {
                intent = null
                mutableState.update {
                    it.copy(
                        intentCleanupPending = false,
                        metadata = PickingMetadataStatus.Available
                    )
                }
            } else {
                mutableState.update {
                    it.copy(
                        metadata = PickingMetadataStatus.Unavailable,
                        notice = PickingNotice.IntentMetadataUnavailable
                    )
                }
            }
        }
    }

    private fun beginIntent(command: PickingIntentCommand, currentAuthority: PickingAuthority) {
        val current = mutableState.value
        if (current.isIntentFrozen || current.command in setOf(
                PickingCommandStatus.Confirmed,
                PickingCommandStatus.Rejected,
                PickingCommandStatus.StaleVersion
            ) || current.metadata != PickingMetadataStatus.Available
        ) {
            return
        }
        val pending = PickingIntentMetadata(
            scope = currentAuthority.scope,
            idempotencyKey = UUID.randomUUID().toString(),
            command = command,
            status = PickingIntentMetadataStatus.Pending
        )
        val requestGeneration = generation
        intent = pending
        mutableState.update {
            it.copy(
                command = PickingCommandStatus.PersistingIntent,
                validationError = null,
                rejectionCode = null,
                confirmedLotId = null,
                confirmedWarehouseId = null,
                confirmedFulfillment = null,
                intentCleanupPending = false
            )
        }
        viewModelScope.launch {
            val saved = metadataMutex.withLock {
                if (!isCurrent(requestGeneration, currentAuthority)) {
                    PickingMetadataWrite.Unavailable
                } else {
                    safeMetadataCall(PickingMetadataWrite.Unavailable) {
                        metadataStore.saveIntent(pending)
                    }
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != PickingMetadataWrite.Saved) {
                intent = null
                mutableState.update {
                    it.copy(
                        command = PickingCommandStatus.Editing,
                        metadata = PickingMetadataStatus.Unavailable,
                        validationError = PickingValidationError.MetadataUnavailable,
                        notice = PickingNotice.IntentMetadataUnavailable
                    )
                }
                return@launch
            }
            mutableState.update { it.copy(command = PickingCommandStatus.Pending) }
            val result = execute(pending, currentAuthority)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            handleResult(pending, result, requestGeneration, currentAuthority)
        }
    }

    private suspend fun execute(
        frozen: PickingIntentMetadata,
        currentAuthority: PickingAuthority
    ): PickingMutationResult = try {
        when (val command = frozen.command) {
            is PickingIntentCommand.Start -> gateway.startPicking(
                command,
                frozen.idempotencyKey,
                currentAuthority
            )

            is PickingIntentCommand.Confirm -> gateway.confirmPicking(
                command.request,
                frozen.idempotencyKey,
                currentAuthority
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PickingMutationResult.UnknownOutcome
    }

    private suspend fun handleResult(
        frozen: PickingIntentMetadata,
        result: PickingMutationResult,
        requestGeneration: Long,
        currentAuthority: PickingAuthority
    ) {
        when (result) {
            is PickingMutationResult.Confirmed -> {
                val cleared = clearIntent(frozen, requestGeneration, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority)) return
                if (cleared) intent = null
                val isConfirmation = frozen.command is PickingIntentCommand.Confirm
                val request = (frozen.command as? PickingIntentCommand.Confirm)?.request
                mutableState.update {
                    it.copy(
                        fulfillment = result.fulfillment,
                        allocation = if (isConfirmation) null else it.allocation,
                        command = if (isConfirmation) {
                            PickingCommandStatus.Confirmed
                        } else {
                            PickingCommandStatus.Editing
                        },
                        confirmedLotId = request?.lotId,
                        confirmedWarehouseId = request?.warehouseId,
                        confirmedFulfillment = if (isConfirmation) result.fulfillment else null,
                        intentCleanupPending = !cleared,
                        notice = if (cleared) null else PickingNotice.IntentMetadataUnavailable,
                        metadata = if (cleared) {
                            PickingMetadataStatus.Available
                        } else {
                            PickingMetadataStatus.Unavailable
                        },
                        validationError = if (isConfirmation) {
                            PickingValidationError.RefreshRequired
                        } else {
                            null
                        }
                    )
                }
            }

            PickingMutationResult.UnknownOutcome,
            PickingMutationResult.ContextInvalidated,
            PickingMutationResult.SessionInvalidated,
            PickingMutationResult.ServiceUnavailable -> {
                val uncertain = frozen.copy(status = PickingIntentMetadataStatus.UnknownOutcome)
                val saved = metadataMutex.withLock {
                    if (!isCurrent(requestGeneration, currentAuthority)) {
                        PickingMetadataWrite.Unavailable
                    } else {
                        safeMetadataCall(PickingMetadataWrite.Unavailable) {
                            metadataStore.saveIntent(uncertain)
                        }
                    }
                }
                if (!isCurrent(requestGeneration, currentAuthority)) return
                intent = uncertain
                mutableState.update {
                    it.copy(
                        command = PickingCommandStatus.UnknownOutcome,
                        notice = if (saved == PickingMetadataWrite.Saved) {
                            result.toNotice()
                        } else {
                            PickingNotice.IntentMetadataUnavailable
                        },
                        metadata = if (saved == PickingMetadataWrite.Saved) {
                            PickingMetadataStatus.Available
                        } else {
                            PickingMetadataStatus.Unavailable
                        }
                    )
                }
            }

            PickingMutationResult.StaleVersion -> finishKnownFailure(
                frozen,
                PickingCommandStatus.StaleVersion,
                null,
                PickingNotice.RefreshRequired,
                requestGeneration,
                currentAuthority
            )

            is PickingMutationResult.Rejected -> finishKnownFailure(
                frozen,
                PickingCommandStatus.Rejected,
                result.code,
                null,
                requestGeneration,
                currentAuthority
            )

            PickingMutationResult.PermissionDenied -> finishKnownFailure(
                frozen,
                PickingCommandStatus.Rejected,
                "PERMISSION_DENIED",
                PickingNotice.PermissionDenied,
                requestGeneration,
                currentAuthority
            )
        }
    }

    private suspend fun finishKnownFailure(
        frozen: PickingIntentMetadata,
        status: PickingCommandStatus,
        code: String?,
        notice: PickingNotice?,
        requestGeneration: Long,
        currentAuthority: PickingAuthority
    ) {
        val cleared = clearIntent(frozen, requestGeneration, currentAuthority)
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared) intent = null
        mutableState.update {
            it.copy(
                command = status,
                rejectionCode = code,
                intentCleanupPending = !cleared,
                metadata = if (cleared) {
                    PickingMetadataStatus.Available
                } else {
                    PickingMetadataStatus.Unavailable
                },
                notice = if (cleared) notice else PickingNotice.IntentMetadataUnavailable,
                validationError = if (status == PickingCommandStatus.StaleVersion) {
                    PickingValidationError.RefreshRequired
                } else {
                    it.validationError
                }
            )
        }
    }

    private suspend fun clearIntent(
        frozen: PickingIntentMetadata,
        requestGeneration: Long,
        currentAuthority: PickingAuthority
    ): Boolean = metadataMutex.withLock {
        if (!isCurrent(requestGeneration, currentAuthority)) {
            false
        } else {
            safeMetadataCall(PickingMetadataWrite.Unavailable) {
                metadataStore.clearIntent(frozen.scope, frozen.idempotencyKey)
            } == PickingMetadataWrite.Saved
        }
    }

    private fun load(requestGeneration: Long, currentAuthority: PickingAuthority) {
        if (!currentAuthority.canRead || !isCurrent(requestGeneration, currentAuthority)) return
        val requestedId = mutableState.value.fulfillmentId
        mutableState.update {
            it.copy(loadStatus = PickingLoadStatus.Loading, notice = null)
        }
        viewModelScope.launch {
            val result = try {
                gateway.load(requestedId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PickingLoadResult.ServiceUnavailable
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            mutableState.update { current ->
                when (result) {
                    is PickingLoadResult.Loaded -> current.copy(
                        loadStatus = PickingLoadStatus.Ready,
                        fulfillment = result.snapshot.fulfillment,
                        allocation = result.snapshot.allocation,
                        selectedAllocationLineId = null,
                        lotIdentifierText = "",
                        quantityText = "",
                        command = if (current.command in setOf(
                                PickingCommandStatus.StaleVersion,
                                PickingCommandStatus.Rejected,
                                PickingCommandStatus.Confirmed
                            ) && !current.intentCleanupPending
                        ) {
                            PickingCommandStatus.Editing
                        } else {
                            current.command
                        },
                        validationError = null,
                        notice = null
                    )

                    is PickingLoadResult.AllocationUnavailable -> current.copy(
                        loadStatus = PickingLoadStatus.AllocationUnavailable,
                        fulfillment = result.fulfillment,
                        allocation = null,
                        selectedAllocationLineId = null,
                        validationError = PickingValidationError.AllocationUnavailable
                    )

                    PickingLoadResult.NotFound -> current.copy(
                        loadStatus = PickingLoadStatus.NotFound,
                        fulfillment = null,
                        allocation = null
                    )

                    PickingLoadResult.NetworkUnavailable -> current.copy(
                        loadStatus = PickingLoadStatus.NetworkUnavailable,
                        notice = PickingNotice.NetworkUnavailable
                    )

                    PickingLoadResult.ServiceUnavailable -> current.copy(
                        loadStatus = PickingLoadStatus.ServiceUnavailable,
                        notice = PickingNotice.ServiceUnavailable
                    )

                    PickingLoadResult.PermissionDenied -> current.copy(
                        loadStatus = PickingLoadStatus.PermissionDenied,
                        notice = PickingNotice.PermissionDenied
                    )

                    PickingLoadResult.ContextInvalidated -> current.copy(
                        loadStatus = PickingLoadStatus.ContextInvalidated,
                        notice = PickingNotice.ContextInvalidated
                    )

                    PickingLoadResult.SessionInvalidated -> current.copy(
                        loadStatus = PickingLoadStatus.SessionInvalidated,
                        notice = PickingNotice.SessionInvalidated
                    )
                }
            }
        }
    }

    private fun canEdit(): Boolean = !mutableState.value.isIntentFrozen &&
        mutableState.value.command in setOf(PickingCommandStatus.Editing)

    private fun showError(error: PickingValidationError) {
        mutableState.update { it.copy(validationError = error) }
    }

    private fun isCurrent(requestGeneration: Long, currentAuthority: PickingAuthority): Boolean =
        generation == requestGeneration && authority == currentAuthority &&
            mutableState.value.authorityEpoch == currentAuthority.authorityEpoch

    private fun PickingIntentCommand.fulfillmentId(): String? = when (this) {
        is PickingIntentCommand.Start -> fulfillmentId
        is PickingIntentCommand.Confirm -> request.fulfillmentId
    }

    private fun PickingMutationResult.toNotice(): PickingNotice? = when (this) {
        PickingMutationResult.UnknownOutcome -> PickingNotice.ServiceUnavailable
        PickingMutationResult.ContextInvalidated -> PickingNotice.ContextInvalidated
        PickingMutationResult.SessionInvalidated -> PickingNotice.SessionInvalidated
        PickingMutationResult.ServiceUnavailable -> PickingNotice.ServiceUnavailable
        else -> null
    }

    private suspend fun <T> safeMetadataCall(fallback: T, call: suspend () -> T): T = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback
    }
}
