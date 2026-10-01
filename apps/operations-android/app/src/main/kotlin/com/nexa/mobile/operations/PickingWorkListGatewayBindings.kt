package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.NexaPickingGateway
import com.nexa.mobile.operations.core.network.PickingWorkListNetworkOutcome
import com.nexa.mobile.operations.core.network.PickingWorkListProjection as NetworkPage
import com.nexa.mobile.operations.core.network.PickingWorkListItemProjection as NetworkItem
import com.nexa.mobile.operations.feature.warehouse.PickingAuthority
import com.nexa.mobile.operations.feature.warehouse.PickingWorkItem
import com.nexa.mobile.operations.feature.warehouse.PickingWorkListGateway
import com.nexa.mobile.operations.feature.warehouse.PickingWorkPage
import com.nexa.mobile.operations.feature.warehouse.PickingWorkListResult
import com.nexa.mobile.operations.feature.warehouse.PickingWorkListViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Applies the current verified session fence around the protected picking work list read. */
@Singleton
internal class OperationsPickingWorkListGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val picking: NexaPickingGateway
) : PickingWorkListGateway {
    override suspend fun list(
        authority: PickingAuthority,
        page: Int,
        size: Int
    ): PickingWorkListResult {
        val before = authorize(authority)
        if (before !is Authorization.Current) return before.toResult()
        val outcome = try {
            picking.workList(page, size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PickingWorkListResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        return when (outcome) {
            is PickingWorkListNetworkOutcome.Loaded -> PickingWorkListResult.Loaded(
                outcome.value.toFeature()
            )
            PickingWorkListNetworkOutcome.NetworkUnavailable -> PickingWorkListResult.NetworkUnavailable
            PickingWorkListNetworkOutcome.ServiceUnavailable -> PickingWorkListResult.ServiceUnavailable
            PickingWorkListNetworkOutcome.PermissionDenied -> PickingWorkListResult.PermissionDenied
            PickingWorkListNetworkOutcome.ContextInvalidated -> PickingWorkListResult.ContextInvalidated
            PickingWorkListNetworkOutcome.SessionInvalidated -> PickingWorkListResult.SessionInvalidated
        }
    }

    private suspend fun authorize(authority: PickingAuthority): Authorization {
        if (sessions.sessionState.value != SessionState.Active) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        if (!authority.canRead) return Authorization.PermissionDenied
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: PickingAuthority,
        originalLease: AccessTokenLease
    ): Boolean = sessions.sessionState.value == SessionState.Active &&
        sessions.isEpochCurrent(originalLease.epoch) &&
        sessions.verifiedSession.value?.matches(authority) == true

    private suspend fun authorityDrift(authority: PickingAuthority): PickingWorkListResult =
        if (sessions.sessionState.value != SessionState.Active) {
            PickingWorkListResult.SessionInvalidated
        } else if (sessions.verifiedSession.value?.matches(authority) == true) {
            PickingWorkListResult.SessionInvalidated
        } else {
            PickingWorkListResult.ContextInvalidated
        }

    private fun VerifiedSession.matches(authority: PickingAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toResult(): PickingWorkListResult = when (this) {
        Authorization.SessionInvalidated -> PickingWorkListResult.SessionInvalidated
        Authorization.ContextInvalidated -> PickingWorkListResult.ContextInvalidated
        Authorization.PermissionDenied -> PickingWorkListResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun NetworkPage.toFeature() = PickingWorkPage(
        items = items.map { it.toFeature() },
        page = page,
        size = size,
        totalItems = totalItems,
        asOf = asOf
    )

    private fun NetworkItem.toFeature() = PickingWorkItem(
        fulfillmentId = fulfillmentId,
        salesOrderId = salesOrderId,
        status = status,
        version = version,
        physicalAllocationId = physicalAllocationId,
        allocationVersion = allocationVersion,
        lineCount = lineCount
    )

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }
}

/** ViewModel factory consumed by the root entry point without leaking app types into the feature. */
internal class PickingWorkListGatewayBindings @Inject constructor(
    private val gateway: OperationsPickingWorkListGateway
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PickingWorkListViewModel::class.java))
            return PickingWorkListViewModel(gateway) as T
        }
    }
}
