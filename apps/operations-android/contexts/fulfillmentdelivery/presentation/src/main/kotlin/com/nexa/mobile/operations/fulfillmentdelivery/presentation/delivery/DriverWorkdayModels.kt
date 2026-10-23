package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandAction
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandIntentRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationCapture
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationReadResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayReadResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliverySnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverLiveLocation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkday
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationEvent
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationSample
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val LOCATION_FRESHNESS_CHECK_INTERVAL_MILLIS = 15_000L
private const val DELIVERY_ELIGIBILITY_CHECK_INTERVAL_MILLIS = 15_000L
private const val MAX_LOCATION_SAMPLE_AGE_MILLIS = 120_000L
private val ACTIVE_LOCATION_DELIVERY_STATUSES = setOf("DISPATCHED", "IN_TRANSIT")

private data class EligibleDelivery(
    val id: String,
    val attemptId: String,
    val attemptStartedAt: Instant
) {
    val consent: DriverLocationConsent
        get() = DriverLocationConsent(id, attemptId)
}

private data class DriverLocationConsent(val deliveryId: String, val attemptId: String)

enum class DriverWorkdayNotice {
    NONE,
    LOCATION_PERMISSION_REQUIRED,
    ACTIVE_DELIVERY_REQUIRED,
    CURRENT_UNAVAILABLE,
    START_UNKNOWN,
    START_REJECTED,
    LOCATION_ACQUIRING,
    END_PENDING_CONFIRMATION,
    AVAILABILITY_UNKNOWN,
    LOCATION_UNAVAILABLE,
    NOTIFICATION_PERMISSION_REQUIRED,
    STALE_WORKDAY,
    PERMISSION_DENIED,
    CONTEXT_INVALIDATED,
    SESSION_INVALIDATED,
    COMMAND_REJECTED,
    COMMAND_STORAGE_UNAVAILABLE,
    UNKNOWN_COMMAND_PENDING
}

@Immutable
data class DriverWorkdayUiState(
    val workday: DriverWorkday? = null,
    val loading: Boolean = false,
    val commandPending: Boolean = false,
    val captureRequested: Boolean = false,
    val locationReady: Boolean = false,
    val lastSampleAt: String? = null,
    val eligibleDeliveryId: String? = null,
    val liveLocation: DriverLiveLocation? = null,
    val pendingCommand: DriverWorkdayCommandIntent? = null,
    val notice: DriverWorkdayNotice = DriverWorkdayNotice.NONE
)

