package com.nexa.mobile.operations.feature.dispatch.application

import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionAssigneesResult
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionAuthority
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionCommand
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionIntent
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionsGatewayResult

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
