package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockConditionGatewayResult
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockConditionGateway
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionAvailability
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionLot
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaStockConditionGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.StockConditionLotProjection
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.StockConditionNetworkOutcome as StockConditionOutcome
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StockConditionGatewayBindings {
    @Provides
    @Singleton
    fun stockConditionGateway(protectedCalls: ProtectedCallExecutor): NexaStockConditionGateway =
        NexaStockConditionGateway(protectedCalls)
}

/** Keeps responses attached to the verified workforce, permission snapshot, and session lease. */
@Singleton
class OperationsStockConditionGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val stockCondition: NexaStockConditionGateway
) : StockConditionGateway {
    override suspend fun lots(context: ActiveOperationsContext): StockConditionGatewayResult {
        val initial = authorize(context)
        if (initial !is Authorization.Current) return initial.toResult()
        val result = stockCondition.lots().toFeatureResult()
        return if (isCurrent(context, initial.lease)) result else authorityDrift(context)
    }

    override suspend fun lot(
        lotId: String,
        context: ActiveOperationsContext
    ): StockConditionGatewayResult {
        val initial = authorize(context)
        if (initial !is Authorization.Current) return initial.toResult()
        val result = stockCondition.lot(lotId).toFeatureResult()
        return if (isCurrent(context, initial.lease)) result else authorityDrift(context)
    }

    override suspend fun availability(
        warehouseId: String,
        catalogItemId: String,
        context: ActiveOperationsContext
    ): StockConditionGatewayResult {
        val initial = authorize(context)
        if (initial !is Authorization.Current) return initial.toResult()
        val result = stockCondition.availability(warehouseId, catalogItemId).toFeatureResult()
        return if (isCurrent(context, initial.lease)) result else authorityDrift(context)
    }

    private suspend fun authorize(context: ActiveOperationsContext): Authorization {
        if (sessions.sessionState.value != SessionState.Active) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.verifiedIdentity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 ||
            listOf(identity.userId, identity.tenantId, identity.workspaceId, identity.membershipId)
                .any(String::isBlank) || !verified.matches(identity)
        ) {
            return Authorization.ContextInvalidated
        }
        return when (stockReadHint(identity.permissions)) {
            PermissionHint.Unknown -> Authorization.ContextInvalidated
            PermissionHint.Unavailable -> Authorization.PermissionDenied
            PermissionHint.Available -> Authorization.Current(lease)
        }
    }

    private suspend fun isCurrent(
        context: ActiveOperationsContext,
        originalLease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        val identity = context.verifiedIdentity ?: return false
        return sessions.verifiedSession.value?.matches(identity) == true &&
            stockReadHint(identity.permissions) == PermissionHint.Available
    }

    private suspend fun authorityDrift(
        context: ActiveOperationsContext
    ): StockConditionGatewayResult = when {
        sessions.sessionState.value != SessionState.Active ->
            StockConditionGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { verified ->
            context.verifiedIdentity?.let { expected -> verified.matches(expected) } == true
        } == true -> StockConditionGatewayResult.SessionInvalidated

        else -> StockConditionGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: VerifiedOperationsIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId &&
            tenantId == expected.tenantId && workspaceId == expected.workspaceId &&
            membershipId == expected.membershipId && permissions == expected.permissions

    private fun stockReadHint(permissions: Set<String>): PermissionHint = when {
        permissions.isEmpty() -> PermissionHint.Unknown
        permissions.any { it in STOCK_READ_PERMISSIONS } -> PermissionHint.Available
        else -> PermissionHint.Unavailable
    }

    private fun Authorization.toResult(): StockConditionGatewayResult = when (this) {
        Authorization.SessionInvalidated -> StockConditionGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> StockConditionGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> StockConditionGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun StockConditionOutcome.toFeatureResult(): StockConditionGatewayResult = when (this) {
        is StockConditionOutcome.Lots -> StockConditionGatewayResult.Lots(
            items.map { item -> item.toFeatureLot() }
        )

        is StockConditionOutcome.Lot -> StockConditionGatewayResult.Lot(
            item.toFeatureLot()
        )

        is StockConditionOutcome.Availability ->
            StockConditionGatewayResult.Availability(
                item?.let {
                    StockConditionAvailability(
                        catalogItemId = it.catalogItemId,
                        status = it.status,
                        asOf = it.asOf,
                        physicalQuantity = it.physicalQuantity,
                        safetyStock = it.safetyStock,
                        sellableQuantity = it.sellableQuantity
                    )
                }
            )

        StockConditionOutcome.NetworkUnavailable ->
            StockConditionGatewayResult.NetworkUnavailable

        StockConditionOutcome.ServiceUnavailable ->
            StockConditionGatewayResult.ServiceUnavailable

        StockConditionOutcome.PermissionDenied ->
            StockConditionGatewayResult.PermissionDenied

        StockConditionOutcome.ContextInvalidated ->
            StockConditionGatewayResult.ContextInvalidated

        StockConditionOutcome.SessionInvalidated ->
            StockConditionGatewayResult.SessionInvalidated
    }

    private fun StockConditionLotProjection.toFeatureLot() = StockConditionLot(
        id = id,
        warehouseId = warehouseId,
        zoneId = zoneId,
        catalogItemId = catalogItemId,
        skuId = skuId,
        batchNumber = batchNumber,
        expirationDate = expirationDate,
        receivedAt = receivedAt,
        onHand = onHand,
        reserved = reserved,
        physicalRemaining = physicalRemaining,
        unit = unit,
        status = status,
        version = version
    )

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val STOCK_READ_PERMISSIONS = setOf("warehouse.read", "inventory.read", "warehouse:read")
    }
}