class DriverWorkdayViewModel(
    private val gateway: DriverWorkdayGateway,
    private val capture: DriverWorkdayLocationCapture,
    private val commandStore: DriverWorkdayCommandStore,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> String = { Instant.now().toString() },
    private val deliveryGateway: DriverDeliveryGateway? = null
) : ViewModel() {
    private val mutableState = MutableStateFlow(DriverWorkdayUiState())
    val state: StateFlow<DriverWorkdayUiState> = mutableState.asStateFlow()

    private var authority: DriverDeliveryAuthority? = null
    private var fineLocationPermission = false
    private var captureJob: Job? = null
    private var locationFreshnessJob: Job? = null
    private var deliveryEligibilityJob: Job? = null
    private var capturingWorkdayId: String? = null
    private var acknowledgedWorkdayId: String? = null
    private var operationGeneration = 0L
    private var availabilityChangeInFlight: Boolean? = null
    private var commandStoreAvailable = true
    private var eligibleDelivery: EligibleDelivery? = null
    private var consentedDelivery: DriverLocationConsent? = null

    fun activate(authority: DriverDeliveryAuthority, hasFineLocationPermission: Boolean) {
        if (this.authority != authority) {
            operationGeneration += 1
            stopCapture()
            eligibleDelivery = null
            clearLocationConsent()
            mutableState.value = DriverWorkdayUiState()
        }
        this.authority = authority
        fineLocationPermission = hasFineLocationPermission
        if (!hasFineLocationPermission) {
            clearLocationConsent()
            stopCapture()
            clearLocationSample()
        }
        mutableState.value = mutableState.value.copy(loading = true)
        viewModelScope.launch {
            when (val stored = commandStore.load(authority.commandScope())) {
                is DriverWorkdayCommandIntentRead.Available -> {
                    commandStoreAvailable = true
                    mutableState.value = mutableState.value.copy(pendingCommand = stored.intent)
                }

                DriverWorkdayCommandIntentRead.Unavailable -> {
                    commandStoreAvailable = false
                    mutableState.value =
                        mutableState.value.copy(
                            notice = DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                        )
                }
            }
            refresh()
        }
    }

    fun refresh() {
        val currentAuthority = authority ?: return
        val requestGeneration = ++operationGeneration
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                loading = true,
                notice = if (commandStoreAvailable) {
                    DriverWorkdayNotice.NONE
                } else {
                    DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                }
            )
            val result = gateway.current(currentAuthority)
            if (!isCurrent(currentAuthority, requestGeneration)) return@launch
            when (result) {
                is DriverWorkdayReadResult.Current -> {
                    val day = result.workday
                    mutableState.value = mutableState.value.copy(workday = day, loading = false)
                    val pending = mutableState.value.pendingCommand
                    if (pending != null && intentSatisfied(pending, day)) clearPending(pending)
                    if (mutableState.value.pendingCommand ==
                        null
                    ) {
                        reconcileCapture(currentAuthority, day)
                    } else {
                        stopCapture()
                    }
                }

                DriverWorkdayReadResult.Unavailable -> {
                    stopCapture()
                    mutableState.value = mutableState.value.copy(
                        workday = null,
                        loading = false,
                        liveLocation = null,
                        lastSampleAt = null,
                        notice = DriverWorkdayNotice.CURRENT_UNAVAILABLE
                    )
                }

                DriverWorkdayReadResult.PermissionDenied -> failRead(
                    DriverWorkdayNotice.PERMISSION_DENIED
                )

                DriverWorkdayReadResult.ContextInvalidated -> invalidate(
                    DriverWorkdayNotice.CONTEXT_INVALIDATED
                )

                DriverWorkdayReadResult.SessionInvalidated -> invalidate(
                    DriverWorkdayNotice.SESSION_INVALIDATED
                )
            }
        }
    }

    fun updateDelivery(delivery: DriverDeliverySnapshot?, detailReady: Boolean) {
        val next = delivery
            ?.takeIf {
                detailReady && it.status in ACTIVE_LOCATION_DELIVERY_STATUSES &&
                    !it.activeAttempt?.id.isNullOrBlank()
            }
            ?.let { selected ->
                val attempt = selected.activeAttempt ?: return@let null
                val attemptStartedAt = attempt.startedAt
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: return@let null
                EligibleDelivery(selected.id, attempt.id, attemptStartedAt)
            }
        if (next == eligibleDelivery) return
        operationGeneration += 1
        val shouldDisable = mutableState.value.workday?.locationAvailable == true
        eligibleDelivery = next
        clearLocationConsent()
        stopCapture()
        mutableState.value = mutableState.value.copy(
            eligibleDeliveryId = next?.id,
            liveLocation = null,
            lastSampleAt = null,
            locationReady = false,
            captureRequested = false
        )
        if (shouldDisable) changeLocationAvailability(false)
    }

    fun startWorkday() {
        val currentAuthority = authority ?: return
        if (!currentAuthority.canWriteWorkday()) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.PERMISSION_DENIED)
            return
        }
        if (mutableState.value.pendingCommand != null) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.UNKNOWN_COMMAND_PENDING)
            return
        }
        if (!commandStoreAvailable) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE)
            return
        }
        val requestGeneration = ++operationGeneration
        viewModelScope.launch {
            mutableState.value =
                mutableState.value.copy(commandPending = true, notice = DriverWorkdayNotice.NONE)
            val current = gateway.current(currentAuthority)
            if (!isCurrent(currentAuthority, requestGeneration)) return@launch
            when (current) {
                is DriverWorkdayReadResult.Current -> {
                    val workday = current.workday
                    if (workday != null && workday.status != DriverWorkdayStatus.CLOSED) {
                        mutableState.value =
                            mutableState.value.copy(
                                workday = workday,
                                commandPending = false
                            )
                        reconcileCapture(currentAuthority, workday)
                        return@launch
                    }
                }

                DriverWorkdayReadResult.Unavailable -> {
                    mutableState.value =
                        mutableState.value.copy(
                            commandPending = false,
                            notice = DriverWorkdayNotice.CURRENT_UNAVAILABLE
                        )
                    return@launch
                }

                DriverWorkdayReadResult.PermissionDenied -> {
                    failCommand(DriverWorkdayNotice.PERMISSION_DENIED)
                    return@launch
                }

                DriverWorkdayReadResult.ContextInvalidated -> {
                    invalidate(DriverWorkdayNotice.CONTEXT_INVALIDATED)
                    return@launch
                }

                DriverWorkdayReadResult.SessionInvalidated -> {
                    invalidate(DriverWorkdayNotice.SESSION_INVALIDATED)
                    return@launch
                }
            }

            val intent = DriverWorkdayCommandIntent(
                scope = currentAuthority.commandScope(),
                action = DriverWorkdayCommandAction.START,
                workdayId = null,
                expectedVersion = null,
                locationAvailable = false,
                idempotencyKey = keyFactory(),
                initiatedAt = clock()
            )
            if (!commandStore.save(intent)) {
                commandStoreAvailable = false
                mutableState.value = mutableState.value.copy(
                    commandPending = false,
                    notice = DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(pendingCommand = intent)
            val command = gateway.start(currentAuthority, intent.idempotencyKey)
            if (!isCurrent(currentAuthority, requestGeneration)) return@launch
            // A replayed START can describe an old day. Only the current-read response can resume capture.
            val refreshed = gateway.current(currentAuthority)
            if (!isCurrent(currentAuthority, requestGeneration)) return@launch
            when (refreshed) {
                is DriverWorkdayReadResult.Current -> {
                    if (intentSatisfied(intent, refreshed.workday) ||
                        command != DriverWorkdayCommandResult.UnknownOutcome
                    ) {
                        clearPending(intent)
                    }
                    mutableState.value = mutableState.value.copy(
                        workday = refreshed.workday,
                        commandPending = false,
                        notice = if (!commandStoreAvailable &&
                            mutableState.value.pendingCommand != null
                        ) {
                            DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                        } else if (refreshed.workday?.status == DriverWorkdayStatus.ACTIVE) {
                            DriverWorkdayNotice.NONE
                        } else if (command == DriverWorkdayCommandResult.UnknownOutcome) {
                            DriverWorkdayNotice.START_UNKNOWN
                        } else {
                            command.noticeForStart()
                        }
                    )
                    if (mutableState.value.pendingCommand ==
                        null
                    ) {
                        reconcileCapture(currentAuthority, refreshed.workday)
                    } else {
                        stopCapture()
                    }
                }

                DriverWorkdayReadResult.Unavailable -> {
                    stopCapture()
                    mutableState.value = mutableState.value.copy(
                        workday = null,
                        commandPending = false,
                        notice = DriverWorkdayNotice.CURRENT_UNAVAILABLE
                    )
                }

                DriverWorkdayReadResult.PermissionDenied -> failCommand(
                    DriverWorkdayNotice.PERMISSION_DENIED
                )

                DriverWorkdayReadResult.ContextInvalidated -> invalidate(
                    DriverWorkdayNotice.CONTEXT_INVALIDATED
                )

                DriverWorkdayReadResult.SessionInvalidated -> invalidate(
                    DriverWorkdayNotice.SESSION_INVALIDATED
                )
            }
        }
    }

    fun enableLocation() {
        val eligible = eligibleDelivery
        if (eligible == null) {
            mutableState.value = mutableState.value.copy(
                notice = DriverWorkdayNotice.ACTIVE_DELIVERY_REQUIRED
            )
            return
        }
        consentedDelivery = eligible.consent
        changeLocationAvailability(true)
    }

    fun disableLocationSharing() {
        clearLocationConsent()
        stopCapture()
        clearLocationSample()
        changeLocationAvailability(false)
    }

    private fun changeLocationAvailability(
        available: Boolean,
        unavailableNotice: DriverWorkdayNotice? = null
    ) {
        val currentAuthority = authority ?: return
        if (!available) {
            clearLocationConsent()
            stopCapture()
            clearLocationSample()
        }
        if (mutableState.value.pendingCommand != null) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.UNKNOWN_COMMAND_PENDING)
            return
        }
        if (!commandStoreAvailable) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE)
            return
        }
        val day = mutableState.value.workday ?: return
        if (available && eligibleDelivery == null) {
            clearLocationConsent()
            mutableState.value = mutableState.value.copy(
                notice = DriverWorkdayNotice.ACTIVE_DELIVERY_REQUIRED
            )
            return
        }
        if (available && !currentAuthority.canWriteWorkday()) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.PERMISSION_DENIED)
            return
        }
        if (day.status == DriverWorkdayStatus.CLOSED || (available && !fineLocationPermission)) {
            if (available) clearLocationConsent()
            mutableState.value = mutableState.value.copy(
                notice = if (available) {
                    DriverWorkdayNotice.LOCATION_PERMISSION_REQUIRED
                } else {
                    DriverWorkdayNotice.NONE
                }
            )
            return
        }
        if (availabilityChangeInFlight != null) return
        if (available && !capture.prepare(day.id)) {
            clearLocationConsent()
            mutableState.value = mutableState.value.copy(
                notice = DriverWorkdayNotice.LOCATION_UNAVAILABLE
            )
            return
        }
        availabilityChangeInFlight = available
        val requestGeneration = ++operationGeneration
        viewModelScope.launch {
            mutableState.value =
                mutableState.value.copy(
                    commandPending = true,
                    notice = unavailableNotice ?: DriverWorkdayNotice.NONE
                )
            val intent = DriverWorkdayCommandIntent(
                scope = currentAuthority.commandScope(),
                action = DriverWorkdayCommandAction.SET_LOCATION_AVAILABILITY,
                workdayId = day.id,
                expectedVersion = day.version,
                locationAvailable = available,
                idempotencyKey = keyFactory(),
                initiatedAt = clock()
            )
            if (!commandStore.save(intent)) {
                if (available) capture.stop()
                availabilityChangeInFlight = null
                commandStoreAvailable = false
                mutableState.value = mutableState.value.copy(
                    commandPending = false,
                    notice = DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(pendingCommand = intent)
            val command = gateway.setLocationAvailability(
                currentAuthority,
                day.id,
                day.version,
                available,
                intent.idempotencyKey
            )
            if (!isCurrent(currentAuthority, requestGeneration)) return@launch
            val refreshed = gateway.current(currentAuthority)
            if (!isCurrent(currentAuthority, requestGeneration)) return@launch
            availabilityChangeInFlight = null
            when (refreshed) {
                is DriverWorkdayReadResult.Current -> {
                    if (intentSatisfied(intent, refreshed.workday) ||
                        command != DriverWorkdayCommandResult.UnknownOutcome
                    ) {
                        clearPending(intent)
                    }
                    mutableState.value = mutableState.value.copy(
                        workday = refreshed.workday,
                        commandPending = false,
                        notice = if (!commandStoreAvailable &&
                            mutableState.value.pendingCommand != null
                        ) {
                            DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                        } else {
                            when (command) {
                                DriverWorkdayCommandResult.Accepted ->
                                    unavailableNotice ?: DriverWorkdayNotice.NONE

                                DriverWorkdayCommandResult.StaleVersion ->
                                    DriverWorkdayNotice.STALE_WORKDAY

                                DriverWorkdayCommandResult.UnknownOutcome ->
                                    if (mutableState.value.pendingCommand !=
                                        null
                                    ) {
                                        DriverWorkdayNotice.AVAILABILITY_UNKNOWN
                                    } else {
                                        unavailableNotice ?: DriverWorkdayNotice.NONE
                                    }

                                DriverWorkdayCommandResult.PermissionDenied ->
                                    DriverWorkdayNotice.PERMISSION_DENIED

                                DriverWorkdayCommandResult.ContextInvalidated ->
                                    DriverWorkdayNotice.CONTEXT_INVALIDATED

                                DriverWorkdayCommandResult.SessionInvalidated ->
                                    DriverWorkdayNotice.SESSION_INVALIDATED

                                else -> DriverWorkdayNotice.COMMAND_REJECTED
                            }
                        }
                    )
                    if (mutableState.value.pendingCommand ==
                        null
                    ) {
                        reconcileCapture(currentAuthority, refreshed.workday)
                    } else {
                        stopCapture()
                    }
                }

                DriverWorkdayReadResult.Unavailable -> {
                    stopCapture()
                    mutableState.value = mutableState.value.copy(
                        commandPending = false,
                        captureRequested = false,
                        notice = DriverWorkdayNotice.CURRENT_UNAVAILABLE
                    )
                }

                DriverWorkdayReadResult.PermissionDenied -> failCommand(
                    DriverWorkdayNotice.PERMISSION_DENIED
                )

                DriverWorkdayReadResult.ContextInvalidated -> invalidate(
                    DriverWorkdayNotice.CONTEXT_INVALIDATED
                )

                DriverWorkdayReadResult.SessionInvalidated -> invalidate(
                    DriverWorkdayNotice.SESSION_INVALIDATED
                )
            }
        }
    }

    fun endWorkday() {
        val currentAuthority = authority ?: return
        val day = mutableState.value.workday ?: return
        if (!currentAuthority.canWriteWorkday()) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.PERMISSION_DENIED)
            return
        }
        if (mutableState.value.pendingCommand != null) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.UNKNOWN_COMMAND_PENDING)
            return
        }
        if (!commandStoreAvailable) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE)
            return
        }
        if (day.status == DriverWorkdayStatus.CLOSED) return
        // Stop the OS capture before dispatching END, even if the network outcome is unknown.
        clearLocationConsent()
        stopCapture()
        clearLocationSample()
        val requestGeneration = ++operationGeneration
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                commandPending = true,
                captureRequested = false,
                liveLocation = null
            )
            val intent = DriverWorkdayCommandIntent(
                scope = currentAuthority.commandScope(),
                action = DriverWorkdayCommandAction.END,
                workdayId = day.id,
                expectedVersion = day.version,
                locationAvailable = null,
                idempotencyKey = keyFactory(),
                initiatedAt = clock()
            )
            if (!commandStore.save(intent)) {
                commandStoreAvailable = false
                failCommand(DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE)
                return@launch
            }
            mutableState.value = mutableState.value.copy(pendingCommand = intent)
            val result = gateway.end(currentAuthority, day.id, day.version, intent.idempotencyKey)
            if (!isCurrent(currentAuthority, requestGeneration)) return@launch
            when (result) {
                DriverWorkdayCommandResult.Accepted -> {
                    clearPending(intent)
                    mutableState.value = mutableState.value.copy(
                        commandPending = false,
                        notice = if (!commandStoreAvailable &&
                            mutableState.value.pendingCommand != null
                        ) {
                            DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                        } else {
                            DriverWorkdayNotice.END_PENDING_CONFIRMATION
                        }
                    )
                }

                DriverWorkdayCommandResult.UnknownOutcome -> {
                    mutableState.value = mutableState.value.copy(
                        commandPending = false,
                        notice = DriverWorkdayNotice.UNKNOWN_COMMAND_PENDING
                    )
                }

                DriverWorkdayCommandResult.StaleVersion -> failCommand(
                    DriverWorkdayNotice.STALE_WORKDAY
                )

                DriverWorkdayCommandResult.PermissionDenied -> failCommand(
                    DriverWorkdayNotice.PERMISSION_DENIED
                )

                DriverWorkdayCommandResult.ContextInvalidated -> invalidate(
                    DriverWorkdayNotice.CONTEXT_INVALIDATED
                )

                DriverWorkdayCommandResult.SessionInvalidated -> invalidate(
                    DriverWorkdayNotice.SESSION_INVALIDATED
                )

                else -> failCommand(DriverWorkdayNotice.COMMAND_REJECTED)
            }
        }
    }

    fun onLocationPermissionChanged(granted: Boolean) {
        fineLocationPermission = granted
        if (!granted) {
            val day = mutableState.value.workday
            val hadLocationSharing = consentedDelivery != null ||
                mutableState.value.captureRequested || day?.locationAvailable == true
            clearLocationConsent()
            stopCapture()
            clearLocationSample()
            if (day != null && day.status != DriverWorkdayStatus.CLOSED && day.locationAvailable) {
                changeLocationAvailability(
                    available = false,
                    unavailableNotice = DriverWorkdayNotice.LOCATION_PERMISSION_REQUIRED
                )
            } else if (hadLocationSharing) {
                mutableState.value = mutableState.value.copy(
                    notice = DriverWorkdayNotice.LOCATION_PERMISSION_REQUIRED
                )
            } else {
                mutableState.value = mutableState.value.copy(notice = DriverWorkdayNotice.NONE)
            }
        }
    }

    fun onNotificationsPermissionChanged(granted: Boolean) {
        if (granted) return
        clearLocationConsent()
        stopCapture()
        clearLocationSample()
        val day = mutableState.value.workday
        if (day != null && day.status != DriverWorkdayStatus.CLOSED && day.locationAvailable) {
            changeLocationAvailability(
                available = false,
                unavailableNotice = DriverWorkdayNotice.NOTIFICATION_PERMISSION_REQUIRED
            )
        } else if (eligibleDelivery != null) {
            mutableState.value = mutableState.value.copy(
                notice = DriverWorkdayNotice.NOTIFICATION_PERMISSION_REQUIRED
            )
        }
    }

    fun retryPendingCommand() {
        val currentAuthority = authority ?: return
        val intent = mutableState.value.pendingCommand ?: return
        if (!commandStoreAvailable || intent.scope != currentAuthority.commandScope()) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE)
            return
        }
        val requestGeneration = ++operationGeneration
        viewModelScope.launch {
            mutableState.value =
                mutableState.value.copy(commandPending = true, notice = DriverWorkdayNotice.NONE)
            when (val current = gateway.current(currentAuthority)) {
                is DriverWorkdayReadResult.Current -> {
                    if (!isCurrent(currentAuthority, requestGeneration)) return@launch
                    if (intentSatisfied(intent, current.workday)) {
                        clearPending(intent)
                        mutableState.value = mutableState.value.copy(
                            workday = current.workday,
                            commandPending = false,
                            notice = if (!commandStoreAvailable &&
                                mutableState.value.pendingCommand != null
                            ) {
                                DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                            } else {
                                DriverWorkdayNotice.NONE
                            }
                        )
                        if (mutableState.value.pendingCommand ==
                            null
                        ) {
                            reconcileCapture(currentAuthority, current.workday)
                        }
                        return@launch
                    }
                    if (intent.action != DriverWorkdayCommandAction.START &&
                        (
                            current.workday?.id != intent.workdayId ||
                                current.workday?.version != intent.expectedVersion
                            )
                    ) {
                        clearPending(intent)
                        mutableState.value = mutableState.value.copy(
                            workday = current.workday,
                            commandPending = false,
                            notice = if (!commandStoreAvailable &&
                                mutableState.value.pendingCommand != null
                            ) {
                                DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                            } else {
                                DriverWorkdayNotice.STALE_WORKDAY
                            }
                        )
                        return@launch
                    }
                    if (intent.action == DriverWorkdayCommandAction.END) stopCapture()
                    val result = when (intent.action) {
                        DriverWorkdayCommandAction.START -> gateway.start(
                            currentAuthority,
                            intent.idempotencyKey
                        )

                        DriverWorkdayCommandAction.END -> gateway.end(
                            currentAuthority,
                            intent.workdayId!!,
                            intent.expectedVersion!!,
                            intent.idempotencyKey
                        )

                        DriverWorkdayCommandAction.SET_LOCATION_AVAILABILITY ->
                            gateway.setLocationAvailability(
                                currentAuthority,
                                intent.workdayId!!,
                                intent.expectedVersion!!,
                                intent.locationAvailable!!,
                                intent.idempotencyKey
                            )
                    }
                    if (!isCurrent(currentAuthority, requestGeneration)) return@launch
                    if (intent.action == DriverWorkdayCommandAction.END) {
                        if (result == DriverWorkdayCommandResult.Accepted) clearPending(intent)
                        mutableState.value = mutableState.value.copy(
                            workday = current.workday,
                            commandPending = false,
                            notice = if (!commandStoreAvailable &&
                                mutableState.value.pendingCommand != null
                            ) {
                                DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                            } else {
                                when (result) {
                                    DriverWorkdayCommandResult.Accepted ->
                                        DriverWorkdayNotice.END_PENDING_CONFIRMATION

                                    DriverWorkdayCommandResult.UnknownOutcome ->
                                        DriverWorkdayNotice.UNKNOWN_COMMAND_PENDING

                                    DriverWorkdayCommandResult.StaleVersion ->
                                        DriverWorkdayNotice.STALE_WORKDAY

                                    else -> DriverWorkdayNotice.COMMAND_REJECTED
                                }
                            }
                        )
                        return@launch
                    }
                    val refreshed = gateway.current(currentAuthority)
                    if (!isCurrent(currentAuthority, requestGeneration)) return@launch
                    when (refreshed) {
                        is DriverWorkdayReadResult.Current -> {
                            if (intentSatisfied(intent, refreshed.workday) ||
                                result != DriverWorkdayCommandResult.UnknownOutcome
                            ) {
                                clearPending(intent)
                            }
                            mutableState.value = mutableState.value.copy(
                                workday = refreshed.workday,
                                commandPending = false,
                                notice = if (!commandStoreAvailable &&
                                    mutableState.value.pendingCommand != null
                                ) {
                                    DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE
                                } else if (mutableState.value.pendingCommand != null) {
                                    DriverWorkdayNotice.UNKNOWN_COMMAND_PENDING
                                } else {
                                    DriverWorkdayNotice.NONE
                                }
                            )
                            if (mutableState.value.pendingCommand ==
                                null
                            ) {
                                reconcileCapture(currentAuthority, refreshed.workday)
                            } else {
                                stopCapture()
                            }
                        }

                        else -> {
                            stopCapture()
                            mutableState.value = mutableState.value.copy(
                                workday = null,
                                commandPending = false,
                                notice = DriverWorkdayNotice.CURRENT_UNAVAILABLE
                            )
                        }
                    }
                }

                else -> {
                    stopCapture()
                    mutableState.value = mutableState.value.copy(
                        commandPending = false,
                        notice = if (current == DriverWorkdayReadResult.PermissionDenied) {
                            DriverWorkdayNotice.PERMISSION_DENIED
                        } else {
                            DriverWorkdayNotice.CURRENT_UNAVAILABLE
                        }
                    )
                }
            }
        }
    }

    fun invalidateIfAuthorityChanged(identity: DriverDeliveryAuthority?) {
        val active = authority ?: return
        if (identity == null || !active.sameContext(identity)) invalidate()
    }

    fun hasAcknowledgedLocationFor(currentAuthority: DriverDeliveryAuthority): Boolean {
        val current = mutableState.value
        val day = current.workday ?: return false
        return authority == currentAuthority && current.locationReady && current.captureRequested &&
            capturingWorkdayId == day.id && day.status == DriverWorkdayStatus.ACTIVE &&
            day.locationAvailable && hasCurrentLocationConsent() &&
            current.lastSampleAt?.let(::isFreshLocationTimestamp) == true
    }

    fun invalidate() = invalidate(DriverWorkdayNotice.NONE)

    private fun invalidate(notice: DriverWorkdayNotice) {
        operationGeneration += 1
        availabilityChangeInFlight = null
        stopCapture()
        eligibleDelivery = null
        clearLocationConsent()
        authority = null
        mutableState.value = DriverWorkdayUiState(notice = notice)
    }

    private suspend fun reconcileCapture(
        currentAuthority: DriverDeliveryAuthority,
        day: DriverWorkday?
    ) {
        if (day == null || day.status != DriverWorkdayStatus.ACTIVE || !day.locationAvailable ||
            !fineLocationPermission || !hasCurrentLocationConsent()
        ) {
            stopCapture()
            clearLocationSample()
            if (day != null && day.status == DriverWorkdayStatus.ACTIVE && day.locationAvailable &&
                (!fineLocationPermission || !hasCurrentLocationConsent())
            ) {
                if (!fineLocationPermission) {
                    clearLocationConsent()
                    mutableState.value = mutableState.value.copy(
                        notice = DriverWorkdayNotice.LOCATION_PERMISSION_REQUIRED,
                        liveLocation = null
                    )
                } else {
                    clearLocationConsent()
                    mutableState.value = mutableState.value.copy(liveLocation = null)
                }
                if (!currentAuthority.canWriteWorkday()) {
                    mutableState.value = mutableState.value.copy(
                        notice = DriverWorkdayNotice.PERMISSION_DENIED
                    )
                    return
                }
                changeLocationAvailability(
                    available = false,
                    unavailableNotice = if (!fineLocationPermission) {
                        DriverWorkdayNotice.LOCATION_PERMISSION_REQUIRED
                    } else {
                        DriverWorkdayNotice.ACTIVE_DELIVERY_REQUIRED
                    }
                )
            }
            return
        }
        if (!currentAuthority.canWriteWorkday()) {
            stopCapture()
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.PERMISSION_DENIED)
            return
        }
        val delivery = eligibleDelivery ?: return
        if (capturingWorkdayId == day.id && captureJob?.isActive == true) {
            startLocationFreshnessMonitor(currentAuthority, day.id)
            return
        }
        stopCapture(stopService = false)
        captureJob = viewModelScope.launch {
            capture.events.collect { event ->
                when (event) {
                    is DriverWorkdayLocationEvent.Sample -> {
                        val delivery = eligibleDelivery
                        val location = event.value
                        if (event.captureId != day.id || delivery == null ||
                            !isLocationCaptureAuthorized(currentAuthority, day.id, delivery) ||
                            !location.isValidFor(delivery.attemptStartedAt)
                        ) {
                            return@collect
                        }

                        val sent = gateway.reportLocation(currentAuthority, day.id, location)
                        if (sent is DriverWorkdayCommandResult.Accepted) {
                            if (!isLocationCaptureAuthorized(currentAuthority, day.id, delivery)) {
                                return@collect
                            }
                            acknowledgedWorkdayId = day.id
                            val currentLocation = gateway.liveLocation(currentAuthority)
                            if (!isLocationCaptureAuthorized(currentAuthority, day.id, delivery)) {
                                return@collect
                            }
                            val liveLocation = when (currentLocation) {
                                is DriverWorkdayLocationReadResult.Current ->
                                    currentLocation.location?.takeIf(::isFreshLiveLocation)

                                DriverWorkdayLocationReadResult.Unavailable -> null

                                DriverWorkdayLocationReadResult.PermissionDenied -> {
                                    clearLocationConsent()
                                    stopCapture()
                                    clearLocationSample()
                                    changeLocationAvailability(
                                        available = false,
                                        unavailableNotice = DriverWorkdayNotice.PERMISSION_DENIED
                                    )
                                    return@collect
                                }

                                DriverWorkdayLocationReadResult.ContextInvalidated -> {
                                    invalidate(DriverWorkdayNotice.CONTEXT_INVALIDATED)
                                    return@collect
                                }

                                DriverWorkdayLocationReadResult.SessionInvalidated -> {
                                    invalidate(DriverWorkdayNotice.SESSION_INVALIDATED)
                                    return@collect
                                }
                            }
                            mutableState.value = mutableState.value.copy(
                                lastSampleAt = location.capturedAt,
                                liveLocation = liveLocation,
                                locationReady = isFreshLocationTimestamp(location.capturedAt)
                            )
                        } else if (sent == DriverWorkdayCommandResult.ContextInvalidated ||
                            sent == DriverWorkdayCommandResult.SessionInvalidated
                        ) {
                            invalidate(
                                if (sent == DriverWorkdayCommandResult.ContextInvalidated) {
                                    DriverWorkdayNotice.CONTEXT_INVALIDATED
                                } else {
                                    DriverWorkdayNotice.SESSION_INVALIDATED
                                }
                            )
                        } else {
                            // Stop after a failed or ambiguous write; only a fresh GET may restart capture.
                            clearLocationConsent()
                            stopCapture()
                            clearLocationSample()
                            changeLocationAvailability(
                                available = false,
                                unavailableNotice = if (
                                    sent == DriverWorkdayCommandResult.PermissionDenied
                                ) {
                                    DriverWorkdayNotice.PERMISSION_DENIED
                                } else {
                                    DriverWorkdayNotice.LOCATION_UNAVAILABLE
                                }
                            )
                        }
                    }

                    is DriverWorkdayLocationEvent.PermissionUnavailable,
                    is DriverWorkdayLocationEvent.ProviderUnavailable -> {
                        if (event.captureId == day.id) {
                            onLocationSourceUnavailable(currentAuthority, day)
                        }
                    }

                    is DriverWorkdayLocationEvent.NotificationUnavailable -> {
                        if (event.captureId == day.id) {
                            onLocationSourceUnavailable(
                                currentAuthority,
                                day,
                                DriverWorkdayNotice.NOTIFICATION_PERMISSION_REQUIRED
                            )
                        }
                    }

                    is DriverWorkdayLocationEvent.UserStopped -> {
                        if (event.captureId == day.id) disableLocationSharing()
                    }
                }
            }
        }
        capturingWorkdayId = day.id
        val requested = capture.start(day.id)
        if (!requested) capturingWorkdayId = null
        mutableState.value = mutableState.value.copy(
            captureRequested = requested,
            locationReady = requested && acknowledgedWorkdayId == day.id &&
                mutableState.value.lastSampleAt?.let(::isFreshLocationTimestamp) == true,
            notice = if (requested) {
                DriverWorkdayNotice.NONE
            } else {
                DriverWorkdayNotice.LOCATION_UNAVAILABLE
            }
        )
        if (requested) {
            startLocationFreshnessMonitor(currentAuthority, day.id)
            startDeliveryEligibilityMonitor(currentAuthority, day.id, delivery)
        } else {
            onLocationSourceUnavailable(currentAuthority, day)
        }
    }

    private fun onLocationSourceUnavailable(
        currentAuthority: DriverDeliveryAuthority,
        day: DriverWorkday,
        notice: DriverWorkdayNotice = DriverWorkdayNotice.LOCATION_UNAVAILABLE
    ) {
        if (authority != currentAuthority || mutableState.value.workday?.id != day.id) return
        stopCapture()
        if (day.locationAvailable && day.status == DriverWorkdayStatus.ACTIVE) {
            clearLocationConsent()
            clearLocationSample()
            changeLocationAvailability(
                available = false,
                unavailableNotice = notice
            )
        } else {
            mutableState.value = mutableState.value.copy(
                captureRequested = false,
                locationReady = false,
                liveLocation = null,
                lastSampleAt = null,
                notice = notice
            )
        }
    }

    private fun stopCapture(stopService: Boolean = true) {
        captureJob?.cancel()
        captureJob = null
        locationFreshnessJob?.cancel()
        locationFreshnessJob = null
        deliveryEligibilityJob?.cancel()
        deliveryEligibilityJob = null
        capturingWorkdayId = null
        acknowledgedWorkdayId = null
        if (stopService) capture.stop()
        if (mutableState.value.captureRequested || mutableState.value.locationReady) {
            mutableState.value = mutableState.value.copy(
                captureRequested = false,
                locationReady = false
            )
        }
    }

    private fun startDeliveryEligibilityMonitor(
        currentAuthority: DriverDeliveryAuthority,
        workdayId: String,
        delivery: EligibleDelivery
    ) {
        val currentGateway = deliveryGateway ?: return
        if (deliveryEligibilityJob?.isActive == true) return
        deliveryEligibilityJob = viewModelScope.launch {
            while (true) {
                delay(DELIVERY_ELIGIBILITY_CHECK_INTERVAL_MILLIS)
                if (!isLocationCaptureAuthorized(currentAuthority, workdayId, delivery)) {
                    return@launch
                }
                when (val result = currentGateway.delivery(delivery.id, currentAuthority)) {
                    is DriverDeliveryLoadResult.DetailLoaded -> {
                        if (!isLocationCaptureAuthorized(currentAuthority, workdayId, delivery)) {
                            return@launch
                        }
                        val snapshot = result.item
                        val attempt = snapshot.activeAttempt
                        val stillEligible = snapshot.id == delivery.id &&
                            snapshot.status in ACTIVE_LOCATION_DELIVERY_STATUSES &&
                            attempt?.id == delivery.attemptId &&
                            attempt?.startedAt?.let {
                                runCatching { Instant.parse(it) }.getOrNull()
                            } == delivery.attemptStartedAt
                        if (!stillEligible) {
                            eligibleDelivery = null
                            mutableState.value = mutableState.value.copy(
                                eligibleDeliveryId = null
                            )
                            clearLocationConsent()
                            stopCapture()
                            clearLocationSample()
                            changeLocationAvailability(
                                available = false,
                                unavailableNotice = DriverWorkdayNotice.ACTIVE_DELIVERY_REQUIRED
                            )
                            return@launch
                        }
                    }

                    DriverDeliveryLoadResult.ContextInvalidated -> {
                        invalidate(DriverWorkdayNotice.CONTEXT_INVALIDATED)
                        return@launch
                    }

                    DriverDeliveryLoadResult.SessionInvalidated -> {
                        invalidate(DriverWorkdayNotice.SESSION_INVALIDATED)
                        return@launch
                    }

                    DriverDeliveryLoadResult.PermissionDenied -> {
                        clearLocationConsent()
                        stopCapture()
                        clearLocationSample()
                        changeLocationAvailability(
                            available = false,
                            unavailableNotice = DriverWorkdayNotice.PERMISSION_DENIED
                        )
                        return@launch
                    }

                    else -> {
                        clearLocationConsent()
                        stopCapture()
                        clearLocationSample()
                        changeLocationAvailability(
                            available = false,
                            unavailableNotice = DriverWorkdayNotice.LOCATION_UNAVAILABLE
                        )
                        return@launch
                    }
                }
            }
        }
    }

    private fun startLocationFreshnessMonitor(
        currentAuthority: DriverDeliveryAuthority,
        workdayId: String
    ) {
        if (locationFreshnessJob?.isActive == true) return
        locationFreshnessJob = viewModelScope.launch {
            while (true) {
                delay(LOCATION_FRESHNESS_CHECK_INTERVAL_MILLIS)
                val current = mutableState.value
                val ready = authority == currentAuthority &&
                    current.workday?.id == workdayId &&
                    current.workday.status == DriverWorkdayStatus.ACTIVE &&
                    current.workday.locationAvailable &&
                    current.captureRequested &&
                    capturingWorkdayId == workdayId &&
                    acknowledgedWorkdayId == workdayId &&
                    current.lastSampleAt?.let(::isFreshLocationTimestamp) == true
                val freshMapLocation = current.liveLocation?.takeIf(::isFreshLiveLocation)
                if (current.locationReady != ready || current.liveLocation != freshMapLocation) {
                    mutableState.value = current.copy(
                        locationReady = ready,
                        liveLocation = freshMapLocation
                    )
                }
            }
        }
    }

    private fun isFreshLocationTimestamp(timestamp: String): Boolean {
        val capturedAtMillis = runCatching { Instant.parse(timestamp).toEpochMilli() }
            .getOrNull() ?: return false
        val ageMillis = nowInstant().toEpochMilli() - capturedAtMillis
        return ageMillis in 0..MAX_LOCATION_SAMPLE_AGE_MILLIS
    }

    private fun isFreshLiveLocation(location: DriverLiveLocation): Boolean {
        val capturedAt = runCatching { Instant.parse(location.capturedAt) }.getOrNull()
            ?: return false
        val expiresAt = runCatching { Instant.parse(location.expiresAt) }.getOrNull()
            ?: return false
        val now = nowInstant()
        val ageMillis = runCatching { Duration.between(capturedAt, now).toMillis() }.getOrNull()
            ?: return false
        return location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.accuracyMeters.isFinite() && location.accuracyMeters >= 0.0 &&
            !capturedAt.isAfter(now) && expiresAt.isAfter(now) &&
            ageMillis in 0..MAX_LOCATION_SAMPLE_AGE_MILLIS
    }

    private fun DriverWorkdayLocationSample.isAtOrAfter(attemptStartedAt: Instant): Boolean =
        runCatching { Instant.parse(capturedAt) }.getOrNull()?.let { it >= attemptStartedAt } ==
            true

    private fun DriverWorkdayLocationSample.isValidFor(attemptStartedAt: Instant): Boolean =
        sampleId.isNotBlank() &&
            latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            accuracyMeters.isFinite() && accuracyMeters >= 0.0 &&
            isFreshLocationTimestamp(capturedAt) && isAtOrAfter(attemptStartedAt)

    private fun isLocationCaptureAuthorized(
        currentAuthority: DriverDeliveryAuthority,
        workdayId: String,
        delivery: EligibleDelivery
    ): Boolean {
        val current = mutableState.value
        val day = current.workday
        return authority == currentAuthority && currentAuthority.canWriteWorkday() &&
            fineLocationPermission && eligibleDelivery == delivery &&
            consentedDelivery == delivery.consent && capturingWorkdayId == workdayId &&
            current.captureRequested && !current.commandPending && current.pendingCommand == null &&
            day?.id == workdayId && day.status == DriverWorkdayStatus.ACTIVE &&
            day.locationAvailable
    }

    private fun hasCurrentLocationConsent(): Boolean =
        eligibleDelivery?.let { consentedDelivery == it.consent } == true

    private fun clearLocationConsent() {
        consentedDelivery = null
    }

    private fun clearLocationSample() {
        mutableState.value = mutableState.value.copy(
            liveLocation = null,
            lastSampleAt = null,
            locationReady = false
        )
    }

    private fun nowInstant(): Instant =
        runCatching { Instant.parse(clock()) }.getOrElse { Instant.now() }

    private fun failRead(notice: DriverWorkdayNotice) {
        stopCapture()
        mutableState.value =
            mutableState.value.copy(loading = false, workday = null, notice = notice)
    }

    private fun failCommand(notice: DriverWorkdayNotice) {
        availabilityChangeInFlight = null
        mutableState.value = mutableState.value.copy(commandPending = false, notice = notice)
    }

    private fun isCurrent(currentAuthority: DriverDeliveryAuthority, generation: Long): Boolean =
        authority == currentAuthority && operationGeneration == generation

    private fun intentSatisfied(intent: DriverWorkdayCommandIntent, day: DriverWorkday?): Boolean =
        when (intent.action) {
            DriverWorkdayCommandAction.START ->
                day != null &&
                    day.status != DriverWorkdayStatus.CLOSED

            DriverWorkdayCommandAction.END ->
                day == null ||
                    day.status == DriverWorkdayStatus.CLOSED

            DriverWorkdayCommandAction.SET_LOCATION_AVAILABILITY ->
                day != null && day.id == intent.workdayId &&
                    day.locationAvailable == intent.locationAvailable
        }

    private suspend fun clearPending(intent: DriverWorkdayCommandIntent) {
        if (commandStore.clear(intent.scope, intent.idempotencyKey)) {
            if (mutableState.value.pendingCommand?.idempotencyKey == intent.idempotencyKey) {
                mutableState.value = mutableState.value.copy(pendingCommand = null)
            }
        } else {
            commandStoreAvailable = false
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.COMMAND_STORAGE_UNAVAILABLE)
        }
    }

    private fun DriverWorkdayCommandResult.noticeForStart(): DriverWorkdayNotice = when (this) {
        DriverWorkdayCommandResult.Accepted -> DriverWorkdayNotice.NONE
        DriverWorkdayCommandResult.UnknownOutcome -> DriverWorkdayNotice.START_UNKNOWN
        DriverWorkdayCommandResult.PermissionDenied -> DriverWorkdayNotice.PERMISSION_DENIED
        DriverWorkdayCommandResult.StaleVersion -> DriverWorkdayNotice.STALE_WORKDAY
        DriverWorkdayCommandResult.ContextInvalidated -> DriverWorkdayNotice.CONTEXT_INVALIDATED
        DriverWorkdayCommandResult.SessionInvalidated -> DriverWorkdayNotice.SESSION_INVALIDATED
        else -> DriverWorkdayNotice.START_REJECTED
    }

    private fun DriverDeliveryAuthority.sameContext(other: DriverDeliveryAuthority): Boolean =
        userId == other.userId && tenantId == other.tenantId && workspaceId == other.workspaceId &&
            membershipId == other.membershipId && permissions == other.permissions &&
            authorityEpoch == other.authorityEpoch

    private fun DriverDeliveryAuthority.canWriteWorkday(): Boolean =
        "dispatch.start_route" in permissions

    private fun DriverDeliveryAuthority.commandScope() = DriverWorkdayCommandScope(
        userId,
        tenantId,
        workspaceId,
        membershipId
    )

    override fun onCleared() {
        stopCapture()
    }
}
