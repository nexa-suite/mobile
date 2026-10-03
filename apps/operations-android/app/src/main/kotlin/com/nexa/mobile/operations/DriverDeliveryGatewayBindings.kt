package com.nexa.mobile.operations

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DriverBusinessEvidenceProjection as BusinessEvidenceProjection
import com.nexa.mobile.operations.core.network.DriverDeliveryAttemptProjection as AttemptProjection
import com.nexa.mobile.operations.core.network.DriverDeliveryNetworkOutcome as Outcome
import com.nexa.mobile.operations.core.network.DriverDeliveryProjection
import com.nexa.mobile.operations.core.network.DriverProofOfDeliveryProjection as ProofOfDeliveryProjection
import com.nexa.mobile.operations.core.network.DriverRemainingQuantityLineProjection as RemainingQuantityLineProjection
import com.nexa.mobile.operations.core.network.NexaDriverDeliveryGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.delivery.DriverArrivalCommand
import com.nexa.mobile.operations.feature.delivery.DriverArrivalMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverArrivalResult
import com.nexa.mobile.operations.feature.delivery.DriverArrivalSummary
import com.nexa.mobile.operations.feature.delivery.DriverAttemptMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverAttemptStartCommand
import com.nexa.mobile.operations.feature.delivery.DriverAttemptStartResult
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryArrivalFact
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAttempt
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryGateway
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryLoadResult
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOutcomeLine
import com.nexa.mobile.operations.feature.delivery.DriverDeliverySnapshot
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryViewModel
import com.nexa.mobile.operations.feature.delivery.DriverOutcomeCommand
import com.nexa.mobile.operations.feature.delivery.DriverOutcomeMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverOutcomeResult
import com.nexa.mobile.operations.feature.delivery.DriverOutcomeSummary
import com.nexa.mobile.operations.feature.delivery.DriverProofAttachCommand
import com.nexa.mobile.operations.feature.delivery.DriverProofAttachResult
import com.nexa.mobile.operations.feature.delivery.DriverProofCreateCommand
import com.nexa.mobile.operations.feature.delivery.DriverProofCreateResult
import com.nexa.mobile.operations.feature.delivery.DriverProofEvidenceKind
import com.nexa.mobile.operations.feature.delivery.DriverProofEvidenceStatusResult as ProofEvidenceStatusResult
import com.nexa.mobile.operations.feature.delivery.DriverProofEvidenceSummary
import com.nexa.mobile.operations.feature.delivery.DriverProofMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverProofSummary
import com.nexa.mobile.operations.feature.delivery.DriverProofUploadCommand
import com.nexa.mobile.operations.feature.delivery.DriverProofUploadResult
import com.nexa.mobile.operations.feature.delivery.DriverRemainingQuantityLine as RemainingQuantityLine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Hands the server-authorized destination snapshot to an external navigation app. */
fun launchDriverDirections(context: Context, destination: String): Boolean {
    val address = destination.trim().takeIf(String::isNotEmpty) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(address)}".toUri())
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** App boundary binds driver reads and starts to full verified scope and session epoch. */
@Singleton
internal class OperationsDriverDeliveryGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val deliveryApi: NexaDriverDeliveryGateway
) : DriverDeliveryGateway {
    override suspend fun assignedDeliveries(
        authority: DriverDeliveryAuthority
    ): DriverDeliveryLoadResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLoadFailure()
        val outcome = try {
            deliveryApi.assignedDeliveries()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverDeliveryLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        return when (outcome) {
            is Outcome.Assigned -> DriverDeliveryLoadResult.ListLoaded(
                outcome.items.map { it.toFeature() }
            )

            Outcome.NotFound -> DriverDeliveryLoadResult.NotFound

            else -> outcome.toLoadFailure()
        }
    }

    override suspend fun delivery(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryLoadResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLoadFailure()
        val outcome = try {
            deliveryApi.delivery(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverDeliveryLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        return when (outcome) {
            is Outcome.Detail -> DriverDeliveryLoadResult.DetailLoaded(
                outcome.item.toFeature()
            )

            Outcome.NotFound -> DriverDeliveryLoadResult.NotFound

            else -> outcome.toLoadFailure()
        }
    }

    override suspend fun startAttempt(
        command: DriverAttemptStartCommand,
        authority: DriverDeliveryAuthority
    ): DriverAttemptStartResult {
        val before = authorize(authority, DRIVER_START_PERMISSIONS)
        if (before !is Authorization.Current) return before.toStartFailure()
        val outcome = try {
            deliveryApi.startAttempt(
                command.deliveryId,
                command.expectedVersion,
                command.idempotencyKey
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverAttemptStartResult.UnknownOutcome
        }
        // A dispatched mutation followed by authority drift has an unknown server outcome.
        if (!currentAfter(authority, before.lease)) return DriverAttemptStartResult.UnknownOutcome
        return when (outcome) {
            is Outcome.Started -> DriverAttemptStartResult.Started(
                outcome.delivery.toFeature(),
                outcome.attempt.toFeature()
            )

            is Outcome.Rejected -> DriverAttemptStartResult.Rejected(
                outcome.code
            )

            Outcome.StaleVersion -> DriverAttemptStartResult.StaleVersion

            Outcome.NotFound -> DriverAttemptStartResult.NotFound

            Outcome.UnknownOutcome -> DriverAttemptStartResult.UnknownOutcome

            Outcome.NetworkUnavailable ->
                DriverAttemptStartResult.UnknownOutcome

            Outcome.ServiceUnavailable ->
                DriverAttemptStartResult.UnknownOutcome

            Outcome.PermissionDenied ->
                DriverAttemptStartResult.PermissionDenied

            Outcome.ContextInvalidated ->
                DriverAttemptStartResult.ContextInvalidated

            Outcome.SessionInvalidated ->
                DriverAttemptStartResult.SessionInvalidated

            is Outcome.Assigned,
            is Outcome.Detail,
            is Outcome.OutcomeRecorded,
            is Outcome.ArrivalRecorded,
            is Outcome.ProofCreated,
            is Outcome.ProofEvidenceUploaded,
            is Outcome.ProofEvidenceStatus,
            is Outcome.ProofEvidenceAttached ->
                DriverAttemptStartResult.ServiceUnavailable
        }
    }

    override suspend fun recordOutcome(
        command: DriverOutcomeCommand,
        authority: DriverDeliveryAuthority
    ): DriverOutcomeResult {
        val before = authorize(authority, DRIVER_START_PERMISSIONS)
        if (before !is Authorization.Current) return before.toOutcomeFailure()
        val outcome = try {
            deliveryApi.recordOutcome(
                command.deliveryId,
                command.attemptId,
                command.expectedVersion,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverOutcomeResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return DriverOutcomeResult.UnknownOutcome
        return when (outcome) {
            is Outcome.OutcomeRecorded -> {
                val value = outcome.value
                if (
                    value.attemptId != command.attemptId ||
                    value.deliveryId != command.deliveryId ||
                    value.outcome != command.outcome.name
                ) {
                    DriverOutcomeResult.UnknownOutcome
                } else {
                    DriverOutcomeResult.Recorded(
                        DriverOutcomeSummary(
                            value.attemptId,
                            value.outcome,
                            value.attemptedAt,
                            value.deliveryVersion,
                            value.partial,
                            value.remainingLines.map { line ->
                                RemainingQuantityLine(
                                    line.fulfillmentLineId,
                                    line.skuId,
                                    line.catalogItemId,
                                    line.quantity,
                                    line.unit
                                )
                            }
                        )
                    )
                }
            }

            is Outcome.Rejected -> DriverOutcomeResult.Rejected(outcome.code)

            Outcome.NotFound -> DriverOutcomeResult.NotFound

            Outcome.StaleVersion -> DriverOutcomeResult.StaleVersion

            Outcome.UnknownOutcome,
            Outcome.NetworkUnavailable,
            Outcome.ServiceUnavailable -> DriverOutcomeResult.UnknownOutcome

            Outcome.PermissionDenied -> DriverOutcomeResult.PermissionDenied

            Outcome.ContextInvalidated ->
                DriverOutcomeResult.ContextInvalidated

            Outcome.SessionInvalidated ->
                DriverOutcomeResult.SessionInvalidated

            is Outcome.Assigned,
            is Outcome.Detail,
            is Outcome.Started,
            is Outcome.ArrivalRecorded,
            is Outcome.ProofCreated,
            is Outcome.ProofEvidenceUploaded,
            is Outcome.ProofEvidenceStatus,
            is Outcome.ProofEvidenceAttached ->
                DriverOutcomeResult.ServiceUnavailable
        }
    }

    override suspend fun signalArrival(
        command: DriverArrivalCommand,
        authority: DriverDeliveryAuthority
    ): DriverArrivalResult {
        val before = authorize(authority, DRIVER_START_PERMISSIONS)
        if (before !is Authorization.Current) return before.toArrivalFailure()
        val outcome = try {
            deliveryApi.signalArrival(
                command.deliveryId,
                command.attemptId,
                command.expectedVersion,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverArrivalResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return DriverArrivalResult.UnknownOutcome
        return when (outcome) {
            is Outcome.ArrivalRecorded -> {
                val value = outcome.value
                if (value.deliveryId != command.deliveryId ||
                    value.attemptId != command.attemptId ||
                    value.actorMembershipId != authority.membershipId ||
                    value.deliveryVersion < command.expectedVersion || value.arrivedAt.isBlank()
                ) {
                    DriverArrivalResult.UnknownOutcome
                } else {
                    DriverArrivalResult.Recorded(
                        DriverArrivalSummary(
                            value.id,
                            value.deliveryId,
                            value.attemptId,
                            value.arrivedAt,
                            value.deliveryVersion
                        )
                    )
                }
            }

            is Outcome.Rejected -> DriverArrivalResult.Rejected(outcome.code)

            Outcome.NotFound -> DriverArrivalResult.NotFound

            Outcome.StaleVersion -> DriverArrivalResult.StaleVersion

            Outcome.UnknownOutcome,
            Outcome.NetworkUnavailable,
            Outcome.ServiceUnavailable -> DriverArrivalResult.UnknownOutcome

            Outcome.PermissionDenied -> DriverArrivalResult.PermissionDenied

            Outcome.ContextInvalidated ->
                DriverArrivalResult.ContextInvalidated

            Outcome.SessionInvalidated ->
                DriverArrivalResult.SessionInvalidated

            is Outcome.Assigned,
            is Outcome.Detail,
            is Outcome.Started,
            is Outcome.OutcomeRecorded,
            is Outcome.ProofCreated,
            is Outcome.ProofEvidenceUploaded,
            is Outcome.ProofEvidenceStatus,
            is Outcome.ProofEvidenceAttached ->
                DriverArrivalResult.ServiceUnavailable
        }
    }

    /** Proof operations require both current driver authority and evidence-upload authority. */
    private suspend fun authorizeProof(authority: DriverDeliveryAuthority): Authorization {
        val current = authorize(authority, setOf("dispatch.start_route"))
        if (current !is Authorization.Current) return current
        return if ("document.upload" in
            authority.permissions
        ) {
            current
        } else {
            Authorization.PermissionDenied
        }
    }

    override suspend fun createProof(
        command: DriverProofCreateCommand,
        authority: DriverDeliveryAuthority
    ): DriverProofCreateResult {
        val before = authorize(authority, setOf("dispatch.start_route"))
        if (before !is Authorization.Current) {
            return when (before) {
                Authorization.SessionInvalidated -> DriverProofCreateResult.SessionInvalidated
                Authorization.ContextInvalidated -> DriverProofCreateResult.ContextInvalidated
                else -> DriverProofCreateResult.PermissionDenied
            }
        }
        val result = try {
            deliveryApi.createProofOfDelivery(
                command.deliveryId,
                command.attemptId,
                command.expectedVersion,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (
            cancelled: CancellationException
        ) {
            throw cancelled
        } catch (_: Exception) {
            return DriverProofCreateResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return DriverProofCreateResult.UnknownOutcome
        return when (result) {
            is Outcome.ProofCreated -> if (result.value.deliveryId ==
                command.deliveryId &&
                result.value.attemptId == command.attemptId &&
                result.value.actorMembershipId == authority.membershipId
            ) {
                DriverProofCreateResult.Created(result.value.toProof())
            } else {
                DriverProofCreateResult.UnknownOutcome
            }

            is Outcome.Rejected -> DriverProofCreateResult.Rejected(
                result.code
            )

            Outcome.NotFound -> DriverProofCreateResult.NotFound

            Outcome.StaleVersion -> DriverProofCreateResult.StaleVersion

            Outcome.PermissionDenied ->
                DriverProofCreateResult.PermissionDenied

            Outcome.ContextInvalidated ->
                DriverProofCreateResult.ContextInvalidated

            Outcome.SessionInvalidated ->
                DriverProofCreateResult.SessionInvalidated

            else -> DriverProofCreateResult.UnknownOutcome
        }
    }

    override suspend fun uploadProofEvidence(
        command: DriverProofUploadCommand,
        authority: DriverDeliveryAuthority
    ): DriverProofUploadResult {
        val before = authorizeProof(authority)
        if (before !is Authorization.Current) {
            return when (before) {
                Authorization.SessionInvalidated -> DriverProofUploadResult.SessionInvalidated
                Authorization.ContextInvalidated -> DriverProofUploadResult.ContextInvalidated
                else -> DriverProofUploadResult.PermissionDenied
            }
        }
        val file = command.candidate
        val result = try {
            deliveryApi.uploadProofEvidence(
                command.proofId,
                command.idempotencyKey,
                file.file,
                file.originalFilename,
                file.declaredContentType,
                file.byteSize,
                file.checksumSha256
            )
        } catch (
            cancelled: CancellationException
        ) {
            throw cancelled
        } catch (_: Exception) {
            return DriverProofUploadResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return DriverProofUploadResult.UnknownOutcome
        return when (result) {
            is Outcome.ProofEvidenceUploaded -> if (result.value.subjectType ==
                "PROOF_OF_DELIVERY" &&
                result.value.subjectId == command.proofId &&
                result.value.byteSize == file.byteSize &&
                result.value.declaredContentType == file.declaredContentType &&
                result.value.checksumSha256?.equals(file.checksumSha256, ignoreCase = true) == true
            ) {
                DriverProofUploadResult.Uploaded(result.value.toEvidence())
            } else {
                DriverProofUploadResult.UnknownOutcome
            }

            is Outcome.Rejected -> DriverProofUploadResult.Rejected(
                result.code
            )

            Outcome.NotFound -> DriverProofUploadResult.NotFound

            Outcome.PermissionDenied ->
                DriverProofUploadResult.PermissionDenied

            Outcome.ContextInvalidated ->
                DriverProofUploadResult.ContextInvalidated

            Outcome.SessionInvalidated ->
                DriverProofUploadResult.SessionInvalidated

            else -> DriverProofUploadResult.UnknownOutcome
        }
    }

    override suspend fun proofEvidenceStatus(
        evidenceId: String,
        proofId: String,
        authority: DriverDeliveryAuthority
    ): ProofEvidenceStatusResult {
        val before = authorize(authority, setOf("document.read"))
        if (before !is Authorization.Current) {
            return when (before) {
                Authorization.SessionInvalidated ->
                    ProofEvidenceStatusResult.SessionInvalidated

                Authorization.ContextInvalidated ->
                    ProofEvidenceStatusResult.ContextInvalidated

                else -> ProofEvidenceStatusResult.PermissionDenied
            }
        }
        val result = try {
            deliveryApi.proofEvidence(evidenceId)
        } catch (
            cancelled: CancellationException
        ) {
            throw cancelled
        } catch (
            _: Exception
        ) {
            return ProofEvidenceStatusResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) {
            return when (authorityDrift(authority)) {
                DriverDeliveryLoadResult.ContextInvalidated ->
                    ProofEvidenceStatusResult.ContextInvalidated

                else -> ProofEvidenceStatusResult.SessionInvalidated
            }
        }
        return when (result) {
            is Outcome.ProofEvidenceStatus ->
                if (result.value.id == evidenceId &&
                    result.value.subjectType == "PROOF_OF_DELIVERY" &&
                    result.value.subjectId == proofId
                ) {
                    ProofEvidenceStatusResult.Loaded(result.value.toEvidence())
                } else {
                    ProofEvidenceStatusResult.ServiceUnavailable
                }

            Outcome.NotFound -> ProofEvidenceStatusResult.NotFound

            Outcome.PermissionDenied ->
                ProofEvidenceStatusResult.PermissionDenied

            Outcome.ContextInvalidated ->
                ProofEvidenceStatusResult.ContextInvalidated

            Outcome.SessionInvalidated ->
                ProofEvidenceStatusResult.SessionInvalidated

            else -> ProofEvidenceStatusResult.ServiceUnavailable
        }
    }

    override suspend fun attachProofEvidence(
        command: DriverProofAttachCommand,
        authority: DriverDeliveryAuthority
    ): DriverProofAttachResult {
        val before = authorizeProof(authority)
        if (before !is Authorization.Current) {
            return when (before) {
                Authorization.SessionInvalidated -> DriverProofAttachResult.SessionInvalidated
                Authorization.ContextInvalidated -> DriverProofAttachResult.ContextInvalidated
                else -> DriverProofAttachResult.PermissionDenied
            }
        }
        val result = try {
            deliveryApi.attachProofEvidence(
                command.deliveryId,
                command.attemptId,
                command.proofId,
                command.expectedVersion,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (
            cancelled: CancellationException
        ) {
            throw cancelled
        } catch (_: Exception) {
            return DriverProofAttachResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return DriverProofAttachResult.UnknownOutcome
        return when (result) {
            is Outcome.ProofEvidenceAttached -> {
                val proof = result.value
                val attachedId = when (command.evidenceKind) {
                    DriverProofEvidenceKind.PHOTO -> proof.photoEvidenceObjectId
                    DriverProofEvidenceKind.SIGNATURE -> proof.signatureEvidenceObjectId
                }
                if (proof.id == command.proofId && proof.deliveryId == command.deliveryId &&
                    proof.attemptId == command.attemptId &&
                    proof.actorMembershipId == authority.membershipId &&
                    attachedId == command.evidenceObjectId
                ) {
                    DriverProofAttachResult.Attached(proof.toProof())
                } else {
                    DriverProofAttachResult.UnknownOutcome
                }
            }

            is Outcome.Rejected -> DriverProofAttachResult.Rejected(
                result.code
            )

            Outcome.NotFound -> DriverProofAttachResult.NotFound

            Outcome.StaleVersion -> DriverProofAttachResult.StaleVersion

            Outcome.PermissionDenied ->
                DriverProofAttachResult.PermissionDenied

            Outcome.ContextInvalidated ->
                DriverProofAttachResult.ContextInvalidated

            Outcome.SessionInvalidated ->
                DriverProofAttachResult.SessionInvalidated

            else -> DriverProofAttachResult.UnknownOutcome
        }
    }

    private fun ProofOfDeliveryProjection.toProof() = DriverProofSummary(
        proofId = id, deliveryId = deliveryId, attemptId = attemptId,
        status = status, receiverName = receiverName,
        capturedAt = capturedAt, photoEvidenceObjectId = photoEvidenceObjectId,
        signatureEvidenceObjectId = signatureEvidenceObjectId, deliveryVersion = deliveryVersion,
        actorMembershipId = actorMembershipId
    )

    private fun BusinessEvidenceProjection.toEvidence() = DriverProofEvidenceSummary(
        evidenceId = id,
        lifecycleStatus = lifecycleStatus,
        contentType = declaredContentType,
        checksumSha256 = checksumSha256,
        byteSize = byteSize,
        subjectType = subjectType,
        subjectId = subjectId
    )

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
        if (authority.permissions.none {
                it in requiredPermissions
            }
        ) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: DriverDeliveryAuthority,
        originalLease: AccessTokenLease
    ): Boolean = sessions.sessionState.value == SessionState.Active &&
        sessions.isEpochCurrent(originalLease.epoch) &&
        sessions.verifiedSession.value?.matches(authority) == true

    private suspend fun authorityDrift(
        authority: DriverDeliveryAuthority
    ): DriverDeliveryLoadResult = if (sessions.sessionState.value != SessionState.Active) {
        DriverDeliveryLoadResult.SessionInvalidated
    } else if (sessions.verifiedSession.value?.matches(authority) == true) {
        DriverDeliveryLoadResult.SessionInvalidated
    } else {
        DriverDeliveryLoadResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(authority: DriverDeliveryAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLoadFailure(): DriverDeliveryLoadResult = when (this) {
        Authorization.SessionInvalidated -> DriverDeliveryLoadResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverDeliveryLoadResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverDeliveryLoadResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toStartFailure(): DriverAttemptStartResult = when (this) {
        Authorization.SessionInvalidated -> DriverAttemptStartResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverAttemptStartResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverAttemptStartResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Outcome.toLoadFailure(): DriverDeliveryLoadResult = when (this) {
        Outcome.NetworkUnavailable ->
            DriverDeliveryLoadResult.NetworkUnavailable

        Outcome.PermissionDenied ->
            DriverDeliveryLoadResult.PermissionDenied

        Outcome.ContextInvalidated ->
            DriverDeliveryLoadResult.ContextInvalidated

        Outcome.SessionInvalidated ->
            DriverDeliveryLoadResult.SessionInvalidated

        Outcome.NotFound -> DriverDeliveryLoadResult.NotFound

        else -> DriverDeliveryLoadResult.ServiceUnavailable
    }

    private fun Authorization.toOutcomeFailure(): DriverOutcomeResult = when (this) {
        Authorization.SessionInvalidated -> DriverOutcomeResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverOutcomeResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverOutcomeResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toArrivalFailure(): DriverArrivalResult = when (this) {
        Authorization.SessionInvalidated -> DriverArrivalResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverArrivalResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverArrivalResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun DriverDeliveryProjection.toFeature() = DriverDeliverySnapshot(
        id = id,
        fulfillmentId = fulfillmentId,
        salesOrderId = salesOrderId,
        status = status,
        destination = destinationSnapshot,
        scheduledAt = scheduledAt,
        dispatchedAt = dispatchedAt,
        deliveredAt = deliveredAt,
        updatedAt = updatedAt,
        version = version,
        activeAttempt = activeAttempt?.toFeature(),
        outcomeLines = outcomeLines.map { line ->
            DriverDeliveryOutcomeLine(
                line.fulfillmentLineId, line.skuId, line.catalogItemId,
                line.dispatchedQuantity, line.deliveredQuantity, line.rejectedQuantity,
                line.cancelledQuantity, line.remainingQuantity, line.unit
            )
        },
        arrival = arrival?.let { fact ->
            DriverDeliveryArrivalFact(fact.id, fact.attemptId, fact.arrivedAt)
        }
    )

    private fun AttemptProjection.toFeature() =
        DriverDeliveryAttempt(id, attemptNumber, status, startedByMembershipId, startedAt)

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val DRIVER_READ_PERMISSIONS = setOf("dispatch.read", "logistics:read")
        val DRIVER_START_PERMISSIONS = setOf("dispatch.start_route", "logistics:write")
    }
}

/** Integration entry for Root without feature dependencies on auth, network, or Hilt. */
internal class DriverDeliveryGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverDeliveryGateway,
    private val metadataStore: DriverAttemptMetadataStore,
    private val outcomeMetadataStore: DriverOutcomeMetadataStore,
    private val arrivalMetadataStore: DriverArrivalMetadataStore,
    private val proofMetadataStore: DriverProofMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriverDeliveryViewModel::class.java))
            return DriverDeliveryViewModel(
                gateway,
                metadataStore,
                outcomeMetadataStore = outcomeMetadataStore,
                arrivalMetadataStore = arrivalMetadataStore,
                proofMetadataStore = proofMetadataStore
            ) as T
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DriverDeliveryGatewayModule {
    @Provides
    @Singleton
    fun driverDeliveryGateway(protectedCalls: ProtectedCallExecutor) =
        NexaDriverDeliveryGateway(protectedCalls)
}
