package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DispatchReadinessNetworkOutcome as ReadinessOutcome
import com.nexa.mobile.operations.core.network.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.core.network.NexaOutgoingGoodsCheckGateway
import com.nexa.mobile.operations.core.network.OutgoingGoodsCheckCommand
import com.nexa.mobile.operations.core.network.OutgoingGoodsCheckNetworkOutcome as OutgoingGoodsCheckOutcome
import com.nexa.mobile.operations.core.network.OutgoingGoodsDiscrepancyProjection
import com.nexa.mobile.operations.core.network.OutgoingGoodsDiscrepancyResolutionCommand
import com.nexa.mobile.operations.core.network.OutgoingGoodsObservation
import com.nexa.mobile.operations.core.network.PhysicalAllocationProjection
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsAllocation as OutgoingGoodsAllocation
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCheck
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCheckLine as OutgoingGoodsCheckLine
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCommand as OutgoingGoodsCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCommandType as OutgoingGoodsCommandType
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsDiscrepancy as OutgoingGoodsDiscrepancy
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsGateway as OutgoingGoodsGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsGatewayResult as OutgoingGoodsGatewayResult
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsLine
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsMetadataStore as OutgoingGoodsMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsObservation
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsResolution as OutgoingGoodsResolution
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsSnapshot as OutgoingGoodsSnapshot
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsViewModel as OutgoingGoodsViewModel
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
    ): OutgoingGoodsGateway = operations
}

