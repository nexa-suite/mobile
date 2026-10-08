package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureEvidenceSelectionContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperaturePhotoCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperaturePhotoGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperaturePhotoUploadIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperaturePhotoUploadMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchTemperatureEvidence
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchTemperaturePhotoEvidence
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchTemperatureReadiness
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DispatchTemperatureViewModel(
    private val gateway: DispatchTemperatureGateway,
    private val metadata: DispatchTemperatureMetadataStore,
    private val now: () -> Instant = Instant::now,
    private val newCommandKey: () -> String = { UUID.randomUUID().toString() },
    private val requestBodyCodec: DispatchRequestBodyCodec
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchTemperatureUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: DispatchAuthorityContext? = null
    private var activeFulfillmentId: String? = null
    private var pendingIntent: DispatchTemperatureIntent? = null
    private var generation = 0L

    fun activate(fulfillmentId: String, context: DispatchAuthorityContext) {
        if (activeFulfillmentId == fulfillmentId && activeContext == context &&
            mutableState.value.status !in INVALIDATED
        ) {
            return
        }
        generation++
        val request = generation
        activeFulfillmentId = fulfillmentId
        activeContext = context
        pendingIntent = null
        mutableState.value = DispatchTemperatureUiState(
            authorityEpoch = context.authorityEpoch,
            fulfillmentId = fulfillmentId,
            canRecord = context.hasFulfillmentManagePermission()
        )
        viewModelScope.launch {
            val scope = context.scopeIdentity()
            if (scope == null) {
                if (request == generation) invalidateContext()
                return@launch
            }
            when (val stored = safe { metadata.loadIntent(scope, fulfillmentId) }) {
                is DispatchTemperatureMetadataRead.Available -> {
                    val intent = stored.intent
                    if (intent != null &&
                        (intent.scope != scope || !requestBodyCodec.isValid(intent.command))
                    ) {
                        if (request ==
                            generation
                        ) {
                            fail(DispatchTemperatureStatus.ServiceUnavailable)
                        }
                        return@launch
                    }
                    if (request == generation && activeContext == context) {
                        pendingIntent = intent
                        mutableState.value = mutableState.value.copy(
                            metadataReady = true,
                            hasPendingCommand = intent != null,
                            pendingCommand = intent?.command,
                            mutationStatus = if (intent ==
                                null
                            ) {
                                DispatchTemperatureMutationStatus.Idle
                            } else {
                                DispatchTemperatureMutationStatus.UnknownOutcome
                            },
                            mutationLotId = intent?.command?.lotId
                        )
                        refresh()
                    }
                }

                DispatchTemperatureMetadataRead.Unavailable,
                null -> {
                    if (request == generation && activeContext == context) {
                        mutableState.value = mutableState.value.copy(metadataReady = false)
                        refresh()
                    }
                }
            }
        }
    }

    /** Refresh only the server view. It deliberately preserves any unresolved exact command. */
    fun refresh() {
        val context = activeContext ?: return
        val fulfillmentId = activeFulfillmentId ?: return
        when (context.readPermissionHint()) {
            PermissionHint.Unknown -> {
                fail(DispatchTemperatureStatus.PermissionUnknown)
                return
            }

            PermissionHint.Unavailable -> {
                fail(DispatchTemperatureStatus.PermissionDenied)
                return
            }

            PermissionHint.Available -> Unit
        }
        if (mutableState.value.mutationStatus ==
            DispatchTemperatureMutationStatus.Submitting
        ) {
            return
        }
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            status = DispatchTemperatureStatus.Loading,
            readiness = null,
            observedAt = null
        )
        viewModelScope.launch {
            when (val result = safe { gateway.current(fulfillmentId, context) }) {
                is DispatchTemperatureGatewayResult.Current -> {
                    if (!result.readiness.fulfillmentId.equals(fulfillmentId, ignoreCase = true) ||
                        result.readiness.temperatureRequiredForFulfillment
                    ) {
                        failIfCurrent(
                            request,
                            context,
                            DispatchTemperatureStatus.ServiceUnavailable
                        )
                    } else if (isCurrent(request, context)) {
                        mutableState.value = mutableState.value.copy(
                            status = DispatchTemperatureStatus.Current,
                            readiness = result.readiness,
                            observedAt = now(),
                            canRecord = context.hasFulfillmentManagePermission(),
                            canUploadPhoto = context.hasPhotoEvidencePermissions(),
                            photoByLotId = retainMatchingPhotos(
                                mutableState.value.photoByLotId,
                                result.readiness
                            )
                        )
                    }
                }

                DispatchTemperatureGatewayResult.OutsideRangeBackendContractGap,
                DispatchTemperatureGatewayResult.UnknownOutcome,
                DispatchTemperatureGatewayResult.ServiceUnavailable,
                is DispatchTemperatureGatewayResult.Recorded ->
                    failIfCurrent(request, context, DispatchTemperatureStatus.ServiceUnavailable)

                DispatchTemperatureGatewayResult.NetworkUnavailable ->
                    failIfCurrent(request, context, DispatchTemperatureStatus.NetworkUnavailable)

                DispatchTemperatureGatewayResult.PermissionDenied ->
                    failIfCurrent(request, context, DispatchTemperatureStatus.PermissionDenied)

                DispatchTemperatureGatewayResult.ContextInvalidated -> invalidateContextIfCurrent(
                    request
                )

                DispatchTemperatureGatewayResult.SessionInvalidated -> invalidateSessionIfCurrent(
                    request
                )

                DispatchTemperatureGatewayResult.Stale,
                DispatchTemperatureGatewayResult.Conflict,
                null -> failIfCurrent(
                    request,
                    context,
                    DispatchTemperatureStatus.ServiceUnavailable
                )
            }
        }
    }

    fun updateValue(lotId: String, valueCelsius: String) {
        val state = mutableState.value
        if (state.status != DispatchTemperatureStatus.Current || state.hasPendingCommand ||
            state.readiness?.lots?.none { it.lotId == lotId } != false
        ) {
            return
        }
        mutableState.value =
            state.copy(valuesCelsius = state.valuesCelsius + (lotId to valueCelsius))
    }

    /** Capture authority and exact warehouse subject before opening the system photo picker. */
    fun excursionEvidenceSelectionContext(
        lotId: String
    ): DispatchTemperatureEvidenceSelectionContext? {
        val context = activeContext ?: return null
        val state = mutableState.value
        val readiness = state.readiness ?: return null
        val scope = context.scopeIdentity() ?: return null
        if (state.status != DispatchTemperatureStatus.Current || !state.canUploadPhoto ||
            state.hasPendingCommand ||
            state.mutationStatus == DispatchTemperatureMutationStatus.Submitting
        ) {
            return null
        }
        val lot = readiness.lots.firstOrNull { it.lotId == lotId } ?: return null
        val warehouseId = lot.warehouseId ?: return null
        if (!lot.supportsInRangeEvidence) return null
        return DispatchTemperatureEvidenceSelectionContext(
            scope = scope,
            authorityEpoch = context.authorityEpoch,
            fulfillmentId = readiness.fulfillmentId,
            lotId = lotId,
            warehouseId = warehouseId
        )
    }

    /** Fast foreground authority check; upload performs a fresh server read as well. */
    fun isCurrentEvidenceSelection(
        selection: DispatchTemperatureEvidenceSelectionContext
    ): Boolean {
        val context = activeContext ?: return false
        val state = mutableState.value
        val lot = state.readiness?.lots?.firstOrNull { it.lotId == selection.lotId } ?: return false
        return selectionMatches(selection, context) &&
            state.status == DispatchTemperatureStatus.Current &&
            state.canUploadPhoto && !state.hasPendingCommand &&
            lot.warehouseId == selection.warehouseId &&
            lot.supportsInRangeEvidence
    }

    /** Re-read after the external picker returns,
     then upload only to the same current warehouse. */
    suspend fun uploadExcursionEvidence(
        candidate: DispatchTemperaturePhotoCandidate,
        selection: DispatchTemperatureEvidenceSelectionContext
    ) {
        val context = activeContext ?: return
        val request = ++generation
        if (!selectionMatches(selection, context) || !context.hasPhotoEvidencePermissions() ||
            mutableState.value.status !in
            setOf(DispatchTemperatureStatus.Current, DispatchTemperatureStatus.Loading) ||
            mutableState.value.hasPendingCommand
        ) {
            return
        }
        val fresh = try {
            gateway.current(selection.fulfillmentId, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DispatchTemperatureGatewayResult.UnknownOutcome
        }
        if (!isCurrent(request, context)) return
        val currentReadiness = when (fresh) {
            is DispatchTemperatureGatewayResult.Current -> fresh.readiness

            DispatchTemperatureGatewayResult.ContextInvalidated -> {
                invalidateContextIfCurrent(request)
                return
            }

            DispatchTemperatureGatewayResult.SessionInvalidated -> {
                invalidateSessionIfCurrent(request)
                return
            }

            DispatchTemperatureGatewayResult.PermissionDenied -> {
                fail(DispatchTemperatureStatus.PermissionDenied)
                return
            }

            DispatchTemperatureGatewayResult.NetworkUnavailable -> {
                fail(DispatchTemperatureStatus.NetworkUnavailable)
                return
            }

            else -> {
                fail(DispatchTemperatureStatus.ServiceUnavailable)
                return
            }
        }
        val selectedLot = currentReadiness.lots.firstOrNull { it.lotId == selection.lotId }
        if (!currentReadiness.fulfillmentId.equals(selection.fulfillmentId, ignoreCase = true) ||
            currentReadiness.temperatureRequiredForFulfillment ||
            selectedLot?.warehouseId != selection.warehouseId ||
            selectedLot?.supportsInRangeEvidence != true
        ) {
            mutableState.value = mutableState.value.copy(
                status = DispatchTemperatureStatus.Current,
                readiness = currentReadiness,
                observedAt = now(),
                canRecord = context.hasFulfillmentManagePermission(),
                canUploadPhoto = context.hasPhotoEvidencePermissions(),
                photoByLotId =
                    retainMatchingPhotos(mutableState.value.photoByLotId, currentReadiness) -
                        selection.lotId
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            status = DispatchTemperatureStatus.Current,
            readiness = currentReadiness,
            observedAt = now(),
            canRecord = context.hasFulfillmentManagePermission(),
            canUploadPhoto = context.hasPhotoEvidencePermissions(),
            photoByLotId = retainMatchingPhotos(mutableState.value.photoByLotId, currentReadiness)
        )
        if (!isCurrentEvidenceSelection(selection)) return

        val storedUpload = safe {
            metadata.loadPhotoUploadIntent(
                selection.scope,
                selection.fulfillmentId,
                selection.lotId
            )
        }
        val previous = when (storedUpload) {
            is DispatchTemperaturePhotoUploadMetadataRead.Available -> storedUpload.intent

            DispatchTemperaturePhotoUploadMetadataRead.Unavailable,
            null -> {
                setPhotoState(
                    selection.lotId,
                    DispatchTemperaturePhotoState(
                        warehouseId = selection.warehouseId,
                        status = DispatchTemperaturePhotoStatus.ServiceUnavailable
                    )
                )
                return
            }
        }
        if (previous != null &&
            (
                previous.scope != selection.scope ||
                    previous.fulfillmentId != selection.fulfillmentId ||
                    previous.lotId != selection.lotId ||
                    previous.warehouseId != selection.warehouseId || !previous.isValid() ||
                    !previous.matches(candidate)
                )
        ) {
            setPhotoState(
                selection.lotId,
                DispatchTemperaturePhotoState(
                    warehouseId = selection.warehouseId,
                    status = DispatchTemperaturePhotoStatus.UnknownOutcome
                )
            )
            return
        }
        val uploadIntent = previous ?: DispatchTemperaturePhotoUploadIntent(
            scope = selection.scope,
            fulfillmentId = selection.fulfillmentId,
            lotId = selection.lotId,
            warehouseId = selection.warehouseId,
            idempotencyKey = newCommandKey(),
            originalFilename = candidate.originalFilename,
            declaredContentType = candidate.declaredContentType,
            byteSize = candidate.byteSize,
            checksumSha256 = candidate.checksumSha256
        )
        if (safe { metadata.savePhotoUploadIntent(uploadIntent) } !=
            DispatchTemperatureMetadataWrite.Saved
        ) {
            setPhotoState(
                selection.lotId,
                DispatchTemperaturePhotoState(
                    warehouseId = selection.warehouseId,
                    status = DispatchTemperaturePhotoStatus.ServiceUnavailable
                )
            )
            return
        }
        val uploadCandidate = candidate.copy(originalFilename = uploadIntent.originalFilename)
        setPhotoState(
            selection.lotId,
            DispatchTemperaturePhotoState(
                warehouseId = selection.warehouseId,
                status = DispatchTemperaturePhotoStatus.Uploading
            )
        )
        val uploaded = try {
            gateway.uploadExcursionPhoto(
                selection.warehouseId,
                uploadCandidate,
                uploadIntent.idempotencyKey,
                context
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DispatchTemperaturePhotoGatewayResult.UnknownOutcome
        }
        if (!isCurrent(request, context) || !isCurrentEvidenceSelection(selection)) return
        when (uploaded) {
            is DispatchTemperaturePhotoGatewayResult.Evidence -> {
                if (!uploaded.photo.matchesWarehouse(selection.warehouseId)) {
                    safe {
                        metadata.clearPhotoUploadIntent(
                            selection.scope,
                            selection.fulfillmentId,
                            selection.lotId,
                            uploadIntent.idempotencyKey
                        )
                    }
                    setPhotoState(
                        selection.lotId,
                        DispatchTemperaturePhotoState(
                            warehouseId = selection.warehouseId,
                            status = DispatchTemperaturePhotoStatus.Rejected
                        )
                    )
                    return
                }
                safe {
                    metadata.clearPhotoUploadIntent(
                        selection.scope,
                        selection.fulfillmentId,
                        selection.lotId,
                        uploadIntent.idempotencyKey
                    )
                }
                setPhotoState(
                    selection.lotId,
                    DispatchTemperaturePhotoState(
                        evidence = uploaded.photo,
                        warehouseId = selection.warehouseId,
                        status = DispatchTemperaturePhotoStatus.Checking
                    )
                )
                checkExcursionEvidence(selection, uploaded.photo.id, context, request)
            }

            DispatchTemperaturePhotoGatewayResult.UnknownOutcome -> setPhotoState(
                selection.lotId,
                DispatchTemperaturePhotoState(
                    warehouseId = selection.warehouseId,
                    status = DispatchTemperaturePhotoStatus.UnknownOutcome
                )
            )

            DispatchTemperaturePhotoGatewayResult.NetworkUnavailable -> setPhotoState(
                selection.lotId,
                DispatchTemperaturePhotoState(
                    warehouseId = selection.warehouseId,
                    status = DispatchTemperaturePhotoStatus.NetworkUnavailable
                )
            )

            DispatchTemperaturePhotoGatewayResult.ServiceUnavailable -> setPhotoState(
                selection.lotId,
                DispatchTemperaturePhotoState(
                    warehouseId = selection.warehouseId,
                    status = DispatchTemperaturePhotoStatus.ServiceUnavailable
                )
            )

            DispatchTemperaturePhotoGatewayResult.PermissionDenied -> setPhotoState(
                selection.lotId,
                DispatchTemperaturePhotoState(
                    warehouseId = selection.warehouseId,
                    status = DispatchTemperaturePhotoStatus.PermissionDenied
                )
            )

            DispatchTemperaturePhotoGatewayResult.ContextInvalidated -> invalidateContextIfCurrent(
                request
            )

            DispatchTemperaturePhotoGatewayResult.SessionInvalidated -> invalidateSessionIfCurrent(
                request
            )

            is DispatchTemperaturePhotoGatewayResult.Rejected -> {
                safe {
                    metadata.clearPhotoUploadIntent(
                        selection.scope,
                        selection.fulfillmentId,
                        selection.lotId,
                        uploadIntent.idempotencyKey
                    )
                }
                setPhotoState(
                    selection.lotId,
                    DispatchTemperaturePhotoState(
                        warehouseId = selection.warehouseId,
                        status = DispatchTemperaturePhotoStatus.Rejected
                    )
                )
            }
        }
    }

    fun refreshExcursionEvidenceStatus(lotId: String) {
        val context = activeContext ?: return
        val selection = excursionEvidenceSelectionContext(lotId) ?: return
        val existing = mutableState.value.photoByLotId[lotId] ?: return
        val evidenceId = existing.evidence?.id ?: return
        val request = ++generation
        setPhotoState(lotId, existing.copy(status = DispatchTemperaturePhotoStatus.Checking))
        viewModelScope.launch {
            checkExcursionEvidence(selection, evidenceId, context, request)
        }
    }

    fun record(lotId: String) {
        val context = activeContext ?: return
        val scope = context.scopeIdentity() ?: return invalidateContext()
        val readiness = mutableState.value.readiness ?: return
        val current = mutableState.value
        if (current.status != DispatchTemperatureStatus.Current || !current.metadataReady ||
            current.hasPendingCommand || pendingIntent != null ||
            current.mutationStatus == DispatchTemperatureMutationStatus.Submitting ||
            !context.hasFulfillmentManagePermission()
        ) {
            return
        }
        val lot = readiness.lots.firstOrNull { it.lotId == lotId } ?: return
        if (!lot.supportsInRangeEvidence) return
        val expectedLotVersion = lot.version ?: return
        val value = current.valuesCelsius[lotId]?.trim()?.toBigDecimalOrNull() ?: run {
            mutableState.value = current.copy(
                mutationStatus = DispatchTemperatureMutationStatus.ServiceUnavailable,
                mutationLotId = lotId
            )
            return
        }
        val outsideRange = !lot.isWithinRange(value)
        val selectedPhoto = current.photoByLotId[lotId]
        val evidenceObjectId = if (outsideRange) {
            selectedPhoto?.takeIf {
                it.isAvailable && it.warehouseId == lot.warehouseId &&
                    it.evidence?.matchesWarehouse(lot.warehouseId.orEmpty()) == true
            }?.evidence?.id
        } else {
            null
        }
        if (outsideRange && evidenceObjectId == null) {
            mutableState.value = current.copy(
                mutationStatus = DispatchTemperatureMutationStatus.ExcursionPhotoRequired,
                mutationLotId = lotId
            )
            return
        }
        val partial = DispatchTemperatureCommand(
            fulfillmentId = readiness.fulfillmentId,
            expectedFulfillmentVersion = readiness.fulfillmentVersion,
            lotId = lotId,
            valueCelsius = value,
            occurredAt = now(),
            idempotencyKey = newCommandKey(),
            exactRequestBody = "",
            evidenceObjectId = evidenceObjectId,
            expectedLotVersion = expectedLotVersion
        )
        val command = partial.copy(
            exactRequestBody = requestBodyCodec.dispatchTemperatureRequestBody(partial)
        )
        if (!requestBodyCodec.isValid(command)) return
        val intent = DispatchTemperatureIntent(scope, command)
        val request = ++generation
        mutableState.value = current.copy(
            mutationStatus = DispatchTemperatureMutationStatus.Submitting,
            mutationLotId = lotId,
            hasPendingCommand = true,
            pendingCommand = command
        )
        viewModelScope.launch {
            when (safe { metadata.saveIntent(intent) }) {
                DispatchTemperatureMetadataWrite.Saved -> {
                    if (!isCurrent(request, context)) return@launch
                    pendingIntent = intent
                    send(intent, context, request)
                }

                DispatchTemperatureMetadataWrite.Conflict,
                DispatchTemperatureMetadataWrite.Stale -> {
                    if (isCurrent(request, context)) {
                        mutableState.value = mutableState.value.copy(
                            mutationStatus = DispatchTemperatureMutationStatus.Conflict,
                            hasPendingCommand = false,
                            pendingCommand = null
                        )
                    }
                }

                DispatchTemperatureMetadataWrite.Unavailable,
                null -> {
                    if (isCurrent(request, context)) {
                        mutableState.value = mutableState.value.copy(
                            mutationStatus = DispatchTemperatureMutationStatus.ServiceUnavailable,
                            hasPendingCommand = false,
                            pendingCommand = null
                        )
                    }
                }
            }
        }
    }

    /** Replays the frozen body/key/version only; it never creates a new reading. */
    fun retryUnknownOutcome() {
        val context = activeContext ?: return
        val scope = context.scopeIdentity() ?: return invalidateContext()
        val intent = pendingIntent ?: return
        if (!mutableState.value.canRetryUnknownOutcome ||
            intent.status != DispatchTemperatureIntentStatus.UnknownOutcome ||
            intent.scope != scope || !requestBodyCodec.isValid(intent.command) ||
            intent.command.fulfillmentId != activeFulfillmentId
        ) {
            return
        }
        val request = ++generation
        mutableState.value = mutableState.value.copy(
            mutationStatus = DispatchTemperatureMutationStatus.Submitting,
            mutationLotId = intent.command.lotId,
            hasPendingCommand = true,
            pendingCommand = intent.command
        )
        viewModelScope.launch {
            when (safe { metadata.saveIntent(intent) }) {
                DispatchTemperatureMetadataWrite.Saved -> {
                    if (!isCurrent(request, context)) return@launch
                    send(intent, context, request)
                }

                DispatchTemperatureMetadataWrite.Conflict,
                DispatchTemperatureMetadataWrite.Stale -> if (isCurrent(request, context)) {
                    mutableState.value = mutableState.value.copy(
                        mutationStatus = DispatchTemperatureMutationStatus.Conflict
                    )
                }

                DispatchTemperatureMetadataWrite.Unavailable,
                null -> if (isCurrent(request, context)) {
                    mutableState.value = mutableState.value.copy(
                        mutationStatus = DispatchTemperatureMutationStatus.ServiceUnavailable
                    )
                }
            }
        }
    }

    fun deactivate() {
        generation++
        activeContext = null
        activeFulfillmentId = null
        pendingIntent = null
        mutableState.value = DispatchTemperatureUiState()
    }

    private fun send(
        intent: DispatchTemperatureIntent,
        context: DispatchAuthorityContext,
        request: Long
    ) {
        viewModelScope.launch {
            val result = safe { gateway.record(intent.command, context) }
            if (!isCurrent(request, context)) return@launch
            when (result) {
                is DispatchTemperatureGatewayResult.Recorded -> {
                    val evidence = result.evidence
                    val command = intent.command
                    if (!evidence.fulfillmentId.equals(command.fulfillmentId, ignoreCase = true) ||
                        evidence.fulfillmentVersion != command.expectedFulfillmentVersion ||
                        !evidence.lotId.equals(command.lotId, ignoreCase = true) ||
                        evidence.valueCelsius.compareTo(command.valueCelsius) != 0 ||
                        evidence.occurredAt != command.occurredAt ||
                        (command.expectedLotVersion != null && !evidence.matches(command))
                    ) {
                        markUnknown(intent, context, request)
                    } else {
                        val cleared = metadata.clearIntent(
                            intent.scope,
                            command.fulfillmentId,
                            command.idempotencyKey
                        )
                        if (cleared == DispatchTemperatureMetadataWrite.Saved) {
                            pendingIntent = null
                            if (isCurrent(request, context)) {
                                val currentReadiness = mutableState.value.readiness
                                mutableState.value = mutableState.value.copy(
                                    readiness = currentReadiness?.copy(
                                        lots = currentReadiness.lots.map { currentLot ->
                                            if (currentLot.lotId == command.lotId) {
                                                currentLot.copy(
                                                    status =
                                                        evidence.inventoryLotStatus
                                                            ?: currentLot.status,
                                                    latestEvidence = evidence
                                                )
                                            } else {
                                                currentLot
                                            }
                                        }
                                    ),
                                    photoByLotId = mutableState.value.photoByLotId - command.lotId,
                                    mutationStatus = DispatchTemperatureMutationStatus.Recorded,
                                    mutationLotId = command.lotId,
                                    hasPendingCommand = false,
                                    pendingCommand = null
                                )
                            }
                        } else {
                            markUnknown(
                                intent.copy(
                                    status = DispatchTemperatureIntentStatus.UnknownOutcome
                                ),
                                context,
                                request
                            )
                            if (isCurrent(request, context)) {
                                mutableState.value = mutableState.value.copy(
                                    mutationStatus = DispatchTemperatureMutationStatus.Recorded
                                )
                            }
                        }
                    }
                }

                DispatchTemperatureGatewayResult.UnknownOutcome,
                DispatchTemperatureGatewayResult.NetworkUnavailable,
                null -> markUnknown(intent, context, request)

                DispatchTemperatureGatewayResult.ContextInvalidated -> {
                    markUnknown(intent, context, request)
                    if (isCurrent(request, context)) invalidateContext()
                }

                DispatchTemperatureGatewayResult.SessionInvalidated -> {
                    markUnknown(intent, context, request)
                    if (isCurrent(request, context)) invalidateSession()
                }

                DispatchTemperatureGatewayResult.OutsideRangeBackendContractGap ->
                    clearKnownRejection(
                        intent,
                        context,
                        request,
                        DispatchTemperatureMutationStatus.OutsideRangeBackendContractGap
                    )

                DispatchTemperatureGatewayResult.Stale -> {
                    val cleared =
                        clearKnownRejection(
                            intent,
                            context,
                            request,
                            DispatchTemperatureMutationStatus.Stale
                        )
                    if (cleared) {
                        mutableState.value = mutableState.value.copy(
                            valuesCelsius = mutableState.value.valuesCelsius - intent.command.lotId
                        )
                        refresh()
                    }
                }

                DispatchTemperatureGatewayResult.Conflict -> {
                    val cleared =
                        clearKnownRejection(
                            intent,
                            context,
                            request,
                            DispatchTemperatureMutationStatus.Conflict
                        )
                    if (cleared) refresh()
                }

                DispatchTemperatureGatewayResult.PermissionDenied ->
                    clearKnownRejection(
                        intent,
                        context,
                        request,
                        DispatchTemperatureMutationStatus.PermissionDenied
                    )

                DispatchTemperatureGatewayResult.ServiceUnavailable ->
                    clearKnownRejection(
                        intent,
                        context,
                        request,
                        DispatchTemperatureMutationStatus.ServiceUnavailable
                    )

                is DispatchTemperatureGatewayResult.Current ->
                    markUnknown(intent, context, request)
            }
        }
    }

    private suspend fun clearKnownRejection(
        intent: DispatchTemperatureIntent,
        context: DispatchAuthorityContext,
        request: Long,
        status: DispatchTemperatureMutationStatus
    ): Boolean {
        val cleared = metadata.clearIntent(
            intent.scope,
            intent.command.fulfillmentId,
            intent.command.idempotencyKey
        ) == DispatchTemperatureMetadataWrite.Saved
        if (cleared) {
            pendingIntent = null
        } else {
            markUnknown(
                intent.copy(status = DispatchTemperatureIntentStatus.UnknownOutcome),
                context,
                request
            )
            return false
        }
        if (isCurrent(request, context)) {
            mutableState.value = mutableState.value.copy(
                mutationStatus = status,
                mutationLotId = intent.command.lotId,
                hasPendingCommand = false,
                pendingCommand = null
            )
        }
        return true
    }

    private suspend fun markUnknown(
        intent: DispatchTemperatureIntent,
        context: DispatchAuthorityContext,
        request: Long
    ) {
        val unknown = intent.copy(status = DispatchTemperatureIntentStatus.UnknownOutcome)
        metadata.saveIntent(unknown)
        pendingIntent = unknown
        if (isCurrent(request, context)) {
            mutableState.value = mutableState.value.copy(
                mutationStatus = DispatchTemperatureMutationStatus.UnknownOutcome,
                mutationLotId = intent.command.lotId,
                hasPendingCommand = true,
                pendingCommand = intent.command
            )
        }
    }

    private fun failIfCurrent(
        request: Long,
        context: DispatchAuthorityContext,
        status: DispatchTemperatureStatus
    ) {
        if (isCurrent(request, context)) fail(status)
    }

    private fun fail(status: DispatchTemperatureStatus) {
        mutableState.value = mutableState.value.copy(
            status = status,
            readiness = null,
            observedAt = null,
            photoByLotId = if (status in setOf(
                    DispatchTemperatureStatus.PermissionDenied,
                    DispatchTemperatureStatus.ContextInvalidated,
                    DispatchTemperatureStatus.SessionInvalidated
                )
            ) {
                emptyMap()
            } else {
                mutableState.value.photoByLotId
            }
        )
    }

    private suspend fun checkExcursionEvidence(
        selection: DispatchTemperatureEvidenceSelectionContext,
        evidenceObjectId: String,
        context: DispatchAuthorityContext,
        request: Long
    ) {
        val result = try {
            gateway.excursionPhotoStatus(evidenceObjectId, selection.warehouseId, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DispatchTemperaturePhotoGatewayResult.ServiceUnavailable
        }
        if (!isCurrent(request, context) || !isCurrentEvidenceSelection(selection)) return
        when (result) {
            is DispatchTemperaturePhotoGatewayResult.Evidence -> {
                val photo = result.photo
                if (photo.id != evidenceObjectId ||
                    !photo.matchesWarehouse(selection.warehouseId)
                ) {
                    setPhotoState(
                        selection.lotId,
                        DispatchTemperaturePhotoState(
                            warehouseId = selection.warehouseId,
                            status = DispatchTemperaturePhotoStatus.Rejected
                        )
                    )
                } else {
                    val available = photo.lifecycleStatus.equals("AVAILABLE", ignoreCase = true)
                    setPhotoState(
                        selection.lotId,
                        DispatchTemperaturePhotoState(
                            evidence = photo,
                            warehouseId = selection.warehouseId,
                            status = if (available) {
                                DispatchTemperaturePhotoStatus.Available
                            } else {
                                DispatchTemperaturePhotoStatus.AwaitingAvailability
                            }
                        )
                    )
                }
            }

            is DispatchTemperaturePhotoGatewayResult.Rejected -> setPhotoState(
                selection.lotId,
                DispatchTemperaturePhotoState(
                    warehouseId = selection.warehouseId,
                    status = DispatchTemperaturePhotoStatus.Rejected
                )
            )

            DispatchTemperaturePhotoGatewayResult.UnknownOutcome -> setExistingPhotoStatus(
                selection,
                DispatchTemperaturePhotoStatus.UnknownOutcome
            )

            DispatchTemperaturePhotoGatewayResult.NetworkUnavailable -> setExistingPhotoStatus(
                selection,
                DispatchTemperaturePhotoStatus.NetworkUnavailable
            )

            DispatchTemperaturePhotoGatewayResult.ServiceUnavailable -> setExistingPhotoStatus(
                selection,
                DispatchTemperaturePhotoStatus.ServiceUnavailable
            )

            DispatchTemperaturePhotoGatewayResult.PermissionDenied -> setPhotoState(
                selection.lotId,
                DispatchTemperaturePhotoState(
                    warehouseId = selection.warehouseId,
                    status = DispatchTemperaturePhotoStatus.PermissionDenied
                )
            )

            DispatchTemperaturePhotoGatewayResult.ContextInvalidated -> invalidateContextIfCurrent(
                request
            )

            DispatchTemperaturePhotoGatewayResult.SessionInvalidated -> invalidateSessionIfCurrent(
                request
            )
        }
    }

    private fun setExistingPhotoStatus(
        selection: DispatchTemperatureEvidenceSelectionContext,
        status: DispatchTemperaturePhotoStatus
    ) {
        val existing = mutableState.value.photoByLotId[selection.lotId]
        setPhotoState(
            selection.lotId,
            existing?.copy(status = status)
                ?: DispatchTemperaturePhotoState(
                    warehouseId = selection.warehouseId,
                    status = status
                )
        )
    }

    private fun setPhotoState(lotId: String, photo: DispatchTemperaturePhotoState) {
        val current = mutableState.value
        val lot = current.readiness?.lots?.firstOrNull { it.lotId == lotId } ?: return
        if (lot.warehouseId != photo.warehouseId) return
        mutableState.value = current.copy(photoByLotId = current.photoByLotId + (lotId to photo))
    }

    private fun retainMatchingPhotos(
        photos: Map<String, DispatchTemperaturePhotoState>,
        readiness: DispatchTemperatureReadiness
    ): Map<String, DispatchTemperaturePhotoState> = photos.filter { (lotId, photo) ->
        readiness.lots.any { it.lotId == lotId && it.warehouseId == photo.warehouseId }
    }

    private fun selectionMatches(
        selection: DispatchTemperatureEvidenceSelectionContext,
        context: DispatchAuthorityContext
    ): Boolean = selection.authorityEpoch == context.authorityEpoch &&
        selection.scope == context.scopeIdentity() &&
        selection.fulfillmentId == activeFulfillmentId &&
        selection.warehouseId.isNotBlank() && selection.lotId.isNotBlank()

    private fun DispatchTemperaturePhotoEvidence.matchesWarehouse(warehouseId: String): Boolean =
        id.isNotBlank() && subjectType == "WAREHOUSE" && subjectId == warehouseId

    private fun DispatchTemperatureEvidence.matches(command: DispatchTemperatureCommand): Boolean {
        if (expectedLotVersion != command.expectedLotVersion ||
            evidenceObjectId != command.evidenceObjectId
        ) {
            return false
        }
        return when {
            status == "OUT_OF_RANGE" ->
                command.evidenceObjectId != null &&
                    inventoryLotStatus == "HOLD" && resultingLotVersion != null &&
                    inventoryTemperatureEvaluationId != null && affectedQuantity?.signum() == 1

            status == "WITHIN_RANGE" -> true

            else -> false
        }
    }

    private fun invalidateContextIfCurrent(request: Long) {
        if (request == generation) invalidateContext()
    }

    private fun invalidateSessionIfCurrent(request: Long) {
        if (request == generation) invalidateSession()
    }

    private fun invalidateContext() {
        generation++
        activeContext = null
        fail(DispatchTemperatureStatus.ContextInvalidated)
    }

    private fun invalidateSession() {
        generation++
        activeContext = null
        fail(DispatchTemperatureStatus.SessionInvalidated)
    }

    private fun isCurrent(request: Long, context: DispatchAuthorityContext): Boolean =
        request == generation && activeContext == context

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun DispatchAuthorityContext.readPermissionHint(): PermissionHint {
        val permissions = identity?.permissions.orEmpty()
        return when {
            permissions.isEmpty() -> PermissionHint.Unknown
            permissions.any { it in FULFILLMENT_READ_PERMISSIONS } -> PermissionHint.Available
            else -> PermissionHint.Unavailable
        }
    }

    private fun DispatchAuthorityContext.hasFulfillmentManagePermission(): Boolean =
        identity?.permissions?.any { it in FULFILLMENT_MANAGE_PERMISSIONS } == true

    private fun DispatchAuthorityContext.hasPhotoEvidencePermissions(): Boolean {
        val permissions = identity?.permissions.orEmpty()
        return permissions.any { it in FULFILLMENT_MANAGE_PERMISSIONS } &&
            DOCUMENT_UPLOAD_PERMISSION in permissions && DOCUMENT_READ_PERMISSION in permissions
    }

    private fun DispatchAuthorityContext.scopeIdentity(): DispatchTemperatureScopeIdentity? {
        val current = identity ?: return null
        val fields =
            listOf(current.userId, current.tenantId, current.workspaceId, current.membershipId)
        if (authorityEpoch <= 0 || fields.any(String::isBlank)) return null
        return DispatchTemperatureScopeIdentity(
            current.userId,
            current.tenantId,
            current.workspaceId,
            current.membershipId
        )
    }

    private enum class PermissionHint {
        Unknown,
        Available,
        Unavailable
    }

    private companion object {
        val INVALIDATED = setOf(
            DispatchTemperatureStatus.ContextInvalidated,
            DispatchTemperatureStatus.SessionInvalidated
        )
        val FULFILLMENT_READ_PERMISSIONS = setOf("fulfillment.read", "fulfillment:read")
        val FULFILLMENT_MANAGE_PERMISSIONS = setOf("fulfillment.manage", "warehouse:write")
        const val DOCUMENT_UPLOAD_PERMISSION = "document.upload"
        const val DOCUMENT_READ_PERMISSION = "document.read"
    }
}
