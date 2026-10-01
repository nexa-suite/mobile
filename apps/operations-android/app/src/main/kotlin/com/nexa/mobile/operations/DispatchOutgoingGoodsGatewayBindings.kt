package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.core.network.NexaOutgoingGoodsCheckGateway
import com.nexa.mobile.operations.core.network.OutgoingGoodsCheckCommand
import com.nexa.mobile.operations.core.network.OutgoingGoodsCheckNetworkOutcome
import com.nexa.mobile.operations.core.network.OutgoingGoodsDiscrepancyResolutionCommand
import com.nexa.mobile.operations.core.network.OutgoingGoodsObservation
import com.nexa.mobile.operations.core.network.PhysicalAllocationProjection
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.DispatchReadinessNetworkOutcome
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsAllocation
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCheck
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCheckLine
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsDiscrepancy
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsGatewayResult
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsLine
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsObservation
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsResolution
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsSnapshot
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchReadiness
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DispatchOutgoingGoodsGatewayBindings {
    @Provides
    @Singleton
    fun outgoingGoodsNetworkGateway(
        protectedCalls: ProtectedCallExecutor
    ): NexaOutgoingGoodsCheckGateway = NexaOutgoingGoodsCheckGateway(protectedCalls)

    @Provides
    @Singleton
    fun dispatchOutgoingGoodsGateway(
        operations: OperationsDispatchOutgoingGoodsGateway
    ): DispatchOutgoingGoodsGateway = operations
}