/** Fences current allocation and outgoing evidence to the verified actor and session lease. */
@Singleton
internal class OperationsDispatchOutgoingGoodsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val readiness: NexaDispatchReadinessGateway,
    private val outgoingGoods: NexaOutgoingGoodsCheckGateway
) : OutgoingGoodsGateway {
    override suspend fun load(
        fulfillment: DispatchReadiness,
        context: DispatchAuthorityContext
    ): OutgoingGoodsGatewayResult {
        val initial = authorize(context)
        if (initial !is Authorization.Current) return initial.toResult()
        val currentReadiness = when (val result = readiness.detail(fulfillment.fulfillmentId)) {
            is ReadinessOutcome.Detail ->
                DispatchReadinessProjectionAdapter.toFeature(
                    result.item
                )

            else -> return result.toOutgoingFailure()
        }
        if (!currentReadiness.matches(fulfillment)) return OutgoingGoodsGatewayResult.Stale
        if (!isCurrent(context, initial.lease)) return authorityDrift(context)

        val snapshot = outgoingGoods.snapshot(fulfillment.fulfillmentId).toFeatureResult()
        if (!isCurrent(context, initial.lease)) return authorityDrift(context)
        return when (snapshot) {
            is OutgoingGoodsGatewayResult.Snapshot -> {
                if (snapshot.value.allocation.id != currentReadiness.physicalAllocationId ||
                    snapshot.value.allocation.version != currentReadiness.physicalAllocationVersion
                ) {
                    OutgoingGoodsGatewayResult.Stale
                } else {
                    snapshot
                }
            }

            else -> snapshot
        }
    }

    override suspend fun record(
        fulfillment: DispatchReadiness,
        allocation: OutgoingGoodsAllocation,
        command: OutgoingGoodsCommand,
        context: DispatchAuthorityContext
    ): OutgoingGoodsGatewayResult {
        val initial = authorize(context)
        if (initial !is Authorization.Current) return initial.toResult()
        val currentReadiness = when (val result = readiness.detail(fulfillment.fulfillmentId)) {
            is ReadinessOutcome.Detail ->
                DispatchReadinessProjectionAdapter.toFeature(
                    result.item
                )

            else -> return result.toOutgoingFailure()
        }
        if (!currentReadiness.matches(fulfillment) ||
            currentReadiness.physicalAllocationId != allocation.id ||
            currentReadiness.physicalAllocationVersion != allocation.version
        ) {
            return OutgoingGoodsGatewayResult.Stale
        }
        if (!currentReadiness.ready || currentReadiness.fulfillmentStatus != READY_FOR_DISPATCH) {
            return OutgoingGoodsGatewayResult.Conflict
        }
        if (!isCurrent(context, initial.lease)) return authorityDrift(context)

        val snapshot = when (
            val result = outgoingGoods.snapshot(
                fulfillment.fulfillmentId
            ).toFeatureResult()
        ) {
            is OutgoingGoodsGatewayResult.Snapshot -> result.value
            else -> return result
        }
        if (snapshot.allocation.id != allocation.id ||
            snapshot.allocation.version != allocation.version
        ) {
            return OutgoingGoodsGatewayResult.Stale
        }
        if (snapshot.currentCheck?.let {
                it.current && it.physicalAllocationId == allocation.id &&
                    it.physicalAllocationVersion == allocation.version
            } == true || snapshot.currentCheck?.openDiscrepancy == true
        ) {
            return OutgoingGoodsGatewayResult.Conflict
        }
        if (!command.matches(fulfillment, allocation) ||
            command.observations.map { it.physicalAllocationLineId }.toSet() !=
            allocation.lines.map { it.physicalAllocationLineId }.toSet()
        ) {
            return OutgoingGoodsGatewayResult.Stale
        }
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
        command: OutgoingGoodsCommand,
        context: DispatchAuthorityContext
    ): OutgoingGoodsGatewayResult {
        val initial = authorize(context)
        if (initial !is Authorization.Current) return initial.toResult()
        if (command.type !=
            OutgoingGoodsCommandType.ResolveDiscrepancy ||
            command.fulfillmentId != fulfillment.fulfillmentId ||
            command.exactRequestBody.isBlank() ||
            command.idempotencyKey.isBlank()
        ) {
            return OutgoingGoodsGatewayResult.Stale
        }
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
        ) {
            return false
        }
        val identity = context.identity ?: return false
        return sessions.verifiedSession.value?.matches(identity) == true &&
            MANAGE_PERMISSION in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active ->
            OutgoingGoodsGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { verified ->
            context.identity?.let { expected -> verified.matches(expected) } == true
        } == true -> OutgoingGoodsGatewayResult.SessionInvalidated

        else -> OutgoingGoodsGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun OutgoingGoodsCheckOutcome.toFeatureResult(): OutgoingGoodsGatewayResult =
        when (this) {
            is OutgoingGoodsCheckOutcome.Snapshot ->
                OutgoingGoodsGatewayResult.Snapshot(
                    OutgoingGoodsSnapshot(
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
                                    OutgoingGoodsCheckLine(
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

            is OutgoingGoodsCheckOutcome.Recorded ->
                OutgoingGoodsGatewayResult.Recorded(
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
                            OutgoingGoodsCheckLine(
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

            is OutgoingGoodsCheckOutcome.Resolved ->
                OutgoingGoodsGatewayResult.Resolved(
                    OutgoingGoodsResolution(
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

            OutgoingGoodsCheckOutcome.NetworkUnavailable ->
                OutgoingGoodsGatewayResult.NetworkUnavailable

            OutgoingGoodsCheckOutcome.UnknownOutcome ->
                OutgoingGoodsGatewayResult.UnknownOutcome

            OutgoingGoodsCheckOutcome.ServiceUnavailable ->
                OutgoingGoodsGatewayResult.ServiceUnavailable

            OutgoingGoodsCheckOutcome.PermissionDenied ->
                OutgoingGoodsGatewayResult.PermissionDenied

            OutgoingGoodsCheckOutcome.ContextInvalidated ->
                OutgoingGoodsGatewayResult.ContextInvalidated

            OutgoingGoodsCheckOutcome.SessionInvalidated ->
                OutgoingGoodsGatewayResult.SessionInvalidated

            OutgoingGoodsCheckOutcome.Stale -> OutgoingGoodsGatewayResult.Stale

            OutgoingGoodsCheckOutcome.Conflict -> OutgoingGoodsGatewayResult.Conflict
        }

    private fun ReadinessOutcome.toOutgoingFailure(): OutgoingGoodsGatewayResult = when (this) {
        ReadinessOutcome.NetworkUnavailable ->
            OutgoingGoodsGatewayResult.NetworkUnavailable

        ReadinessOutcome.ServiceUnavailable ->
            OutgoingGoodsGatewayResult.ServiceUnavailable

        ReadinessOutcome.PermissionDenied ->
            OutgoingGoodsGatewayResult.PermissionDenied

        ReadinessOutcome.ContextInvalidated ->
            OutgoingGoodsGatewayResult.ContextInvalidated

        ReadinessOutcome.SessionInvalidated ->
            OutgoingGoodsGatewayResult.SessionInvalidated

        is ReadinessOutcome.ListResult,
        is ReadinessOutcome.Detail ->
            OutgoingGoodsGatewayResult.ServiceUnavailable
    }

    private fun Authorization.toResult(): OutgoingGoodsGatewayResult = when (this) {
        Authorization.SessionInvalidated -> OutgoingGoodsGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> OutgoingGoodsGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> OutgoingGoodsGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun PhysicalAllocationProjection.toFeature() = OutgoingGoodsAllocation(
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

    private fun OutgoingGoodsDiscrepancyProjection.toFeature() = OutgoingGoodsDiscrepancy(
        id = id,
        fulfillmentVersion = fulfillmentVersion,
        physicalAllocationId = physicalAllocationId,
        physicalAllocationVersion = physicalAllocationVersion,
        checkedByMembershipId = checkedByMembershipId,
        checkedAt = checkedAt,
        lines = lines.map {
            OutgoingGoodsCheckLine(
                it.physicalAllocationLineId,
                it.expectedLotId,
                it.observedLotId,
                it.expectedQuantity,
                it.observedQuantity,
                it.unit,
                it.matches
            )
        }
    )

    private fun DispatchReadiness.matches(expected: DispatchReadiness): Boolean =
        subjectKind == expected.subjectKind && fulfillmentId == expected.fulfillmentId &&
            fulfillmentVersion == expected.fulfillmentVersion &&
            fulfillmentStatus == expected.fulfillmentStatus &&
            physicalAllocationId == expected.physicalAllocationId &&
            physicalAllocationVersion == expected.physicalAllocationVersion &&
            ready == expected.ready

    private fun OutgoingGoodsCommand.matches(
        fulfillment: DispatchReadiness,
        allocation: OutgoingGoodsAllocation
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
    private val gateway: OutgoingGoodsGateway,
    private val metadata: OutgoingGoodsMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(OutgoingGoodsViewModel::class.java))
        return OutgoingGoodsViewModel(gateway, metadata) as T
    }
}
