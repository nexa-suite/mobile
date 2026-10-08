package com.nexa.mobile.operations.feature.dispatch.application

import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadCommand
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadCommandMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadCommandMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DeliveryLoadScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext

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
