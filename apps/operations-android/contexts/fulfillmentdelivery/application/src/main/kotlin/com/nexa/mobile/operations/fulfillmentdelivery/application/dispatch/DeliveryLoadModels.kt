package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommandMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommandMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext

interface DeliveryLoadCommandMetadataStore {
    suspend fun load(scope: DeliveryLoadScopeIdentity): DeliveryLoadCommandMetadataRead
    suspend fun save(command: DeliveryLoadCommand): DeliveryLoadCommandMetadataWrite
    suspend fun clear(
        scope: DeliveryLoadScopeIdentity,
        idempotencyKey: String
    ): DeliveryLoadCommandMetadataWrite
}

interface DeliveryLoadGateway {
    suspend fun current(
        context: DispatchAuthorityContext,
        driver: Boolean
    ): DeliveryLoadGatewayResult
    suspend fun mutate(
        command: DeliveryLoadCommand,
        context: DispatchAuthorityContext
    ): DeliveryLoadGatewayResult
}
