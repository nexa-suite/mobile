package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.feature.warehouse.application.StockTransferGateway
import com.nexa.mobile.operations.feature.warehouse.application.StockTransferMetadataStore
import com.nexa.mobile.operations.feature.warehouse.model.ConfirmedStockTransfer
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferAuthority
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferRequest
import com.nexa.mobile.operations.feature.warehouse.model.TransferIntentStatus
import com.nexa.mobile.operations.feature.warehouse.model.TransferLookupResult
import com.nexa.mobile.operations.feature.warehouse.model.TransferMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.TransferMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.TransferSourceLotChoice
import com.nexa.mobile.operations.feature.warehouse.model.TransferSubmitResult
import com.nexa.mobile.operations.feature.warehouse.model.TransferWarehouseChoice
import com.nexa.mobile.operations.feature.warehouse.model.TransferZoneChoice
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TransferMetadataStatus {
    Loading,
    Available,
    Unavailable
}
enum class TransferCommandStatus {
    Editing,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Confirmed,
    Rejected
}

enum class TransferValidationError {
    SourceLotRequired,
    DestinationWarehouseRequired,
    DestinationZoneRequired,
    SameLocation,
    QuantityRequired,
    QuantityInvalid,
    QuantityMustBePositive,
    QuantityExceedsSource,
    ReasonRequired,
    MetadataUnavailable
}

enum class TransferSubmitNotice {
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    PreconditionFailed,
    Conflict,
    IntentMetadataUnavailable
}

data class StockTransferUiState(
    val authorityEpoch: Long = 0,
    val canLookUp: Boolean = false,
    val canCreate: Boolean = false,
    val sourceLots: List<TransferSourceLotChoice> = emptyList(),
    val sourceLotLookup: TransferLookupStatus = TransferLookupStatus.NotRequested,
    val warehouses: List<TransferWarehouseChoice> = emptyList(),
    val warehouseLookup: TransferLookupStatus = TransferLookupStatus.NotRequested,
    val zones: List<TransferZoneChoice> = emptyList(),
    val zoneLookup: TransferLookupStatus = TransferLookupStatus.NotRequested,
    val selectedSourceLotId: String? = null,
    val selectedDestinationWarehouseId: String? = null,
    val selectedDestinationZoneId: String? = null,
    val quantityText: String = "",
    val reason: String = "",
    val metadata: TransferMetadataStatus = TransferMetadataStatus.Loading,
    val command: TransferCommandStatus = TransferCommandStatus.Editing,
    val frozenIntent: StockTransferIntent? = null,
    val validationError: TransferValidationError? = null,
    val notice: TransferSubmitNotice? = null,
    val rejectionCode: String? = null,
    val confirmed: ConfirmedStockTransfer? = null,
    val intentCleanupPending: Boolean = false
) {
    val isFrozen: Boolean
        get() = command in setOf(
            TransferCommandStatus.PersistingIntent,
            TransferCommandStatus.Pending,
            TransferCommandStatus.UnknownOutcome
        ) || intentCleanupPending

    override fun toString(): String =
        "StockTransferUiState(epoch=$authorityEpoch, command=$command, metadata=$metadata, " +
            "lots=${sourceLots.size}, warehouses=${warehouses.size}, zones=${zones.size})"
}

