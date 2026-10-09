package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptLookupResult as ReceiptLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationMetadataRead as ObservationMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationResult as ReceiptObservationResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferScope
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptObservationMetadataStore as ObservationMetadataStore
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferReceiptObservation
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockTransferReceiptTransfer
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TransferWarehouseChoice
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockTransferReceiptObservationNotice as ReceiptObservationNotice
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.UnavailableReceiptObservationMetadataStore as MetadataStore
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Records the API's complete expected receipt; it has no client-selected lot or quantity. */
class StockTransferReceiptViewModel(
    private val gateway: StockTransferReceiptGateway,
    private val metadataStore: StockTransferReceiptMetadataStore,
    private val observationMetadataStore: ObservationMetadataStore = MetadataStore,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(StockTransferReceiptUiState())
    val state = mutableState.asStateFlow()
    private var authority: StockTransferAuthority? = null
    private var generation = 0L
    private var lookupGeneration = 0L
    private var intent: StockTransferReceiptIntent? = null
    private var observationIntent: StockTransferReceiptObservationIntent? = null
    private val metadataMutex = Mutex()
    private val observationMetadataMutex = Mutex()

    fun activate(currentAuthority: StockTransferAuthority) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        intent = null
        mutableState.value = StockTransferReceiptUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canLookUp = currentAuthority.canLookUp,
            canReceive = currentAuthority.canCreate,
            warehouseLookup = if (currentAuthority.canLookUp) {
                TransferLookupStatus.Loading
            } else {
                TransferLookupStatus.PermissionDenied
            },
            metadata = TransferMetadataStatus.Loading,
            observationMetadata = TransferMetadataStatus.Loading
        )
        reloadWarehouses()
        viewModelScope.launch {
            val read = metadataMutex.withLock {
                safeRead { metadataStore.loadIntent(currentAuthority.scope) }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val restored = (read as? StockTransferReceiptMetadataRead.Available)?.value
                ?.takeIf { it.scope == currentAuthority.scope && it.transfer.id.isNotBlank() }
            intent = restored?.copy(status = StockTransferReceiptIntentStatus.UnknownOutcome)
            mutableState.update {
                it.copy(
                    metadata = if (read is StockTransferReceiptMetadataRead.Available) {
                        TransferMetadataStatus.Available
                    } else {
                        TransferMetadataStatus.Unavailable
                    },
                    frozenIntent = intent,
                    command = if (restored == null) {
                        StockTransferReceiptCommandStatus.Editing
                    } else {
                        StockTransferReceiptCommandStatus.UnknownOutcome
                    },
                    selectedDestinationWarehouseId = restored?.transfer?.destinationWarehouseId,
                    selectedTransferId = restored?.transfer?.id,
                    notice = if (read is StockTransferReceiptMetadataRead.Available) {
                        null
                    } else {
                        StockTransferReceiptNotice.MetadataUnavailable
                    }
                )
            }
            if (restored != null) {
                if (restored.status == StockTransferReceiptIntentStatus.Pending) {
                    val marked = metadataMutex.withLock {
                        safeWrite {
                            metadataStore.markUnknownOutcome(
                                currentAuthority.scope,
                                restored.idempotencyKey
                            )
                        }
                    }
                    if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                    if (marked != StockTransferReceiptMetadataWrite.Saved) {
                        mutableState.update {
                            it.copy(
                                metadata = TransferMetadataStatus.Unavailable,
                                notice = StockTransferReceiptNotice.MetadataUnavailable
                            )
                        }
                    }
                }
                loadCurrentTransfer(requestGeneration, currentAuthority, restored.transfer.id)
            }
        }
        viewModelScope.launch {
            val read = observationMetadataMutex.withLock {
                safeObservationRead { observationMetadataStore.loadIntent(currentAuthority.scope) }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val restored = (read as? ObservationMetadataRead.Available)?.value
                ?.takeIf { it.scope == currentAuthority.scope && it.transfer.id.isNotBlank() }
            observationIntent =
                restored?.copy(status = StockTransferReceiptObservationIntentStatus.UnknownOutcome)
            mutableState.update {
                it.copy(
                    observationMetadata = if (read is ObservationMetadataRead.Available) {
                        TransferMetadataStatus.Available
                    } else {
                        TransferMetadataStatus.Unavailable
                    },
                    frozenObservationIntent = observationIntent,
                    observationCommand = if (restored ==
                        null
                    ) {
                        StockTransferReceiptObservationCommandStatus.Editing
                    } else {
                        StockTransferReceiptObservationCommandStatus.UnknownOutcome
                    },
                    selectedDestinationWarehouseId = it.selectedDestinationWarehouseId
                        ?: restored?.transfer?.destinationWarehouseId,
                    selectedTransferId = it.selectedTransferId ?: restored?.transfer?.id,
                    observationNotice = if (read is ObservationMetadataRead.Available) {
                        null
                    } else {
                        ReceiptObservationNotice.MetadataUnavailable
                    }
                )
            }
            if (restored != null) {
                if (restored.status == StockTransferReceiptObservationIntentStatus.Pending) {
                    val marked = observationMetadataMutex.withLock {
                        safeObservationWrite {
                            observationMetadataStore.markUnknownOutcome(
                                currentAuthority.scope,
                                restored.idempotencyKey
                            )
                        }
                    }
                    if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                    if (marked != StockTransferReceiptObservationMetadataWrite.Saved) {
                        mutableState.update {
                            it.copy(
                                observationMetadata = TransferMetadataStatus.Unavailable,
                                observationNotice = ReceiptObservationNotice.MetadataUnavailable
                            )
                        }
                    }
                }
                loadCurrentTransfer(requestGeneration, currentAuthority, restored.transfer.id)
            }
        }
    }

    fun deactivate() {
        generation++
        lookupGeneration++
        authority = null
        intent = null
        observationIntent = null
        mutableState.value = StockTransferReceiptUiState()
    }

    fun reloadWarehouses() {
        val currentAuthority = authority ?: return
        if (!currentAuthority.canLookUp) {
            mutableState.update { it.copy(warehouseLookup = TransferLookupStatus.PermissionDenied) }
            return
        }
        val requestGeneration = generation
        mutableState.update { it.copy(warehouseLookup = TransferLookupStatus.Loading) }
        viewModelScope.launch {
            val result = safeLookup { gateway.warehouses(currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (result) {
                is ReceiptLookupResult.Warehouses -> mutableState.update {
                    it.copy(
                        warehouses = result.items,
                        warehouseLookup =
                            if (result.items.isEmpty()) {
                                TransferLookupStatus.Empty
                            } else {
                                TransferLookupStatus.Ready
                            },
                        notice = null
                    )
                }

                else -> mutableState.update { it.copy(warehouseLookup = result.toLookupStatus()) }
            }
        }
    }

    fun selectDestinationWarehouse(warehouseId: String) {
        if (!canEditSelection()) return
        val warehouse = mutableState.value.warehouses.singleOrNull { it.id == warehouseId }
            ?.takeIf(TransferWarehouseChoice::isSelectable) ?: return
        lookupGeneration++
        mutableState.update {
            it.copy(
                selectedDestinationWarehouseId = warehouse.id,
                transfers = emptyList(),
                transferLookup = TransferLookupStatus.Loading,
                page = 0,
                total = 0,
                selectedTransferId = null,
                confirmed = null,
                notice = null
            )
        }
        loadTransfers(warehouse.id, 0, append = false)
    }

    fun loadMoreTransfers() {
        val current = mutableState.value
        val warehouseId = current.selectedDestinationWarehouseId ?: return
        if (!current.hasMoreTransfers || current.isFrozen ||
            current.transferLookup == TransferLookupStatus.Loading
        ) {
            return
        }
        loadTransfers(warehouseId, current.page + 1, append = true)
    }

    fun selectTransfer(transferId: String) {
        if (!canEditSelection()) return
        val transfer = mutableState.value.transfers.singleOrNull { it.id == transferId } ?: return
        if (transfer.destinationWarehouseId !=
            mutableState.value.selectedDestinationWarehouseId
        ) {
            return
        }
        mutableState.update {
            it.copy(
                selectedTransferId = transfer.id,
                confirmed = null,
                notice = if (transfer.canReceiveExpectedQuantity) {
                    null
                } else {
                    StockTransferReceiptNotice.ExpectedQuantityOnly
                }
            )
        }
    }

    fun receiveExpectedQuantity() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val transfer = current.selectedTransfer ?: return
        if (current.recordedObservation?.let { it.transferId == transfer.id && it.hasDifference } ==
            true
        ) {
            mutableState.update {
                it.copy(notice = StockTransferReceiptNotice.ObservedDifferenceRequiresResolution)
            }
            return
        }
        if (!current.canReceive || current.isFrozen ||
            current.metadata != TransferMetadataStatus.Available
        ) {
            return
        }
        if (!transfer.canReceiveExpectedQuantity ||
            transfer.destinationWarehouseId != current.selectedDestinationWarehouseId
        ) {
            mutableState.update {
                it.copy(notice = StockTransferReceiptNotice.ExpectedQuantityOnly)
            }
            return
        }
        val command = StockTransferReceiptIntent(
            scope = currentAuthority.scope,
            idempotencyKey = newIdempotencyKey(),
            transfer = transfer,
            status = StockTransferReceiptIntentStatus.Pending
        )
        intent = command
        mutableState.update {
            it.copy(
                frozenIntent = command,
                command = StockTransferReceiptCommandStatus.PersistingIntent,
                notice = null
            )
        }
        persistThenReceive(command, currentAuthority, generation)
    }

    /** Freezes and records observed arrival facts without calling the stock-receipt command. */
    fun observeArrival(batch: String, expiry: String?, quantity: String, unit: String) {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val transfer = current.selectedTransfer ?: return
        if (!current.canReceive || current.isFrozen ||
            current.observationMetadata != TransferMetadataStatus.Available ||
            current.metadata != TransferMetadataStatus.Available ||
            current.command == StockTransferReceiptCommandStatus.Conflict ||
            current.observationCommand == StockTransferReceiptObservationCommandStatus.Conflict
        ) {
            return
        }
        val observedBatch = batch.trim()
        if (observedBatch.isBlank() || observedBatch.length > MAX_OBSERVED_BATCH_LENGTH ||
            observedBatch.any(Char::isISOControl)
        ) {
            rejectObservationInput(ReceiptObservationNotice.InvalidBatch)
            return
        }
        val observedExpiration = expiry?.trim()?.takeIf(String::isNotEmpty)
        if (observedExpiration != null && !observedExpiration.isCanonicalLocalDate()) {
            rejectObservationInput(ReceiptObservationNotice.InvalidExpirationDate)
            return
        }
        val observedQuantity = quantity.trim().toBigDecimalOrNull()
        if (observedQuantity == null || observedQuantity.signum() < 0) {
            rejectObservationInput(ReceiptObservationNotice.InvalidQuantity)
            return
        }
        val normalizedUnit = unit.trim()
        if (normalizedUnit.isBlank() || !normalizedUnit.equals(transfer.unit, ignoreCase = true)) {
            rejectObservationInput(ReceiptObservationNotice.UnitMismatch)
            return
        }
        val expectedQuantity = transfer.expectedQuantity
        val expectedBatch = transfer.batchNumber
        if (!transfer.canReceiveExpectedQuantity ||
            transfer.destinationWarehouseId != current.selectedDestinationWarehouseId ||
            expectedQuantity == null || expectedBatch.isNullOrBlank()
        ) {
            mutableState.update {
                it.copy(
                    observationNotice = ReceiptObservationNotice.CurrentTransferUnavailable
                )
            }
            return
        }
        val differs = observedBatch != expectedBatch ||
            (observedExpiration != null && observedExpiration != transfer.expirationDate) ||
            observedQuantity.compareTo(expectedQuantity) != 0
        if (!differs) {
            rejectObservationInput(ReceiptObservationNotice.NoDifference)
            return
        }
        val command = StockTransferReceiptObservationIntent(
            scope = currentAuthority.scope,
            idempotencyKey = newIdempotencyKey(),
            transfer = transfer,
            observedBatchNumber = observedBatch,
            observedExpirationDate = observedExpiration,
            observedQuantityText = observedQuantity.toPlainString(),
            observedUnit = normalizedUnit,
            status = StockTransferReceiptObservationIntentStatus.Pending
        )
        observationIntent = command
        mutableState.update {
            it.copy(
                frozenObservationIntent = command,
                observationCommand = StockTransferReceiptObservationCommandStatus.PersistingIntent,
                observationNotice = null,
                recordedObservation = null
            )
        }
        persistThenObserve(command, currentAuthority, generation)
    }

    /** Replays only the same persisted key,
     body, transfer version and scope after an explicit user action. */
    fun retryObservationUnknownOutcome() {
        val currentAuthority = authority ?: return
        val frozen = observationIntent ?: return
        val current = mutableState.value
        if (current.observationCommand !=
            StockTransferReceiptObservationCommandStatus.UnknownOutcome ||
            frozen.scope != currentAuthority.scope || !current.canReceive ||
            current.observationMetadata != TransferMetadataStatus.Available
        ) {
            return
        }
        mutableState.update {
            it.copy(
                observationCommand = StockTransferReceiptObservationCommandStatus.Pending,
                observationNotice = null
            )
        }
        executeObservation(
            frozen.copy(status = StockTransferReceiptObservationIntentStatus.UnknownOutcome),
            currentAuthority,
            generation
        )
    }

    fun retryObservationIntentCleanup() {
        val currentAuthority = authority ?: return
        val frozen = observationIntent ?: return
        if (!mutableState.value.observationCleanupPending ||
            frozen.scope != currentAuthority.scope
        ) {
            return
        }
        viewModelScope.launch {
            val cleared = observationMetadataMutex.withLock {
                safeObservationWrite {
                    observationMetadataStore.clearIntent(
                        currentAuthority.scope,
                        frozen.idempotencyKey
                    )
                }
            }
            if (!isCurrent(generation, currentAuthority)) return@launch
            if (cleared == StockTransferReceiptObservationMetadataWrite.Saved) {
                observationIntent = null
                mutableState.update {
                    it.copy(
                        frozenObservationIntent = null,
                        observationCleanupPending = false,
                        observationMetadata = TransferMetadataStatus.Available
                    )
                }
            } else {
                mutableState.update {
                    it.copy(observationMetadata = TransferMetadataStatus.Unavailable)
                }
            }
        }
    }

    fun retryUnknownOutcome() {
        val currentAuthority = authority ?: return
        val frozen = intent ?: return
        if (mutableState.value.command != StockTransferReceiptCommandStatus.UnknownOutcome ||
            frozen.scope != currentAuthority.scope || !mutableState.value.canReceive ||
            mutableState.value.metadata != TransferMetadataStatus.Available
        ) {
            return
        }
        mutableState.update {
            it.copy(command = StockTransferReceiptCommandStatus.Pending, notice = null)
        }
        execute(
            frozen.copy(status = StockTransferReceiptIntentStatus.UnknownOutcome),
            currentAuthority,
            generation
        )
    }

    fun retryIntentCleanup() {
        val currentAuthority = authority ?: return
        val frozen = intent ?: return
        if (!mutableState.value.intentCleanupPending ||
            frozen.scope != currentAuthority.scope
        ) {
            return
        }
        viewModelScope.launch {
            val cleared = metadataMutex.withLock {
                safeWrite {
                    metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
                }
            }
            if (!isCurrent(generation, currentAuthority)) return@launch
            if (cleared == StockTransferReceiptMetadataWrite.Saved) {
                intent = null
                mutableState.update {
                    it.copy(
                        frozenIntent = null,
                        intentCleanupPending = false,
                        metadata = TransferMetadataStatus.Available
                    )
                }
            } else {
                mutableState.update { it.copy(metadata = TransferMetadataStatus.Unavailable) }
            }
        }
    }

    private fun loadTransfers(warehouseId: String, page: Int, append: Boolean) {
        val currentAuthority = authority ?: return
        val requestGeneration = generation
        val lookup = ++lookupGeneration
        viewModelScope.launch {
            val result = safeLookup { gateway.transfers(warehouseId, page, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority) || lookup != lookupGeneration ||
                mutableState.value.selectedDestinationWarehouseId != warehouseId
            ) {
                return@launch
            }
            when (result) {
                is ReceiptLookupResult.TransferPage -> mutableState.update { current ->
                    val combined = if (append) current.transfers + result.items else result.items
                    current.copy(
                        transfers = combined.distinctBy(StockTransferReceiptTransfer::id),
                        page = result.page,
                        total = result.total,
                        transferLookup =
                            if (combined.isEmpty()) {
                                TransferLookupStatus.Empty
                            } else {
                                TransferLookupStatus.Ready
                            },
                        notice = null
                    )
                }

                else -> mutableState.update { it.copy(transferLookup = result.toLookupStatus()) }
            }
        }
    }

    private fun loadCurrentTransfer(
        requestGeneration: Long,
        currentAuthority: StockTransferAuthority,
        transferId: String
    ) {
        viewModelScope.launch {
            val result = safeLookup { gateway.transfer(transferId, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (result is ReceiptLookupResult.Transfer) {
                mutableState.update { current ->
                    current.copy(
                        transfers =
                            current.transfers.filterNot { it.id == transferId } + result.item,
                        selectedDestinationWarehouseId = result.item.destinationWarehouseId,
                        selectedTransferId = transferId,
                        notice = if (result.item.status ==
                            "RECEIVED"
                        ) {
                            StockTransferReceiptNotice.CurrentTransferUnavailable
                        } else {
                            current.notice
                        }
                    )
                }
            } else {
                mutableState.update {
                    it.copy(notice = StockTransferReceiptNotice.CurrentTransferUnavailable)
                }
            }
        }
    }

    private fun persistThenReceive(
        command: StockTransferReceiptIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long
    ) {
        viewModelScope.launch {
            val saved = metadataMutex.withLock { safeWrite { metadataStore.saveIntent(command) } }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != StockTransferReceiptMetadataWrite.Saved) {
                intent = null
                mutableState.update {
                    it.copy(
                        frozenIntent = null,
                        command = StockTransferReceiptCommandStatus.Editing,
                        metadata = TransferMetadataStatus.Unavailable,
                        notice = StockTransferReceiptNotice.MetadataUnavailable
                    )
                }
                return@launch
            }
            mutableState.update { it.copy(command = StockTransferReceiptCommandStatus.Pending) }
            execute(command, currentAuthority, requestGeneration)
        }
    }

    private fun execute(
        command: StockTransferReceiptIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long
    ) {
        viewModelScope.launch {
            when (val result = safeReceive { gateway.receive(command, currentAuthority) }) {
                is StockTransferReceiptResult.Confirmed -> confirm(
                    command,
                    result.transfer,
                    currentAuthority,
                    requestGeneration
                )

                is StockTransferReceiptResult.Rejected -> clearRejected(
                    command,
                    currentAuthority,
                    requestGeneration,
                    result.code
                )

                StockTransferReceiptResult.PreconditionFailed -> {
                    clearRejected(command, currentAuthority, requestGeneration, null, stale = true)
                    loadCurrentTransfer(requestGeneration, currentAuthority, command.transfer.id)
                }

                StockTransferReceiptResult.Conflict -> finish(
                    command,
                    currentAuthority,
                    requestGeneration,
                    StockTransferReceiptCommandStatus.Conflict,
                    StockTransferReceiptNotice.Conflict,
                    markUnknown = false
                )

                StockTransferReceiptResult.UnknownOutcome -> finishUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    StockTransferReceiptNotice.ServiceUnavailable
                )

                StockTransferReceiptResult.NetworkUnavailable -> finishUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    StockTransferReceiptNotice.NetworkUnavailable
                )

                StockTransferReceiptResult.ServiceUnavailable -> finishUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    StockTransferReceiptNotice.ServiceUnavailable
                )

                StockTransferReceiptResult.PermissionDenied -> finishUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    StockTransferReceiptNotice.PermissionDenied
                )

                StockTransferReceiptResult.ContextInvalidated -> finishUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    StockTransferReceiptNotice.ContextInvalidated
                )

                StockTransferReceiptResult.SessionInvalidated -> finishUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    StockTransferReceiptNotice.SessionInvalidated
                )
            }
        }
    }

    private fun persistThenObserve(
        command: StockTransferReceiptObservationIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long
    ) {
        viewModelScope.launch {
            val saved = observationMetadataMutex.withLock {
                safeObservationWrite { observationMetadataStore.saveIntent(command) }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (saved != StockTransferReceiptObservationMetadataWrite.Saved) {
                observationIntent = null
                mutableState.update {
                    it.copy(
                        frozenObservationIntent = null,
                        observationCommand = StockTransferReceiptObservationCommandStatus.Editing,
                        observationMetadata = TransferMetadataStatus.Unavailable,
                        observationNotice = ReceiptObservationNotice.MetadataUnavailable
                    )
                }
                return@launch
            }
            mutableState.update {
                it.copy(observationCommand = StockTransferReceiptObservationCommandStatus.Pending)
            }
            executeObservation(command, currentAuthority, requestGeneration)
        }
    }

    private fun executeObservation(
        command: StockTransferReceiptObservationIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long
    ) {
        viewModelScope.launch {
            when (
                val result = safeObserveArrival {
                    gateway.observeArrival(command, currentAuthority)
                }
            ) {
                is ReceiptObservationResult.Recorded ->
                    recordObservation(
                        command,
                        result.observation,
                        currentAuthority,
                        requestGeneration
                    )

                is ReceiptObservationResult.Rejected ->
                    clearObservationIntent(
                        command,
                        currentAuthority,
                        requestGeneration,
                        result.code
                    )

                ReceiptObservationResult.PreconditionFailed -> {
                    clearObservationIntent(
                        command,
                        currentAuthority,
                        requestGeneration,
                        null,
                        stale = true
                    )
                    loadCurrentTransfer(requestGeneration, currentAuthority, command.transfer.id)
                }

                ReceiptObservationResult.Conflict -> {
                    finishObservationConflict(command, currentAuthority, requestGeneration)
                }

                ReceiptObservationResult.UnknownOutcome -> finishObservationUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    ReceiptObservationNotice.ServiceUnavailable
                )

                ReceiptObservationResult.NetworkUnavailable -> finishObservationUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    ReceiptObservationNotice.NetworkUnavailable
                )

                ReceiptObservationResult.ServiceUnavailable -> finishObservationUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    ReceiptObservationNotice.ServiceUnavailable
                )

                ReceiptObservationResult.PermissionDenied -> finishObservationUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    ReceiptObservationNotice.PermissionDenied
                )

                ReceiptObservationResult.ContextInvalidated -> finishObservationUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    ReceiptObservationNotice.ContextInvalidated
                )

                ReceiptObservationResult.SessionInvalidated -> finishObservationUnknown(
                    command,
                    currentAuthority,
                    requestGeneration,
                    ReceiptObservationNotice.SessionInvalidated
                )
            }
        }
    }

    private suspend fun recordObservation(
        command: StockTransferReceiptObservationIntent,
        observation: StockTransferReceiptObservation,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long
    ) {
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val cleared = observationMetadataMutex.withLock {
            safeObservationWrite {
                observationMetadataStore.clearIntent(currentAuthority.scope, command.idempotencyKey)
            }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared == StockTransferReceiptObservationMetadataWrite.Saved) observationIntent = null
        mutableState.update {
            it.copy(
                recordedObservation = observation,
                frozenObservationIntent = if (cleared ==
                    StockTransferReceiptObservationMetadataWrite.Saved
                ) {
                    null
                } else {
                    command
                },
                observationCleanupPending =
                    cleared != StockTransferReceiptObservationMetadataWrite.Saved,
                observationMetadata = if (cleared ==
                    StockTransferReceiptObservationMetadataWrite.Saved
                ) {
                    TransferMetadataStatus.Available
                } else {
                    TransferMetadataStatus.Unavailable
                },
                observationCommand = StockTransferReceiptObservationCommandStatus.Recorded,
                observationNotice = if (cleared ==
                    StockTransferReceiptObservationMetadataWrite.Saved
                ) {
                    null
                } else {
                    ReceiptObservationNotice.MetadataUnavailable
                }
            )
        }
    }

    private suspend fun clearObservationIntent(
        command: StockTransferReceiptObservationIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long,
        code: String?,
        stale: Boolean = false
    ) {
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val cleared = observationMetadataMutex.withLock {
            safeObservationWrite {
                observationMetadataStore.clearIntent(currentAuthority.scope, command.idempotencyKey)
            }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared == StockTransferReceiptObservationMetadataWrite.Saved) observationIntent = null
        mutableState.update {
            it.copy(
                frozenObservationIntent = if (cleared ==
                    StockTransferReceiptObservationMetadataWrite.Saved
                ) {
                    null
                } else {
                    command
                },
                observationCleanupPending =
                    cleared != StockTransferReceiptObservationMetadataWrite.Saved,
                observationMetadata = if (cleared ==
                    StockTransferReceiptObservationMetadataWrite.Saved
                ) {
                    TransferMetadataStatus.Available
                } else {
                    TransferMetadataStatus.Unavailable
                },
                observationCommand = if (stale) {
                    StockTransferReceiptObservationCommandStatus.PreconditionFailed
                } else {
                    StockTransferReceiptObservationCommandStatus.Rejected
                },
                observationNotice = when {
                    stale -> ReceiptObservationNotice.PreconditionFailed

                    cleared != StockTransferReceiptObservationMetadataWrite.Saved ->
                        ReceiptObservationNotice.MetadataUnavailable

                    else -> null
                },
                rejectionCode = code
            )
        }
    }

    private suspend fun finishObservationUnknown(
        command: StockTransferReceiptObservationIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long,
        notice: ReceiptObservationNotice
    ) {
        val unknown = command.copy(
            status = StockTransferReceiptObservationIntentStatus.UnknownOutcome
        )
        val marked = observationMetadataMutex.withLock {
            safeObservationWrite {
                observationMetadataStore.markUnknownOutcome(
                    currentAuthority.scope,
                    command.idempotencyKey
                )
            }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        observationIntent = unknown
        mutableState.update {
            it.copy(
                frozenObservationIntent = unknown,
                observationMetadata = if (marked ==
                    StockTransferReceiptObservationMetadataWrite.Saved
                ) {
                    TransferMetadataStatus.Available
                } else {
                    TransferMetadataStatus.Unavailable
                },
                observationCommand = StockTransferReceiptObservationCommandStatus.UnknownOutcome,
                observationNotice = if (marked ==
                    StockTransferReceiptObservationMetadataWrite.Saved
                ) {
                    notice
                } else {
                    ReceiptObservationNotice.MetadataUnavailable
                }
            )
        }
    }

    private fun finishObservationConflict(
        command: StockTransferReceiptObservationIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long
    ) {
        if (!isCurrent(requestGeneration, currentAuthority)) return
        observationIntent = command
        mutableState.update {
            it.copy(
                frozenObservationIntent = command,
                observationCommand = StockTransferReceiptObservationCommandStatus.Conflict,
                observationNotice = ReceiptObservationNotice.Conflict
            )
        }
    }

    private fun rejectObservationInput(notice: ReceiptObservationNotice) {
        mutableState.update {
            it.copy(
                observationCommand = StockTransferReceiptObservationCommandStatus.Rejected,
                observationNotice = notice
            )
        }
    }

    private suspend fun confirm(
        command: StockTransferReceiptIntent,
        transfer: StockTransferReceiptTransfer,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long
    ) {
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val cleared = metadataMutex.withLock {
            safeWrite { metadataStore.clearIntent(currentAuthority.scope, command.idempotencyKey) }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared == StockTransferReceiptMetadataWrite.Saved) intent = null
        mutableState.update {
            it.copy(
                transfers = it.transfers.filterNot { row -> row.id == transfer.id } + transfer,
                confirmed = transfer,
                frozenIntent = if (cleared ==
                    StockTransferReceiptMetadataWrite.Saved
                ) {
                    null
                } else {
                    command
                },
                intentCleanupPending = cleared != StockTransferReceiptMetadataWrite.Saved,
                metadata = if (cleared ==
                    StockTransferReceiptMetadataWrite.Saved
                ) {
                    TransferMetadataStatus.Available
                } else {
                    TransferMetadataStatus.Unavailable
                },
                command = StockTransferReceiptCommandStatus.Confirmed,
                notice = if (cleared == StockTransferReceiptMetadataWrite.Saved) {
                    null
                } else {
                    StockTransferReceiptNotice.MetadataUnavailable
                }
            )
        }
    }

    private suspend fun clearRejected(
        command: StockTransferReceiptIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long,
        code: String?,
        stale: Boolean = false
    ) {
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val cleared = metadataMutex.withLock {
            safeWrite { metadataStore.clearIntent(currentAuthority.scope, command.idempotencyKey) }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleared == StockTransferReceiptMetadataWrite.Saved) intent = null
        mutableState.update {
            it.copy(
                frozenIntent = if (cleared ==
                    StockTransferReceiptMetadataWrite.Saved
                ) {
                    null
                } else {
                    command
                },
                intentCleanupPending = cleared != StockTransferReceiptMetadataWrite.Saved,
                metadata = if (cleared ==
                    StockTransferReceiptMetadataWrite.Saved
                ) {
                    TransferMetadataStatus.Available
                } else {
                    TransferMetadataStatus.Unavailable
                },
                command = if (stale) {
                    StockTransferReceiptCommandStatus.PreconditionFailed
                } else {
                    StockTransferReceiptCommandStatus.Rejected
                },
                notice = if (stale) {
                    StockTransferReceiptNotice.PreconditionFailed
                } else if (cleared == StockTransferReceiptMetadataWrite.Saved) {
                    null
                } else {
                    StockTransferReceiptNotice.MetadataUnavailable
                },
                rejectionCode = code
            )
        }
    }

    private suspend fun finishUnknown(
        command: StockTransferReceiptIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long,
        notice: StockTransferReceiptNotice
    ) {
        val unknown = command.copy(status = StockTransferReceiptIntentStatus.UnknownOutcome)
        val marked = metadataMutex.withLock {
            safeWrite {
                metadataStore.markUnknownOutcome(currentAuthority.scope, command.idempotencyKey)
            }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        intent = unknown
        mutableState.update {
            it.copy(
                frozenIntent = unknown,
                metadata = if (marked ==
                    StockTransferReceiptMetadataWrite.Saved
                ) {
                    TransferMetadataStatus.Available
                } else {
                    TransferMetadataStatus.Unavailable
                },
                command = StockTransferReceiptCommandStatus.UnknownOutcome,
                notice = if (marked == StockTransferReceiptMetadataWrite.Saved) {
                    notice
                } else {
                    StockTransferReceiptNotice.MetadataUnavailable
                }
            )
        }
    }

    private suspend fun finish(
        command: StockTransferReceiptIntent,
        currentAuthority: StockTransferAuthority,
        requestGeneration: Long,
        status: StockTransferReceiptCommandStatus,
        notice: StockTransferReceiptNotice,
        markUnknown: Boolean
    ) {
        val stored = if (markUnknown) {
            command.copy(
                status = StockTransferReceiptIntentStatus.UnknownOutcome
            )
        } else {
            command
        }
        if (markUnknown) {
            metadataMutex.withLock {
                safeWrite {
                    metadataStore.markUnknownOutcome(currentAuthority.scope, command.idempotencyKey)
                }
            }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        intent = stored
        mutableState.update { it.copy(frozenIntent = stored, command = status, notice = notice) }
    }

    private fun canEditSelection(): Boolean {
        val current = mutableState.value
        return current.canLookUp && current.metadata == TransferMetadataStatus.Available &&
            !current.isFrozen &&
            current.command != StockTransferReceiptCommandStatus.Conflict &&
            current.observationCommand != StockTransferReceiptObservationCommandStatus.Conflict
    }

    private fun ReceiptLookupResult.toLookupStatus(): TransferLookupStatus = when (this) {
        ReceiptLookupResult.NetworkUnavailable -> TransferLookupStatus.NetworkUnavailable
        ReceiptLookupResult.ServiceUnavailable -> TransferLookupStatus.ServiceUnavailable
        ReceiptLookupResult.PermissionDenied -> TransferLookupStatus.PermissionDenied
        ReceiptLookupResult.ContextInvalidated -> TransferLookupStatus.ContextInvalidated
        ReceiptLookupResult.SessionInvalidated -> TransferLookupStatus.SessionInvalidated
        else -> TransferLookupStatus.Empty
    }

    private suspend fun safeRead(block: suspend () -> StockTransferReceiptMetadataRead) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StockTransferReceiptMetadataRead.Unavailable
    }

    private suspend fun safeWrite(block: suspend () -> StockTransferReceiptMetadataWrite) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StockTransferReceiptMetadataWrite.Unavailable
    }

    private suspend fun safeObservationRead(block: suspend () -> ObservationMetadataRead) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ObservationMetadataRead.Unavailable
    }

    private suspend fun safeObservationWrite(
        block: suspend () -> StockTransferReceiptObservationMetadataWrite
    ) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StockTransferReceiptObservationMetadataWrite.Unavailable
    }

    private suspend fun safeLookup(block: suspend () -> ReceiptLookupResult) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ReceiptLookupResult.ServiceUnavailable
    }

    private suspend fun safeReceive(block: suspend () -> StockTransferReceiptResult) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StockTransferReceiptResult.UnknownOutcome
    }

    private suspend fun safeObserveArrival(block: suspend () -> ReceiptObservationResult) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ReceiptObservationResult.UnknownOutcome
    }

    private fun isCurrent(
        requestGeneration: Long,
        currentAuthority: StockTransferAuthority
    ): Boolean = generation == requestGeneration && authority == currentAuthority &&
        mutableState.value.authorityEpoch == currentAuthority.authorityEpoch

    private fun String.isCanonicalLocalDate(): Boolean = try {
        LocalDate.parse(this).toString() == this
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val MAX_OBSERVED_BATCH_LENGTH = 80
    }
}

private object UnavailableReceiptObservationMetadataStore : ObservationMetadataStore {
    override suspend fun loadIntent(scope: StockTransferScope): ObservationMetadataRead =
        ObservationMetadataRead.Unavailable

    override suspend fun saveIntent(
        intent: StockTransferReceiptObservationIntent
    ): StockTransferReceiptObservationMetadataWrite =
        StockTransferReceiptObservationMetadataWrite.Unavailable

    override suspend fun markUnknownOutcome(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptObservationMetadataWrite =
        StockTransferReceiptObservationMetadataWrite.Unavailable

    override suspend fun clearIntent(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptObservationMetadataWrite =
        StockTransferReceiptObservationMetadataWrite.Unavailable
}
