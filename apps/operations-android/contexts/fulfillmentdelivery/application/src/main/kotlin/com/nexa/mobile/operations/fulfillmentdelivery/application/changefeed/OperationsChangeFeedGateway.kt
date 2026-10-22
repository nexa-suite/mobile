package com.nexa.mobile.operations.fulfillmentdelivery.application.changefeed

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Emits refresh signals for current logistics data; event content is never business state. */
fun interface OperationsChangeFeedGateway {
    fun relevantChangeSignals(): Flow<Unit>

    /** Hints for refreshing warehouse and zone choices; carries no business state. */
    fun receivingLookupChangeSignals(): Flow<Unit> = emptyFlow()

    /** Hints for refreshing current warehouse lot and selected availability reads. */
    fun stockConditionChangeSignals(): Flow<Unit> = emptyFlow()

    companion object {
        val None = OperationsChangeFeedGateway { emptyFlow() }
    }
}
