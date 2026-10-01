package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DispatchAssignmentNetworkOutcome
import com.nexa.mobile.operations.core.network.DispatchDriverAssignmentProjection
import com.nexa.mobile.operations.core.network.DispatchDriverAssignmentRequest
import com.nexa.mobile.operations.core.network.DispatchReadinessNetworkOutcome
import com.nexa.mobile.operations.core.network.DispatchReadinessProjection
import com.nexa.mobile.operations.core.network.NexaDispatchAssignmentGateway
import com.nexa.mobile.operations.core.network.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentGatewayResult
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentSnapshot
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchDriverCandidate
import com.nexa.mobile.operations.feature.dispatch.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessGatewayResult
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessLine
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessViewModel
import com.nexa.mobile.operations.feature.dispatch.PreparedFulfillmentDriverAssignment
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

    @Provides
    @Singleton
    fun dispatchAssignmentNetworkGateway(
        protectedCalls: ProtectedCallExecutor
    ): NexaDispatchAssignmentGateway = NexaDispatchAssignmentGateway(protectedCalls)
}

/** Current dispatch assignment gateway, fenced to the verified context and session lease. */
@Singleton
internal class OperationsDispatchAssignmentGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val assignments: NexaDispatchAssignmentGateway,
    private val readiness: NexaDispatchReadinessGateway
) : DispatchAssignmentGateway {
    override suspend fun load(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchAssignmentGatewayResult {
        val authorization = authorize(context, requireAssignmentPermission = false)
        if (authorization !is Authorization.Current) return authorization.toResult()

        val currentReadiness = when (val result = readiness.detail(fulfillmentId)) {
            is DispatchReadinessNetworkOutcome.Detail -> toFeature(result.item)
            else -> return result.toReadFailure()
        }
        if (currentReadiness.fulfillmentId != fulfillmentId) {
            return DispatchAssignmentGatewayResult.ServiceUnavailable
        }
        val candidates = when (val result = assignments.candidates()) {
            is DispatchAssignmentNetworkOutcome.Candidates -> result.items.map {
                DispatchDriverCandidate(it.membershipId, it.email, it.displayName)
            }

            else -> return result.toAssignmentFailure(mutation = false)
        }
        val currentAssignment = when (val result = assignments.current(fulfillmentId)) {
            is DispatchAssignmentNetworkOutcome.Current -> result.item?.toFeature()
            else -> return result.toAssignmentFailure(mutation = false)
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return DispatchAssignmentGatewayResult.Snapshot(
            DispatchAssignmentSnapshot(currentReadiness, candidates, currentAssignment)
        )
    }

    override suspend fun assign(
        fulfillment: DispatchReadiness,
        responsibleMembershipId: String,
        context: DispatchAuthorityContext,
        idempotencyKey: String
    ): DispatchAssignmentGatewayResult {
        val authorization = authorize(context, requireAssignmentPermission = true)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val current = when (val result = readiness.detail(fulfillment.fulfillmentId)) {
            is DispatchReadinessNetworkOutcome.Detail -> toFeature(result.item)
            else -> return result.toReadFailure()
        }
        if (!current.matches(fulfillment)) return DispatchAssignmentGatewayResult.Stale
        if (!current.ready || current.fulfillmentStatus != "READY_FOR_DISPATCH") {
            return DispatchAssignmentGatewayResult.NotReady
        }
        val candidates = when (val result = assignments.candidates()) {
            is DispatchAssignmentNetworkOutcome.Candidates -> result.items
            else -> return result.toAssignmentFailure(mutation = false)
        }
        if (candidates.none { it.membershipId == responsibleMembershipId }) {
            return DispatchAssignmentGatewayResult.Conflict
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        val result = assignments.assign(
            DispatchDriverAssignmentRequest(
                fulfillmentId = current.fulfillmentId,
                expectedFulfillmentVersion = current.fulfillmentVersion,
                physicalAllocationId = current.physicalAllocationId,
                physicalAllocationVersion = current.physicalAllocationVersion,
                responsibleMembershipId = responsibleMembershipId
            ),
            idempotencyKey
        )
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return when (result) {
            is DispatchAssignmentNetworkOutcome.Assigned ->
                DispatchAssignmentGatewayResult.Assigned(result.item.toFeature())

            else -> result.toAssignmentFailure(mutation = true)
        }
    }

    override suspend fun replay(
        intent: DispatchAssignmentIntent,
        context: DispatchAuthorityContext
    ): DispatchAssignmentGatewayResult {
        val authorization = authorize(context, requireAssignmentPermission = true)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val identity = context.identity ?: return DispatchAssignmentGatewayResult.ContextInvalidated
        if (intent.scope != DispatchAssignmentScopeIdentity(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            )
        ) {
            return DispatchAssignmentGatewayResult.ContextInvalidated
        }

        val current = when (val result = readiness.detail(intent.fulfillmentId)) {
            is DispatchReadinessNetworkOutcome.Detail -> toFeature(result.item)
            else -> return result.toReadFailure()
        }
        if (current.fulfillmentId != intent.fulfillmentId ||
            current.physicalAllocationId != intent.physicalAllocationId ||
            current.physicalAllocationVersion != intent.physicalAllocationVersion
        ) {
            return DispatchAssignmentGatewayResult.Stale
        }
        val candidates = when (val result = assignments.candidates()) {
            is DispatchAssignmentNetworkOutcome.Candidates -> result.items
            else -> return result.toAssignmentFailure(mutation = false)
        }
        if (candidates.none { it.membershipId == intent.responsibleMembershipId }) {
            return DispatchAssignmentGatewayResult.Conflict
        }

        when (current.fulfillmentVersion) {
            intent.expectedFulfillmentVersion -> {
                if (!current.ready || current.fulfillmentStatus != "READY_FOR_DISPATCH") {
                    return DispatchAssignmentGatewayResult.NotReady
                }
                when (val result = assignments.current(intent.fulfillmentId)) {
                    is DispatchAssignmentNetworkOutcome.Current -> if (result.item != null) {
                        return DispatchAssignmentGatewayResult.Stale
                    }

                    else -> return result.toAssignmentFailure(mutation = false)
                }
            }

            intent.expectedFulfillmentVersion + 1 -> {
                val assigned = when (val result = assignments.current(intent.fulfillmentId)) {
                    is DispatchAssignmentNetworkOutcome.Current -> result.item
                    else -> return result.toAssignmentFailure(mutation = false)
                } ?: return DispatchAssignmentGatewayResult.Stale
                if (assigned.responsibleMembershipId != intent.responsibleMembershipId ||
                    assigned.physicalAllocationId != intent.physicalAllocationId ||
                    assigned.physicalAllocationVersion != intent.physicalAllocationVersion ||
                    assigned.fulfillmentVersion != current.fulfillmentVersion
                ) {
                    return DispatchAssignmentGatewayResult.Stale
                }
            }

            else -> return DispatchAssignmentGatewayResult.Stale
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        val result = assignments.assign(
            DispatchDriverAssignmentRequest(
                fulfillmentId = intent.fulfillmentId,
                expectedFulfillmentVersion = intent.expectedFulfillmentVersion,
                physicalAllocationId = intent.physicalAllocationId,
                physicalAllocationVersion = intent.physicalAllocationVersion,
                responsibleMembershipId = intent.responsibleMembershipId
            ),
            intent.idempotencyKey
        )
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return when (result) {
            is DispatchAssignmentNetworkOutcome.Assigned ->
                DispatchAssignmentGatewayResult.Assigned(result.item.toFeature())

            else -> result.toAssignmentFailure(mutation = true)
        }
    }

    private suspend fun authorize(
        context: DispatchAuthorityContext,
        requireAssignmentPermission: Boolean
    ): Authorization {
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

            DISPATCH_READ_PERMISSION !in identity.permissions ->
                Authorization.PermissionDenied

            requireAssignmentPermission &&
                identity.permissions.none { it in DISPATCH_ASSIGN_PERMISSIONS } ->
                Authorization.PermissionDenied

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
        val current = sessions.verifiedSession.value ?: return false
        return current.matches(identity) && DISPATCH_READ_PERMISSION in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active ->
            DispatchAssignmentGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { verified ->
            context.identity?.let { expected -> verified.matches(expected) } == true
        } == true -> DispatchAssignmentGatewayResult.SessionInvalidated

        else -> DispatchAssignmentGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun DispatchReadiness.matches(expected: DispatchReadiness): Boolean =
        subjectKind == expected.subjectKind && fulfillmentId == expected.fulfillmentId &&
            fulfillmentVersion == expected.fulfillmentVersion &&
            physicalAllocationId == expected.physicalAllocationId &&
            physicalAllocationVersion == expected.physicalAllocationVersion &&
            ready == expected.ready &&
            fulfillmentStatus == expected.fulfillmentStatus

    private fun DispatchDriverAssignmentProjection.toFeature() =
        PreparedFulfillmentDriverAssignment(
            id = id,
            fulfillmentId = fulfillmentId,
            fulfillmentVersion = fulfillmentVersion,
            physicalAllocationId = physicalAllocationId,
            physicalAllocationVersion = physicalAllocationVersion,
            responsibleMembershipId = responsibleMembershipId,
            responsibleDisplayName = responsibleDisplayName,
            assignedAt = assignedAt,
            deliveryId = deliveryId
        )

    private fun toFeature(item: DispatchReadinessProjection) =
        DispatchReadinessProjectionAdapter.toFeature(item)

    private fun DispatchReadinessNetworkOutcome.toReadFailure(): DispatchAssignmentGatewayResult =
        when (this) {
            DispatchReadinessNetworkOutcome.NetworkUnavailable ->
                DispatchAssignmentGatewayResult.NetworkUnavailable

            DispatchReadinessNetworkOutcome.ServiceUnavailable ->
                DispatchAssignmentGatewayResult.ServiceUnavailable

            DispatchReadinessNetworkOutcome.PermissionDenied ->
                DispatchAssignmentGatewayResult.PermissionDenied

            DispatchReadinessNetworkOutcome.ContextInvalidated ->
                DispatchAssignmentGatewayResult.ContextInvalidated

            DispatchReadinessNetworkOutcome.SessionInvalidated ->
                DispatchAssignmentGatewayResult.SessionInvalidated

            is DispatchReadinessNetworkOutcome.ListResult,
            is DispatchReadinessNetworkOutcome.Detail ->
                DispatchAssignmentGatewayResult.ServiceUnavailable
        }

    private fun DispatchAssignmentNetworkOutcome.toAssignmentFailure(
        mutation: Boolean
    ): DispatchAssignmentGatewayResult = when (this) {
        DispatchAssignmentNetworkOutcome.NetworkUnavailable ->
            DispatchAssignmentGatewayResult.NetworkUnavailable

        DispatchAssignmentNetworkOutcome.UnknownOutcome ->
            DispatchAssignmentGatewayResult.UnknownOutcome

        DispatchAssignmentNetworkOutcome.ServiceUnavailable ->
            DispatchAssignmentGatewayResult.ServiceUnavailable

        DispatchAssignmentNetworkOutcome.PermissionDenied ->
            DispatchAssignmentGatewayResult.PermissionDenied

        DispatchAssignmentNetworkOutcome.ContextInvalidated ->
            DispatchAssignmentGatewayResult.ContextInvalidated

        DispatchAssignmentNetworkOutcome.SessionInvalidated ->
            DispatchAssignmentGatewayResult.SessionInvalidated

        DispatchAssignmentNetworkOutcome.Stale -> DispatchAssignmentGatewayResult.Stale

        DispatchAssignmentNetworkOutcome.Conflict -> DispatchAssignmentGatewayResult.Conflict

        is DispatchAssignmentNetworkOutcome.Candidates,
        is DispatchAssignmentNetworkOutcome.Current,
        is DispatchAssignmentNetworkOutcome.Assigned -> if (mutation) {
            DispatchAssignmentGatewayResult.UnknownOutcome
        } else {
            DispatchAssignmentGatewayResult.ServiceUnavailable
        }
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private fun Authorization.toResult(): DispatchAssignmentGatewayResult = when (this) {
        Authorization.SessionInvalidated -> DispatchAssignmentGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispatchAssignmentGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> DispatchAssignmentGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private companion object {
        const val DISPATCH_READ_PERMISSION = "dispatch.read"
        const val LOGISTICS_READ_PERMISSION = "logistics.read"
        val DISPATCH_ASSIGN_PERMISSIONS = setOf("dispatch.assign", "logistics:write")
    }
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

internal object DispatchReadinessProjectionAdapter {
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

internal class DispatchAssignmentViewModelFactory @Inject constructor(
    private val gateway: OperationsDispatchAssignmentGateway,
    private val metadata: DispatchAssignmentMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchAssignmentViewModel::class.java))
        return DispatchAssignmentViewModel(gateway, metadata) as T
    }
}
