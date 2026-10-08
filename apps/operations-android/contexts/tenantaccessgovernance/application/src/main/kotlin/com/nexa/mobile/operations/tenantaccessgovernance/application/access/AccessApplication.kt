package com.nexa.mobile.operations.tenantaccessgovernance.application.access

import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary

sealed interface SignInResult {
    data class Authenticated(val context: WorkforceContextSummary) : SignInResult
    data object SelectionRequired : SignInResult
    data object NoWorkContext : SignInResult
    data object Rejected : SignInResult
    data object NetworkUnavailable : SignInResult
    data object ServiceUnavailable : SignInResult
    data object IntegrationUnavailable : SignInResult
}

sealed interface CurrentSessionContextResult {
    data class Available(val context: WorkforceContextSummary) : CurrentSessionContextResult
    data object Invalid : CurrentSessionContextResult
    data object Unavailable : CurrentSessionContextResult
}

sealed interface ContextListResult {
    data class Available(val contexts: List<WorkforceContextSummary>) : ContextListResult
    data object NetworkUnavailable : ContextListResult
    data object ServiceUnavailable : ContextListResult
    data object IntegrationUnavailable : ContextListResult
    data object TicketExpired : ContextListResult
    data object SessionExpired : ContextListResult
}

sealed interface ContextSelectionResult {
    data class Confirmed(val context: WorkforceContextSummary) : ContextSelectionResult
    data object Rejected : ContextSelectionResult
    data object Unavailable : ContextSelectionResult
    data object IntegrationUnavailable : ContextSelectionResult
    data object UnknownOutcome : ContextSelectionResult
    data object SessionExpired : ContextSelectionResult
}

/** Client port. Implementations must return only server-confirmed context outcomes. */

interface AccessGateway {
    suspend fun signIn(identifier: String, password: String): SignInResult
    suspend fun currentSessionContext(): CurrentSessionContextResult
    suspend fun listContexts(): ContextListResult
    suspend fun selectContext(context: WorkforceContextSummary): ContextSelectionResult
}
