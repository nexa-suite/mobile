package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DispatchReadinessNetworkOutcome
import com.nexa.mobile.operations.core.network.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessGatewayResult
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessLine
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DispatchReadinessGatewayBindings {
    @Provides
    @Singleton
    fun dispatchReadinessGateway(
        protectedCalls: ProtectedCallExecutor
    ): NexaDispatchReadinessGateway = NexaDispatchReadinessGateway(protectedCalls)
}

/** Fences read results to the captured scope, permission snapshot and session lease. */
@Singleton
internal class OperationsDispatchReadinessGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val readiness: NexaDispatchReadinessGateway
) : DispatchReadinessGateway {
    override suspend fun list(context: DispatchAuthorityContext): DispatchReadinessGatewayResult {
        val authorization = authorize(context)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val result = readiness.list().toFeatureResult()
        return if (isCurrent(context, authorization.lease)) result else authorityDrift(context)
    }

    override suspend fun detail(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchReadinessGatewayResult {
        val authorization = authorize(context)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val result = readiness.detail(fulfillmentId).toFeatureResult()
        return if (isCurrent(context, authorization.lease)) result else authorityDrift(context)
    }

    private suspend fun authorize(context: DispatchAuthorityContext): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.identity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 || listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).any(String::isBlank) || !verified.matches(identity)
        ) {
            return Authorization.ContextInvalidated
        }
        return when {
            identity.permissions.isEmpty() -> Authorization.ContextInvalidated
            DISPATCH_READ_PERMISSION !in identity.permissions -> Authorization.PermissionDenied
            else -> Authorization.Current(lease)
        }
    }

    private suspend fun isCurrent(
        context: DispatchAuthorityContext,
        originalLease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        val identity = context.identity ?: return false
        return sessions.verifiedSession.value?.matches(identity) == true &&
            DISPATCH_READ_PERMISSION in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active ->
            DispatchReadinessGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { verified ->
            context.identity?.let { expected -> verified.matches(expected) } == true
        } == true ->
            DispatchReadinessGatewayResult.SessionInvalidated

        else -> DispatchReadinessGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun DispatchReadinessNetworkOutcome.toFeatureResult(): DispatchReadinessGatewayResult =
        when (this) {
            is DispatchReadinessNetworkOutcome.ListResult ->
                DispatchReadinessGatewayResult.ListResult(
                    items = items.map(DispatchReadinessProjectionAdapter::toFeature),
                    asOf = asOf
                )

            is DispatchReadinessNetworkOutcome.Detail -> DispatchReadinessGatewayResult.Detail(
                DispatchReadinessProjectionAdapter.toFeature(item)
            )

            DispatchReadinessNetworkOutcome.NetworkUnavailable ->
                DispatchReadinessGatewayResult.NetworkUnavailable

            DispatchReadinessNetworkOutcome.ServiceUnavailable ->
                DispatchReadinessGatewayResult.ServiceUnavailable

            DispatchReadinessNetworkOutcome.PermissionDenied ->
                DispatchReadinessGatewayResult.PermissionDenied

            DispatchReadinessNetworkOutcome.ContextInvalidated ->
                DispatchReadinessGatewayResult.ContextInvalidated

            DispatchReadinessNetworkOutcome.SessionInvalidated ->
                DispatchReadinessGatewayResult.SessionInvalidated
        }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private fun Authorization.toResult(): DispatchReadinessGatewayResult = when (this) {
        Authorization.SessionInvalidated -> DispatchReadinessGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispatchReadinessGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> DispatchReadinessGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private companion object {
        const val DISPATCH_READ_PERMISSION = "dispatch.read"
    }
}

private object DispatchReadinessProjectionAdapter {
    fun toFeature(item: com.nexa.mobile.operations.core.network.DispatchReadinessProjection) =
        DispatchReadiness(
            subjectKind = item.subjectKind,
            fulfillmentId = item.fulfillmentId,
            fulfillmentVersion = item.fulfillmentVersion,
            fulfillmentStatus = item.fulfillmentStatus,
            physicalAllocationId = item.physicalAllocationId,
            physicalAllocationStatus = item.physicalAllocationStatus,
            physicalAllocationVersion = item.physicalAllocationVersion,
            deliveryId = item.deliveryId,
            deliveryStatus = item.deliveryStatus,
            deliveryVersion = item.deliveryVersion,
            allocationComplete = item.allocationComplete,
            pickingComplete = item.pickingComplete,
            pickingEvidenceComplete = item.pickingEvidenceComplete,
            ready = item.ready,
            reasons = item.reasons,
            lines = item.lines.map {
                DispatchReadinessLine(
                    fulfillmentLineId = it.fulfillmentLineId,
                    skuId = it.skuId,
                    catalogItemId = it.catalogItemId,
                    allocatedQuantity = it.allocatedQuantity,
                    physicallyAllocatedQuantity = it.physicallyAllocatedQuantity,
                    pickedQuantity = it.pickedQuantity,
                    evidencedPickedQuantity = it.evidencedPickedQuantity,
                    allocationComplete = it.allocationComplete,
                    pickingComplete = it.pickingComplete,
                    evidenceComplete = it.evidenceComplete
                )
            },
            asOf = item.asOf
        )
}

internal class DispatchReadinessViewModelFactory @Inject constructor(
    private val gateway: OperationsDispatchReadinessGateway
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchReadinessViewModel::class.java))
        return DispatchReadinessViewModel(gateway) as T
    }
}
