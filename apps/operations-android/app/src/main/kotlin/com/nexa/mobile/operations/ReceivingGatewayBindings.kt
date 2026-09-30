package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.InboundReceiptCommand
import com.nexa.mobile.operations.core.network.NexaReceivingGateway
import com.nexa.mobile.operations.core.network.ReceivingNetworkOutcome
import com.nexa.mobile.operations.feature.warehouse.InboundReceiptRequest
import com.nexa.mobile.operations.feature.warehouse.ReceivedLotFacts
import com.nexa.mobile.operations.feature.warehouse.ReceivingAuthority
import com.nexa.mobile.operations.feature.warehouse.ReceivingGateway
import com.nexa.mobile.operations.feature.warehouse.ReceivingLookupResult
import com.nexa.mobile.operations.feature.warehouse.ReceivingMetadataStore
import com.nexa.mobile.operations.feature.warehouse.ReceivingSubmitResult
import com.nexa.mobile.operations.feature.warehouse.ReceivingViewModel
import com.nexa.mobile.operations.feature.warehouse.ReceivingWarehouseChoice
import com.nexa.mobile.operations.feature.warehouse.ReceivingZoneChoice
import com.nexa.mobile.operations.feature.warehouse.UnavailableReceivingMetadataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** App boundary binds Receiving to verified identity, current permission, and session epoch. */
@Singleton
internal class OperationsReceivingGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val receiving: NexaReceivingGateway
) : ReceivingGateway {
    override suspend fun warehouses(authority: ReceivingAuthority): ReceivingLookupResult {
        val before = authorize(authority, WAREHOUSE_LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = receiving.warehouses().toLookupResult()
        return if (currentAfter(authority, before.lease)) {
            result
        } else {
            authorityDriftLookupResult(authority)
        }
    }

    override suspend fun zones(
        warehouseId: String,
        authority: ReceivingAuthority
    ): ReceivingLookupResult {
        val before = authorize(authority, WAREHOUSE_LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = receiving.zones(warehouseId).toLookupResult()
        return if (currentAfter(authority, before.lease)) {
            result
        } else {
            authorityDriftLookupResult(authority)
        }
    }

    override suspend fun receive(
        request: InboundReceiptRequest,
        idempotencyKey: String,
        authority: ReceivingAuthority
    ): ReceivingSubmitResult {
        val before = authorize(authority, RECEIPT_PERMISSIONS)
        if (before !is Authorization.Current) return before.toSubmitFailure()
        val result = try {
            receiving.receive(request.toTransport(), idempotencyKey).toSubmitResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceivingSubmitResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease)) {
            result
        } else {
            when (authorityDriftLookupResult(authority)) {
                ReceivingLookupResult.SessionInvalidated -> ReceivingSubmitResult.SessionInvalidated
                else -> ReceivingSubmitResult.ContextInvalidated
            }
        }
    }

    private suspend fun authorize(
        authority: ReceivingAuthority,
        requiredPermissions: Set<String>
    ): Authorization {
        if (sessions.sessionState.value != SessionState.Active) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        if (authority.permissions.none { it in requiredPermissions }) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: ReceivingAuthority,
        originalLease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        val current = sessions.verifiedSession.value ?: return false
        return current.matches(authority)
    }

    private suspend fun authorityDriftLookupResult(
        authority: ReceivingAuthority
    ): ReceivingLookupResult = when {
        sessions.sessionState.value != SessionState.Active ->
            ReceivingLookupResult.SessionInvalidated

        sessions.verifiedSession.value?.matches(authority) == true ->
            ReceivingLookupResult.SessionInvalidated

        else -> ReceivingLookupResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(authority: ReceivingAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLookupFailure(): ReceivingLookupResult = when (this) {
        Authorization.SessionInvalidated -> ReceivingLookupResult.SessionInvalidated
        Authorization.ContextInvalidated -> ReceivingLookupResult.ContextInvalidated
        Authorization.PermissionDenied -> ReceivingLookupResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toSubmitFailure(): ReceivingSubmitResult = when (this) {
        Authorization.SessionInvalidated -> ReceivingSubmitResult.SessionInvalidated
        Authorization.ContextInvalidated -> ReceivingSubmitResult.ContextInvalidated
        Authorization.PermissionDenied -> ReceivingSubmitResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun ReceivingNetworkOutcome.toLookupResult(): ReceivingLookupResult = when (this) {
        is ReceivingNetworkOutcome.Warehouses -> ReceivingLookupResult.Warehouses(
            items.map { ReceivingWarehouseChoice(it.id, it.code, it.name, it.status) }
        )

        is ReceivingNetworkOutcome.Zones -> ReceivingLookupResult.Zones(
            items.map { ReceivingZoneChoice(it.id, it.warehouseId, it.code, it.name, it.status) }
        )

        ReceivingNetworkOutcome.NetworkUnavailable -> ReceivingLookupResult.NetworkUnavailable

        ReceivingNetworkOutcome.ServiceUnavailable,
        is ReceivingNetworkOutcome.Rejected,
        is ReceivingNetworkOutcome.Confirmed,
        ReceivingNetworkOutcome.UnknownOutcome -> ReceivingLookupResult.ServiceUnavailable

        ReceivingNetworkOutcome.PermissionDenied -> ReceivingLookupResult.PermissionDenied

        ReceivingNetworkOutcome.ContextInvalidated -> ReceivingLookupResult.ContextInvalidated

        ReceivingNetworkOutcome.SessionInvalidated -> ReceivingLookupResult.SessionInvalidated
    }

    private fun ReceivingNetworkOutcome.toSubmitResult(): ReceivingSubmitResult = when (this) {
        is ReceivingNetworkOutcome.Confirmed -> ReceivingSubmitResult.Confirmed(
            ReceivedLotFacts(
                id = lot.id,
                warehouseId = lot.warehouseId,
                zoneId = lot.zoneId,
                catalogItemId = lot.catalogItemId,
                skuId = lot.skuId,
                batchNumber = lot.batchNumber,
                expirationDate = lot.expirationDate,
                receivedAt = lot.receivedAt,
                onHand = lot.onHand,
                reserved = lot.reserved,
                available = lot.available,
                unit = lot.unit,
                status = lot.status,
                version = lot.version
            )
        )

        is ReceivingNetworkOutcome.Rejected -> ReceivingSubmitResult.Rejected(code)

        ReceivingNetworkOutcome.UnknownOutcome -> ReceivingSubmitResult.UnknownOutcome

        ReceivingNetworkOutcome.NetworkUnavailable -> ReceivingSubmitResult.UnknownOutcome

        ReceivingNetworkOutcome.ServiceUnavailable -> ReceivingSubmitResult.UnknownOutcome

        ReceivingNetworkOutcome.PermissionDenied -> ReceivingSubmitResult.PermissionDenied

        ReceivingNetworkOutcome.ContextInvalidated -> ReceivingSubmitResult.ContextInvalidated

        ReceivingNetworkOutcome.SessionInvalidated -> ReceivingSubmitResult.SessionInvalidated

        is ReceivingNetworkOutcome.Warehouses,
        is ReceivingNetworkOutcome.Zones -> ReceivingSubmitResult.ServiceUnavailable
    }

    private fun InboundReceiptRequest.toTransport() = InboundReceiptCommand(
        warehouseId = warehouseId,
        zoneId = zoneId,
        catalogItemId = catalogItemId,
        skuId = skuId,
        batchNumber = batchNumber,
        expirationDate = expirationDate,
        quantity = quantity,
        unit = unit,
        temperatureReading = temperatureReading,
        notes = notes
    )

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val WAREHOUSE_LOOKUP_PERMISSIONS =
            setOf("warehouse.read", "inventory.read", "warehouse:read")
        val RECEIPT_PERMISSIONS = setOf("inventory.receive", "warehouse:write")
    }
}

/** Factory entry for routing without coupling the feature to auth/network/Hilt. */
internal class ReceivingGatewayBindings @Inject constructor(
    private val gateway: OperationsReceivingGateway,
    private val metadataStore: ReceivingMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReceivingViewModel::class.java))
            return ReceivingViewModel(gateway, metadataStore) as T
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object ReceivingGatewayModule {
    @Provides
    @Singleton
    fun receivingGateway(
        protectedCalls: com.nexa.mobile.operations.core.network.ProtectedCallExecutor
    ) = NexaReceivingGateway(protectedCalls)

    @Provides
    @Singleton
    fun receivingMetadataStore(): ReceivingMetadataStore = UnavailableReceivingMetadataStore
}
