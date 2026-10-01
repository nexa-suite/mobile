package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.FulfillmentTemperatureEvidenceProjection
import com.nexa.mobile.operations.core.network.FulfillmentTemperatureLotProjection
import com.nexa.mobile.operations.core.network.FulfillmentTemperatureNetworkOutcome
import com.nexa.mobile.operations.core.network.FulfillmentTemperatureReadinessProjection
import com.nexa.mobile.operations.core.network.NexaFulfillmentTemperatureGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureEvidence
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureGatewayResult
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureLot
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureReadiness
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DispatchTemperatureGatewayBindings {
    @Provides
    @Singleton
    fun dispatchTemperatureNetworkGateway(
        protectedCalls: ProtectedCallExecutor
    ): NexaFulfillmentTemperatureGateway = NexaFulfillmentTemperatureGateway(protectedCalls)

    @Provides
    @Singleton
    fun dispatchTemperatureGateway(
        operations: OperationsDispatchTemperatureGateway
    ): DispatchTemperatureGateway = operations
}

/** Checks live session identity around each protected cold-chain request. */
@Singleton
internal class OperationsDispatchTemperatureGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val temperature: NexaFulfillmentTemperatureGateway
) : DispatchTemperatureGateway {
    override suspend fun current(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchTemperatureGatewayResult {
        val authorization = authorize(context, requireWrite = false)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val result = temperature.current(fulfillmentId).toFeatureResult()
        return if (isCurrent(context, authorization.lease, requireWrite = false)) result else authorityDrift()
    }

    override suspend fun record(
        command: DispatchTemperatureCommand,
        context: DispatchAuthorityContext
    ): DispatchTemperatureGatewayResult {
        val authorization = authorize(context, requireWrite = true)
        if (authorization !is Authorization.Current) return authorization.toResult()
        if (!command.isValid()) return DispatchTemperatureGatewayResult.ServiceUnavailable
        val result = temperature.record(
            fulfillmentId = command.fulfillmentId,
            expectedFulfillmentVersion = command.expectedFulfillmentVersion,
            lotId = command.lotId,
            valueCelsius = command.valueCelsius,
            occurredAt = command.occurredAt,
            idempotencyKey = command.idempotencyKey,
            exactRequestBody = command.exactRequestBody
        ).toFeatureResult(command.fulfillmentId)
        return if (isCurrent(context, authorization.lease, requireWrite = true)) result else authorityDrift()
    }

    private suspend fun authorize(
        context: DispatchAuthorityContext,
        requireWrite: Boolean
    ): Authorization {
        if (sessions.sessionState.value != SessionState.Active) return Authorization.SessionInvalidated
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.identity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 || listOf(
                identity.userId, identity.tenantId, identity.workspaceId, identity.membershipId
            ).any(String::isBlank) || !verified.matches(identity)
        ) return Authorization.ContextInvalidated
        if (identity.permissions.isEmpty()) return Authorization.ContextInvalidated
        if (identity.permissions.none { it in FULFILLMENT_READ_PERMISSIONS } ||
            (requireWrite && identity.permissions.none { it in FULFILLMENT_MANAGE_PERMISSIONS })
        ) return Authorization.PermissionDenied
        return Authorization.Current(lease)
    }

    private suspend fun isCurrent(
        context: DispatchAuthorityContext,
        originalLease: AccessTokenLease,
        requireWrite: Boolean
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) return false
        val identity = context.identity ?: return false
        val permissions = identity.permissions
        return sessions.verifiedSession.value?.matches(identity) == true &&
            permissions.any { it in FULFILLMENT_READ_PERMISSIONS } &&
            (!requireWrite || permissions.any { it in FULFILLMENT_MANAGE_PERMISSIONS })
    }

    private suspend fun authorityDrift(): DispatchTemperatureGatewayResult =
        if (sessions.sessionState.value != SessionState.Active) {
            DispatchTemperatureGatewayResult.SessionInvalidated
        } else {
            DispatchTemperatureGatewayResult.ContextInvalidated
        }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun FulfillmentTemperatureNetworkOutcome.toFeatureResult(): DispatchTemperatureGatewayResult = when (this) {
        is FulfillmentTemperatureNetworkOutcome.Current -> readiness.toFeatureResult()
        FulfillmentTemperatureNetworkOutcome.OutsideRangeBackendContractGap ->
            DispatchTemperatureGatewayResult.OutsideRangeBackendContractGap
        FulfillmentTemperatureNetworkOutcome.UnknownOutcome -> DispatchTemperatureGatewayResult.UnknownOutcome
        FulfillmentTemperatureNetworkOutcome.NetworkUnavailable -> DispatchTemperatureGatewayResult.NetworkUnavailable
        FulfillmentTemperatureNetworkOutcome.ServiceUnavailable -> DispatchTemperatureGatewayResult.ServiceUnavailable
        FulfillmentTemperatureNetworkOutcome.PermissionDenied -> DispatchTemperatureGatewayResult.PermissionDenied
        FulfillmentTemperatureNetworkOutcome.ContextInvalidated -> DispatchTemperatureGatewayResult.ContextInvalidated
        FulfillmentTemperatureNetworkOutcome.SessionInvalidated -> DispatchTemperatureGatewayResult.SessionInvalidated
        FulfillmentTemperatureNetworkOutcome.Stale -> DispatchTemperatureGatewayResult.Stale
        FulfillmentTemperatureNetworkOutcome.Conflict -> DispatchTemperatureGatewayResult.Conflict
        is FulfillmentTemperatureNetworkOutcome.Recorded -> DispatchTemperatureGatewayResult.ServiceUnavailable
    }

    private fun FulfillmentTemperatureNetworkOutcome.toFeatureResult(
        fulfillmentId: String
    ): DispatchTemperatureGatewayResult = when (this) {
        is FulfillmentTemperatureNetworkOutcome.Recorded -> {
            val featureEvidence = evidence.toFeature(fulfillmentId)
            if (featureEvidence == null) DispatchTemperatureGatewayResult.UnknownOutcome
            else DispatchTemperatureGatewayResult.Recorded(featureEvidence)
        }
        FulfillmentTemperatureNetworkOutcome.OutsideRangeBackendContractGap ->
            DispatchTemperatureGatewayResult.OutsideRangeBackendContractGap
        FulfillmentTemperatureNetworkOutcome.UnknownOutcome -> DispatchTemperatureGatewayResult.UnknownOutcome
        FulfillmentTemperatureNetworkOutcome.NetworkUnavailable -> DispatchTemperatureGatewayResult.NetworkUnavailable
        FulfillmentTemperatureNetworkOutcome.ServiceUnavailable -> DispatchTemperatureGatewayResult.ServiceUnavailable
        FulfillmentTemperatureNetworkOutcome.PermissionDenied -> DispatchTemperatureGatewayResult.PermissionDenied
        FulfillmentTemperatureNetworkOutcome.ContextInvalidated -> DispatchTemperatureGatewayResult.ContextInvalidated
        FulfillmentTemperatureNetworkOutcome.SessionInvalidated -> DispatchTemperatureGatewayResult.SessionInvalidated
        FulfillmentTemperatureNetworkOutcome.Stale -> DispatchTemperatureGatewayResult.Stale
        FulfillmentTemperatureNetworkOutcome.Conflict -> DispatchTemperatureGatewayResult.Conflict
        is FulfillmentTemperatureNetworkOutcome.Current -> DispatchTemperatureGatewayResult.ServiceUnavailable
    }

    private fun FulfillmentTemperatureReadinessProjection.toFeatureResult(): DispatchTemperatureGatewayResult {
        val lots = mutableListOf<DispatchTemperatureLot>()
        for (lot in this.lots) {
            val evidence = lot.latestEvidence?.toFeature(fulfillmentId) ?: if (lot.latestEvidence == null) null
            else return DispatchTemperatureGatewayResult.ServiceUnavailable
            lots += lot.toFeature(evidence)
        }
        return DispatchTemperatureGatewayResult.Current(
            DispatchTemperatureReadiness(
                fulfillmentId,
                fulfillmentStatus,
                fulfillmentVersion,
                physicalAllocationId,
                physicalAllocationVersion,
                temperatureRequiredForFulfillment,
                asOf,
                lots
            )
        )
    }

    private fun FulfillmentTemperatureLotProjection.toFeature(
        evidence: DispatchTemperatureEvidence?
    ) = DispatchTemperatureLot(
        skuId,
        lotId,
        warehouseId,
        zoneId,
        skuColdChainRequired,
        requiredForFulfillment,
        minimumCelsius,
        maximumCelsius,
        status,
        evidence
    )

    private fun FulfillmentTemperatureEvidenceProjection.toFeature(
        fulfillmentId: String
    ): DispatchTemperatureEvidence? {
        val version = fulfillmentVersion ?: return null
        if (subjectType != "FULFILLMENT" || !subjectId.equals(fulfillmentId, ignoreCase = true) ||
            unit != "CELSIUS"
        ) return null
        return DispatchTemperatureEvidence(
            id,
            fulfillmentId,
            version,
            lotId,
            value,
            occurredAt,
            actorMembershipId,
            status
        )
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private fun Authorization.toResult(): DispatchTemperatureGatewayResult = when (this) {
        Authorization.SessionInvalidated -> DispatchTemperatureGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispatchTemperatureGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> DispatchTemperatureGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private companion object {
        val FULFILLMENT_READ_PERMISSIONS = setOf("fulfillment.read", "fulfillment:read")
        val FULFILLMENT_MANAGE_PERMISSIONS = setOf("fulfillment.manage", "warehouse:write")
    }
}

internal class DispatchTemperatureViewModelFactory @Inject constructor(
    private val gateway: DispatchTemperatureGateway,
    private val metadata: DispatchTemperatureMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchTemperatureViewModel::class.java))
        return DispatchTemperatureViewModel(gateway, metadata) as T
    }
}
