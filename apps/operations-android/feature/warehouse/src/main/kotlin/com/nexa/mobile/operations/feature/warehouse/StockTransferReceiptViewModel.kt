package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(StockTransferReceiptUiState())
    val state = mutableState.asStateFlow()
    private var authority: StockTransferAuthority? = null
    private var generation = 0L
    private var lookupGeneration = 0L
    private var intent: StockTransferReceiptIntent? = null
    private val metadataMutex = Mutex()

    fun activate(currentAuthority: StockTransferAuthority) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        intent = null
        mutableState.value = StockTransferReceiptUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canLookUp = currentAuthority.canLookUp,
            canReceive = currentAuthority.canCreate,
            warehouseLookup = if (currentAuthority.canLookUp) TransferLookupStatus.Loading
            else TransferLookupStatus.PermissionDenied,
            metadata = TransferMetadataStatus.Loading
        )
        reloadWarehouses()
        viewModelScope.launch {
            val read = metadataMutex.withLock { safeRead { metadataStore.loadIntent(currentAuthority.scope) } }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val restored = (read as? StockTransferReceiptMetadataRead.Available)?.value
                ?.takeIf { it.scope == currentAuthority.scope && it.transfer.id.isNotBlank() }
            intent = restored?.copy(status = StockTransferReceiptIntentStatus.UnknownOutcome)
            mutableState.update {
                it.copy(
                    metadata = if (read is StockTransferReceiptMetadataRead.Available) TransferMetadataStatus.Available
                    else TransferMetadataStatus.Unavailable,
                    frozenIntent = intent,
                    command = if (restored == null) StockTransferReceiptCommandStatus.Editing
                    else StockTransferReceiptCommandStatus.UnknownOutcome,
                    selectedDestinationWarehouseId = restored?.transfer?.destinationWarehouseId,
                    selectedTransferId = restored?.transfer?.id,
                    notice = if (read is StockTransferReceiptMetadataRead.Available) null
                    else StockTransferReceiptNotice.MetadataUnavailable
                )
            }
            if (restored != null) {
                if (restored.status == StockTransferReceiptIntentStatus.Pending) {
                    val marked = metadataMutex.withLock {
                        safeWrite { metadataStore.markUnknownOutcome(currentAuthority.scope, restored.idempotencyKey) }
                    }
                    if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                    if (marked != StockTransferReceiptMetadataWrite.Saved) {
                        mutableState.update {
                            it.copy(metadata = TransferMetadataStatus.Unavailable, notice = StockTransferReceiptNotice.MetadataUnavailable)
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
                is StockTransferReceiptLookupResult.Warehouses -> mutableState.update {
                    it.copy(
                        warehouses = result.items,
                        warehouseLookup = if (result.items.isEmpty()) TransferLookupStatus.Empty else TransferLookupStatus.Ready,
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
                transfers = emptyList(), transferLookup = TransferLookupStatus.Loading,
                page = 0, total = 0, selectedTransferId = null, confirmed = null, notice = null
            )
        }
        loadTransfers(warehouse.id, 0, append = false)
    }

    fun loadMoreTransfers() {
        val current = mutableState.value
        val warehouseId = current.selectedDestinationWarehouseId ?: return
        if (!current.hasMoreTransfers || current.isFrozen || current.transferLookup == TransferLookupStatus.Loading) return
        loadTransfers(warehouseId, current.page + 1, append = true)
    }

    fun selectTransfer(transferId: String) {
        if (!canEditSelection()) return
        val transfer = mutableState.value.transfers.singleOrNull { it.id == transferId } ?: return
        if (transfer.destinationWarehouseId != mutableState.value.selectedDestinationWarehouseId) return
        mutableState.update {
            it.copy(
                selectedTransferId = transfer.id,
                confirmed = null,
                notice = if (transfer.canReceiveExpectedQuantity) null
                else StockTransferReceiptNotice.ExpectedQuantityOnly
            )
        }
    }

    fun receiveExpectedQuantity() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val transfer = current.selectedTransfer ?: return
        if (!current.canReceive || current.isFrozen || current.metadata != TransferMetadataStatus.Available) return
        if (!transfer.canReceiveExpectedQuantity || transfer.destinationWarehouseId != current.selectedDestinationWarehouseId) {
            mutableState.update { it.copy(notice = StockTransferReceiptNotice.ExpectedQuantityOnly) }
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
            it.copy(frozenIntent = command, command = StockTransferReceiptCommandStatus.PersistingIntent, notice = null)
        }
        persistThenReceive(command, currentAuthority, generation)
    }

    fun retryUnknownOutcome() {
        val currentAuthority = authority ?: return
        val frozen = intent ?: return
        if (mutableState.value.command != StockTransferReceiptCommandStatus.UnknownOutcome ||
            frozen.scope != currentAuthority.scope || !mutableState.value.canReceive ||
            mutableState.value.metadata != TransferMetadataStatus.Available
        ) return
        mutableState.update { it.copy(command = StockTransferReceiptCommandStatus.Pending, notice = null) }
        execute(frozen.copy(status = StockTransferReceiptIntentStatus.UnknownOutcome), currentAuthority, generation)
    }

    fun retryIntentCleanup() {
        val currentAuthority = authority ?: return
        val frozen = intent ?: return
        if (!mutableState.value.intentCleanupPending || frozen.scope != currentAuthority.scope) return
        viewModelScope.launch {
            val cleared = metadataMutex.withLock {
                safeWrite { metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey) }
            }
            if (!isCurrent(generation, currentAuthority)) return@launch
            if (cleared == StockTransferReceiptMetadataWrite.Saved) {
                intent = null
                mutableState.update {
                    it.copy(frozenIntent = null, intentCleanupPending = false, metadata = TransferMetadataStatus.Available)
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
            ) return@launch
            when (result) {
                is StockTransferReceiptLookupResult.TransferPage -> mutableState.update { current ->
                    val combined = if (append) current.transfers + result.items else result.items
                    current.copy(
                        transfers = combined.distinctBy(StockTransferReceiptTransfer::id),
                        page = result.page,
                        total = result.total,
                        transferLookup = if (combined.isEmpty()) TransferLookupStatus.Empty else TransferLookupStatus.Ready,
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
            if (result is StockTransferReceiptLookupResult.Transfer) {
                mutableState.update { current ->
                    current.copy(
                        transfers = current.transfers.filterNot { it.id == transferId } + result.item,
                        selectedDestinationWarehouseId = result.item.destinationWarehouseId,
                        selectedTransferId = transferId,
                        notice = if (result.item.status == "RECEIVED") StockTransferReceiptNotice.CurrentTransferUnavailable
                        else current.notice
                    )
                }
            } else {
                mutableState.update { it.copy(notice = StockTransferReceiptNotice.CurrentTransferUnavailable) }
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
                is StockTransferReceiptResult.Confirmed -> confirm(command, result.transfer, currentAuthority, requestGeneration)
                is StockTransferReceiptResult.Rejected -> clearRejected(command, currentAuthority, requestGeneration, result.code)
                StockTransferReceiptResult.PreconditionFailed -> {
                    clearRejected(command, currentAuthority, requestGeneration, null, stale = true)
                    loadCurrentTransfer(requestGeneration, currentAuthority, command.transfer.id)
                }
                StockTransferReceiptResult.Conflict -> finish(command, currentAuthority, requestGeneration,
                    StockTransferReceiptCommandStatus.Conflict, StockTransferReceiptNotice.Conflict, markUnknown = false)
                StockTransferReceiptResult.UnknownOutcome -> finishUnknown(command, currentAuthority, requestGeneration,
                    StockTransferReceiptNotice.ServiceUnavailable)
                StockTransferReceiptResult.NetworkUnavailable -> finishUnknown(command, currentAuthority, requestGeneration,
                    StockTransferReceiptNotice.NetworkUnavailable)
                StockTransferReceiptResult.ServiceUnavailable -> finishUnknown(command, currentAuthority, requestGeneration,
                    StockTransferReceiptNotice.ServiceUnavailable)
                StockTransferReceiptResult.PermissionDenied -> finishUnknown(command, currentAuthority, requestGeneration,
                    StockTransferReceiptNotice.PermissionDenied)
                StockTransferReceiptResult.ContextInvalidated -> finishUnknown(command, currentAuthority, requestGeneration,
                    StockTransferReceiptNotice.ContextInvalidated)
                StockTransferReceiptResult.SessionInvalidated -> finishUnknown(command, currentAuthority, requestGeneration,
                    StockTransferReceiptNotice.SessionInvalidated)
            }
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
                frozenIntent = if (cleared == StockTransferReceiptMetadataWrite.Saved) null else command,
                intentCleanupPending = cleared != StockTransferReceiptMetadataWrite.Saved,
                metadata = if (cleared == StockTransferReceiptMetadataWrite.Saved) TransferMetadataStatus.Available
                else TransferMetadataStatus.Unavailable,
                command = StockTransferReceiptCommandStatus.Confirmed,
                notice = if (cleared == StockTransferReceiptMetadataWrite.Saved) null
                else StockTransferReceiptNotice.MetadataUnavailable
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
                frozenIntent = if (cleared == StockTransferReceiptMetadataWrite.Saved) null else command,
                intentCleanupPending = cleared != StockTransferReceiptMetadataWrite.Saved,
                metadata = if (cleared == StockTransferReceiptMetadataWrite.Saved) TransferMetadataStatus.Available
                else TransferMetadataStatus.Unavailable,
                command = if (stale) StockTransferReceiptCommandStatus.PreconditionFailed
                else StockTransferReceiptCommandStatus.Rejected,
                notice = if (stale) StockTransferReceiptNotice.PreconditionFailed
                else if (cleared == StockTransferReceiptMetadataWrite.Saved) null
                else StockTransferReceiptNotice.MetadataUnavailable,
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
            safeWrite { metadataStore.markUnknownOutcome(currentAuthority.scope, command.idempotencyKey) }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        intent = unknown
        mutableState.update {
            it.copy(
                frozenIntent = unknown,
                metadata = if (marked == StockTransferReceiptMetadataWrite.Saved) TransferMetadataStatus.Available
                else TransferMetadataStatus.Unavailable,
                command = StockTransferReceiptCommandStatus.UnknownOutcome,
                notice = if (marked == StockTransferReceiptMetadataWrite.Saved) notice
                else StockTransferReceiptNotice.MetadataUnavailable
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
        val stored = if (markUnknown) command.copy(status = StockTransferReceiptIntentStatus.UnknownOutcome) else command
        if (markUnknown) {
            metadataMutex.withLock { safeWrite { metadataStore.markUnknownOutcome(currentAuthority.scope, command.idempotencyKey) } }
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        intent = stored
        mutableState.update { it.copy(frozenIntent = stored, command = status, notice = notice) }
    }

    private fun canEditSelection(): Boolean {
        val current = mutableState.value
        return current.canLookUp && current.metadata == TransferMetadataStatus.Available && !current.isFrozen &&
            current.command != StockTransferReceiptCommandStatus.Conflict
    }

    private fun StockTransferReceiptLookupResult.toLookupStatus(): TransferLookupStatus = when (this) {
        StockTransferReceiptLookupResult.NetworkUnavailable -> TransferLookupStatus.NetworkUnavailable
        StockTransferReceiptLookupResult.ServiceUnavailable -> TransferLookupStatus.ServiceUnavailable
        StockTransferReceiptLookupResult.PermissionDenied -> TransferLookupStatus.PermissionDenied
        StockTransferReceiptLookupResult.ContextInvalidated -> TransferLookupStatus.ContextInvalidated
        StockTransferReceiptLookupResult.SessionInvalidated -> TransferLookupStatus.SessionInvalidated
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

    private suspend fun safeLookup(block: suspend () -> StockTransferReceiptLookupResult) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StockTransferReceiptLookupResult.ServiceUnavailable
    }

    private suspend fun safeReceive(block: suspend () -> StockTransferReceiptResult) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StockTransferReceiptResult.UnknownOutcome
    }

    private fun isCurrent(requestGeneration: Long, currentAuthority: StockTransferAuthority): Boolean =
        generation == requestGeneration && authority == currentAuthority &&
            mutableState.value.authorityEpoch == currentAuthority.authorityEpoch
}
