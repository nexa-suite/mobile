package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandIntentRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayReadResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationEvent
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationSample
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
    suspend fun acquireFreshLocation(timeoutMillis: Long): DriverWorkdayLocationAcquisition
    fun start(workdayId: String): Boolean
    fun stop()
}

enum class DriverWorkdayLocationAcquisition {
    SAMPLE_AVAILABLE,
    PERMISSION_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    TIMED_OUT,
    SERVICE_UNAVAILABLE
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
