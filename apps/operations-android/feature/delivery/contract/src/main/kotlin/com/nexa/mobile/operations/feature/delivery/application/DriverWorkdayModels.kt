package com.nexa.mobile.operations.feature.delivery.application

import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayCommandIntent
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayCommandIntentRead
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayCommandResult
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayCommandScope
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayLocationEvent
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayLocationSample
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayReadResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Small volatile handoff only; there is no replay or disk-backed coordinate queue. */
class DriverWorkdayLocationEventStream {
    private val mutableEvents = MutableSharedFlow<DriverWorkdayLocationEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: Flow<DriverWorkdayLocationEvent> = mutableEvents

    fun publish(event: DriverWorkdayLocationEvent): Boolean = mutableEvents.tryEmit(event)
}

/** The app adapter is an Android foreground location service; events are never buffered to disk. */
interface DriverWorkdayLocationCapture {
    val events: Flow<DriverWorkdayLocationEvent>
    fun start(workdayId: String): Boolean
    fun stop()
}

interface DriverWorkdayGateway {
    suspend fun current(authority: DriverDeliveryAuthority): DriverWorkdayReadResult
    suspend fun start(
        authority: DriverDeliveryAuthority,
        idempotencyKey: String
    ): DriverWorkdayCommandResult
    suspend fun end(
        authority: DriverDeliveryAuthority,
        workdayId: String,
        version: Long,
        idempotencyKey: String
    ): DriverWorkdayCommandResult
    suspend fun setLocationAvailability(
        authority: DriverDeliveryAuthority,
        workdayId: String,
        version: Long,
        locationAvailable: Boolean,
        idempotencyKey: String
    ): DriverWorkdayCommandResult
    suspend fun reportLocation(
        authority: DriverDeliveryAuthority,
        workdayId: String,
        location: DriverWorkdayLocationSample
    ): DriverWorkdayCommandResult
}

interface DriverWorkdayCommandStore {
    suspend fun load(scope: DriverWorkdayCommandScope): DriverWorkdayCommandIntentRead
    suspend fun save(intent: DriverWorkdayCommandIntent): Boolean
    suspend fun clear(scope: DriverWorkdayCommandScope, idempotencyKey: String): Boolean
}
