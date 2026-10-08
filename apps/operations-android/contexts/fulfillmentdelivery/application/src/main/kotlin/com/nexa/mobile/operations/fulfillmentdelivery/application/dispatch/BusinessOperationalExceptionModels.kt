package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAssigneesResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionsGatewayResult

interface BusinessOperationalExceptionMetadataStore {
    suspend fun load(
        scope: BusinessOperationalExceptionScopeIdentity
    ): BusinessOperationalExceptionMetadataRead
    suspend fun save(
        intent: BusinessOperationalExceptionIntent
    ): BusinessOperationalExceptionMetadataWrite
    suspend fun clear(
        scope: BusinessOperationalExceptionScopeIdentity,
        idempotencyKey: String
    ): BusinessOperationalExceptionMetadataWrite
}

interface BusinessOperationalExceptionsGateway {
    suspend fun current(
        authority: BusinessOperationalExceptionAuthority
    ): BusinessOperationalExceptionsGatewayResult
    suspend fun assignees(
        exceptionId: String,
        authority: BusinessOperationalExceptionAuthority
    ): BusinessOperationalExceptionAssigneesResult
    suspend fun mutate(
        command: BusinessOperationalExceptionCommand,
        authority: BusinessOperationalExceptionAuthority
    ): BusinessOperationalExceptionsGatewayResult
}
