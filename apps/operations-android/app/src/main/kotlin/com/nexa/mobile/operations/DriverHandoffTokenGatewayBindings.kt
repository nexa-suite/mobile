package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DriverDeliveryNetworkOutcome
import com.nexa.mobile.operations.core.network.DriverHandoffTokenNetworkOutcome
import com.nexa.mobile.operations.core.network.NexaDriverDeliveryGateway
import com.nexa.mobile.operations.core.network.NexaDriverHandoffTokenGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.DriverHandoffCurrentDelivery
import com.nexa.mobile.operations.feature.delivery.DriverHandoffCurrentDeliveryResult
import com.nexa.mobile.operations.feature.delivery.DriverHandoffIssueCommand
import com.nexa.mobile.operations.feature.delivery.DriverHandoffIssueResult
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenGateway
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenReceipt
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Verifies full current identity and session lease for driver handoff issuance. */
@Singleton
internal class OperationsDriverHandoffTokenGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val deliveries: NexaDriverDeliveryGateway,
    private val handoffs: NexaDriverHandoffTokenGateway
) : DriverHandoffTokenGateway {
    override suspend fun currentDelivery(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverHandoffCurrentDeliveryResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toCurrentFailure()
        val outcome = try {
            deliveries.delivery(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverHandoffCurrentDeliveryResult.Unavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        return when (outcome) {
            is DriverDeliveryNetworkOutcome.Detail -> {
                val value = outcome.item
                if (value.id != deliveryId || value.version < 0) DriverHandoffCurrentDeliveryResult.Unavailable
                else DriverHandoffCurrentDeliveryResult.Loaded(
                    DriverHandoffCurrentDelivery(value.id, value.status, value.version, value.activeAttempt?.id)
                )
            }
            DriverDeliveryNetworkOutcome.NotFound -> DriverHandoffCurrentDeliveryResult.NotFound
            DriverDeliveryNetworkOutcome.PermissionDenied -> DriverHandoffCurrentDeliveryResult.PermissionDenied
            DriverDeliveryNetworkOutcome.ContextInvalidated -> DriverHandoffCurrentDeliveryResult.ContextInvalidated
            DriverDeliveryNetworkOutcome.SessionInvalidated -> DriverHandoffCurrentDeliveryResult.SessionInvalidated
            else -> DriverHandoffCurrentDeliveryResult.Unavailable
        }
    }

    override suspend fun issue(
        command: DriverHandoffIssueCommand,
        authority: DriverDeliveryAuthority
    ): DriverHandoffIssueResult {
        val before = authorize(authority, HANDOFF_WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toIssueFailure()
        val outcome = try {
            handoffs.issue(command.deliveryId, command.attemptId,
                command.idempotencyKey, command.frozenBody)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverHandoffIssueResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return DriverHandoffIssueResult.UnknownOutcome
        return when (outcome) {
            is DriverHandoffTokenNetworkOutcome.Issued -> {
                val value = outcome.value
                if (value.deliveryId != command.deliveryId || value.attemptId != command.attemptId) {
                    DriverHandoffIssueResult.UnknownOutcome
                } else {
                    val receipt = DriverHandoffTokenReceipt(value.handoffId, value.deliveryId,
                        value.attemptId, value.expiresAt, value.status)
                    value.token?.let { DriverHandoffIssueResult.Issued(receipt, it) }
                        ?: DriverHandoffIssueResult.TokenUnavailable(receipt)
                }
            }
            is DriverHandoffTokenNetworkOutcome.Rejected -> DriverHandoffIssueResult.Rejected(outcome.code)
            DriverHandoffTokenNetworkOutcome.NotFound -> DriverHandoffIssueResult.NotFound
            DriverHandoffTokenNetworkOutcome.PermissionDenied -> DriverHandoffIssueResult.PermissionDenied
            DriverHandoffTokenNetworkOutcome.ContextInvalidated -> DriverHandoffIssueResult.ContextInvalidated
            DriverHandoffTokenNetworkOutcome.SessionInvalidated -> DriverHandoffIssueResult.SessionInvalidated
            DriverHandoffTokenNetworkOutcome.UnknownOutcome,
            DriverHandoffTokenNetworkOutcome.Unavailable -> DriverHandoffIssueResult.UnknownOutcome
        }
    }

    private suspend fun authorize(
        authority: DriverDeliveryAuthority,
        requiredPermissions: Set<String>
    ): Authorization {
        if (sessions.sessionState.value != SessionState.Active) return Authorization.SessionInvalidated
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        if (authority.permissions.none(requiredPermissions::contains)) return Authorization.PermissionDenied
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(authority: DriverDeliveryAuthority, lease: AccessTokenLease): Boolean =
        sessions.sessionState.value == SessionState.Active && sessions.isEpochCurrent(lease.epoch) &&
            sessions.verifiedSession.value?.matches(authority) == true

    private fun authorityDrift(authority: DriverDeliveryAuthority): DriverHandoffCurrentDeliveryResult =
        if (sessions.sessionState.value != SessionState.Active) DriverHandoffCurrentDeliveryResult.SessionInvalidated
        else if (sessions.verifiedSession.value?.matches(authority) == true) DriverHandoffCurrentDeliveryResult.SessionInvalidated
        else DriverHandoffCurrentDeliveryResult.ContextInvalidated

    private fun VerifiedSession.matches(authority: DriverDeliveryAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toCurrentFailure(): DriverHandoffCurrentDeliveryResult = when (this) {
        Authorization.SessionInvalidated -> DriverHandoffCurrentDeliveryResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverHandoffCurrentDeliveryResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverHandoffCurrentDeliveryResult.PermissionDenied
        is Authorization.Current -> error("current delivery failure mapping requires a failure")
    }

    private fun Authorization.toIssueFailure(): DriverHandoffIssueResult = when (this) {
        Authorization.SessionInvalidated -> DriverHandoffIssueResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverHandoffIssueResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverHandoffIssueResult.PermissionDenied
        is Authorization.Current -> error("handoff issue failure mapping requires a failure")
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val DRIVER_READ_PERMISSIONS = setOf("dispatch.read", "logistics:read")
        val HANDOFF_WRITE_PERMISSIONS = setOf("logistics:write")
    }
}

/** Route/factory seam for Root navigation. Root owns MainActivity and DeliveryScreen. */
internal class DriverHandoffTokenGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverHandoffTokenGateway,
    private val metadataStore: DriverHandoffTokenMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriverHandoffTokenViewModel::class.java))
            return DriverHandoffTokenViewModel(gateway, metadataStore) as T
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DriverHandoffTokenGatewayModule {
    @Provides
    @Singleton
    fun provideNexaDriverHandoffTokenGateway(protectedCalls: ProtectedCallExecutor) =
        NexaDriverHandoffTokenGateway(protectedCalls)

    @Provides
    @Singleton
    fun provideDriverHandoffTokenGatewayBindings(
        gateway: OperationsDriverHandoffTokenGateway,
        metadataStore: DriverHandoffTokenMetadataStore
    ) = DriverHandoffTokenGatewayBindings(gateway, metadataStore)
}
