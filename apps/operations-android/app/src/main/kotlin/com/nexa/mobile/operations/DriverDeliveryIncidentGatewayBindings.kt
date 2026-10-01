package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DriverDeliveryNetworkOutcome
import com.nexa.mobile.operations.core.network.DriverIncidentNetworkOutcome
import com.nexa.mobile.operations.core.network.DriverIncidentWireCommand
import com.nexa.mobile.operations.core.network.NexaDriverDeliveryGateway
import com.nexa.mobile.operations.core.network.NexaDriverIncidentGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryIncidentViewModel
import com.nexa.mobile.operations.feature.delivery.DriverIncidentCurrentDelivery
import com.nexa.mobile.operations.feature.delivery.DriverIncidentCurrentDeliveryResult
import com.nexa.mobile.operations.feature.delivery.DriverIncidentEvidenceAttachCommand
import com.nexa.mobile.operations.feature.delivery.DriverIncidentEvidenceProjection
import com.nexa.mobile.operations.feature.delivery.DriverIncidentEvidenceResult
import com.nexa.mobile.operations.feature.delivery.DriverIncidentEvidenceUploadCommand
import com.nexa.mobile.operations.feature.delivery.DriverIncidentGateway
import com.nexa.mobile.operations.feature.delivery.DriverIncidentMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverIncidentMetadataWrite
import com.nexa.mobile.operations.feature.delivery.DriverIncidentResult
import com.nexa.mobile.operations.feature.delivery.DriverIncidentSummary
import com.nexa.mobile.operations.feature.delivery.DriverIncidentCommand
import com.nexa.mobile.operations.feature.delivery.DriverIncidentSelectionContext
import com.nexa.mobile.operations.feature.delivery.DriverProofFileCandidate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Binds incident reads and append-only writes to the current verified identity and session lease. */
@Singleton
internal class OperationsDriverIncidentGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val deliveries: NexaDriverDeliveryGateway,
    private val incidents: NexaDriverIncidentGateway
) : DriverIncidentGateway {
    override suspend fun currentDelivery(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverIncidentCurrentDeliveryResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toCurrentFailure()
        val outcome = try {
            deliveries.delivery(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverIncidentCurrentDeliveryResult.Unavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        return when (outcome) {
            is DriverDeliveryNetworkOutcome.Detail -> {
                val value = outcome.item
                if (value.id != deliveryId || value.version < 0) {
                    DriverIncidentCurrentDeliveryResult.Unavailable
                } else {
                    DriverIncidentCurrentDeliveryResult.Loaded(
                        DriverIncidentCurrentDelivery(
                            value.id, value.status, value.version, value.activeAttempt?.id
                        )
                    )
                }
            }

            DriverDeliveryNetworkOutcome.NotFound -> DriverIncidentCurrentDeliveryResult.NotFound
            DriverDeliveryNetworkOutcome.PermissionDenied -> DriverIncidentCurrentDeliveryResult.PermissionDenied
            DriverDeliveryNetworkOutcome.ContextInvalidated -> DriverIncidentCurrentDeliveryResult.ContextInvalidated
            DriverDeliveryNetworkOutcome.SessionInvalidated -> DriverIncidentCurrentDeliveryResult.SessionInvalidated
            else -> DriverIncidentCurrentDeliveryResult.Unavailable
        }
    }

    override suspend fun recordIncident(
        command: DriverIncidentCommand,
        authority: DriverDeliveryAuthority
    ): DriverIncidentResult {
        val before = authorize(authority, INCIDENT_WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toIncidentFailure()
        val outcome = try {
            incidents.record(
                DriverIncidentWireCommand(
                    command.deliveryId, command.attemptId, command.expectedVersion,
                    command.idempotencyKey, command.reason, command.description, command.place,
                    command.frozenBody
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverIncidentResult.UnknownOutcome
        }
        // A request may have committed before the verified identity or lease changed.
        if (!currentAfter(authority, before.lease)) return DriverIncidentResult.UnknownOutcome
        return when (outcome) {
            is DriverIncidentNetworkOutcome.Recorded -> {
                val incident = outcome.incident
                if (incident.deliveryId != command.deliveryId || incident.attemptId != command.attemptId ||
                    incident.recordedByMembershipId != authority.membershipId ||
                    incident.reason != command.reason || incident.description != command.description ||
                    incident.place != command.place || incident.deliveryVersion < command.expectedVersion
                ) {
                    DriverIncidentResult.UnknownOutcome
                } else {
                    DriverIncidentResult.Recorded(
                        DriverIncidentSummary(
                            incident.id, incident.deliveryId, incident.attemptId, incident.reason,
                            incident.description, incident.place, incident.recordedByMembershipId,
                            incident.recordedAt, incident.evidenceObjectIds, incident.deliveryVersion,
                            incident.replayed
                        )
                    )
                }
            }

            is DriverIncidentNetworkOutcome.Rejected -> DriverIncidentResult.Rejected(outcome.code)
            DriverIncidentNetworkOutcome.NotFound -> DriverIncidentResult.NotFound
            DriverIncidentNetworkOutcome.StaleVersion -> DriverIncidentResult.StaleVersion
            DriverIncidentNetworkOutcome.UnknownOutcome,
            DriverIncidentNetworkOutcome.Unavailable -> DriverIncidentResult.UnknownOutcome
            DriverIncidentNetworkOutcome.PermissionDenied -> DriverIncidentResult.PermissionDenied
            DriverIncidentNetworkOutcome.ContextInvalidated -> DriverIncidentResult.ContextInvalidated
            DriverIncidentNetworkOutcome.SessionInvalidated -> DriverIncidentResult.SessionInvalidated
            is DriverIncidentNetworkOutcome.EvidenceUploaded,
            is DriverIncidentNetworkOutcome.EvidenceStatus,
            is DriverIncidentNetworkOutcome.EvidenceAttached -> DriverIncidentResult.UnknownOutcome
        }
    }

    override suspend fun uploadEvidence(
        command: DriverIncidentEvidenceUploadCommand,
        authority: DriverDeliveryAuthority
    ): DriverIncidentEvidenceResult {
        val before = authorize(authority, INCIDENT_EVIDENCE_WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toEvidenceFailure()
        val candidate = command.candidate
        val outcome = try {
            incidents.uploadEvidence(
                command.incidentId, command.idempotencyKey, candidate.file, candidate.originalFilename,
                candidate.declaredContentType, candidate.byteSize, candidate.checksumSha256
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverIncidentEvidenceResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return DriverIncidentEvidenceResult.UnknownOutcome
        return when (outcome) {
            is DriverIncidentNetworkOutcome.EvidenceUploaded -> {
                val evidence = outcome.evidence
                if (evidence.subjectType != "DELIVERY_INCIDENT" || evidence.subjectId != command.incidentId ||
                    evidence.declaredContentType != candidate.declaredContentType || evidence.byteSize != candidate.byteSize ||
                    evidence.checksumSha256?.let { it != candidate.checksumSha256 } == true
                ) DriverIncidentEvidenceResult.UnknownOutcome
                else DriverIncidentEvidenceResult.Uploaded(evidence.toEvidenceProjection())
            }
            is DriverIncidentNetworkOutcome.Rejected -> DriverIncidentEvidenceResult.Rejected(outcome.code)
            DriverIncidentNetworkOutcome.NotFound -> DriverIncidentEvidenceResult.NotFound
            DriverIncidentNetworkOutcome.PermissionDenied -> DriverIncidentEvidenceResult.PermissionDenied
            DriverIncidentNetworkOutcome.ContextInvalidated -> DriverIncidentEvidenceResult.ContextInvalidated
            DriverIncidentNetworkOutcome.SessionInvalidated -> DriverIncidentEvidenceResult.SessionInvalidated
            else -> DriverIncidentEvidenceResult.UnknownOutcome
        }
    }

    override suspend fun evidenceStatus(
        evidenceId: String,
        authority: DriverDeliveryAuthority
    ): DriverIncidentEvidenceResult {
        val before = authorize(authority, INCIDENT_EVIDENCE_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toEvidenceFailure()
        val outcome = try {
            incidents.evidenceStatus(evidenceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverIncidentEvidenceResult.Unavailable
        }
        if (!currentAfter(authority, before.lease)) return DriverIncidentEvidenceResult.ContextInvalidated
        return when (outcome) {
            is DriverIncidentNetworkOutcome.EvidenceStatus -> DriverIncidentEvidenceResult.Current(
                outcome.evidence.toEvidenceProjection()
            )
            is DriverIncidentNetworkOutcome.Rejected -> DriverIncidentEvidenceResult.Rejected(outcome.code)
            DriverIncidentNetworkOutcome.NotFound -> DriverIncidentEvidenceResult.NotFound
            DriverIncidentNetworkOutcome.PermissionDenied -> DriverIncidentEvidenceResult.PermissionDenied
            DriverIncidentNetworkOutcome.ContextInvalidated -> DriverIncidentEvidenceResult.ContextInvalidated
            DriverIncidentNetworkOutcome.SessionInvalidated -> DriverIncidentEvidenceResult.SessionInvalidated
            else -> DriverIncidentEvidenceResult.Unavailable
        }
    }

    override suspend fun attachEvidence(
        command: DriverIncidentEvidenceAttachCommand,
        authority: DriverDeliveryAuthority
    ): DriverIncidentResult {
        val before = authorize(authority, INCIDENT_EVIDENCE_WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toIncidentFailure()
        val outcome = try {
            incidents.attachEvidence(
                command.deliveryId, command.attemptId, command.incidentId, command.evidenceId,
                command.expectedVersion, command.idempotencyKey, command.frozenBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverIncidentResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return DriverIncidentResult.UnknownOutcome
        return when (outcome) {
            is DriverIncidentNetworkOutcome.EvidenceAttached -> {
                val incident = outcome.incident
                if (incident.deliveryId != command.deliveryId || incident.attemptId != command.attemptId ||
                    incident.id != command.incidentId || command.evidenceId !in incident.evidenceObjectIds ||
                    incident.recordedByMembershipId != authority.membershipId ||
                    incident.deliveryVersion < command.expectedVersion
                ) DriverIncidentResult.UnknownOutcome
                else DriverIncidentResult.Recorded(incident.toIncidentSummary())
            }
            is DriverIncidentNetworkOutcome.Rejected -> DriverIncidentResult.Rejected(outcome.code)
            DriverIncidentNetworkOutcome.NotFound -> DriverIncidentResult.NotFound
            DriverIncidentNetworkOutcome.StaleVersion -> DriverIncidentResult.StaleVersion
            DriverIncidentNetworkOutcome.PermissionDenied -> DriverIncidentResult.PermissionDenied
            DriverIncidentNetworkOutcome.ContextInvalidated -> DriverIncidentResult.ContextInvalidated
            DriverIncidentNetworkOutcome.SessionInvalidated -> DriverIncidentResult.SessionInvalidated
            DriverIncidentNetworkOutcome.Unavailable -> DriverIncidentResult.Unavailable
            else -> DriverIncidentResult.UnknownOutcome
        }
    }

    private fun com.nexa.mobile.operations.core.network.DriverIncidentProjection.toIncidentSummary() =
        DriverIncidentSummary(id, deliveryId, attemptId, reason, description, place, recordedByMembershipId,
            recordedAt, evidenceObjectIds, deliveryVersion, replayed)

    private fun com.nexa.mobile.operations.core.network.DriverIncidentEvidenceProjection.toEvidenceProjection() =
        DriverIncidentEvidenceProjection(id, subjectType, subjectId, lifecycleStatus, declaredContentType,
            checksumSha256, byteSize)

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

    private fun authorityDrift(authority: DriverDeliveryAuthority): DriverIncidentCurrentDeliveryResult =
        if (sessions.sessionState.value != SessionState.Active) {
            DriverIncidentCurrentDeliveryResult.SessionInvalidated
        } else if (sessions.verifiedSession.value?.matches(authority) == true) {
            DriverIncidentCurrentDeliveryResult.SessionInvalidated
        } else {
            DriverIncidentCurrentDeliveryResult.ContextInvalidated
        }

    private fun VerifiedSession.matches(authority: DriverDeliveryAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toCurrentFailure(): DriverIncidentCurrentDeliveryResult = when (this) {
        Authorization.SessionInvalidated -> DriverIncidentCurrentDeliveryResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverIncidentCurrentDeliveryResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverIncidentCurrentDeliveryResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toIncidentFailure(): DriverIncidentResult = when (this) {
        Authorization.SessionInvalidated -> DriverIncidentResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverIncidentResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverIncidentResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toEvidenceFailure(): DriverIncidentEvidenceResult = when (this) {
        Authorization.SessionInvalidated -> DriverIncidentEvidenceResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverIncidentEvidenceResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverIncidentEvidenceResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val DRIVER_READ_PERMISSIONS = setOf("dispatch.read", "logistics:read")
        val INCIDENT_WRITE_PERMISSIONS = setOf("dispatch.start_route")
        val INCIDENT_EVIDENCE_WRITE_PERMISSIONS = setOf("dispatch.start_route", "document.upload")
        val INCIDENT_EVIDENCE_READ_PERMISSIONS = setOf("dispatch.read", "document.read")
    }
}

/** Factory seam for Root navigation; Root owns route construction and lifecycle. */
internal class DriverDeliveryIncidentGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverIncidentGateway,
    private val metadataStore: DriverIncidentMetadataStore
) {
    suspend fun stageReturnedEvidence(
        context: DriverIncidentSelectionContext,
        candidate: DriverProofFileCandidate
    ): DriverIncidentMetadataWrite = metadataStore.stageReturnedEvidence(context, candidate)

    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriverDeliveryIncidentViewModel::class.java))
            return DriverDeliveryIncidentViewModel(gateway, metadataStore) as T
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DriverIncidentGatewayModule {
    @Provides
    @Singleton
    fun provideNexaDriverIncidentGateway(protectedCalls: ProtectedCallExecutor) =
        NexaDriverIncidentGateway(protectedCalls)

    @Provides
    @Singleton
    fun provideDriverIncidentGatewayBindings(
        gateway: OperationsDriverIncidentGateway,
        metadataStore: DriverIncidentMetadataStore
    ) = DriverDeliveryIncidentGatewayBindings(gateway, metadataStore)
}