/** Fences current allocation and outgoing evidence to the verified actor and session lease. */
@Singleton
internal class OperationsDispatchOutgoingGoodsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val readiness: NexaDispatchReadinessGateway,
    private val outgoingGoods: NexaOutgoingGoodsCheckGateway
) : DispatchOutgoingGoodsGateway {
    override suspend fun load(
        fulfillment: DispatchReadiness,
        context: DispatchAuthorityContext
    ): DispatchOutgoingGoodsGatewayResult {
        val initial = authorize(context)
        if (initial !is Authorization.Current) return initial.toResult()
        val currentReadiness = when (val result = readiness.detail(fulfillment.fulfillmentId)) {
            is DispatchReadinessNetworkOutcome.Detail -> DispatchReadinessProjectionAdapter.toFeature(result.item)
            else -> return result.toOutgoingFailure()
        }
        if (!currentReadiness.matches(fulfillment)) return DispatchOutgoingGoodsGatewayResult.Stale
        if (!isCurrent(context, initial.lease)) return authorityDrift(context)

        val snapshot = outgoingGoods.snapshot(fulfillment.fulfillmentId).toFeatureResult()
        if (!isCurrent(context, initial.lease)) return authorityDrift(context)
        return when (snapshot) {
            is DispatchOutgoingGoodsGatewayResult.Snapshot -> {
                if (snapshot.value.allocation.id != currentReadiness.physicalAllocationId ||
                    snapshot.value.allocation.version != currentReadiness.physicalAllocationVersion
                ) {
                    DispatchOutgoingGoodsGatewayResult.Stale
                } else {
                    snapshot
                }
            }

            else -> snapshot
        }
    }

    override suspend fun record(
        fulfillment: DispatchReadiness,
        allocation: DispatchOutgoingGoodsAllocation,
        command: DispatchOutgoingGoodsCommand,
        context: DispatchAuthorityContext
    ): DispatchOutgoingGoodsGatewayResult {
        val initial = authorize(context)
        if (initial !is Authorization.Current) return initial.toResult()
        val currentReadiness = when (val result = readiness.detail(fulfillment.fulfillmentId)) {
            is DispatchReadinessNetworkOutcome.Detail -> DispatchReadinessProjectionAdapter.toFeature(result.item)
            else -> return result.toOutgoingFailure()
        }
        if (!currentReadiness.matches(fulfillment) ||
            currentReadiness.physicalAllocationId != allocation.id ||
            currentReadiness.physicalAllocationVersion != allocation.version
        ) return DispatchOutgoingGoodsGatewayResult.Stale
        if (!currentReadiness.ready || currentReadiness.fulfillmentStatus != READY_FOR_DISPATCH) {
            return DispatchOutgoingGoodsGatewayResult.Conflict
        }
        if (!isCurrent(context, initial.lease)) return authorityDrift(context)

        val snapshot = when (val result = outgoingGoods.snapshot(fulfillment.fulfillmentId).toFeatureResult()) {
            is DispatchOutgoingGoodsGatewayResult.Snapshot -> result.value
            else -> return result
        }
        if (snapshot.allocation.id != allocation.id || snapshot.allocation.version != allocation.version) {
            return DispatchOutgoingGoodsGatewayResult.Stale
        }
        if (snapshot.currentCheck?.let {
                it.current && it.physicalAllocationId == allocation.id &&
                    it.physicalAllocationVersion == allocation.version
            } == true || snapshot.currentCheck?.openDiscrepancy == true
        ) return DispatchOutgoingGoodsGatewayResult.Conflict
        if (!command.matches(fulfillment, allocation) ||
            command.observations.map { it.physicalAllocationLineId }.toSet() !=
            allocation.lines.map { it.physicalAllocationLineId }.toSet()
        ) return DispatchOutgoingGoodsGatewayResult.Stale
        if (!isCurrent(context, initial.lease)) return authorityDrift(context)

        val result = outgoingGoods.record(
            OutgoingGoodsCheckCommand(
                fulfillmentId = command.fulfillmentId,
                expectedFulfillmentVersion = command.expectedFulfillmentVersion,
                physicalAllocationId = command.physicalAllocationId,
                physicalAllocationVersion = command.physicalAllocationVersion,
                observations = command.observations.map {
                    OutgoingGoodsObservation(
                        physicalAllocationLineId = it.physicalAllocationLineId,
                        observedLotId = it.observedLotId,
                        observedQuantity = it.observedQuantity
                    )
                },
                idempotencyKey = command.idempotencyKey,
                exactRequestBody = command.exactRequestBody
            )
        ).toFeatureResult()
        return if (isCurrent(context, initial.lease)) result else authorityDrift(context)
    }

    override suspend fun resolveDiscrepancy(
        fulfillment: DispatchReadiness,
        command: DispatchOutgoingGoodsCommand,
        context: DispatchAuthorityContext
    ): DispatchOutgoingGoodsGatewayResult {
        val initial = authorize(context)
        if (initial !is Authorization.Current) return initial.toResult()
        if (command.type != com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCommandType.ResolveDiscrepancy ||
            command.fulfillmentId != fulfillment.fulfillmentId || command.exactRequestBody.isBlank() ||
            command.idempotencyKey.isBlank()
        ) return DispatchOutgoingGoodsGatewayResult.Stale
        if (!isCurrent(context, initial.lease)) return authorityDrift(context)
        val result = outgoingGoods.resolve(
            OutgoingGoodsDiscrepancyResolutionCommand(
                fulfillmentId = command.fulfillmentId,
                expectedFulfillmentVersion = command.expectedFulfillmentVersion,
                physicalAllocationId = command.physicalAllocationId,
                physicalAllocationVersion = command.physicalAllocationVersion,
                discrepancyCheckId = command.discrepancyCheckId.orEmpty(),
                matchingCheckId = command.matchingCheckId.orEmpty(),
                reason = command.reason.orEmpty(),
                idempotencyKey = command.idempotencyKey,
                exactRequestBody = command.exactRequestBody
            )
        ).toFeatureResult()
        return if (isCurrent(context, initial.lease)) result else authorityDrift(context)
    }

    private suspend fun authorize(context: DispatchAuthorityContext): Authorization {
        if (sessions.sessionState.value != SessionState.Active) return Authorization.SessionInvalidated
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.identity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 || listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).any(String::isBlank) || !verified.matches(identity)
        ) return Authorization.ContextInvalidated
        return if (MANAGE_PERMISSION in identity.permissions) {
            Authorization.Current(lease)
        } else {
            Authorization.PermissionDenied
        }
    }

    private suspend fun isCurrent(
        context: DispatchAuthorityContext,
        originalLease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) return false
        val identity = context.identity ?: return false
        return sessions.verifiedSession.value?.matches(identity) == true &&
            MANAGE_PERMISSION in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active ->
            DispatchOutgoingGoodsGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { verified ->
            context.identity?.let { expected -> verified.matches(expected) } == true
        } == true -> DispatchOutgoingGoodsGatewayResult.SessionInvalidated

        else -> DispatchOutgoingGoodsGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun OutgoingGoodsCheckNetworkOutcome.toFeatureResult(): DispatchOutgoingGoodsGatewayResult =
        when (this) {
            is OutgoingGoodsCheckNetworkOutcome.Snapshot -> DispatchOutgoingGoodsGatewayResult.Snapshot(
                DispatchOutgoingGoodsSnapshot(
                    allocation.toFeature(),
                    currentCheck?.let { check ->
                        DispatchOutgoingGoodsCheck(
                            id = check.id,
                            fulfillmentId = check.fulfillmentId,
                            fulfillmentVersion = check.fulfillmentVersion,
                            physicalAllocationId = check.physicalAllocationId,
                            physicalAllocationVersion = check.physicalAllocationVersion,
                            matches = check.matches,
                            current = check.current,
                            openDiscrepancy = check.openDiscrepancy,
                            checkedAt = check.checkedAt,
                            lines = check.lines.map {
                                DispatchOutgoingGoodsCheckLine(
                                    it.physicalAllocationLineId,
                                    it.expectedLotId,
                                    it.observedLotId,
                                    it.expectedQuantity,
                                    it.observedQuantity,
                                    it.unit,
                                    it.matches
                                )
                            },
                            replayed = check.replayed,
                            discrepancy = check.discrepancy?.let { it.toFeature() }
                        )
                    }
                )
            )

            is OutgoingGoodsCheckNetworkOutcome.Recorded -> DispatchOutgoingGoodsGatewayResult.Recorded(
                DispatchOutgoingGoodsCheck(
                    id = check.id,
                    fulfillmentId = check.fulfillmentId,
                    fulfillmentVersion = check.fulfillmentVersion,
                    physicalAllocationId = check.physicalAllocationId,
                    physicalAllocationVersion = check.physicalAllocationVersion,
                    matches = check.matches,
                    current = check.current,
                    openDiscrepancy = check.openDiscrepancy,
                    checkedAt = check.checkedAt,
                    lines = check.lines.map {
                        DispatchOutgoingGoodsCheckLine(
                            it.physicalAllocationLineId,
                            it.expectedLotId,
                            it.observedLotId,
                            it.expectedQuantity,
                            it.observedQuantity,
                            it.unit,
                            it.matches
                        )
                    },
                    replayed = check.replayed,
                    discrepancy = check.discrepancy?.let { it.toFeature() }
                )
            )

            is OutgoingGoodsCheckNetworkOutcome.Resolved -> DispatchOutgoingGoodsGatewayResult.Resolved(
                DispatchOutgoingGoodsResolution(
                    id = resolution.id,
                    fulfillmentId = resolution.fulfillmentId,
                    fulfillmentVersion = resolution.fulfillmentVersion,
                    physicalAllocationId = resolution.physicalAllocationId,
                    physicalAllocationVersion = resolution.physicalAllocationVersion,
                    discrepancyCheckId = resolution.discrepancyCheckId,
                    matchingCheckId = resolution.matchingCheckId,
                    actorMembershipId = resolution.actorMembershipId,
                    reason = resolution.reason,
                    resolvedAt = resolution.resolvedAt,
                    current = resolution.current,
                    replayed = resolution.replayed
                )
            )

            OutgoingGoodsCheckNetworkOutcome.NetworkUnavailable -> DispatchOutgoingGoodsGatewayResult.NetworkUnavailable
            OutgoingGoodsCheckNetworkOutcome.UnknownOutcome -> DispatchOutgoingGoodsGatewayResult.UnknownOutcome
            OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable -> DispatchOutgoingGoodsGatewayResult.ServiceUnavailable
            OutgoingGoodsCheckNetworkOutcome.PermissionDenied -> DispatchOutgoingGoodsGatewayResult.PermissionDenied
            OutgoingGoodsCheckNetworkOutcome.ContextInvalidated -> DispatchOutgoingGoodsGatewayResult.ContextInvalidated
            OutgoingGoodsCheckNetworkOutcome.SessionInvalidated -> DispatchOutgoingGoodsGatewayResult.SessionInvalidated
            OutgoingGoodsCheckNetworkOutcome.Stale -> DispatchOutgoingGoodsGatewayResult.Stale
            OutgoingGoodsCheckNetworkOutcome.Conflict -> DispatchOutgoingGoodsGatewayResult.Conflict
        }

    private fun DispatchReadinessNetworkOutcome.toOutgoingFailure(): DispatchOutgoingGoodsGatewayResult = when (this) {
        DispatchReadinessNetworkOutcome.NetworkUnavailable -> DispatchOutgoingGoodsGatewayResult.NetworkUnavailable
        DispatchReadinessNetworkOutcome.ServiceUnavailable -> DispatchOutgoingGoodsGatewayResult.ServiceUnavailable
        DispatchReadinessNetworkOutcome.PermissionDenied -> DispatchOutgoingGoodsGatewayResult.PermissionDenied
        DispatchReadinessNetworkOutcome.ContextInvalidated -> DispatchOutgoingGoodsGatewayResult.ContextInvalidated
        DispatchReadinessNetworkOutcome.SessionInvalidated -> DispatchOutgoingGoodsGatewayResult.SessionInvalidated
        is DispatchReadinessNetworkOutcome.ListResult,
        is DispatchReadinessNetworkOutcome.Detail -> DispatchOutgoingGoodsGatewayResult.ServiceUnavailable
    }

    private fun Authorization.toResult(): DispatchOutgoingGoodsGatewayResult = when (this) {
        Authorization.SessionInvalidated -> DispatchOutgoingGoodsGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispatchOutgoingGoodsGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> DispatchOutgoingGoodsGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun PhysicalAllocationProjection.toFeature() = DispatchOutgoingGoodsAllocation(
        id = id,
        status = status,
        version = version,
        asOf = asOf,
        lines = lines.map {
            DispatchOutgoingGoodsLine(
                physicalAllocationLineId = it.id,
                skuId = it.skuId,
                catalogItemId = it.catalogItemId,
                expectedLotId = it.lotId,
                allocatedQuantity = it.quantity,
                releasedQuantity = it.releasedQuantity,
                consumedQuantity = it.consumedQuantity,
                remainingQuantity = it.remainingQuantity,
                unit = it.unit
            )
        }
    )

    private fun com.nexa.mobile.operations.core.network.OutgoingGoodsDiscrepancyProjection.toFeature() =
        DispatchOutgoingGoodsDiscrepancy(
            id = id,
            fulfillmentVersion = fulfillmentVersion,
            physicalAllocationId = physicalAllocationId,
            physicalAllocationVersion = physicalAllocationVersion,
            checkedByMembershipId = checkedByMembershipId,
            checkedAt = checkedAt,
            lines = lines.map {
                DispatchOutgoingGoodsCheckLine(it.physicalAllocationLineId, it.expectedLotId, it.observedLotId,
                    it.expectedQuantity, it.observedQuantity, it.unit, it.matches)
            }
        )

    private fun DispatchReadiness.matches(expected: DispatchReadiness): Boolean =
        subjectKind == expected.subjectKind && fulfillmentId == expected.fulfillmentId &&
            fulfillmentVersion == expected.fulfillmentVersion && fulfillmentStatus == expected.fulfillmentStatus &&
            physicalAllocationId == expected.physicalAllocationId &&
            physicalAllocationVersion == expected.physicalAllocationVersion && ready == expected.ready

    private fun DispatchOutgoingGoodsCommand.matches(
        fulfillment: DispatchReadiness,
        allocation: DispatchOutgoingGoodsAllocation
    ): Boolean = fulfillmentId == fulfillment.fulfillmentId &&
        expectedFulfillmentVersion == fulfillment.fulfillmentVersion &&
        physicalAllocationId == allocation.id && physicalAllocationVersion == allocation.version

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        const val MANAGE_PERMISSION = "fulfillment.manage"
        const val READY_FOR_DISPATCH = "READY_FOR_DISPATCH"
    }
}

internal class DispatchOutgoingGoodsViewModelFactory @Inject constructor(
    private val gateway: DispatchOutgoingGoodsGateway,
    private val metadata: com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchOutgoingGoodsViewModel::class.java))
        return DispatchOutgoingGoodsViewModel(gateway, metadata) as T
    }
}
