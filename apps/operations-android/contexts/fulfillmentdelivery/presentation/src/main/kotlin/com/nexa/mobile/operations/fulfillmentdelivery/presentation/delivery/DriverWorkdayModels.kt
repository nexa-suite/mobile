package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationCapture
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkday
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandAction
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandIntentRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandScope
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationEvent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayReadResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class DriverWorkdayNotice {
    NONE,
    LOCATION_PERMISSION_REQUIRED,
    CURRENT_UNAVAILABLE,
    START_UNKNOWN,
    START_REJECTED,
    END_PENDING_CONFIRMATION,
    AVAILABILITY_UNKNOWN,
    LOCATION_UNAVAILABLE,
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
    val lastSampleAt: String? = null,
    val pendingCommand: DriverWorkdayCommandIntent? = null,
    val notice: DriverWorkdayNotice = DriverWorkdayNotice.NONE
)

class DriverWorkdayViewModel(
    private val gateway: DriverWorkdayGateway,
    private val capture: DriverWorkdayLocationCapture,
    private val commandStore: DriverWorkdayCommandStore,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> String = { Instant.now().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DriverWorkdayUiState())
    val state: StateFlow<DriverWorkdayUiState> = mutableState.asStateFlow()

    private var authority: DriverDeliveryAuthority? = null
    private var fineLocationPermission = false
    private var captureJob: Job? = null
    private var capturingWorkdayId: String? = null
    private var operationGeneration = 0L
    private var availabilityChangeInFlight: Boolean? = null
    private var commandStoreAvailable = true

    fun activate(authority: DriverDeliveryAuthority, hasFineLocationPermission: Boolean) {
        if (this.authority != authority) {
            operationGeneration += 1
            stopCapture()
            mutableState.value = DriverWorkdayUiState()
        }
        this.authority = authority
        fineLocationPermission = hasFineLocationPermission
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
        if (!fineLocationPermission) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.LOCATION_PERMISSION_REQUIRED)
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
                locationAvailable = true,
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
        changeLocationAvailability(true)
    }

    private fun changeLocationAvailability(available: Boolean) {
        val currentAuthority = authority ?: return
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
        if (available && !currentAuthority.canWriteWorkday()) {
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.PERMISSION_DENIED)
            return
        }
        if (day.status == DriverWorkdayStatus.CLOSED || (available && !fineLocationPermission)) {
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
        availabilityChangeInFlight = available
        val requestGeneration = ++operationGeneration
        if (!available) stopCapture()
        viewModelScope.launch {
            mutableState.value =
                mutableState.value.copy(commandPending = true, notice = DriverWorkdayNotice.NONE)
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
                                DriverWorkdayCommandResult.Accepted -> DriverWorkdayNotice.NONE

                                DriverWorkdayCommandResult.StaleVersion ->
                                    DriverWorkdayNotice.STALE_WORKDAY

                                DriverWorkdayCommandResult.UnknownOutcome ->
                                    if (mutableState.value.pendingCommand !=
                                        null
                                    ) {
                                        DriverWorkdayNotice.AVAILABILITY_UNKNOWN
                                    } else {
                                        DriverWorkdayNotice.NONE
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
        stopCapture()
        val requestGeneration = ++operationGeneration
        viewModelScope.launch {
            mutableState.value =
                mutableState.value.copy(commandPending = true, captureRequested = false)
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
            if (day != null && day.status != DriverWorkdayStatus.CLOSED && day.locationAvailable) {
                changeLocationAvailability(false)
            } else {
                stopCapture()
            }
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

    fun invalidate() = invalidate(DriverWorkdayNotice.NONE)

    private fun invalidate(notice: DriverWorkdayNotice) {
        operationGeneration += 1
        availabilityChangeInFlight = null
        stopCapture()
        authority = null
        mutableState.value = DriverWorkdayUiState(notice = notice)
    }

    private suspend fun reconcileCapture(
        currentAuthority: DriverDeliveryAuthority,
        day: DriverWorkday?
    ) {
        if (day == null || day.status != DriverWorkdayStatus.ACTIVE || !day.locationAvailable ||
            !fineLocationPermission
        ) {
            stopCapture()
            if (day != null && day.status == DriverWorkdayStatus.ACTIVE && day.locationAvailable &&
                !fineLocationPermission
            ) {
                mutableState.value =
                    mutableState.value.copy(
                        notice = DriverWorkdayNotice.LOCATION_PERMISSION_REQUIRED
                    )
                changeLocationAvailability(false)
            }
            return
        }
        if (!currentAuthority.canWriteWorkday()) {
            stopCapture()
            mutableState.value =
                mutableState.value.copy(notice = DriverWorkdayNotice.PERMISSION_DENIED)
            return
        }
        if (capturingWorkdayId == day.id && captureJob?.isActive == true) return
        stopCapture()
        captureJob = viewModelScope.launch {
            capture.events.collect { event ->
                when (event) {
                    is DriverWorkdayLocationEvent.Sample -> {
                        if (authority == currentAuthority &&
                            mutableState.value.workday?.id == day.id &&
                            mutableState.value.workday?.status == DriverWorkdayStatus.ACTIVE
                        ) {
                            val location = event.value
                            val sent = gateway.reportLocation(currentAuthority, day.id, location)
                            if (sent is DriverWorkdayCommandResult.Accepted) {
                                mutableState.value =
                                    mutableState.value.copy(lastSampleAt = location.capturedAt)
                            } else if (sent == DriverWorkdayCommandResult.ContextInvalidated ||
                                sent == DriverWorkdayCommandResult.SessionInvalidated
                            ) {
                                invalidate(
                                    if (sent ==
                                        DriverWorkdayCommandResult.ContextInvalidated
                                    ) {
                                        DriverWorkdayNotice.CONTEXT_INVALIDATED
                                    } else {
                                        DriverWorkdayNotice.SESSION_INVALIDATED
                                    }
                                )
                            } else {
                                // Stop immediately after a failed/ambiguous coordinate write. A new GET is
                                // the only path that may authorize capture again; the sample is discarded.
                                stopCapture()
                                refresh()
                            }
                        }
                    }

                    DriverWorkdayLocationEvent.PermissionUnavailable,
                    DriverWorkdayLocationEvent.ProviderUnavailable -> onLocationSourceUnavailable(
                        currentAuthority,
                        day
                    )
                }
            }
        }
        val requested = capture.start(day.id)
        capturingWorkdayId = if (requested) day.id else null
        mutableState.value = mutableState.value.copy(
            captureRequested = requested,
            notice = if (requested) {
                DriverWorkdayNotice.NONE
            } else {
                DriverWorkdayNotice.LOCATION_UNAVAILABLE
            }
        )
        if (!requested) onLocationSourceUnavailable(currentAuthority, day)
    }

    private fun onLocationSourceUnavailable(
        currentAuthority: DriverDeliveryAuthority,
        day: DriverWorkday
    ) {
        if (authority != currentAuthority || mutableState.value.workday?.id != day.id) return
        stopCapture()
        if (day.locationAvailable && day.status == DriverWorkdayStatus.ACTIVE) {
            changeLocationAvailability(false)
        } else {
            mutableState.value = mutableState.value.copy(
                captureRequested = false,
                notice = DriverWorkdayNotice.LOCATION_UNAVAILABLE
            )
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        capturingWorkdayId = null
        capture.stop()
        if (mutableState.value.captureRequested) {
            mutableState.value = mutableState.value.copy(captureRequested = false)
        }
    }

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
