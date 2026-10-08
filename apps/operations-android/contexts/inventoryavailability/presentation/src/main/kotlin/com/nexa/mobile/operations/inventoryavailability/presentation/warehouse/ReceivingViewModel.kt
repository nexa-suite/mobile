package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingIntentCoordinator
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingIntentExecution
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingMetadataStore
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ConfirmedReceivingProduct
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundReceiptRequest
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivedLotFacts
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingDraftMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingEvidenceCandidate
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingEvidenceObject
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingEvidenceResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingEvidenceSelectionContext
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadataStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingProductReference
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingSubmitResult
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingWarehouseChoice
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingZoneChoice
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

enum class ReceivingMetadataStatus {
    Loading,
    Available,
    Saving,
    Unavailable
}

enum class ReceivingEvidenceStatus {
    None,
    Uploading,
    Checking,
    AwaitingAvailability,
    Available,
    UnknownOutcome,
    Rejected,
    Unavailable
}

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
    TemperatureEvidenceNotAvailable,
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
    val temperatureEvidenceObjectId: String? = null,
    val temperatureEvidenceStatus: ReceivingEvidenceStatus = ReceivingEvidenceStatus.None,
    val temperatureEvidenceFailureCode: String? = null,
    val canUploadTemperatureEvidence: Boolean = false,
    val canReadTemperatureEvidence: Boolean = false,
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
    private val intentCoordinator = ReceivingIntentCoordinator(
        gateway = gateway,
        metadataStore = metadataStore,
        metadataMutex = metadataMutex
    )

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
            canUploadTemperatureEvidence = currentAuthority.permissions.containsAll(
                setOf("document.upload", "document.read")
            ),
            canReadTemperatureEvidence = "document.read" in currentAuthority.permissions,
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
                        unit = restoredIntent.request.unit,
                        temperatureEvidenceObjectId =
                            restoredIntent.request.temperatureEvidenceObjectId,
                        temperatureEvidenceStatus = if (
                            restoredIntent.request.temperatureEvidenceObjectId != null
                        ) {
                            ReceivingEvidenceStatus.UnknownOutcome
                        } else {
                            ReceivingEvidenceStatus.None
                        }
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
            if (mutableState.value.temperatureEvidenceObjectId != null) {
                refreshTemperatureEvidence()
            }
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
                temperatureEvidenceObjectId = null,
                temperatureEvidenceStatus = ReceivingEvidenceStatus.None,
                temperatureEvidenceFailureCode = null,
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
                temperatureEvidenceObjectId = if (current.selectedWarehouseId == warehouse.id) {
                    current.temperatureEvidenceObjectId
                } else {
                    null
                },
                temperatureEvidenceStatus = if (current.selectedWarehouseId == warehouse.id) {
                    current.temperatureEvidenceStatus
                } else {
                    ReceivingEvidenceStatus.None
                },
                temperatureEvidenceFailureCode = null,
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

    fun temperatureReadingChanged(value: String) = updateDraft {
        it.copy(
            temperatureReadingText = value,
            temperatureEvidenceObjectId = null,
            temperatureEvidenceStatus = ReceivingEvidenceStatus.None,
            temperatureEvidenceFailureCode = null
        )
    }

    fun temperatureEvidenceSelectionContext(): ReceivingEvidenceSelectionContext? {
        val currentAuthority = authority ?: return null
        val current = mutableState.value
        val warehouseId = current.selectedWarehouseId ?: return null
        if (!current.canReceive || !current.canUploadTemperatureEvidence ||
            current.isIntentFrozen ||
            current.warehouses.none { it.id == warehouseId && it.isSelectable }
        ) {
            return null
        }
        return ReceivingEvidenceSelectionContext(currentAuthority.scope, warehouseId)
    }

    /** Uploads a selected image against the exact active warehouse, then reads back its status. */
    suspend fun uploadTemperatureEvidence(
        candidate: ReceivingEvidenceCandidate,
        selection: ReceivingEvidenceSelectionContext
    ) {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val warehouseId = current.selectedWarehouseId ?: return
        if (selection.scope != currentAuthority.scope ||
            selection.warehouseId != warehouseId
        ) {
            return
        }
        if (!canEdit() || !current.canReceive || !current.canUploadTemperatureEvidence ||
            current.warehouses.none { it.id == warehouseId && it.isSelectable } ||
            !candidate.file.isFile || candidate.byteSize <= 0 ||
            candidate.file.length() != candidate.byteSize
        ) {
            mutableState.update {
                it.copy(
                    temperatureEvidenceStatus = if (current.canUploadTemperatureEvidence) {
                        ReceivingEvidenceStatus.Rejected
                    } else {
                        ReceivingEvidenceStatus.Unavailable
                    },
                    temperatureEvidenceFailureCode = if (current.canUploadTemperatureEvidence) {
                        "INVALID_EVIDENCE"
                    } else {
                        "PERMISSION_DENIED"
                    }
                )
            }
            return
        }
        val requestGeneration = generation
        val uploadKey = UUID.randomUUID().toString()
        mutableState.update {
            it.copy(
                temperatureEvidenceObjectId = null,
                temperatureEvidenceStatus = ReceivingEvidenceStatus.Uploading,
                temperatureEvidenceFailureCode = null
            )
        }
        val upload = try {
            gateway.uploadTemperatureEvidence(warehouseId, candidate, uploadKey, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceivingEvidenceResult.UnknownOutcome
        }
        if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch) ||
            mutableState.value.selectedWarehouseId != warehouseId
        ) {
            return
        }
        when (upload) {
            is ReceivingEvidenceResult.Loaded -> {
                if (!upload.evidence.matchesWarehouse(warehouseId)) {
                    mutableState.update {
                        it.copy(
                            temperatureEvidenceStatus = ReceivingEvidenceStatus.Rejected,
                            temperatureEvidenceFailureCode = "EVIDENCE_SUBJECT_MISMATCH"
                        )
                    }
                    return
                }
                mutableState.update {
                    it.copy(
                        temperatureEvidenceObjectId = upload.evidence.id,
                        temperatureEvidenceStatus = ReceivingEvidenceStatus.Checking,
                        temperatureEvidenceFailureCode = null
                    )
                }
                persistDraft()
                checkTemperatureEvidence(
                    upload.evidence.id,
                    warehouseId,
                    requestGeneration,
                    currentAuthority
                )
            }

            is ReceivingEvidenceResult.Rejected -> mutableState.update {
                it.copy(
                    temperatureEvidenceStatus = ReceivingEvidenceStatus.Rejected,
                    temperatureEvidenceFailureCode = upload.code
                )
            }

            ReceivingEvidenceResult.PermissionDenied -> setEvidenceStatus(
                ReceivingEvidenceStatus.Unavailable,
                "PERMISSION_DENIED"
            )

            ReceivingEvidenceResult.ContextInvalidated -> setEvidenceStatus(
                ReceivingEvidenceStatus.Unavailable,
                "CONTEXT_INVALIDATED"
            )

            ReceivingEvidenceResult.SessionInvalidated -> setEvidenceStatus(
                ReceivingEvidenceStatus.Unavailable,
                "SESSION_INVALIDATED"
            )

            ReceivingEvidenceResult.NetworkUnavailable,
            ReceivingEvidenceResult.UnknownOutcome -> setEvidenceStatus(
                ReceivingEvidenceStatus.UnknownOutcome,
                null
            )

            ReceivingEvidenceResult.ServiceUnavailable -> setEvidenceStatus(
                ReceivingEvidenceStatus.Unavailable,
                null
            )
        }
    }

    /** Rechecks the server status for evidence that was previously uploaded or restored. */
    fun refreshTemperatureEvidence() {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val evidenceId = current.temperatureEvidenceObjectId ?: return
        val warehouseId = current.selectedWarehouseId ?: return
        if (!current.canReadTemperatureEvidence || current.isIntentFrozen) return
        val requestGeneration = generation
        mutableState.update {
            it.copy(
                temperatureEvidenceStatus = ReceivingEvidenceStatus.Checking,
                temperatureEvidenceFailureCode = null
            )
        }
        viewModelScope.launch {
            checkTemperatureEvidence(evidenceId, warehouseId, requestGeneration, currentAuthority)
        }
    }

    private suspend fun checkTemperatureEvidence(
        evidenceId: String,
        warehouseId: String,
        requestGeneration: Long,
        currentAuthority: ReceivingAuthority
    ) {
        val result = try {
            gateway.temperatureEvidenceStatus(evidenceId, warehouseId, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceivingEvidenceResult.ServiceUnavailable
        }
        if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch) ||
            mutableState.value.selectedWarehouseId != warehouseId ||
            mutableState.value.temperatureEvidenceObjectId != evidenceId
        ) {
            return
        }
        when (result) {
            is ReceivingEvidenceResult.Loaded -> {
                if (!result.evidence.matchesWarehouse(warehouseId) ||
                    result.evidence.id != evidenceId
                ) {
                    setEvidenceStatus(ReceivingEvidenceStatus.Rejected, "EVIDENCE_SUBJECT_MISMATCH")
                } else {
                    val available = result.evidence.lifecycleStatus.equals(
                        "AVAILABLE",
                        ignoreCase = true
                    )
                    mutableState.update {
                        it.copy(
                            temperatureEvidenceStatus = if (available) {
                                ReceivingEvidenceStatus.Available
                            } else {
                                ReceivingEvidenceStatus.AwaitingAvailability
                            },
                            temperatureEvidenceFailureCode = null
                        )
                    }
                    persistDraft()
                }
            }

            is ReceivingEvidenceResult.Rejected -> setEvidenceStatus(
                ReceivingEvidenceStatus.Rejected,
                result.code
            )

            ReceivingEvidenceResult.PermissionDenied -> setEvidenceStatus(
                ReceivingEvidenceStatus.Unavailable,
                "PERMISSION_DENIED"
            )

            ReceivingEvidenceResult.ContextInvalidated -> setEvidenceStatus(
                ReceivingEvidenceStatus.Unavailable,
                "CONTEXT_INVALIDATED"
            )

            ReceivingEvidenceResult.SessionInvalidated -> setEvidenceStatus(
                ReceivingEvidenceStatus.Unavailable,
                "SESSION_INVALIDATED"
            )

            ReceivingEvidenceResult.UnknownOutcome,
            ReceivingEvidenceResult.NetworkUnavailable,
            ReceivingEvidenceResult.ServiceUnavailable -> setEvidenceStatus(
                ReceivingEvidenceStatus.Unavailable,
                null
            )
        }
    }

    private fun setEvidenceStatus(status: ReceivingEvidenceStatus, code: String?) {
        mutableState.update {
            it.copy(
                temperatureEvidenceStatus = status,
                temperatureEvidenceFailureCode = code
            )
        }
    }

    private fun ReceivingEvidenceObject.matchesWarehouse(warehouseId: String): Boolean =
        subjectType == "WAREHOUSE" && subjectId == warehouseId && id.isNotBlank()

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
            val execution = intentCoordinator.execute(
                command = command,
                draft = draft,
                authority = currentAuthority,
                isCurrent = { isCurrent(requestGeneration, currentAuthority.authorityEpoch) },
                onIntentPersisted = {
                    if (isCurrent(requestGeneration, currentAuthority.authorityEpoch)) {
                        mutableState.update {
                            it.copy(
                                metadata = ReceivingMetadataStatus.Available,
                                command = ReceivingCommandStatus.Pending
                            )
                        }
                    }
                }
            )
            applyIntentExecution(
                execution,
                command,
                requestGeneration,
                currentAuthority,
                isReplay = false
            )
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
            val pending = frozen.copy(status = ReceivingIntentMetadataStatus.Pending)
            val execution = intentCoordinator.execute(
                command = pending,
                draft = null,
                authority = currentAuthority,
                isCurrent = { isCurrent(requestGeneration, currentAuthority.authorityEpoch) },
                onIntentPersisted = {
                    if (isCurrent(requestGeneration, currentAuthority.authorityEpoch)) {
                        mutableState.update {
                            it.copy(
                                command = ReceivingCommandStatus.Pending,
                                notice = null,
                                confirmedLot = null
                            )
                        }
                    }
                }
            )
            applyIntentExecution(
                execution,
                pending,
                requestGeneration,
                currentAuthority,
                isReplay = true
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
                temperatureEvidenceObjectId = null,
                temperatureEvidenceStatus = ReceivingEvidenceStatus.None,
                temperatureEvidenceFailureCode = null,
                command = ReceivingCommandStatus.Editing,
                confirmedLot = null,
                validationError = null,
                rejectionCode = null,
                notice = null
            )
        }
        persistDraft()
    }

    private fun applyIntentExecution(
        execution: ReceivingIntentExecution,
        command: ReceivingIntentMetadata,
        requestGeneration: Long,
        currentAuthority: ReceivingAuthority,
        isReplay: Boolean
    ) {
        if (!isCurrent(requestGeneration, currentAuthority.authorityEpoch)) return
        when (execution) {
            ReceivingIntentExecution.Stale -> return

            ReceivingIntentExecution.MetadataUnavailable -> {
                if (!isReplay) intent = null
                mutableState.update {
                    it.copy(
                        metadata = ReceivingMetadataStatus.Unavailable,
                        command = if (isReplay) {
                            ReceivingCommandStatus.UnknownOutcome
                        } else {
                            ReceivingCommandStatus.Editing
                        },
                        validationError = if (isReplay) {
                            it.validationError
                        } else {
                            ReceivingValidationError.MetadataUnavailable
                        },
                        notice = ReceivingSubmitNotice.IntentMetadataUnavailable
                    )
                }
            }

            is ReceivingIntentExecution.Terminal -> when (val result = execution.result) {
                is ReceivingSubmitResult.Confirmed -> {
                    if (execution.intentCleared) intent = null
                    mutableState.update {
                        it.copy(
                            command = ReceivingCommandStatus.Confirmed,
                            confirmedLot = result.facts,
                            intentCleanupPending = !execution.intentCleared,
                            metadata = if (execution.intentCleared) {
                                ReceivingMetadataStatus.Available
                            } else {
                                ReceivingMetadataStatus.Unavailable
                            },
                            notice = if (execution.intentCleared) {
                                null
                            } else {
                                ReceivingSubmitNotice.IntentMetadataUnavailable
                            }
                        )
                    }
                }

                is ReceivingSubmitResult.Rejected -> {
                    if (execution.intentCleared) intent = null
                    mutableState.update {
                        it.copy(
                            command = ReceivingCommandStatus.Rejected,
                            confirmedLot = null,
                            rejectionCode = result.code,
                            intentCleanupPending = !execution.intentCleared,
                            metadata = if (execution.intentCleared) {
                                ReceivingMetadataStatus.Available
                            } else {
                                ReceivingMetadataStatus.Unavailable
                            },
                            notice = if (execution.intentCleared) {
                                null
                            } else {
                                ReceivingSubmitNotice.IntentMetadataUnavailable
                            }
                        )
                    }
                }

                else -> return
            }

            is ReceivingIntentExecution.UnknownOutcome -> {
                val frozen = command.copy(status = ReceivingIntentMetadataStatus.UnknownOutcome)
                intent = frozen
                val notice = when (execution.reason) {
                    ReceivingSubmitResult.PermissionDenied ->
                        ReceivingSubmitNotice.PermissionDenied

                    ReceivingSubmitResult.ContextInvalidated ->
                        ReceivingSubmitNotice.ContextInvalidated

                    ReceivingSubmitResult.SessionInvalidated ->
                        ReceivingSubmitNotice.SessionInvalidated

                    else -> if (execution.intentPersisted) {
                        null
                    } else {
                        ReceivingSubmitNotice.IntentMetadataUnavailable
                    }
                }
                mutableState.update {
                    it.copy(
                        command = ReceivingCommandStatus.UnknownOutcome,
                        confirmedLot = null,
                        metadata = if (execution.intentPersisted) {
                            it.metadata
                        } else {
                            ReceivingMetadataStatus.Unavailable
                        },
                        notice = notice
                    )
                }
            }
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
        if (temperatureEvidenceObjectId != null &&
            temperatureEvidenceStatus != ReceivingEvidenceStatus.Available
        ) {
            return invalid(ReceivingValidationError.TemperatureEvidenceNotAvailable)
        }
        if (temperatureEvidenceStatus in setOf(
                ReceivingEvidenceStatus.Uploading,
                ReceivingEvidenceStatus.Checking
            )
        ) {
            return invalid(ReceivingValidationError.TemperatureEvidenceNotAvailable)
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
            temperatureReading = temperature,
            temperatureEvidenceObjectId = temperatureEvidenceObjectId
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
        temperatureReadingText = temperatureReadingText,
        temperatureEvidenceObjectId = temperatureEvidenceObjectId
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
            temperatureEvidenceObjectId = request?.temperatureEvidenceObjectId
                ?: temperatureEvidenceObjectId,
            temperatureEvidenceStatus = when {
                request?.temperatureEvidenceObjectId != null ->
                    ReceivingEvidenceStatus.UnknownOutcome

                temperatureEvidenceObjectId != null && !current.canReadTemperatureEvidence ->
                    ReceivingEvidenceStatus.Unavailable

                temperatureEvidenceObjectId != null -> ReceivingEvidenceStatus.Checking

                else -> ReceivingEvidenceStatus.None
            },
            productVerifiedEpoch = null
        )
    }

    private fun ReceivingProductReference.matches(request: InboundReceiptRequest): Boolean =
        (request.catalogItemId == null || catalogItemId == request.catalogItemId) &&
            (request.skuId == null || skuId == request.skuId)

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
