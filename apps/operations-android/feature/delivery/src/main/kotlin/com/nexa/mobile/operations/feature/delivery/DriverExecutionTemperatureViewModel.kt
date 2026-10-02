package com.nexa.mobile.operations.feature.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureCommandStatus as TemperatureCommandStatus
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureEvidenceStatus as TemperatureEvidenceStatus
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureIntentStatus as TemperatureIntentStatus
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureLoadStatus as TemperatureLoadStatus
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureMode as ExecutionTemperatureMode
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureMutationResult as TemperatureMutationResult
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DriverExecutionTemperatureViewModel(
    private val gateway: DriverExecutionTemperatureGateway,
    private val incidentGateway: DriverIncidentGateway,
    private val metadata: DriverExecutionTemperatureMetadataStore,
    private val now: () -> Instant = Instant::now,
    private val newKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DriverExecutionTemperatureUiState())
    val state = mutableState.asStateFlow()

    private var authority: DriverDeliveryAuthority? = null
    private var activeDeliveryId: String? = null
    private var mode = ExecutionTemperatureMode.DRIVER
    private var generation = 0L
    private var pendingIntent: DriverExecutionTemperatureIntent? = null
    private var staleIntentAwaitingFreshRead: DriverExecutionTemperatureIntent? = null
    private var metadataAvailable = true

    fun activate(
        currentAuthority: DriverDeliveryAuthority,
        deliveryId: String,
        requestedMode: ExecutionTemperatureMode
    ) {
        generation++
        val request = generation
        authority = currentAuthority
        activeDeliveryId = deliveryId
        mode = requestedMode
        pendingIntent = null
        staleIntentAwaitingFreshRead = null
        metadataAvailable = true
        val canRead = canRead(currentAuthority, requestedMode)
        val canDispose = requestedMode == ExecutionTemperatureMode.HOLD_DISPOSITION &&
            DISPOSE_PERMISSION in currentAuthority.permissions
        val canRecord =
            requestedMode == ExecutionTemperatureMode.DRIVER && currentAuthority.canStart
        mutableState.value = DriverExecutionTemperatureUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            mode = requestedMode,
            deliveryId = deliveryId,
            canRead = canRead,
            canRecord = canRecord,
            canDispose = canDispose,
            currentMembershipId = currentAuthority.membershipId,
            loadStatus = if (canRead) {
                TemperatureLoadStatus.Loading
            } else {
                TemperatureLoadStatus.PermissionDenied
            }
        )
        if (!canRead) return
        viewModelScope.launch {
            when (val stored = safe { metadata.loadIntent(currentAuthority.scopeIdentity) }) {
                is DriverExecutionTemperatureMetadataRead.Available -> {
                    val intent = stored.intent
                    if (intent != null &&
                        (
                            !intent.command.isValid() ||
                                intent.scope != currentAuthority.scopeIdentity
                            )
                    ) {
                        if (isCurrent(
                                request,
                                currentAuthority
                            )
                        ) {
                            fail(TemperatureLoadStatus.ServiceUnavailable)
                        }
                        return@launch
                    }
                    pendingIntent = intent
                    when (intent?.status) {
                        TemperatureIntentStatus.Pending,
                        TemperatureIntentStatus.UnknownOutcome -> {
                            val unknown = intent.copy(
                                status = TemperatureIntentStatus.UnknownOutcome
                            )
                            pendingIntent = unknown
                            mutableState.value = mutableState.value.copy(
                                commandStatus = if (intent.command.deliveryId == deliveryId) {
                                    TemperatureCommandStatus.UnknownOutcome
                                } else {
                                    TemperatureCommandStatus.PersistenceUnavailable
                                },
                                command = intent.command,
                                hasRecoverableCommand = intent.command.deliveryId == deliveryId,
                                unresolvedCommandForOtherDelivery =
                                    intent.command.deliveryId != deliveryId
                            )
                        }

                        TemperatureIntentStatus.StaleVersion -> {
                            staleIntentAwaitingFreshRead = intent
                            mutableState.value = mutableState.value.copy(
                                commandStatus = TemperatureCommandStatus.StaleVersion,
                                command = intent.command,
                                unresolvedCommandForOtherDelivery =
                                    intent.command.deliveryId != deliveryId
                            )
                        }

                        null -> Unit
                    }
                    if (isCurrent(
                            request,
                            currentAuthority
                        )
                    ) {
                        loadCurrent(request, currentAuthority, deliveryId, requestedMode)
                    }
                }

                DriverExecutionTemperatureMetadataRead.Unavailable, null -> {
                    metadataAvailable = false
                    mutableState.value = mutableState.value.copy(
                        commandStatus = TemperatureCommandStatus.PersistenceUnavailable
                    )
                    if (isCurrent(
                            request,
                            currentAuthority
                        )
                    ) {
                        loadCurrent(request, currentAuthority, deliveryId, requestedMode)
                    }
                }
            }
        }
    }

    fun deactivate() {
        generation++
        authority = null
        activeDeliveryId = null
        pendingIntent = null
        staleIntentAwaitingFreshRead = null
        metadataAvailable = true
        mutableState.value = DriverExecutionTemperatureUiState()
    }

    fun refresh() {
        val currentAuthority = authority ?: return
        val deliveryId = activeDeliveryId ?: return
        if (!canRead(currentAuthority, mode) || !isCurrent(generation, currentAuthority)) return
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            loadStatus = TemperatureLoadStatus.Loading,
            snapshot = null,
            sourceIncidentId = null,
            sourceEvidenceObjectId = null,
            sourceEvidenceStatus = TemperatureEvidenceStatus.None
        )
        viewModelScope.launch { loadCurrent(request, currentAuthority, deliveryId, mode) }
    }

    fun updateQuantity(fulfillmentLineId: String, value: String) {
        val current = mutableState.value
        if (current.mode != ExecutionTemperatureMode.DRIVER || !current.canRecord ||
            current.loadStatus != TemperatureLoadStatus.Ready ||
            current.hasRecoverableCommand ||
            current.unresolvedCommandForOtherDelivery || !lineExists(fulfillmentLineId)
        ) {
            return
        }
        mutableState.value =
            current.copy(quantitiesByLine = current.quantitiesByLine + (fulfillmentLineId to value))
    }

    fun updateCelsius(fulfillmentLineId: String, value: String) {
        val current = mutableState.value
        if (current.mode != ExecutionTemperatureMode.DRIVER || !current.canRecord ||
            current.loadStatus != TemperatureLoadStatus.Ready ||
            current.hasRecoverableCommand ||
            current.unresolvedCommandForOtherDelivery || !lineExists(fulfillmentLineId)
        ) {
            return
        }
        mutableState.value =
            current.copy(
                valuesCelsiusByLine =
                    current.valuesCelsiusByLine + (fulfillmentLineId to value)
            )
    }

    fun updateDispositionReason(holdId: String, value: String) {
        val current = mutableState.value
        if (current.mode != ExecutionTemperatureMode.HOLD_DISPOSITION || !current.canDispose ||
            current.loadStatus != TemperatureLoadStatus.Ready ||
            current.hasRecoverableCommand ||
            current.unresolvedCommandForOtherDelivery ||
            current.snapshot?.holds?.none { it.id == holdId && it.status == "HELD" } != false
        ) {
            return
        }
        mutableState.value =
            current.copy(
                dispositionReasonsByHold =
                    current.dispositionReasonsByHold + (holdId to value)
            )
    }

    /** Accept typed server summary only; refresh evidence status and execution snapshot before exposing source IDs. */
    fun selectRecordedIncident(summary: DriverIncidentSummary, evidenceObjectId: String) {
        val currentAuthority = authority ?: return
        val deliveryId = activeDeliveryId ?: return
        val current = mutableState.value
        if (mode != ExecutionTemperatureMode.DRIVER || !currentAuthority.canStart ||
            !currentAuthority.canReadProofEvidence || current.hasRecoverableCommand ||
            current.unresolvedCommandForOtherDelivery ||
            summary.type != DriverIncidentType.TEMPERATURE_EXCURSION ||
            summary.severity != "CRITICAL" ||
            summary.operationalExceptionId.isNullOrBlank() || summary.deliveryId != deliveryId ||
            summary.recordedByMembershipId != currentAuthority.membershipId ||
            evidenceObjectId !in summary.evidenceObjectIds
        ) {
            return
        }
        val request = ++generation
        mutableState.value = current.copy(
            loadStatus = TemperatureLoadStatus.Loading,
            snapshot = null,
            sourceIncidentId = null,
            sourceEvidenceObjectId = null,
            sourceEvidenceStatus = TemperatureEvidenceStatus.Checking,
            rejectionCode = null
        )
        viewModelScope.launch {
            when (val recovery = safe { metadata.loadIntent(currentAuthority.scopeIdentity) }) {
                is DriverExecutionTemperatureMetadataRead.Available -> {
                    val recovered = recovery.intent
                    if (recovered != null) {
                        val intent = if (recovered.status ==
                            TemperatureIntentStatus.Pending
                        ) {
                            val unknown = recovered.copy(
                                status = TemperatureIntentStatus.UnknownOutcome
                            )
                            safe { metadata.saveIntent(unknown) }
                            unknown
                        } else {
                            recovered
                        }
                        pendingIntent = intent
                        if (intent.status == TemperatureIntentStatus.StaleVersion) {
                            staleIntentAwaitingFreshRead = intent
                        }
                        if (isCurrent(request, currentAuthority)) {
                            mutableState.value = mutableState.value.copy(
                                command = intent.command,
                                commandStatus = when {
                                    intent.command.deliveryId != deliveryId ->
                                        TemperatureCommandStatus.PersistenceUnavailable

                                    intent.status ==
                                        TemperatureIntentStatus.StaleVersion ->
                                        TemperatureCommandStatus.StaleVersion

                                    else -> TemperatureCommandStatus.UnknownOutcome
                                },
                                hasRecoverableCommand = intent.command.deliveryId == deliveryId &&
                                    intent.status ==
                                    TemperatureIntentStatus.UnknownOutcome,
                                unresolvedCommandForOtherDelivery =
                                    intent.command.deliveryId != deliveryId,
                                sourceIncidentId = null,
                                sourceEvidenceObjectId = null,
                                sourceEvidenceStatus = TemperatureEvidenceStatus.None
                            )
                            loadCurrent(
                                request,
                                currentAuthority,
                                deliveryId,
                                ExecutionTemperatureMode.DRIVER
                            )
                        }
                        return@launch
                    }
                }

                DriverExecutionTemperatureMetadataRead.Unavailable, null -> {
                    if (isCurrent(request, currentAuthority)) {
                        metadataAvailable = false
                        mutableState.value = mutableState.value.copy(
                            sourceEvidenceStatus = TemperatureEvidenceStatus.Unavailable(
                                "METADATA_UNAVAILABLE"
                            ),
                            commandStatus = TemperatureCommandStatus.PersistenceUnavailable
                        )
                    }
                    return@launch
                }
            }
            val evidenceResult = try {
                incidentGateway.evidenceStatus(evidenceObjectId, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverIncidentEvidenceResult.Unavailable
            }
            if (!isCurrent(request, currentAuthority)) return@launch
            val evidence = when (evidenceResult) {
                is DriverIncidentEvidenceResult.Current -> evidenceResult.evidence.takeIf {
                    it.evidenceId == evidenceObjectId && it.subjectType == "DELIVERY_INCIDENT" &&
                        it.subjectId == summary.incidentId && it.lifecycleStatus == "AVAILABLE"
                }

                else -> null
            }
            if (evidence == null) {
                mutableState.value = mutableState.value.copy(
                    loadStatus = TemperatureLoadStatus.Ready,
                    sourceEvidenceStatus = TemperatureEvidenceStatus.Unavailable(
                        evidenceResult.toEvidenceCode()
                    ),
                    rejectionCode = "TEMPERATURE_EVIDENCE_REQUIRED"
                )
                return@launch
            }
            val loaded = try {
                gateway.current(deliveryId, ExecutionTemperatureMode.DRIVER, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverExecutionTemperatureLoadResult.ServiceUnavailable
            }
            if (!isCurrent(request, currentAuthority)) return@launch
            when (loaded) {
                is DriverExecutionTemperatureLoadResult.Loaded -> {
                    val snapshot = loaded.snapshot
                    if (snapshot.deliveryId != deliveryId || snapshot.attemptId == null ||
                        snapshot.attemptId != summary.attemptId
                    ) {
                        mutableState.value = mutableState.value.copy(
                            loadStatus = TemperatureLoadStatus.Ready,
                            sourceEvidenceStatus = TemperatureEvidenceStatus.Unavailable(
                                "STALE_DELIVERY_ATTEMPT"
                            ),
                            rejectionCode = "STALE_DELIVERY_ATTEMPT"
                        )
                    } else {
                        installSnapshot(request, currentAuthority, snapshot)
                        mutableState.value = mutableState.value.copy(
                            sourceIncidentId = summary.incidentId,
                            sourceEvidenceObjectId = evidenceObjectId,
                            sourceEvidenceStatus = TemperatureEvidenceStatus.Available,
                            rejectionCode = null
                        )
                    }
                }

                else -> applyLoadFailure(request, currentAuthority, loaded)
            }
        }
    }

    fun record(fulfillmentLineId: String, quantityText: String, valueText: String) {
        val currentAuthority = authority ?: return
        val deliveryId = activeDeliveryId ?: return
        val current = mutableState.value
        if (mode != ExecutionTemperatureMode.DRIVER || !current.canRecord ||
            current.loadStatus != TemperatureLoadStatus.Ready || !metadataAvailable ||
            current.hasRecoverableCommand || current.unresolvedCommandForOtherDelivery ||
            pendingIntent != null
        ) {
            return
        }
        val snapshot = current.snapshot ?: return
        val line =
            snapshot.lines.firstOrNull { it.fulfillmentLineId == fulfillmentLineId } ?: return
        if (!line.supportsReading || snapshot.attemptId == null ||
            snapshot.deliveryStatus !in ACTIVE_DELIVERY_STATUSES
        ) {
            return
        }
        val quantity = quantityText.trim().toBigDecimalOrNull()
        val value = valueText.trim().toBigDecimalOrNull()
        if (quantity == null || quantity.signum() <= 0 || quantity > line.remainingQuantity ||
            value == null || value.abs() >= BigDecimal("1000")
        ) {
            mutableState.value = current.copy(
                commandStatus = TemperatureCommandStatus.Rejected,
                rejectionCode = "INVALID_READING"
            )
            return
        }
        val excursion = !line.isWithinRange(value)
        val sourceIncidentId = if (excursion) current.sourceIncidentId else null
        val evidenceObjectId = if (excursion) current.sourceEvidenceObjectId else null
        if (excursion && (
                sourceIncidentId == null || evidenceObjectId == null ||
                    current.sourceEvidenceStatus !=
                    TemperatureEvidenceStatus.Available
                )
        ) {
            mutableState.value = current.copy(rejectionCode = "TEMPERATURE_EVIDENCE_REQUIRED")
            return
        }
        // Capture time locally at the moment the user submits. The server remains the
        // authority for its allowed freshness window and rejects future timestamps.
        val occurredAt = now()
        val partial = DriverExecutionTemperatureCommand.Reading(
            deliveryId = deliveryId,
            expectedDeliveryVersion = snapshot.deliveryVersion,
            fulfillmentLineId = line.fulfillmentLineId,
            skuId = line.skuId,
            affectedQuantity = quantity,
            valueCelsius = value,
            occurredAt = occurredAt,
            sourceIncidentId = sourceIncidentId,
            evidenceObjectId = evidenceObjectId,
            idempotencyKey = newKey(),
            frozenBody = ""
        )
        val command = partial.copy(
            frozenBody = driverExecutionTemperatureReadingBody(
                line.fulfillmentLineId,
                line.skuId,
                quantity,
                value,
                occurredAt,
                sourceIncidentId,
                evidenceObjectId
            )
        )
        if (!command.isValid()) return
        persistAndSend(command, currentAuthority)
    }

    fun dispose(
        holdId: String,
        disposition: DriverExecutionTemperatureDisposition,
        reason: String
    ) {
        val currentAuthority = authority ?: return
        val current = mutableState.value
        val snapshot = current.snapshot ?: return
        if (mode != ExecutionTemperatureMode.HOLD_DISPOSITION || !current.canDispose ||
            current.loadStatus != TemperatureLoadStatus.Ready || !metadataAvailable ||
            current.hasRecoverableCommand || current.unresolvedCommandForOtherDelivery ||
            pendingIntent != null ||
            snapshot.holds.none { it.id == holdId && it.status == "HELD" }
        ) {
            return
        }
        val normalized = reason.trim()
        if (normalized.isEmpty() || normalized.length > 2000) {
            mutableState.value = current.copy(
                commandStatus = TemperatureCommandStatus.Rejected,
                rejectionCode = "INVALID_REQUEST"
            )
            return
        }
        val partial = DriverExecutionTemperatureCommand.Disposition(
            deliveryId = snapshot.deliveryId,
            holdId = holdId,
            expectedDeliveryVersion = snapshot.deliveryVersion,
            disposition = disposition,
            reason = normalized,
            idempotencyKey = newKey(),
            frozenBody = ""
        )
        val command = partial.copy(
            frozenBody = driverExecutionTemperatureDispositionBody(disposition, normalized)
        )
        if (command.isValid()) persistAndSend(command, currentAuthority)
    }

    fun retryUnknownOutcome() {
        val currentAuthority = authority ?: return
        val intent = pendingIntent ?: return
        val current = mutableState.value
        if (!current.canRetryUnknownOutcome ||
            intent.status != TemperatureIntentStatus.UnknownOutcome ||
            intent.scope != currentAuthority.scopeIdentity ||
            intent.command.deliveryId != activeDeliveryId ||
            !intent.command.isValid() || !modeAllows(intent.command, mode, currentAuthority)
        ) {
            return
        }
        val request = ++generation
        val pending = intent.copy(status = TemperatureIntentStatus.Pending)
        mutableState.value = current.copy(
            commandStatus = TemperatureCommandStatus.Pending,
            command = pending.command,
            hasRecoverableCommand = true,
            rejectionCode = null
        )
        viewModelScope.launch {
            when (safe { metadata.saveIntent(pending) }) {
                DriverExecutionTemperatureMetadataWrite.Saved -> {
                    pendingIntent = pending
                    send(pending, currentAuthority, request)
                }

                else -> if (isCurrent(request, currentAuthority)) {
                    mutableState.value =
                        mutableState.value.copy(
                            commandStatus = TemperatureCommandStatus.PersistenceUnavailable
                        )
                }
            }
        }
    }

    private fun persistAndSend(
        command: DriverExecutionTemperatureCommand,
        currentAuthority: DriverDeliveryAuthority
    ) {
        val scope = currentAuthority.scopeIdentity
        val intent =
            DriverExecutionTemperatureIntent(
                scope,
                command,
                now(),
                TemperatureIntentStatus.Pending
            )
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            commandStatus = TemperatureCommandStatus.PersistingIntent,
            command = command,
            hasRecoverableCommand = true,
            rejectionCode = null
        )
        viewModelScope.launch {
            when (safe { metadata.saveIntent(intent) }) {
                DriverExecutionTemperatureMetadataWrite.Saved -> {
                    if (!isCurrent(request, currentAuthority)) return@launch
                    pendingIntent = intent
                    mutableState.value =
                        mutableState.value.copy(
                            commandStatus = TemperatureCommandStatus.Pending
                        )
                    send(intent, currentAuthority, request)
                }

                DriverExecutionTemperatureMetadataWrite.Conflict,
                DriverExecutionTemperatureMetadataWrite.Stale -> if (isCurrent(
                        request,
                        currentAuthority
                    )
                ) {
                    mutableState.value = mutableState.value.copy(
                        commandStatus = TemperatureCommandStatus.PersistenceUnavailable,
                        hasRecoverableCommand = false
                    )
                }

                DriverExecutionTemperatureMetadataWrite.Unavailable,
                null -> if (isCurrent(request, currentAuthority)) {
                    mutableState.value =
                        mutableState.value.copy(
                            commandStatus = TemperatureCommandStatus.PersistenceUnavailable,
                            hasRecoverableCommand = false
                        )
                }
            }
        }
    }

    private fun send(
        intent: DriverExecutionTemperatureIntent,
        currentAuthority: DriverDeliveryAuthority,
        request: Long
    ) {
        if (!isCurrent(request, currentAuthority)) return
        viewModelScope.launch {
            val result = try {
                when (val command = intent.command) {
                    is DriverExecutionTemperatureCommand.Reading -> gateway.record(
                        command,
                        currentAuthority
                    )

                    is DriverExecutionTemperatureCommand.Disposition -> gateway.dispose(
                        command,
                        currentAuthority
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TemperatureMutationResult.UnknownOutcome
            }
            if (!isCurrent(request, currentAuthority)) return@launch
            when (result) {
                is TemperatureMutationResult.ReadingRecorded -> {
                    val command = intent.command as? DriverExecutionTemperatureCommand.Reading
                    if (command == null ||
                        !result.reading.matches(command)
                    ) {
                        markUnknown(intent, currentAuthority, request)
                    } else {
                        finish(intent, currentAuthority, request) {
                            val currentSnapshot = mutableState.value.snapshot
                            val hold = result.reading.hold
                            mutableState.value = mutableState.value.copy(
                                lastReading = result.reading,
                                snapshot = currentSnapshot?.copy(
                                    deliveryVersion = result.reading.deliveryVersion,
                                    holds = if (hold == null) {
                                        currentSnapshot.holds
                                    } else {
                                        currentSnapshot.holds.filterNot { it.id == hold.id } + hold
                                    }
                                ),
                                commandStatus = TemperatureCommandStatus.Recorded,
                                replayed = result.reading.replayed,
                                rejectionCode = null
                            )
                        }.also { if (isCurrent(request, currentAuthority)) refresh() }
                    }
                }

                is TemperatureMutationResult.DispositionRecorded -> {
                    val command = intent.command as? DriverExecutionTemperatureCommand.Disposition
                    if (command == null || result.hold.id != command.holdId ||
                        result.hold.status != expectedDispositionStatus(command.disposition) ||
                        result.deliveryVersion <= command.expectedDeliveryVersion
                    ) {
                        markUnknown(intent, currentAuthority, request)
                    } else {
                        finish(intent, currentAuthority, request) {
                            val currentSnapshot = mutableState.value.snapshot
                            mutableState.value = mutableState.value.copy(
                                snapshot = currentSnapshot?.copy(
                                    deliveryVersion = result.deliveryVersion,
                                    holds = currentSnapshot.holds.map {
                                        if (it.id ==
                                            result.hold.id
                                        ) {
                                            result.hold
                                        } else {
                                            it
                                        }
                                    }
                                ),
                                commandStatus = TemperatureCommandStatus.Disposed,
                                replayed = result.replayed,
                                rejectionCode = null
                            )
                        }.also { if (isCurrent(request, currentAuthority)) refresh() }
                    }
                }

                TemperatureMutationResult.StaleVersion -> {
                    val stale = intent.copy(
                        status = TemperatureIntentStatus.StaleVersion
                    )
                    if (safe { metadata.saveIntent(stale) } ==
                        DriverExecutionTemperatureMetadataWrite.Saved
                    ) {
                        pendingIntent = stale
                        staleIntentAwaitingFreshRead = stale
                        if (isCurrent(request, currentAuthority)) {
                            mutableState.value = mutableState.value.copy(
                                commandStatus = TemperatureCommandStatus.StaleVersion,
                                hasRecoverableCommand = false,
                                rejectionCode = "CONCURRENCY_CONFLICT"
                            )
                            refresh()
                        }
                    } else {
                        markUnknown(intent, currentAuthority, request)
                    }
                }

                is TemperatureMutationResult.Rejected,
                TemperatureMutationResult.Conflict,
                TemperatureMutationResult.NotFound -> {
                    val rejection =
                        (result as? TemperatureMutationResult.Rejected)?.code
                            ?: when (result) {
                                TemperatureMutationResult.Conflict ->
                                    "OPERATIONAL_EXCEPTION_TRANSITION_INVALID"

                                TemperatureMutationResult.NotFound -> "DELIVERY_NOT_FOUND"

                                else -> null
                            }
                    finish(intent, currentAuthority, request) {
                        mutableState.value = mutableState.value.copy(
                            commandStatus = if (result ==
                                TemperatureMutationResult.NotFound
                            ) {
                                TemperatureCommandStatus.Rejected
                            } else {
                                TemperatureCommandStatus.Rejected
                            },
                            rejectionCode = rejection,
                            hasRecoverableCommand = false
                        )
                    }
                }

                TemperatureMutationResult.NetworkUnavailable,
                TemperatureMutationResult.ServiceUnavailable,
                TemperatureMutationResult.UnknownOutcome -> markUnknown(
                    intent,
                    currentAuthority,
                    request
                )

                TemperatureMutationResult.PermissionDenied -> fail(
                    TemperatureLoadStatus.PermissionDenied
                )

                TemperatureMutationResult.ContextInvalidated -> invalidateContext(
                    request
                )

                TemperatureMutationResult.SessionInvalidated -> invalidateSession(
                    request
                )
            }
        }
    }

    private suspend fun finish(
        intent: DriverExecutionTemperatureIntent,
        currentAuthority: DriverDeliveryAuthority,
        request: Long,
        update: () -> Unit
    ) {
        if (safe { metadata.clearIntent(intent.scope, intent.command.idempotencyKey) } !=
            DriverExecutionTemperatureMetadataWrite.Saved
        ) {
            markUnknown(
                intent.copy(status = TemperatureIntentStatus.UnknownOutcome),
                currentAuthority,
                request
            )
            return
        }
        if (!isCurrent(request, currentAuthority)) return
        pendingIntent = null
        update()
        mutableState.value = mutableState.value.copy(hasRecoverableCommand = false, command = null)
    }

    private suspend fun markUnknown(
        intent: DriverExecutionTemperatureIntent,
        currentAuthority: DriverDeliveryAuthority,
        request: Long
    ) {
        val unknown = intent.copy(status = TemperatureIntentStatus.UnknownOutcome)
        val saved =
            safe { metadata.saveIntent(unknown) } == DriverExecutionTemperatureMetadataWrite.Saved
        if (!isCurrent(request, currentAuthority)) return
        pendingIntent = unknown
        mutableState.value = mutableState.value.copy(
            commandStatus = if (saved) {
                TemperatureCommandStatus.UnknownOutcome
            } else {
                TemperatureCommandStatus.PersistenceUnavailable
            },
            command = unknown.command,
            hasRecoverableCommand = saved && unknown.command.deliveryId == activeDeliveryId,
            unresolvedCommandForOtherDelivery = unknown.command.deliveryId != activeDeliveryId
        )
    }

    private suspend fun loadCurrent(
        request: Long,
        currentAuthority: DriverDeliveryAuthority,
        deliveryId: String,
        requestedMode: ExecutionTemperatureMode
    ) {
        val result = try {
            gateway.current(deliveryId, requestedMode, currentAuthority)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverExecutionTemperatureLoadResult.ServiceUnavailable
        }
        if (!isCurrent(request, currentAuthority)) return
        when (result) {
            is DriverExecutionTemperatureLoadResult.Loaded -> {
                if (result.snapshot.deliveryId != deliveryId) {
                    fail(TemperatureLoadStatus.ServiceUnavailable)
                    return
                }
                installSnapshot(request, currentAuthority, result.snapshot)
            }

            else -> applyLoadFailure(request, currentAuthority, result)
        }
    }

    private fun installSnapshot(
        request: Long,
        currentAuthority: DriverDeliveryAuthority,
        snapshot: DriverExecutionTemperatureSnapshot
    ) {
        if (!isCurrent(request, currentAuthority)) return
        val stale = staleIntentAwaitingFreshRead
        if (stale != null) {
            viewModelScope.launch {
                val cleared =
                    safe { metadata.clearIntent(stale.scope, stale.command.idempotencyKey) }
                if (!isCurrent(request, currentAuthority)) return@launch
                if (cleared == DriverExecutionTemperatureMetadataWrite.Saved) {
                    pendingIntent = null
                    staleIntentAwaitingFreshRead = null
                    mutableState.value = mutableState.value.copy(
                        snapshot = snapshot,
                        loadStatus = TemperatureLoadStatus.Ready,
                        commandStatus = TemperatureCommandStatus.StaleVersion,
                        command = null,
                        hasRecoverableCommand = false,
                        unresolvedCommandForOtherDelivery = false,
                        rejectionCode = "FRESH_DECISION_REQUIRED"
                    )
                } else {
                    mutableState.value = mutableState.value.copy(
                        loadStatus = TemperatureLoadStatus.Ready,
                        commandStatus = TemperatureCommandStatus.PersistenceUnavailable
                    )
                }
            }
            return
        }
        val current = mutableState.value
        mutableState.value = current.copy(
            snapshot = snapshot,
            loadStatus = TemperatureLoadStatus.Ready,
            canRecord =
                mode == ExecutionTemperatureMode.DRIVER && currentAuthority.canStart &&
                    metadataAvailable && snapshot.attemptId != null &&
                    snapshot.deliveryStatus in ACTIVE_DELIVERY_STATUSES,
            canDispose = mode == ExecutionTemperatureMode.HOLD_DISPOSITION &&
                DISPOSE_PERMISSION in currentAuthority.permissions && metadataAvailable,
            sourceIncidentId = current.sourceIncidentId,
            sourceEvidenceObjectId = current.sourceEvidenceObjectId,
            sourceEvidenceStatus = current.sourceEvidenceStatus
        )
    }

    private fun applyLoadFailure(
        request: Long,
        currentAuthority: DriverDeliveryAuthority,
        result: DriverExecutionTemperatureLoadResult
    ) {
        if (!isCurrent(request, currentAuthority)) return
        when (result) {
            DriverExecutionTemperatureLoadResult.NotFound -> fail(
                TemperatureLoadStatus.NotFound
            )

            DriverExecutionTemperatureLoadResult.NetworkUnavailable -> fail(
                TemperatureLoadStatus.NetworkUnavailable
            )

            DriverExecutionTemperatureLoadResult.PermissionDenied -> fail(
                TemperatureLoadStatus.PermissionDenied
            )

            DriverExecutionTemperatureLoadResult.ContextInvalidated -> invalidateContext(request)

            DriverExecutionTemperatureLoadResult.SessionInvalidated -> invalidateSession(request)

            else -> fail(TemperatureLoadStatus.ServiceUnavailable)
        }
    }

    private fun fail(status: TemperatureLoadStatus) {
        mutableState.value = mutableState.value.copy(
            loadStatus = status,
            snapshot = null,
            sourceIncidentId = null,
            sourceEvidenceObjectId = null,
            sourceEvidenceStatus = TemperatureEvidenceStatus.None,
            canRecord = false,
            canDispose = false
        )
    }

    private fun invalidateContext(request: Long) {
        if (request != generation) return
        generation++
        authority = null
        mutableState.value = mutableState.value.copy(
            loadStatus = TemperatureLoadStatus.ContextInvalidated,
            snapshot = null,
            sourceIncidentId = null,
            sourceEvidenceObjectId = null,
            sourceEvidenceStatus = TemperatureEvidenceStatus.None,
            canRecord = false,
            canDispose = false
        )
    }

    private fun invalidateSession(request: Long) {
        if (request != generation) return
        generation++
        authority = null
        mutableState.value = mutableState.value.copy(
            loadStatus = TemperatureLoadStatus.SessionInvalidated,
            snapshot = null,
            sourceIncidentId = null,
            sourceEvidenceObjectId = null,
            sourceEvidenceStatus = TemperatureEvidenceStatus.None,
            canRecord = false,
            canDispose = false
        )
    }

    private fun isCurrent(request: Long, currentAuthority: DriverDeliveryAuthority): Boolean =
        request == generation && authority == currentAuthority &&
            activeDeliveryId == mutableState.value.deliveryId &&
            mutableState.value.authorityEpoch == currentAuthority.authorityEpoch

    private fun canRead(
        authority: DriverDeliveryAuthority,
        requestedMode: ExecutionTemperatureMode
    ): Boolean = when (requestedMode) {
        ExecutionTemperatureMode.DRIVER -> authority.canRead

        ExecutionTemperatureMode.HOLD_DISPOSITION ->
            DISPOSE_PERMISSION in
                authority.permissions
    }

    private fun modeAllows(
        command: DriverExecutionTemperatureCommand,
        requestedMode: ExecutionTemperatureMode,
        currentAuthority: DriverDeliveryAuthority
    ): Boolean = when (command) {
        is DriverExecutionTemperatureCommand.Reading ->
            requestedMode ==
                ExecutionTemperatureMode.DRIVER &&
                currentAuthority.canStart

        is DriverExecutionTemperatureCommand.Disposition ->
            requestedMode ==
                ExecutionTemperatureMode.HOLD_DISPOSITION &&
                DISPOSE_PERMISSION in currentAuthority.permissions
    }

    private fun lineExists(lineId: String): Boolean = mutableState.value.snapshot?.lines?.any {
        it.fulfillmentLineId == lineId && it.supportsReading
    } == true

    private fun DriverIncidentEvidenceResult.toEvidenceCode(): String? = when (this) {
        is DriverIncidentEvidenceResult.Rejected -> code
        DriverIncidentEvidenceResult.NotFound -> "EVIDENCE_NOT_FOUND"
        DriverIncidentEvidenceResult.PermissionDenied -> "FORBIDDEN"
        else -> "TEMPERATURE_EVIDENCE_REQUIRED"
    }

    private fun DriverExecutionTemperatureReading.matches(
        command: DriverExecutionTemperatureCommand.Reading
    ): Boolean =
        deliveryId == command.deliveryId && fulfillmentLineId == command.fulfillmentLineId &&
            skuId == command.skuId &&
            affectedQuantity.compareTo(command.affectedQuantity) == 0 &&
            valueCelsius.compareTo(command.valueCelsius) == 0 &&
            temperatureUnit == "CELSIUS" && occurredAt == command.occurredAt &&
            sourceIncidentId == command.sourceIncidentId &&
            evidenceObjectId == command.evidenceObjectId &&
            deliveryVersion > command.expectedDeliveryVersion &&
            if (command.sourceIncidentId != null) {
                status == "OUT_OF_RANGE" && hold?.status == "HELD"
            } else {
                status == "WITHIN_RANGE" && hold == null
            }

    private fun expectedDispositionStatus(
        disposition: DriverExecutionTemperatureDisposition
    ): String = when (disposition) {
        DriverExecutionTemperatureDisposition.RELEASE -> "RELEASED"
        DriverExecutionTemperatureDisposition.CONTINUE_HOLD -> "HELD"
        DriverExecutionTemperatureDisposition.REJECT -> "REJECTED"
        DriverExecutionTemperatureDisposition.WASTE -> "WASTED"
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val DISPOSE_PERMISSION = "delivery.execution_hold.dispose"
        val ACTIVE_DELIVERY_STATUSES = setOf("DISPATCHED", "IN_TRANSIT", "PARTIAL")
    }
}
