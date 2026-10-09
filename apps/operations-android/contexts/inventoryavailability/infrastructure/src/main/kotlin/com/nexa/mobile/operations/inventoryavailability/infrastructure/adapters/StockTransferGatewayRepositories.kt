package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TransferLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TransferSubmitResult
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferGateway
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ConfirmedStockTransfer
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TransferSourceLotChoice
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TransferWarehouseChoice
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TransferZoneChoice
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaReceivingGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaStockConditionGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaStockTransferGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.ReceivingNetworkOutcome
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.StockConditionNetworkOutcome as StockConditionOutcome
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.StockTransferNetworkOutcome as StockTransferOutcome
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class OperationsStockTransferGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val receiving: NexaReceivingGateway,
    private val stockCondition: NexaStockConditionGateway,
    private val transfers: NexaStockTransferGateway
) : StockTransferGateway {
    override suspend fun warehouses(authority: StockTransferAuthority): TransferLookupResult {
        val before = authorize(authority, LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = safeLookup { receiving.warehouses().toTransferWarehouses() }
        return if (currentAfter(authority, before.lease, LOOKUP_PERMISSIONS)) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun zones(
        warehouseId: String,
        authority: StockTransferAuthority
    ): TransferLookupResult {
        val before = authorize(authority, LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = safeLookup { receiving.zones(warehouseId).toTransferZones() }
        return if (currentAfter(authority, before.lease, LOOKUP_PERMISSIONS)) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun sourceLots(authority: StockTransferAuthority): TransferLookupResult {
        val before = authorize(authority, LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = safeLookup { stockCondition.lots().toTransferLots() }
        return if (currentAfter(authority, before.lease, LOOKUP_PERMISSIONS)) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun create(
        frozenPayload: String,
        expectedSourceVersion: Long,
        idempotencyKey: String,
        authority: StockTransferAuthority
    ): TransferSubmitResult {
        val before = authorize(authority, WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toSubmitFailure()
        val result = try {
            transfers.createTransfer(frozenPayload, expectedSourceVersion, idempotencyKey)
                .toTransferSubmitResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TransferSubmitResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease, WRITE_PERMISSIONS)) {
            result
        } else {
            when (authorityDrift(authority)) {
                TransferLookupResult.SessionInvalidated -> TransferSubmitResult.SessionInvalidated
                else -> TransferSubmitResult.ContextInvalidated
            }
        }
    }

    private suspend fun authorize(
        authority: StockTransferAuthority,
        requiredPermissions: Set<String>
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        return if (authority.permissions.any { it in requiredPermissions }) {
            Authorization.Current(lease)
        } else {
            Authorization.PermissionDenied
        }
    }

    private suspend fun currentAfter(
        authority: StockTransferAuthority,
        originalLease: AccessTokenLease,
        requiredPermissions: Set<String>
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        val current = sessions.verifiedSession.value ?: return false
        return current.matches(authority) && authority.permissions.any { it in requiredPermissions }
    }

    private suspend fun authorityDrift(authority: StockTransferAuthority): TransferLookupResult =
        when {
            sessions.sessionState.value != SessionState.Active ->
                TransferLookupResult.SessionInvalidated

            sessions.verifiedSession.value?.matches(authority) == true ->
                TransferLookupResult.SessionInvalidated

            else -> TransferLookupResult.ContextInvalidated
        }

    private fun VerifiedSession.matches(authority: StockTransferAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLookupFailure(): TransferLookupResult = when (this) {
        Authorization.SessionInvalidated -> TransferLookupResult.SessionInvalidated
        Authorization.ContextInvalidated -> TransferLookupResult.ContextInvalidated
        Authorization.PermissionDenied -> TransferLookupResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a lookup failure")
    }

    private fun Authorization.toSubmitFailure(): TransferSubmitResult = when (this) {
        Authorization.SessionInvalidated -> TransferSubmitResult.SessionInvalidated
        Authorization.ContextInvalidated -> TransferSubmitResult.ContextInvalidated
        Authorization.PermissionDenied -> TransferSubmitResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a command failure")
    }

    private fun ReceivingNetworkOutcome.toTransferWarehouses(): TransferLookupResult = when (this) {
        is ReceivingNetworkOutcome.Warehouses -> TransferLookupResult.Warehouses(
            items.map { TransferWarehouseChoice(it.id, it.code, it.name, it.status) }
        )

        ReceivingNetworkOutcome.NetworkUnavailable -> TransferLookupResult.NetworkUnavailable

        ReceivingNetworkOutcome.ServiceUnavailable,
        is ReceivingNetworkOutcome.Zones,
        is ReceivingNetworkOutcome.Rejected,
        is ReceivingNetworkOutcome.Confirmed,
        is ReceivingNetworkOutcome.EvidenceStatus,
        is ReceivingNetworkOutcome.EvidenceUploaded,
        ReceivingNetworkOutcome.UnknownOutcome -> TransferLookupResult.ServiceUnavailable

        ReceivingNetworkOutcome.PermissionDenied -> TransferLookupResult.PermissionDenied

        ReceivingNetworkOutcome.ContextInvalidated -> TransferLookupResult.ContextInvalidated

        ReceivingNetworkOutcome.SessionInvalidated -> TransferLookupResult.SessionInvalidated
    }

    private fun ReceivingNetworkOutcome.toTransferZones(): TransferLookupResult = when (this) {
        is ReceivingNetworkOutcome.Zones -> TransferLookupResult.Zones(
            items.map { TransferZoneChoice(it.id, it.warehouseId, it.code, it.name, it.status) }
        )

        ReceivingNetworkOutcome.NetworkUnavailable -> TransferLookupResult.NetworkUnavailable

        ReceivingNetworkOutcome.ServiceUnavailable,
        is ReceivingNetworkOutcome.Rejected,
        is ReceivingNetworkOutcome.Confirmed,
        is ReceivingNetworkOutcome.EvidenceStatus,
        is ReceivingNetworkOutcome.EvidenceUploaded,
        is ReceivingNetworkOutcome.Warehouses,
        ReceivingNetworkOutcome.UnknownOutcome -> TransferLookupResult.ServiceUnavailable

        ReceivingNetworkOutcome.PermissionDenied -> TransferLookupResult.PermissionDenied

        ReceivingNetworkOutcome.ContextInvalidated -> TransferLookupResult.ContextInvalidated

        ReceivingNetworkOutcome.SessionInvalidated -> TransferLookupResult.SessionInvalidated
    }

    private fun StockConditionOutcome.toTransferLots(): TransferLookupResult = when (this) {
        is StockConditionOutcome.Lots -> TransferLookupResult.Lots(
            items.map {
                TransferSourceLotChoice(
                    id = it.id,
                    warehouseId = it.warehouseId,
                    zoneId = it.zoneId,
                    catalogItemId = it.catalogItemId,
                    skuId = it.skuId,
                    batchNumber = it.batchNumber,
                    physicalRemaining = it.physicalRemaining,
                    unit = it.unit,
                    status = it.status,
                    version = it.version
                )
            }
        )

        StockConditionOutcome.NetworkUnavailable -> TransferLookupResult.NetworkUnavailable

        StockConditionOutcome.ServiceUnavailable,
        is StockConditionOutcome.Lot,
        is StockConditionOutcome.Availability -> TransferLookupResult.ServiceUnavailable

        StockConditionOutcome.PermissionDenied -> TransferLookupResult.PermissionDenied

        StockConditionOutcome.ContextInvalidated -> TransferLookupResult.ContextInvalidated

        StockConditionOutcome.SessionInvalidated -> TransferLookupResult.SessionInvalidated
    }

    private fun StockTransferOutcome.toTransferSubmitResult(): TransferSubmitResult = when (this) {
        is StockTransferOutcome.Confirmed -> TransferSubmitResult.Confirmed(
            ConfirmedStockTransfer(
                id = transfer.id,
                status = transfer.status,
                sourceLotId = transfer.sourceLotId,
                sourceWarehouseId = transfer.sourceWarehouseId,
                sourceZoneId = transfer.sourceZoneId,
                destinationWarehouseId = transfer.destinationWarehouseId,
                destinationZoneId = transfer.destinationZoneId,
                requestedQuantity = transfer.requestedQuantity,
                transferredQuantity = transfer.transferredQuantity,
                unit = transfer.unit,
                sourceVersionBefore = transfer.sourceVersionBefore,
                version = transfer.version
            )
        )

        is StockTransferOutcome.Rejected -> TransferSubmitResult.Rejected(code)

        StockTransferOutcome.UnknownOutcome -> TransferSubmitResult.UnknownOutcome

        StockTransferOutcome.PreconditionFailed ->
            TransferSubmitResult.PreconditionFailed

        StockTransferOutcome.Conflict -> TransferSubmitResult.Conflict

        StockTransferOutcome.NetworkUnavailable ->
            TransferSubmitResult.NetworkUnavailable

        StockTransferOutcome.ServiceUnavailable ->
            TransferSubmitResult.ServiceUnavailable

        StockTransferOutcome.PermissionDenied -> TransferSubmitResult.PermissionDenied

        StockTransferOutcome.ContextInvalidated ->
            TransferSubmitResult.ContextInvalidated

        StockTransferOutcome.SessionInvalidated ->
            TransferSubmitResult.SessionInvalidated
    }

    private suspend fun safeLookup(
        operation: suspend () -> TransferLookupResult
    ): TransferLookupResult = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TransferLookupResult.ServiceUnavailable
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val LOOKUP_PERMISSIONS = setOf("warehouse:read", "warehouse.read", "inventory.read")
        val WRITE_PERMISSIONS = setOf("warehouse:write")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object StockTransferNetworkBindings {
    @Provides
    @Singleton
    fun nexaStockTransferGateway(protectedCalls: ProtectedCallExecutor): NexaStockTransferGateway =
        NexaStockTransferGateway(protectedCalls)
}
