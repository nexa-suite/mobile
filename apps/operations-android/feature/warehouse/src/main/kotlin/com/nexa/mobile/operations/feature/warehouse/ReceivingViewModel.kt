package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReceivingLookupStatus {
    NotRequested,
    Loading,
    Ready,
    Empty,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class ReceivingMetadataStatus { Loading, Available, Saving, Unavailable }

enum class ReceivingCommandStatus {
    Editing,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Confirmed,
    Rejected
}

enum class ReceivingValidationError {
    ProductRequired,
    ProductMustBeReconfirmed,
    WarehouseRequired,
    ZoneRequired,
    BatchRequired,
    ExpiryRequired,
    ExpiryMalformed,
    QuantityRequired,
    QuantityInvalid,
    QuantityMustBePositive,
    TemperatureInvalid,
    UnitRequired,
    MetadataUnavailable
}

data class ReceivingUiState(
    val authorityEpoch: Long = 0,
    val canLookUpWarehouses: Boolean = false,
    val canReceive: Boolean = false,
    val product: ReceivingProductReference? = null,
    val productVerifiedEpoch: Long? = null,
    val warehouses: List<ReceivingWarehouseChoice> = emptyList(),
    val selectedWarehouseId: String? = null,
    val warehouseLookup: ReceivingLookupStatus = ReceivingLookupStatus.NotRequested,
    val zones: List<ReceivingZoneChoice> = emptyList(),
    val selectedZoneId: String? = null,
    val zoneLookup: ReceivingLookupStatus = ReceivingLookupStatus.NotRequested,
    val batchNumber: String = "",
    val expirationDateText: String = "",
    val quantityText: String = "",
    val unit: String = "",
    val temperatureReadingText: String = "",
    val metadata: ReceivingMetadataStatus = ReceivingMetadataStatus.Loading,
    val command: ReceivingCommandStatus = ReceivingCommandStatus.Editing,
    val validationError: ReceivingValidationError? = null,
    val rejectionCode: String? = null,
    val confirmedLot: ReceivedLotFacts? = null,
    val intentCleanupPending: Boolean = false,
    val notice: ReceivingSubmitNotice? = null
) {
    val isIntentFrozen: Boolean
        get() = command in setOf(
            ReceivingCommandStatus.PersistingIntent,
            ReceivingCommandStatus.Pending,
            ReceivingCommandStatus.UnknownOutcome
        ) || (command == ReceivingCommandStatus.Confirmed && intentCleanupPending) ||
            (command == ReceivingCommandStatus.Rejected && intentCleanupPending)

    override fun toString(): String =
        "ReceivingUiState(epoch=$authorityEpoch, command=$command, metadata=$metadata, " +
            "warehouses=${warehouses.size}, zones=${zones.size})"
}

enum class ReceivingSubmitNotice {
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    IntentMetadataUnavailable
}

/** Connected Receiving controller. It never treats a locally prepared draft as stock. */
class ReceivingViewModel(
    private val gateway: ReceivingGateway,
    private val metadataStore: ReceivingMetadataStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReceivingUiState())
    val state = mutableState.asStateFlow()

    private var authority: ReceivingAuthority? = null
    private var generation = 0L
    private var intent: ReceivingIntentMetadata? = null
    private var draftWriteGeneration = 0L
    private var warehouseLookupGeneration = 0L
    private var zoneLookupGeneration = 0L

    /** Serializes scoped metadata access so reactivation cannot miss a late old-epoch write. */
    private val metadataMutex = Mutex()

    fun activate(
        currentAuthority: ReceivingAuthority,
        initialProduct: ConfirmedReceivingProduct? = null
    ) {
        generation++
        val requestGeneration = generation
        authority = currentAuthority
        intent = null
        mutableState.value = ReceivingUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            canLookUpWarehouses = currentAuthority.canLookUpWarehouses,
            canReceive = currentAuthority.canReceive,
            product = initialProduct?.reference,
            productVerifiedEpoch = initialProduct
                ?.takeIf { it.verifiedAuthorityEpoch == currentAuthority.authorityEpoch }
                ?.verifiedAuthorityEpoch,
            unit = initialProduct?.reference?.unit.orEmpty(),
            metadata = ReceivingMetadataStatus.Loading,
            warehouseLookup = if (currentAuthority.canLookUpWarehouses) {
                ReceivingLookupStatus.Loading
            } else {
                ReceivingLookupStatus.PermissionDenied
            }
        )
        reloadWarehouses()

        viewModelScope.launch {
            val (draftRead, intentRead) = metadataMutex.withLock {
                safeMetadataCall(ReceivingMetadataRead.Unavailable) {
                    metadataStore.loadDraft(currentAuthority.scope)
                } to safeMetadataCall(ReceivingMetadataRead.Unavailable) {
                    metadataStore.loadIntent(currentAuthority.scope)
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return@launch
            val restoredIntent = (intentRead as? ReceivingMetadataRead.Available)
                ?.value
                ?.takeIf { it.scope == currentAuthority.scope && it.idempotencyKey.isNotBlank() }
            intent = restoredIntent?.copy(status = ReceivingIntentMetadataStatus.UnknownOutcome)
            val restoredDraft = (draftRead as? ReceivingMetadataRead.Available)?.value
            val metadataAvailable = draftRead is ReceivingMetadataRead.Available &&
                intentRead is ReceivingMetadataRead.Available

            mutableState.update { current ->
                val base = if (restoredDraft != null) {
                    restoredDraft.toUiState(current, restoredIntent)
                } else if (restoredIntent != null) {
                    current.copy(
                        selectedWarehouseId = restoredIntent.request.warehouseId,
                        selectedZoneId = restoredIntent.request.zoneId,
                        batchNumber = restoredIntent.request.batchNumber,
                        expirationDateText = restoredIntent.request.expirationDate.toString(),
                        quantityText = restoredIntent.request.quantity.toPlainString(),
                        unit = restoredIntent.request.unit
                    )
                } else {
                    current
                }
                val product = when {
                    restoredIntent != null ->
                        initialProduct?.reference
                            ?.takeIf { it.matches(restoredIntent.request) }
                            ?: restoredDraft?.selectedProduct?.takeIf {
                                it.matches(restoredIntent.request)
                            }

                    initialProduct != null -> initialProduct.reference

                    else -> restoredDraft?.selectedProduct
                }
                base.copy(
                    product = product,
                    productVerifiedEpoch = initialProduct
                        ?.takeIf {
                            it.verifiedAuthorityEpoch == currentAuthority.authorityEpoch &&
                                (
                                    restoredIntent == null ||
                                        it.reference.matches(restoredIntent.request)
                                    )
                        }
                        ?.verifiedAuthorityEpoch,
                    unit = if (restoredIntent != null) {
                        restoredIntent.request.unit
                    } else {
                        if (initialProduct != null) initialProduct.reference.unit else base.unit
                    },
                    metadata = if (metadataAvailable) {
                        ReceivingMetadataStatus.Available
                    } else {
                        ReceivingMetadataStatus.Unavailable
                    },
                    command = if (restoredIntent != null) {
                        ReceivingCommandStatus.UnknownOutcome
                    } else {
                        ReceivingCommandStatus.Editing
                    },
                    validationError = if (metadataAvailable) {
                        null
                    } else {
                        ReceivingValidationError.MetadataUnavailable
                    },
                    notice = if (metadataAvailable) {
                        null
                    } else {
                        ReceivingSubmitNotice.IntentMetadataUnavailable
                    }
                )
            }

            if (!metadataAvailable) return@launch
            if (restoredIntent != null &&
                restoredIntent.status == ReceivingIntentMetadataStatus.Pending
            ) {
                val saved = metadataMutex.withLock {
                    if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) {
                        ReceivingMetadataWrite.Unavailable
                    } else {
                        safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                            metadataStore.saveIntent(
                                restoredIntent.copy(
                                    status = ReceivingIntentMetadataStatus.UnknownOutcome
                                )
                            )
                        }
                    }
                }
                if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return@launch
                if (saved != ReceivingMetadataWrite.Saved) {
                    mutableState.update {
                        it.copy(
                            metadata = ReceivingMetadataStatus.Unavailable,
                            notice = ReceivingSubmitNotice.IntentMetadataUnavailable
                        )
                    }
                }
            } else if (restoredIntent == null && initialProduct != null && restoredDraft == null) {
                persistDraft()
            }
        }
    }

    fun invalidate() {
        generation++
        authority = null
        intent = null
        mutableState.value = ReceivingUiState(
            authorityEpoch = mutableState.value.authorityEpoch + 1,
            warehouseLookup = ReceivingLookupStatus.SessionInvalidated,
            metadata = ReceivingMetadataStatus.Loading
        )
    }

    fun selectProduct(product: ConfirmedReceivingProduct) {
        if (!canEdit()) return
        val epoch = authority?.authorityEpoch ?: return
        if (product.verifiedAuthorityEpoch != epoch) return
        mutableState.update {
            it.copy(
                product = product.reference,
                productVerifiedEpoch = product.verifiedAuthorityEpoch,
                unit = product.reference.unit,
                validationError = null,
                rejectionCode = null,
                confirmedLot = null,
                notice = null,
                command = ReceivingCommandStatus.Editing
            )
        }
        persistDraft()
    }

    fun selectWarehouse(warehouseId: String) {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!current.canLookUpWarehouses || current.isIntentFrozen) return
        val warehouse = current.warehouses.firstOrNull { it.id == warehouseId && it.isSelectable }
            ?: return
        val requestGeneration = generation
        val lookupGeneration = ++zoneLookupGeneration
        mutableState.update {
            it.copy(
                selectedWarehouseId = warehouse.id,
                selectedZoneId = null,
                zones = emptyList(),
                zoneLookup = ReceivingLookupStatus.Loading,
                command = ReceivingCommandStatus.Editing,
                confirmedLot = null,
                rejectionCode = null,
                validationError = null
            )
        }
        persistDraft()
        viewModelScope.launch {
            val result = try {
                gateway.zones(warehouse.id, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReceivingLookupResult.ServiceUnavailable
            }
            if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch) ||
                lookupGeneration != zoneLookupGeneration ||
                mutableState.value.selectedWarehouseId != warehouse.id
            ) {
                return@launch
            }
            when (result) {
                is ReceivingLookupResult.Zones -> {
                    val safeZones = result.items.filter { zone ->
                        zone.warehouseId == warehouse.id && zone.isSelectable
                    }
                    mutableState.update {
                        it.copy(
                            zones = safeZones,
                            zoneLookup = if (safeZones.isEmpty()) {
                                ReceivingLookupStatus.Empty
                            } else {
                                ReceivingLookupStatus.Ready
                            }
                        )
                    }
                }

                else -> applyLookupFailure(
                    result,
                    zones = true,
                    requestGeneration = requestGeneration
                )
            }
        }
    }

    fun selectZone(zoneId: String) {
        if (!canEdit()) return
        val current = mutableState.value
        val warehouseId = current.selectedWarehouseId ?: return
        val zone = current.zones.firstOrNull {
            it.id == zoneId && it.warehouseId == warehouseId && it.isSelectable
        } ?: return
        mutableState.update {
            it.copy(selectedZoneId = zone.id, validationError = null, rejectionCode = null)
        }
        persistDraft()
    }

    fun batchNumberChanged(value: String) = updateDraft { it.copy(batchNumber = value) }

    fun expirationDateChanged(value: String) = updateDraft { it.copy(expirationDateText = value) }

    fun quantityChanged(value: String) = updateDraft { it.copy(quantityText = value) }

    fun unitChanged(value: String) = updateDraft { it.copy(unit = value) }

    fun temperatureReadingChanged(value: String) =
        updateDraft { it.copy(temperatureReadingText = value) }

    fun reloadWarehouses() {
        val currentAuthority = authority ?: return
        if (!currentAuthority.canLookUpWarehouses) {
            mutableState.update {
                it.copy(warehouseLookup = ReceivingLookupStatus.PermissionDenied)
            }
            return
        }
        val requestGeneration = generation
        val lookupGeneration = ++warehouseLookupGeneration
        mutableState.update { it.copy(warehouseLookup = ReceivingLookupStatus.Loading) }
        viewModelScope.launch {
            val result = try {
                gateway.warehouses(currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReceivingLookupResult.ServiceUnavailable
            }
            if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch) ||
                lookupGeneration != warehouseLookupGeneration
            ) {
                return@launch
            }
            when (result) {
                is ReceivingLookupResult.Warehouses -> {
                    val safeWarehouses = result.items.filter { it.isSelectable }
                    val existingId = mutableState.value.selectedWarehouseId
                    val selectedStillAvailable = safeWarehouses.any { it.id == existingId }
                    mutableState.update {
                        it.copy(
                            warehouses = safeWarehouses,
                            selectedWarehouseId = if (selectedStillAvailable) existingId else null,
                            selectedZoneId = if (selectedStillAvailable) {
                                it.selectedZoneId
                            } else {
                                null
                            },
                            zones = if (selectedStillAvailable) it.zones else emptyList(),
                            warehouseLookup = if (safeWarehouses.isEmpty()) {
                                ReceivingLookupStatus.Empty
                            } else {
                                ReceivingLookupStatus.Ready
                            }
                        )
                    }
                    if (selectedStillAvailable && mutableState.value.zones.isEmpty()) {
                        selectWarehouse(existingId!!)
                    }
                }

                else -> applyLookupFailure(
                    result,
                    zones = false,
                    requestGeneration = requestGeneration
                )
            }
        }
    }

    fun reloadZones() {
        val currentAuthority = authority ?: return
        val warehouseId = mutableState.value.selectedWarehouseId ?: return
        if (!currentAuthority.canLookUpWarehouses) {
            mutableState.update { it.copy(zoneLookup = ReceivingLookupStatus.PermissionDenied) }
            return
        }
        val requestGeneration = generation
        val lookupGeneration = ++zoneLookupGeneration
        mutableState.update { it.copy(zoneLookup = ReceivingLookupStatus.Loading) }
        viewModelScope.launch {
            val result = try {
                gateway.zones(warehouseId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReceivingLookupResult.ServiceUnavailable
            }
            if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch) ||
                lookupGeneration != zoneLookupGeneration ||
                mutableState.value.selectedWarehouseId != warehouseId
            ) {
                return@launch
            }
            when (result) {
                is ReceivingLookupResult.Zones -> {
                    val safeZones = result.items.filter {
                        it.warehouseId == warehouseId && it.isSelectable
                    }
                    val selectedZoneId = mutableState.value.selectedZoneId
                    val stillAvailable = safeZones.any { it.id == selectedZoneId }
                    mutableState.update {
                        it.copy(
                            zones = safeZones,
                            selectedZoneId = if (stillAvailable) selectedZoneId else null,
                            zoneLookup = if (safeZones.isEmpty()) {
                                ReceivingLookupStatus.Empty
                            } else {
                                ReceivingLookupStatus.Ready
                            }
                        )
                    }
                }

                else -> applyLookupFailure(
                    result,
                    zones = true,
                    requestGeneration = requestGeneration
                )
            }
        }
    }

    fun submit() {
        if (!canEdit()) return
        val currentAuthority = authority ?: return
        val current = mutableState.value
        if (!current.canReceive) {
            mutableState.update {
                it.copy(notice = ReceivingSubmitNotice.PermissionDenied)
            }
            return
        }
        if (current.metadata != ReceivingMetadataStatus.Available) {
            mutableState.update {
                it.copy(
                    metadata = ReceivingMetadataStatus.Unavailable,
                    validationError = ReceivingValidationError.MetadataUnavailable,
                    notice = ReceivingSubmitNotice.IntentMetadataUnavailable
                )
            }
            return
        }
        val request = current.toRequestOrNull().also { candidate ->
            if (candidate == null) return
        } ?: return
        val command = ReceivingIntentMetadata(
            scope = currentAuthority.scope,
            idempotencyKey = UUID.randomUUID().toString(),
            request = request,
            status = ReceivingIntentMetadataStatus.Pending
        )
        val draft = current.toDraftMetadata()
        intent = command
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                command = ReceivingCommandStatus.PersistingIntent,
                validationError = null,
                rejectionCode = null,
                confirmedLot = null,
                notice = null
            )
        }
        viewModelScope.launch {
            val savedCommand = metadataMutex.withLock {
                if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) {
                    return@withLock ReceivingMetadataWrite.Unavailable
                }
                val savedDraft = safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                    metadataStore.saveDraft(currentAuthority.scope, draft)
                }
                if (savedDraft == ReceivingMetadataWrite.Saved) {
                    safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                        metadataStore.saveIntent(command)
                    }
                } else {
                    ReceivingMetadataWrite.Unavailable
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return@launch
            if (savedCommand != ReceivingMetadataWrite.Saved) {
                intent = null
                mutableState.update {
                    it.copy(
                        metadata = ReceivingMetadataStatus.Unavailable,
                        command = ReceivingCommandStatus.Editing,
                        validationError = ReceivingValidationError.MetadataUnavailable,
                        notice = ReceivingSubmitNotice.IntentMetadataUnavailable
                    )
                }
                return@launch
            }
            mutableState.update {
                it.copy(
                    metadata = ReceivingMetadataStatus.Available,
                    command = ReceivingCommandStatus.Pending
                )
            }
            dispatch(command, requestGeneration, currentAuthority)
        }
    }

    /** Explicit same-intent replay; it never changes the key or the immutable request. */
    fun retryUnknownOutcome() {
        val currentAuthority = authority ?: return
        val frozen = intent ?: return
        val current = mutableState.value
        if (current.command != ReceivingCommandStatus.UnknownOutcome ||
            frozen.scope != currentAuthority.scope || !currentAuthority.canReceive ||
            current.metadata != ReceivingMetadataStatus.Available
        ) {
            return
        }
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                command = ReceivingCommandStatus.PersistingIntent,
                notice = null,
                confirmedLot = null
            )
        }
        viewModelScope.launch {
            val saved = metadataMutex.withLock {
                if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) {
                    ReceivingMetadataWrite.Unavailable
                } else {
                    safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                        metadataStore.saveIntent(
                            frozen.copy(status = ReceivingIntentMetadataStatus.Pending)
                        )
                    }
                }
            }
            if (saved != ReceivingMetadataWrite.Saved) {
                if (isCurrent(requestGeneration, currentAuthority.authorityEpoch)) {
                    mutableState.update {
                        it.copy(
                            metadata = ReceivingMetadataStatus.Unavailable,
                            command = ReceivingCommandStatus.UnknownOutcome,
                            notice = ReceivingSubmitNotice.IntentMetadataUnavailable
                        )
                    }
                }
                return@launch
            }
            if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return@launch
            mutableState.update {
                it.copy(
                    command = ReceivingCommandStatus.Pending,
                    notice = null,
                    confirmedLot = null
                )
            }
            dispatch(
                frozen.copy(status = ReceivingIntentMetadataStatus.Pending),
                requestGeneration,
                currentAuthority
            )
        }
    }

    /** Retry metadata deletion after a known terminal outcome; never repeats the network command. */
    fun retryIntentCleanup() {
        val currentAuthority = authority ?: return
        val frozen = intent ?: return
        if (!mutableState.value.intentCleanupPending) return
        val requestGeneration = generation
        viewModelScope.launch {
            val result = metadataMutex.withLock {
                if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) {
                    return@withLock ReceivingMetadataWrite.Unavailable
                }
                val cleared = safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                    metadataStore.clearIntent(currentAuthority.scope, frozen.idempotencyKey)
                }
                if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch) &&
                    cleared == ReceivingMetadataWrite.Saved
                ) {
                    safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                        metadataStore.saveIntent(
                            frozen.copy(status = ReceivingIntentMetadataStatus.UnknownOutcome)
                        )
                    }
                }
                cleared
            }
            if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return@launch
            if (result == ReceivingMetadataWrite.Saved) {
                intent = null
                mutableState.update {
                    it.copy(
                        intentCleanupPending = false,
                        metadata = ReceivingMetadataStatus.Available
                    )
                }
            } else {
                mutableState.update { it.copy(metadata = ReceivingMetadataStatus.Unavailable) }
            }
        }
    }

    fun startAnotherReceipt() {
        val current = mutableState.value
        if (authority == null || current.command != ReceivingCommandStatus.Confirmed ||
            current.intentCleanupPending
        ) {
            return
        }
        mutableState.update {
            it.copy(
                batchNumber = "",
                expirationDateText = "",
                quantityText = "",
                temperatureReadingText = "",
                command = ReceivingCommandStatus.Editing,
                confirmedLot = null,
                validationError = null,
                rejectionCode = null,
                notice = null
            )
        }
        persistDraft()
    }

    private suspend fun dispatch(
        command: ReceivingIntentMetadata,
        requestGeneration: Long,
        currentAuthority: ReceivingAuthority
    ) {
        val result = try {
            gateway.receive(command.request, command.idempotencyKey, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceivingSubmitResult.UnknownOutcome
        }
        if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return
        when (result) {
            is ReceivingSubmitResult.Confirmed -> {
                if (!result.facts.matches(command.request)) {
                    markUnknown(command, requestGeneration, currentAuthority)
                    return
                }
                val cleared = cleanupTerminalIntent(command, requestGeneration, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return
                mutableState.update {
                    it.copy(
                        command = ReceivingCommandStatus.Confirmed,
                        confirmedLot = result.facts,
                        intentCleanupPending = !cleared,
                        metadata = if (cleared) {
                            ReceivingMetadataStatus.Available
                        } else {
                            ReceivingMetadataStatus.Unavailable
                        },
                        notice = if (cleared) {
                            null
                        } else {
                            ReceivingSubmitNotice.IntentMetadataUnavailable
                        }
                    )
                }
            }

            is ReceivingSubmitResult.Rejected -> {
                val cleared = cleanupTerminalIntent(command, requestGeneration, currentAuthority)
                if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return
                mutableState.update {
                    it.copy(
                        command = ReceivingCommandStatus.Rejected,
                        confirmedLot = null,
                        rejectionCode = result.code,
                        intentCleanupPending = !cleared,
                        metadata = if (cleared) {
                            ReceivingMetadataStatus.Available
                        } else {
                            ReceivingMetadataStatus.Unavailable
                        },
                        notice = if (cleared) {
                            null
                        } else {
                            ReceivingSubmitNotice.IntentMetadataUnavailable
                        }
                    )
                }
            }

            ReceivingSubmitResult.UnknownOutcome -> markUnknown(
                command,
                requestGeneration,
                currentAuthority
            )

            ReceivingSubmitResult.PermissionDenied -> terminalFailure(
                command,
                requestGeneration,
                currentAuthority,
                ReceivingSubmitNotice.PermissionDenied
            )

            ReceivingSubmitResult.ContextInvalidated -> terminalFailure(
                command,
                requestGeneration,
                currentAuthority,
                ReceivingSubmitNotice.ContextInvalidated
            )

            ReceivingSubmitResult.SessionInvalidated -> terminalFailure(
                command,
                requestGeneration,
                currentAuthority,
                ReceivingSubmitNotice.SessionInvalidated
            )

            ReceivingSubmitResult.ServiceUnavailable -> markUnknown(
                command,
                requestGeneration,
                currentAuthority
            )
        }
    }

    /** Clears only while this screen still owns the authority; a late clear restores uncertainty. */
    private suspend fun cleanupTerminalIntent(
        command: ReceivingIntentMetadata,
        requestGeneration: Long,
        currentAuthority: ReceivingAuthority
    ): Boolean = metadataMutex.withLock {
        if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return@withLock false
        val cleared = safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
            metadataStore.clearIntent(currentAuthority.scope, command.idempotencyKey)
        } == ReceivingMetadataWrite.Saved
        if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) {
            if (cleared) {
                safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                    metadataStore.saveIntent(
                        command.copy(status = ReceivingIntentMetadataStatus.UnknownOutcome)
                    )
                }
            }
            return@withLock false
        }
        if (cleared) intent = null
        cleared
    }

    private suspend fun markUnknown(
        command: ReceivingIntentMetadata,
        requestGeneration: Long,
        currentAuthority: ReceivingAuthority
    ) {
        val frozen = command.copy(status = ReceivingIntentMetadataStatus.UnknownOutcome)
        val saved = metadataMutex.withLock {
            safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                metadataStore.saveIntent(frozen)
            }
        }
        if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return
        intent = frozen
        mutableState.update {
            it.copy(
                command = ReceivingCommandStatus.UnknownOutcome,
                confirmedLot = null,
                metadata = if (saved == ReceivingMetadataWrite.Saved) {
                    it.metadata
                } else {
                    ReceivingMetadataStatus.Unavailable
                },
                notice = if (saved == ReceivingMetadataWrite.Saved) {
                    null
                } else {
                    ReceivingSubmitNotice.IntentMetadataUnavailable
                }
            )
        }
    }

    private suspend fun terminalFailure(
        command: ReceivingIntentMetadata,
        requestGeneration: Long,
        currentAuthority: ReceivingAuthority,
        notice: ReceivingSubmitNotice
    ) {
        // A scope/session change after dispatch may hide the response even if the server committed.
        val frozen = command.copy(status = ReceivingIntentMetadataStatus.UnknownOutcome)
        val saved = metadataMutex.withLock {
            safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                metadataStore.saveIntent(frozen)
            }
        }
        if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return
        intent = frozen
        mutableState.update {
            it.copy(
                command = ReceivingCommandStatus.UnknownOutcome,
                metadata = if (saved == ReceivingMetadataWrite.Saved) {
                    it.metadata
                } else {
                    ReceivingMetadataStatus.Unavailable
                },
                confirmedLot = null,
                notice = notice
            )
        }
    }

    private fun updateDraft(transform: (ReceivingUiState) -> ReceivingUiState) {
        if (!canEdit()) return
        mutableState.update { current ->
            transform(current).copy(
                validationError = null,
                rejectionCode = null,
                confirmedLot = null,
                command = ReceivingCommandStatus.Editing
            )
        }
        persistDraft()
    }

    private fun persistDraft() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val writeGeneration = ++draftWriteGeneration
        val requestGeneration = generation
        val draft = current.toDraftMetadata()
        mutableState.update { it.copy(metadata = ReceivingMetadataStatus.Saving) }
        viewModelScope.launch {
            val result = metadataMutex.withLock {
                if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) {
                    ReceivingMetadataWrite.Unavailable
                } else {
                    safeMetadataCall(ReceivingMetadataWrite.Unavailable) {
                        metadataStore.saveDraft(currentAuthority.scope, draft)
                    }
                }
            }
            if (writeGeneration != draftWriteGeneration ||
                !isCurrent(requestGeneration, currentAuthority.authorityEpoch)
            ) {
                return@launch
            }
            mutableState.update {
                it.copy(
                    metadata = if (result == ReceivingMetadataWrite.Saved) {
                        ReceivingMetadataStatus.Available
                    } else {
                        ReceivingMetadataStatus.Unavailable
                    },
                    notice = if (result == ReceivingMetadataWrite.Saved) {
                        it.notice
                    } else {
                        ReceivingSubmitNotice.IntentMetadataUnavailable
                    }
                )
            }
        }
    }

    private fun applyLookupFailure(
        result: ReceivingLookupResult,
        zones: Boolean,
        requestGeneration: Long
    ) {
        if (requestGeneration != generation) return
        val status = when (result) {
            ReceivingLookupResult.NetworkUnavailable -> ReceivingLookupStatus.NetworkUnavailable

            ReceivingLookupResult.ServiceUnavailable -> ReceivingLookupStatus.ServiceUnavailable

            ReceivingLookupResult.PermissionDenied -> ReceivingLookupStatus.PermissionDenied

            ReceivingLookupResult.ContextInvalidated -> ReceivingLookupStatus.ContextInvalidated

            ReceivingLookupResult.SessionInvalidated -> ReceivingLookupStatus.SessionInvalidated

            is ReceivingLookupResult.Warehouses,
            is ReceivingLookupResult.Zones -> ReceivingLookupStatus.ServiceUnavailable
        }
        mutableState.update {
            if (zones) {
                it.copy(zoneLookup = status, zones = emptyList())
            } else {
                it.copy(warehouseLookup = status, warehouses = emptyList())
            }
        }
    }

    private fun ReceivingUiState.toRequestOrNull(): InboundReceiptRequest? {
        fun invalid(error: ReceivingValidationError): InboundReceiptRequest? {
            mutableState.update { it.copy(validationError = error) }
            return null
        }
        val selectedProduct = product ?: return invalid(ReceivingValidationError.ProductRequired)
        if (productVerifiedEpoch != authorityEpoch) {
            return invalid(ReceivingValidationError.ProductMustBeReconfirmed)
        }
        val warehouseId = selectedWarehouseId
            ?: return invalid(ReceivingValidationError.WarehouseRequired)
        if (warehouses.none { it.id == warehouseId && it.isSelectable }) {
            return invalid(ReceivingValidationError.WarehouseRequired)
        }
        val zoneId = selectedZoneId ?: return invalid(ReceivingValidationError.ZoneRequired)
        if (zones.none {
                it.id == zoneId && it.warehouseId == warehouseId && it.isSelectable
            }
        ) {
            return invalid(ReceivingValidationError.ZoneRequired)
        }
        val batch = batchNumber.takeIf { it.isNotBlank() }
            ?: return invalid(ReceivingValidationError.BatchRequired)
        if (expirationDateText.isBlank()) return invalid(ReceivingValidationError.ExpiryRequired)
        val expiry = try {
            LocalDate.parse(expirationDateText)
        } catch (_: DateTimeParseException) {
            return invalid(ReceivingValidationError.ExpiryMalformed)
        }
        if (quantityText.isBlank()) return invalid(ReceivingValidationError.QuantityRequired)
        val quantity = try {
            BigDecimal(quantityText)
        } catch (_: NumberFormatException) {
            return invalid(ReceivingValidationError.QuantityInvalid)
        }
        if (quantity.signum() <= 0) {
            return invalid(ReceivingValidationError.QuantityMustBePositive)
        }
        val temperature = if (temperatureReadingText.isBlank()) {
            null
        } else {
            try {
                BigDecimal(temperatureReadingText)
            } catch (_: NumberFormatException) {
                return invalid(ReceivingValidationError.TemperatureInvalid)
            }
        }
        val selectedUnit = unit.takeIf { it.isNotBlank() }
            ?: return invalid(ReceivingValidationError.UnitRequired)
        return InboundReceiptRequest(
            warehouseId = warehouseId,
            zoneId = zoneId,
            catalogItemId = selectedProduct.catalogItemId,
            skuId = selectedProduct.skuId,
            batchNumber = batch,
            expirationDate = expiry,
            quantity = quantity,
            unit = selectedUnit,
            temperatureReading = temperature
        )
    }

    private fun ReceivingUiState.toDraftMetadata() = ReceivingDraftMetadata(
        selectedProduct = product,
        warehouseId = selectedWarehouseId,
        zoneId = selectedZoneId,
        batchNumber = batchNumber,
        expirationDateText = expirationDateText,
        quantityText = quantityText,
        unit = unit,
        temperatureReadingText = temperatureReadingText
    )

    private fun ReceivingDraftMetadata.toUiState(
        current: ReceivingUiState,
        intent: ReceivingIntentMetadata?
    ): ReceivingUiState {
        val request = intent?.request
        val restoredProduct = selectedProduct?.takeIf { request == null || it.matches(request) }
        return current.copy(
            product = restoredProduct ?: current.product,
            selectedWarehouseId = request?.warehouseId ?: warehouseId,
            selectedZoneId = request?.zoneId ?: zoneId,
            batchNumber = request?.batchNumber ?: batchNumber,
            expirationDateText = request?.expirationDate?.toString() ?: expirationDateText,
            quantityText = request?.quantity?.toPlainString() ?: quantityText,
            unit = request?.unit ?: unit,
            temperatureReadingText = request?.temperatureReading?.toPlainString()
                ?: temperatureReadingText,
            productVerifiedEpoch = null
        )
    }

    private fun ReceivingProductReference.matches(request: InboundReceiptRequest): Boolean =
        (request.catalogItemId == null || catalogItemId == request.catalogItemId) &&
            (request.skuId == null || skuId == request.skuId)

    private fun ReceivedLotFacts.matches(request: InboundReceiptRequest): Boolean =
        id.isNotBlank() && warehouseId == request.warehouseId && zoneId == request.zoneId &&
            (request.catalogItemId == null || catalogItemId == request.catalogItemId) &&
            (request.skuId == null || skuId == request.skuId) &&
            batchNumber == request.batchNumber && expirationDate == request.expirationDate &&
            onHand.compareTo(request.quantity) == 0 && unit.equals(request.unit, ignoreCase = true)

    private fun canEdit(): Boolean {
        val current = mutableState.value
        return authority != null && current.command in setOf(
            ReceivingCommandStatus.Editing,
            ReceivingCommandStatus.Rejected
        ) && !current.intentCleanupPending
    }

    private fun isCurrent(requestGeneration: Long, epoch: Long): Boolean =
        requestGeneration == generation && epoch == mutableState.value.authorityEpoch &&
            authority?.authorityEpoch == epoch

    private suspend fun <T> safeMetadataCall(fallback: T, operation: suspend () -> T): T = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback
    }
}