/** Online-only transfer request flow; local state freezes a retry but never changes stock. */
class StockTransferViewModel(
    private val gateway: StockTransferGateway,
    private val metadataStore: StockTransferMetadataStore,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(StockTransferUiState())
    val state = mutableState.asStateFlow()

    private var authority: StockTransferAuthority? = null
    private var generation = 0L
    private var lotLookupGeneration = 0L
    private var warehouseLookupGeneration = 0L
    private var zoneLookupGeneration = 0L
    private var intent: StockTransferIntent? = null
    private val metadataMutex = Mutex()
    private val commandMutex = Mutex()

    fun activate(currentAuthority: StockTransferAuthority) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        intent = null
        mutableState.value = StockTransferUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canLookUp = currentAuthority.canLookUp,
            canCreate = currentAuthority.canCreate,
            sourceLotLookup = if (currentAuthority.canLookUp) {
                TransferLookupStatus.Loading
            } else {
                TransferLookupStatus.PermissionDenied
            },
            warehouseLookup = if (currentAuthority.canLookUp) {
                TransferLookupStatus.Loading
            } else {
                TransferLookupStatus.PermissionDenied
            },
            metadata = TransferMetadataStatus.Loading
        )
        reloadSourceLots()
        reloadWarehouses()
        viewModelScope.launch {
            val read = metadataMutex.withLock {
                safeMetadataRead { metadataStore.loadIntent(currentAuthority.scope) }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val stored = (read as? TransferMetadataRead.Available)
                ?.value
                ?.takeIf {
                    it.scope == currentAuthority.scope && it.idempotencyKey.isNotBlank() &&
                        it.frozenPayload.isNotBlank() && it.expectedSourceVersion >= 0
                }
            intent = stored?.copy(status = TransferIntentStatus.UnknownOutcome)
            val available = read is TransferMetadataRead.Available
            mutableState.update {
                it.copy(
                    frozenIntent = intent,
                    metadata = if (available) {
                        TransferMetadataStatus.Available
                    } else {
                        TransferMetadataStatus.Unavailable
                    },
                    command = if (stored != null) {
                        TransferCommandStatus.UnknownOutcome
                    } else {
                        TransferCommandStatus.Editing
                    },
                    notice = if (available) {
                        null
                    } else {
                        TransferSubmitNotice.IntentMetadataUnavailable
                    },
                    validationError =
                        if (available) null else TransferValidationError.MetadataUnavailable
                )
            }
            if (stored?.status == TransferIntentStatus.Pending) {
                val marked = withMetadataLock(requestGeneration, currentAuthority) {
                    metadataStore.markUnknownOutcome(
                        currentAuthority.scope,
                        stored.idempotencyKey
                    )
                }
                if (!isCurrent(requestGeneration, currentAuthority)) return@launch
                if (marked != TransferMetadataWrite.Saved) {
                    mutableState.update {
                        it.copy(
                            metadata = TransferMetadataStatus.Unavailable,
                            notice = TransferSubmitNotice.IntentMetadataUnavailable
                        )
                    }
                }
            }
        }
    }

    fun deactivate() {
        generation++
        lotLookupGeneration++
        warehouseLookupGeneration++
        zoneLookupGeneration++
        authority = null
        intent = null
        mutableState.value = StockTransferUiState()
    }

    fun selectSourceLot(lotId: String) {
        if (!isEditable()) return
        val lot = mutableState.value.sourceLots.singleOrNull { it.id == lotId }
            ?.takeIf(TransferSourceLotChoice::isSelectable) ?: return
        mutableState.update {
            it.copy(
                selectedSourceLotId = lot.id,
                quantityText = "",
                validationError = null,
                notice = null,
                rejectionCode = null,
                confirmed = null
            )
        }
    }

    fun selectDestinationWarehouse(warehouseId: String) {
        if (!isEditable()) return
        val warehouse = mutableState.value.warehouses.singleOrNull { it.id == warehouseId }
            ?.takeIf(TransferWarehouseChoice::isSelectable) ?: return
        zoneLookupGeneration++
        mutableState.update {
            it.copy(
                selectedDestinationWarehouseId = warehouse.id,
                selectedDestinationZoneId = null,
                zones = emptyList(),
                zoneLookup = TransferLookupStatus.Loading,
                validationError = null,
                notice = null,
                rejectionCode = null
            )
        }
        reloadZones(warehouse.id)
    }

    fun selectDestinationZone(zoneId: String) {
        if (!isEditable()) return
        val warehouseId = mutableState.value.selectedDestinationWarehouseId ?: return
        val zone = mutableState.value.zones.singleOrNull { it.id == zoneId }
            ?.takeIf { it.warehouseId == warehouseId && it.isSelectable } ?: return
        mutableState.update {
            it.copy(
                selectedDestinationZoneId = zone.id,
                validationError = null,
                notice = null,
                rejectionCode = null
            )
        }
    }

    fun quantityChanged(value: String) {
        if (!isEditable()) return
        mutableState.update { it.copy(quantityText = value, validationError = null) }
    }

    fun reasonChanged(value: String) {
        if (!isEditable()) return
        mutableState.update { it.copy(reason = value, validationError = null) }
    }

    fun reloadSourceLots() {
        val currentAuthority = authority ?: return
        val requestGeneration = generation
        val lookupGeneration = ++lotLookupGeneration
        if (!currentAuthority.canLookUp) {
            mutableState.update { it.copy(sourceLotLookup = TransferLookupStatus.PermissionDenied) }
            return
        }
        mutableState.update { it.copy(sourceLotLookup = TransferLookupStatus.Loading) }
        viewModelScope.launch {
            val result = safeLookup { gateway.sourceLots(currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority) ||
                lotLookupGeneration != lookupGeneration
            ) {
                return@launch
            }
            when (result) {
                is TransferLookupResult.Lots -> mutableState.update {
                    it.copy(
                        sourceLots = result.items,
                        selectedSourceLotId = it.selectedSourceLotId?.takeIf { id ->
                            result.items.any { lot -> lot.id == id && lot.isSelectable }
                        },
                        sourceLotLookup = if (result.items.isEmpty()) {
                            TransferLookupStatus.Empty
                        } else {
                            TransferLookupStatus.Ready
                        }
                    )
                }

                else -> mutableState.update { it.copy(sourceLotLookup = result.toLookupStatus()) }
            }
        }
    }

    fun reloadWarehouses() {
        val currentAuthority = authority ?: return
        val requestGeneration = generation
        val lookupGeneration = ++warehouseLookupGeneration
        if (!currentAuthority.canLookUp) {
            mutableState.update { it.copy(warehouseLookup = TransferLookupStatus.PermissionDenied) }
            return
        }
        mutableState.update { it.copy(warehouseLookup = TransferLookupStatus.Loading) }
        viewModelScope.launch {
            val result = safeLookup { gateway.warehouses(currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority) ||
                warehouseLookupGeneration != lookupGeneration
            ) {
                return@launch
            }
            when (result) {
                is TransferLookupResult.Warehouses -> mutableState.update { current ->
                    val selected = current.selectedDestinationWarehouseId?.takeIf { id ->
                        result.items.any { it.id == id && it.isSelectable }
                    }
                    current.copy(
                        warehouses = result.items,
                        selectedDestinationWarehouseId = selected,
                        warehouseLookup = if (result.items.isEmpty()) {
                            TransferLookupStatus.Empty
                        } else {
                            TransferLookupStatus.Ready
                        }
                    )
                }

                else -> mutableState.update { it.copy(warehouseLookup = result.toLookupStatus()) }
            }
        }
    }

    fun reloadZones() {
        val warehouseId = mutableState.value.selectedDestinationWarehouseId ?: return
        reloadZones(warehouseId)
    }

    private fun reloadZones(warehouseId: String) {
        val currentAuthority = authority ?: return
        if (!currentAuthority.canLookUp) {
            mutableState.update { it.copy(zoneLookup = TransferLookupStatus.PermissionDenied) }
            return
        }
        val requestGeneration = generation
        val lookupGeneration = ++zoneLookupGeneration
        viewModelScope.launch {
            val result = safeLookup { gateway.zones(warehouseId, currentAuthority) }
            if (!isCurrent(requestGeneration, currentAuthority) ||
                zoneLookupGeneration != lookupGeneration ||
                mutableState.value.selectedDestinationWarehouseId != warehouseId
            ) {
                return@launch
            }
            when (result) {
                is TransferLookupResult.Zones -> mutableState.update { current ->
                    current.copy(
                        zones = result.items.filter { it.warehouseId == warehouseId },
                        selectedDestinationZoneId =
                            current.selectedDestinationZoneId?.takeIf { id ->
                                result.items.any {
                                    it.id == id && it.warehouseId == warehouseId &&
                                        it.isSelectable
                                }
                            },
                        zoneLookup = if (result.items.none { it.warehouseId == warehouseId }) {
                            TransferLookupStatus.Empty
                        } else {
                            TransferLookupStatus.Ready
                        }
                    )
                }

                else -> mutableState.update { it.copy(zoneLookup = result.toLookupStatus()) }
            }
        }
    }

    fun startTransfer() {
        if (mutableState.value.command != TransferCommandStatus.Editing ||
            mutableState.value.intentCleanupPending
        ) {
            return
        }
        if (!commandMutex.tryLock()) return
        viewModelScope.launch {
            try {
                val currentAuthority = authority ?: return@launch
                val requestGeneration = generation
                val currentState = mutableState.value
                val validationError = validate(currentState)
                if (!currentAuthority.canCreate) {
                    mutableState.update {
                        it.copy(
                            notice = TransferSubmitNotice.PermissionDenied,
                            rejectionCode = "PERMISSION_DENIED"
                        )
                    }
                    return@launch
                }
                if (validationError != null) {
                    mutableState.update { it.copy(validationError = validationError) }
                    return@launch
                }
                val request = currentRequest(currentState)
                if (request == null) {
                    mutableState.update { it.copy(validationError = validate(it)) }
                    return@launch
                }
                val payload = try {
                    request.canonicalPayload()
                } catch (_: IllegalArgumentException) {
                    mutableState.update { it.copy(validationError = validate(it)) }
                    return@launch
                }
                if (!isCurrent(requestGeneration, currentAuthority) ||
                    !currentAuthority.canCreate
                ) {
                    return@launch
                }
                val frozen = StockTransferIntent(
                    scope = currentAuthority.scope,
                    idempotencyKey = newIdempotencyKey(),
                    frozenPayload = payload,
                    expectedSourceVersion =
                        selectedSourceLot(mutableState.value)?.version ?: return@launch,
                    status = TransferIntentStatus.Pending
                )
                mutableState.update {
                    it.copy(
                        command = TransferCommandStatus.PersistingIntent,
                        validationError = null,
                        notice = null,
                        rejectionCode = null
                    )
                }
                val save = withMetadataLock(requestGeneration, currentAuthority) {
                    metadataStore.saveIntent(frozen)
                }
                if (!isCurrent(requestGeneration, currentAuthority)) {
                    withMetadataLock(null, currentAuthority) {
                        metadataStore.markUnknownOutcome(
                            currentAuthority.scope,
                            frozen.idempotencyKey
                        )
                    }
                    return@launch
                }
                if (save != TransferMetadataWrite.Saved) {
                    intent = frozen
                    mutableState.update {
                        it.copy(
                            frozenIntent = frozen,
                            command = TransferCommandStatus.UnknownOutcome,
                            metadata = TransferMetadataStatus.Unavailable,
                            notice = TransferSubmitNotice.IntentMetadataUnavailable,
                            validationError = TransferValidationError.MetadataUnavailable
                        )
                    }
                    return@launch
                }
                intent = frozen
                mutableState.update {
                    it.copy(
                        frozenIntent = frozen,
                        command = TransferCommandStatus.Pending,
                        metadata = TransferMetadataStatus.Available
                    )
                }
                sendFrozen(requestGeneration, currentAuthority, frozen)
            } finally {
                commandMutex.unlock()
            }
        }
    }

    fun retryUnknownOutcome() {
        if (!commandMutex.tryLock()) return
        viewModelScope.launch {
            try {
                val currentAuthority = authority ?: return@launch
                val currentIntent = intent ?: return@launch
                val requestGeneration = generation
                if (!currentAuthority.canCreate ||
                    currentIntent.scope != currentAuthority.scope
                ) {
                    return@launch
                }
                if (mutableState.value.command !=
                    TransferCommandStatus.UnknownOutcome
                ) {
                    return@launch
                }
                mutableState.update {
                    it.copy(command = TransferCommandStatus.Pending, notice = null)
                }
                sendFrozen(requestGeneration, currentAuthority, currentIntent)
            } finally {
                commandMutex.unlock()
            }
        }
    }

    fun retryIntentCleanup() {
        val currentAuthority = authority ?: return
        val currentIntent = intent ?: return
        if (currentIntent.scope != currentAuthority.scope ||
            !mutableState.value.intentCleanupPending
        ) {
            return
        }
        val requestGeneration = generation
        viewModelScope.launch {
            val result = withMetadataLock(requestGeneration, currentAuthority) {
                metadataStore.clearIntent(currentAuthority.scope, currentIntent.idempotencyKey)
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            if (result == TransferMetadataWrite.Saved) {
                intent = null
                mutableState.update { it.copy(intentCleanupPending = false, frozenIntent = null) }
            }
        }
    }

    fun startAnotherTransfer() {
        if (mutableState.value.isFrozen) return
        mutableState.update {
            it.copy(
                command = TransferCommandStatus.Editing,
                selectedSourceLotId = null,
                selectedDestinationWarehouseId = null,
                selectedDestinationZoneId = null,
                zones = emptyList(),
                zoneLookup = TransferLookupStatus.NotRequested,
                quantityText = "",
                reason = "",
                validationError = null,
                notice = null,
                rejectionCode = null,
                confirmed = null
            )
        }
    }

    private suspend fun sendFrozen(
        requestGeneration: Long,
        currentAuthority: StockTransferAuthority,
        frozen: StockTransferIntent
    ) {
        val result = safeSubmit {
            gateway.create(
                frozen.frozenPayload,
                frozen.expectedSourceVersion,
                frozen.idempotencyKey,
                currentAuthority
            )
        }
        if (!isCurrent(requestGeneration, currentAuthority)) {
            withMetadataLock(null, currentAuthority) {
                metadataStore.markUnknownOutcome(currentAuthority.scope, frozen.idempotencyKey)
            }
            return
        }
        when (result) {
            is TransferSubmitResult.Confirmed -> {
                val cleanup = withMetadataLock(requestGeneration, currentAuthority) {
                    metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
                }
                intent = if (cleanup == TransferMetadataWrite.Saved) {
                    null
                } else {
                    frozen.copy(
                        status = TransferIntentStatus.UnknownOutcome
                    )
                }
                mutableState.update {
                    it.copy(
                        command = TransferCommandStatus.Confirmed,
                        frozenIntent = intent,
                        confirmed = result.transfer,
                        intentCleanupPending = cleanup != TransferMetadataWrite.Saved,
                        metadata = if (cleanup == TransferMetadataWrite.Saved) {
                            TransferMetadataStatus.Available
                        } else {
                            TransferMetadataStatus.Unavailable
                        },
                        notice = if (cleanup == TransferMetadataWrite.Saved) {
                            null
                        } else {
                            TransferSubmitNotice.IntentMetadataUnavailable
                        }
                    )
                }
            }

            TransferSubmitResult.PreconditionFailed -> finishDefiniteFailure(
                requestGeneration,
                currentAuthority,
                frozen,
                TransferSubmitNotice.PreconditionFailed,
                result
            )

            is TransferSubmitResult.Rejected -> finishRejected(
                requestGeneration,
                currentAuthority,
                frozen,
                result.code,
                null
            )

            TransferSubmitResult.PermissionDenied -> finishRejected(
                requestGeneration,
                currentAuthority,
                frozen,
                "PERMISSION_DENIED",
                TransferSubmitNotice.PermissionDenied
            )

            TransferSubmitResult.Conflict -> keepUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TransferSubmitNotice.Conflict
            )

            TransferSubmitResult.UnknownOutcome -> keepUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                null
            )

            TransferSubmitResult.NetworkUnavailable -> keepUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TransferSubmitNotice.NetworkUnavailable
            )

            TransferSubmitResult.ServiceUnavailable -> keepUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TransferSubmitNotice.ServiceUnavailable
            )

            TransferSubmitResult.ContextInvalidated -> keepUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TransferSubmitNotice.ContextInvalidated
            )

            TransferSubmitResult.SessionInvalidated -> keepUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TransferSubmitNotice.SessionInvalidated
            )
        }
    }

    private suspend fun finishDefiniteFailure(
        requestGeneration: Long,
        currentAuthority: StockTransferAuthority,
        frozen: StockTransferIntent,
        notice: TransferSubmitNotice,
        result: TransferSubmitResult
    ) {
        val cleanup = withMetadataLock(requestGeneration, currentAuthority) {
            metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleanup == TransferMetadataWrite.Saved) {
            intent = null
            mutableState.update {
                it.copy(
                    command = TransferCommandStatus.Rejected,
                    frozenIntent = null,
                    intentCleanupPending = false,
                    notice = notice,
                    confirmed = null
                )
            }
            if (result == TransferSubmitResult.PreconditionFailed) reloadSourceLots()
        } else {
            keepUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TransferSubmitNotice.IntentMetadataUnavailable
            )
        }
    }

    private suspend fun finishRejected(
        requestGeneration: Long,
        currentAuthority: StockTransferAuthority,
        frozen: StockTransferIntent,
        code: String?,
        notice: TransferSubmitNotice?
    ) {
        val cleanup = withMetadataLock(requestGeneration, currentAuthority) {
            metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        if (cleanup == TransferMetadataWrite.Saved) {
            intent = null
            mutableState.update {
                it.copy(
                    command = TransferCommandStatus.Rejected,
                    frozenIntent = null,
                    intentCleanupPending = false,
                    rejectionCode = code,
                    notice = notice,
                    confirmed = null
                )
            }
        } else {
            keepUnknown(
                requestGeneration,
                currentAuthority,
                frozen,
                TransferSubmitNotice.IntentMetadataUnavailable
            )
        }
    }

    private suspend fun keepUnknown(
        requestGeneration: Long,
        currentAuthority: StockTransferAuthority,
        frozen: StockTransferIntent,
        notice: TransferSubmitNotice?
    ) {
        val marked = withMetadataLock(requestGeneration, currentAuthority) {
            metadataStore.markUnknownOutcome(currentAuthority.scope, frozen.idempotencyKey)
        }
        if (!isCurrent(requestGeneration, currentAuthority)) return
        intent = frozen.copy(status = TransferIntentStatus.UnknownOutcome)
        mutableState.update {
            it.copy(
                command = TransferCommandStatus.UnknownOutcome,
                frozenIntent = intent,
                metadata = if (marked ==
                    TransferMetadataWrite.Saved
                ) {
                    TransferMetadataStatus.Available
                } else {
                    TransferMetadataStatus.Unavailable
                },
                notice = notice ?: if (marked == TransferMetadataWrite.Saved) {
                    null
                } else {
                    TransferSubmitNotice.IntentMetadataUnavailable
                }
            )
        }
    }

    private fun currentRequest(current: StockTransferUiState): StockTransferRequest? {
        val source = selectedSourceLot(current) ?: return null
        val destinationWarehouse = current.selectedDestinationWarehouseId ?: return null
        val destinationZone = current.selectedDestinationZoneId ?: return null
        val zone = current.zones.singleOrNull { it.id == destinationZone } ?: return null
        if (!zone.warehouseId.equals(destinationWarehouse, ignoreCase = true)) return null
        return StockTransferRequest(
            sourceLotId = source.id,
            sourceWarehouseId = source.warehouseId,
            sourceZoneId = source.zoneId,
            destinationWarehouseId = destinationWarehouse,
            destinationZoneId = destinationZone,
            skuId = source.skuId,
            catalogItemId = source.catalogItemId,
            quantityText = current.quantityText,
            unit = source.unit,
            reason = current.reason.trim()
        )
    }

    private fun validate(current: StockTransferUiState): TransferValidationError? {
        if (selectedSourceLot(current) == null) return TransferValidationError.SourceLotRequired
        if (current.selectedDestinationWarehouseId == null) {
            return TransferValidationError.DestinationWarehouseRequired
        }
        val destinationZoneId = current.selectedDestinationZoneId
            ?: return TransferValidationError.DestinationZoneRequired
        val source = selectedSourceLot(current) ?: return TransferValidationError.SourceLotRequired
        val zone = current.zones.singleOrNull { it.id == destinationZoneId }
            ?: return TransferValidationError.DestinationZoneRequired
        if (source.warehouseId == zone.warehouseId && source.zoneId == zone.id) {
            return TransferValidationError.SameLocation
        }
        if (current.quantityText.isBlank()) return TransferValidationError.QuantityRequired
        val quantity = try {
            if (!current.quantityText.trim().matches(DECIMAL_LEXEME)) {
                return TransferValidationError.QuantityInvalid
            }
            BigDecimal(current.quantityText.trim())
        } catch (_: NumberFormatException) {
            return TransferValidationError.QuantityInvalid
        }
        if (quantity.signum() <= 0) return TransferValidationError.QuantityMustBePositive
        if (quantity >
            source.physicalRemaining
        ) {
            return TransferValidationError.QuantityExceedsSource
        }
        if (current.reason.isBlank() || current.reason != current.reason.trim() ||
            current.reason.length > 2_000
        ) {
            return TransferValidationError.ReasonRequired
        }
        if (current.metadata != TransferMetadataStatus.Available) {
            return TransferValidationError.MetadataUnavailable
        }
        return null
    }

    private fun selectedSourceLot(current: StockTransferUiState): TransferSourceLotChoice? =
        current.sourceLots.singleOrNull { it.id == current.selectedSourceLotId }
            ?.takeIf(TransferSourceLotChoice::isSelectable)

    private fun isEditable(): Boolean = mutableState.value.command in
        setOf(TransferCommandStatus.Editing, TransferCommandStatus.Rejected) &&
        !mutableState.value.intentCleanupPending

    private fun isCurrent(
        requestGeneration: Long,
        expectedAuthority: StockTransferAuthority
    ): Boolean = generation == requestGeneration && authority == expectedAuthority

    private suspend fun withMetadataLock(
        requestGeneration: Long?,
        expectedAuthority: StockTransferAuthority,
        operation: suspend () -> TransferMetadataWrite
    ): TransferMetadataWrite = metadataMutex.withLock {
        if (requestGeneration != null && !isCurrent(requestGeneration, expectedAuthority)) {
            return@withLock TransferMetadataWrite.Unavailable
        }
        safeMetadataWrite(operation)
    }

    private suspend fun safeMetadataRead(
        operation: suspend () -> TransferMetadataRead<StockTransferIntent>
    ): TransferMetadataRead<StockTransferIntent> = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TransferMetadataRead.Unavailable
    }

    private suspend fun safeMetadataWrite(
        operation: suspend () -> TransferMetadataWrite
    ): TransferMetadataWrite = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TransferMetadataWrite.Unavailable
    }

    private suspend fun safeLookup(
        operation: suspend () -> TransferLookupResult
    ): TransferLookupResult = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TransferLookupResult.ServiceUnavailable
    }

    private suspend fun safeSubmit(
        operation: suspend () -> TransferSubmitResult
    ): TransferSubmitResult = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TransferSubmitResult.UnknownOutcome
    }

    private fun TransferLookupResult.toLookupStatus(): TransferLookupStatus = when (this) {
        TransferLookupResult.NetworkUnavailable -> TransferLookupStatus.NetworkUnavailable

        TransferLookupResult.ServiceUnavailable -> TransferLookupStatus.ServiceUnavailable

        TransferLookupResult.PermissionDenied -> TransferLookupStatus.PermissionDenied

        TransferLookupResult.ContextInvalidated -> TransferLookupStatus.ContextInvalidated

        TransferLookupResult.SessionInvalidated -> TransferLookupStatus.SessionInvalidated

        is TransferLookupResult.Lots,
        is TransferLookupResult.Warehouses,
        is TransferLookupResult.Zones -> TransferLookupStatus.ServiceUnavailable
    }

    private companion object {
        val DECIMAL_LEXEME = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    }
}
