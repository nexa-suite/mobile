package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DriverDeliveryNetworkOutcome as Outcome
import com.nexa.mobile.operations.core.network.DriverHandoffTokenNetworkOutcome as HandoffTokenOutcome
import com.nexa.mobile.operations.core.network.NexaDriverDeliveryGateway
import com.nexa.mobile.operations.core.network.NexaDriverHandoffTokenGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.DriverHandoffCurrentDelivery as HandoffCurrentDelivery
import com.nexa.mobile.operations.feature.delivery.DriverHandoffCurrentDeliveryResult as HandoffCurrentDeliveryResult
import com.nexa.mobile.operations.feature.delivery.DriverHandoffIssueCommand
import com.nexa.mobile.operations.feature.delivery.DriverHandoffIssueResult
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenGateway
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenMetadataStore as HandoffTokenMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenReceipt
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenViewModel as HandoffTokenViewModel
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
    ): HandoffCurrentDeliveryResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toCurrentFailure()
        val outcome = try {
            deliveries.delivery(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return HandoffCurrentDeliveryResult.Unavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        return when (outcome) {
            is Outcome.Detail -> {
                val value = outcome.item
                if (value.id != deliveryId ||
                    value.version < 0
                ) {
                    HandoffCurrentDeliveryResult.Unavailable
                } else {
                    HandoffCurrentDeliveryResult.Loaded(
                        HandoffCurrentDelivery(
                            value.id,
                            value.status,
                            value.version,
                            value.activeAttempt?.id
                        )
                    )
                }
            }

            Outcome.NotFound -> HandoffCurrentDeliveryResult.NotFound

            Outcome.PermissionDenied ->
                HandoffCurrentDeliveryResult.PermissionDenied

            Outcome.ContextInvalidated ->
                HandoffCurrentDeliveryResult.ContextInvalidated

            Outcome.SessionInvalidated ->
                HandoffCurrentDeliveryResult.SessionInvalidated

            else -> HandoffCurrentDeliveryResult.Unavailable
        }
    }

    override suspend fun issue(
        command: DriverHandoffIssueCommand,
        authority: DriverDeliveryAuthority
    ): DriverHandoffIssueResult {
        val before = authorize(authority, HANDOFF_WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toIssueFailure()
        val outcome = try {
            handoffs.issue(
                command.deliveryId,
                command.attemptId,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverHandoffIssueResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return DriverHandoffIssueResult.UnknownOutcome
        return when (outcome) {
            is HandoffTokenOutcome.Issued -> {
                val value = outcome.value
                if (value.deliveryId != command.deliveryId ||
                    value.attemptId != command.attemptId
                ) {
                    DriverHandoffIssueResult.UnknownOutcome
                } else {
                    val receipt = DriverHandoffTokenReceipt(
                        value.handoffId,
                        value.deliveryId,
                        value.attemptId,
                        value.expiresAt,
                        value.status
                    )
                    value.token?.let { DriverHandoffIssueResult.Issued(receipt, it) }
                        ?: DriverHandoffIssueResult.TokenUnavailable(receipt)
                }
            }

            is HandoffTokenOutcome.Rejected -> DriverHandoffIssueResult.Rejected(
                outcome.code
            )

            HandoffTokenOutcome.NotFound -> DriverHandoffIssueResult.NotFound

            HandoffTokenOutcome.PermissionDenied ->
                DriverHandoffIssueResult.PermissionDenied

            HandoffTokenOutcome.ContextInvalidated ->
                DriverHandoffIssueResult.ContextInvalidated

            HandoffTokenOutcome.SessionInvalidated ->
                DriverHandoffIssueResult.SessionInvalidated

            HandoffTokenOutcome.UnknownOutcome,
            HandoffTokenOutcome.Unavailable -> DriverHandoffIssueResult.UnknownOutcome
        }
    }

    private suspend fun authorize(
        authority: DriverDeliveryAuthority,
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
        if (authority.permissions.none(
                requiredPermissions::contains
            )
        ) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: DriverDeliveryAuthority,
        lease: AccessTokenLease
    ): Boolean = sessions.sessionState.value == SessionState.Active && sessions.isEpochCurrent(
        lease.epoch
    ) &&
        sessions.verifiedSession.value?.matches(authority) == true

    private fun authorityDrift(authority: DriverDeliveryAuthority): HandoffCurrentDeliveryResult =
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            HandoffCurrentDeliveryResult.SessionInvalidated
        } else if (sessions.verifiedSession.value?.matches(authority) ==
            true
        ) {
            HandoffCurrentDeliveryResult.SessionInvalidated
        } else {
            HandoffCurrentDeliveryResult.ContextInvalidated
        }

    private fun VerifiedSession.matches(authority: DriverDeliveryAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toCurrentFailure(): HandoffCurrentDeliveryResult = when (this) {
        Authorization.SessionInvalidated -> HandoffCurrentDeliveryResult.SessionInvalidated
        Authorization.ContextInvalidated -> HandoffCurrentDeliveryResult.ContextInvalidated
        Authorization.PermissionDenied -> HandoffCurrentDeliveryResult.PermissionDenied
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
    private val metadataStore: HandoffTokenMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HandoffTokenViewModel::class.java))
            return HandoffTokenViewModel(gateway, metadataStore) as T
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
        metadataStore: HandoffTokenMetadataStore
    ) = DriverHandoffTokenGatewayBindings(gateway, metadataStore)
}
