package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import java.time.Instant

data class DispatchAuthorityIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>
) {
    override fun toString(): String = "DispatchAuthorityIdentity(REDACTED)"
}

/** Full current scope captured from the verified session for each protected read. */

data class DispatchAuthorityContext(
    val authorityEpoch: Long,
    val identity: DispatchAuthorityIdentity?
) {
    override fun toString(): String = "DispatchAuthorityContext(authorityEpoch=$authorityEpoch, " +
        "identity=${identity != null})"
}

sealed interface DispatchReadinessGatewayResult {
    data class ListResult(val items: List<DispatchReadiness>, val asOf: Instant) :
        DispatchReadinessGatewayResult

    data class Detail(val item: DispatchReadiness) : DispatchReadinessGatewayResult
    data object NetworkUnavailable : DispatchReadinessGatewayResult
    data object ServiceUnavailable : DispatchReadinessGatewayResult
    data object PermissionDenied : DispatchReadinessGatewayResult
    data object ContextInvalidated : DispatchReadinessGatewayResult
    data object SessionInvalidated : DispatchReadinessGatewayResult
}
