package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.InboundDiscrepancyCaseProjection
import com.nexa.mobile.operations.core.network.InboundDiscrepancyEvidenceProjection
import com.nexa.mobile.operations.core.network.InboundDiscrepancyNetworkOutcome as InboundDiscrepancyOutcome
import com.nexa.mobile.operations.core.network.NexaInboundDiscrepancyGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyAuthority
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyCase
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyCreateCommand
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyEvidence
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyEvidenceStatusResult
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyGateway
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyMutationResult
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancySubmitCommand
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyUploadCommand
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
internal class OperationsInboundDiscrepancyGateway(
    private val sessions: SessionCoordinator,
    private val transport: NexaInboundDiscrepancyGateway
) : InboundDiscrepancyGateway {
    override suspend fun createCase(
        command: InboundDiscrepancyCreateCommand,
        authority: InboundDiscrepancyAuthority
    ): InboundDiscrepancyMutationResult {
        val before = authorize(authority, RECEIVE_PERMISSION)
        if (before !is Authorization.Current) return before.toMutationFailure()
        val result =
            safeMutation {
                transport.createCase(command.idempotencyKey, command.frozenBody).toMutationResult()
            }
        return if (currentAfter(
                authority,
                before.lease,
                RECEIVE_PERMISSION
            )
        ) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun uploadEvidence(
        command: InboundDiscrepancyUploadCommand,
        authority: InboundDiscrepancyAuthority
    ): InboundDiscrepancyMutationResult {
        if (command.candidate.file.length() != command.candidate.byteSize) {
            return InboundDiscrepancyMutationResult.Rejected("INVALID_EVIDENCE")
        }
        val before = authorize(authority, DOCUMENT_UPLOAD_PERMISSION)
        if (before !is Authorization.Current) return before.toMutationFailure()
        val candidate = command.candidate
        val result = safeMutation {
            transport.uploadEvidence(
                command.caseId,
                command.idempotencyKey,
                candidate.file,
                candidate.originalFilename,
                candidate.declaredContentType,
                candidate.byteSize,
                candidate.checksumSha256
            ).toMutationResult()
        }
        return if (currentAfter(
                authority,
                before.lease,
                DOCUMENT_UPLOAD_PERMISSION
            )
        ) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun evidenceStatus(
        evidenceId: String,
        caseId: String,
        authority: InboundDiscrepancyAuthority
    ): InboundDiscrepancyEvidenceStatusResult {
        val before = authorize(authority, DOCUMENT_READ_PERMISSION)
        if (before !is Authorization.Current) return before.toEvidenceFailure()
        val result = try {
            when (val response = transport.evidenceStatus(evidenceId, caseId)) {
                is InboundDiscrepancyOutcome.EvidenceStatus ->
                    InboundDiscrepancyEvidenceStatusResult.Loaded(
                        response.value.toFeatureEvidence()
                    )

                is InboundDiscrepancyOutcome.Rejected ->
                    InboundDiscrepancyEvidenceStatusResult.Rejected(
                        response.code
                    )

                InboundDiscrepancyOutcome.PermissionDenied ->
                    InboundDiscrepancyEvidenceStatusResult.PermissionDenied

                InboundDiscrepancyOutcome.ContextInvalidated ->
                    InboundDiscrepancyEvidenceStatusResult.ContextInvalidated

                InboundDiscrepancyOutcome.SessionInvalidated ->
                    InboundDiscrepancyEvidenceStatusResult.SessionInvalidated

                else -> InboundDiscrepancyEvidenceStatusResult.ServiceUnavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            InboundDiscrepancyEvidenceStatusResult.ServiceUnavailable
        }
        return if (currentAfter(authority, before.lease, DOCUMENT_READ_PERMISSION)) {
            result
        } else {
            when (authorityDrift(authority)) {
                InboundDiscrepancyMutationResult.SessionInvalidated ->
                    InboundDiscrepancyEvidenceStatusResult.SessionInvalidated

                else -> InboundDiscrepancyEvidenceStatusResult.ContextInvalidated
            }
        }
    }

    override suspend fun submitForReview(
        command: InboundDiscrepancySubmitCommand,
        authority: InboundDiscrepancyAuthority
    ): InboundDiscrepancyMutationResult {
        val before = authorize(authority, RECEIVE_PERMISSION, SUBMIT_DOCUMENT_PERMISSIONS)
        if (before !is Authorization.Current) return before.toMutationFailure()
        val result = safeMutation {
            transport.submitForReview(
                command.caseId,
                command.evidenceId,
                command.expectedVersion,
                command.idempotencyKey,
                command.frozenBody
            ).toMutationResult()
        }
        return if (currentAfter(
                authority,
                before.lease,
                RECEIVE_PERMISSION,
                SUBMIT_DOCUMENT_PERMISSIONS
            )
        ) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    private suspend fun authorize(
        authority: InboundDiscrepancyAuthority,
        required: Set<String>,
        requiredAll: Set<String> = emptySet()
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (authority.authorityEpoch <= 0 ||
            !verified.matches(authority)
        ) {
            return Authorization.ContextInvalidated
        }
        return if (verified.permissions.any(required::contains) &&
            verified.permissions.containsAll(requiredAll)
        ) {
            Authorization.Current(lease)
        } else {
            Authorization.PermissionDenied
        }
    }

    private suspend fun currentAfter(
        authority: InboundDiscrepancyAuthority,
        originalLease: AccessTokenLease,
        required: Set<String>,
        requiredAll: Set<String> = emptySet()
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        val current = sessions.verifiedSession.value ?: return false
        return current.matches(authority) && current.permissions.any(required::contains) &&
            current.permissions.containsAll(requiredAll)
    }

    private fun VerifiedSession.matches(authority: InboundDiscrepancyAuthority): Boolean =
        hasAuthorizedContext && userId == authority.scope.userId &&
            tenantId == authority.scope.tenantId &&
            workspaceId == authority.scope.workspaceId &&
            membershipId == authority.scope.membershipId

    private fun authorityDrift(
        authority: InboundDiscrepancyAuthority
    ): InboundDiscrepancyMutationResult = when {
        sessions.sessionState.value != SessionState.Active ->
            InboundDiscrepancyMutationResult.SessionInvalidated

        sessions.verifiedSession.value?.matches(
            authority
        ) == true -> InboundDiscrepancyMutationResult.SessionInvalidated

        else -> InboundDiscrepancyMutationResult.ContextInvalidated
    }

    private fun Authorization.toMutationFailure(): InboundDiscrepancyMutationResult = when (this) {
        Authorization.SessionInvalidated -> InboundDiscrepancyMutationResult.SessionInvalidated
        Authorization.ContextInvalidated -> InboundDiscrepancyMutationResult.ContextInvalidated
        Authorization.PermissionDenied -> InboundDiscrepancyMutationResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toEvidenceFailure(): InboundDiscrepancyEvidenceStatusResult =
        when (this) {
            Authorization.SessionInvalidated ->
                InboundDiscrepancyEvidenceStatusResult.SessionInvalidated

            Authorization.ContextInvalidated ->
                InboundDiscrepancyEvidenceStatusResult.ContextInvalidated

            Authorization.PermissionDenied ->
                InboundDiscrepancyEvidenceStatusResult.PermissionDenied

            is Authorization.Current -> error("authorized result is not a failure")
        }

    private fun InboundDiscrepancyOutcome.toMutationResult(): InboundDiscrepancyMutationResult =
        when (this) {
            is InboundDiscrepancyOutcome.CaseConfirmed ->
                InboundDiscrepancyMutationResult.CaseConfirmed(
                    value.toFeatureCase()
                )

            is InboundDiscrepancyOutcome.EvidenceUploaded ->
                InboundDiscrepancyMutationResult.EvidenceUploaded(
                    value.toFeatureEvidence()
                )

            is InboundDiscrepancyOutcome.Rejected ->
                InboundDiscrepancyMutationResult.Rejected(
                    code
                )

            InboundDiscrepancyOutcome.PreconditionFailed ->
                InboundDiscrepancyMutationResult.PreconditionFailed

            InboundDiscrepancyOutcome.Conflict -> InboundDiscrepancyMutationResult.Conflict

            InboundDiscrepancyOutcome.UnknownOutcome ->
                InboundDiscrepancyMutationResult.UnknownOutcome

            InboundDiscrepancyOutcome.NetworkUnavailable ->
                InboundDiscrepancyMutationResult.NetworkUnavailable

            InboundDiscrepancyOutcome.ServiceUnavailable ->
                InboundDiscrepancyMutationResult.ServiceUnavailable

            InboundDiscrepancyOutcome.PermissionDenied ->
                InboundDiscrepancyMutationResult.PermissionDenied

            InboundDiscrepancyOutcome.ContextInvalidated ->
                InboundDiscrepancyMutationResult.ContextInvalidated

            InboundDiscrepancyOutcome.SessionInvalidated ->
                InboundDiscrepancyMutationResult.SessionInvalidated

            is InboundDiscrepancyOutcome.EvidenceStatus ->
                InboundDiscrepancyMutationResult.UnknownOutcome
        }

    private fun InboundDiscrepancyCaseProjection.toFeatureCase() = InboundDiscrepancyCase(
        id, warehouseId, expectedSkuId, observedSkuId, expectedBatchReference,
        observedBatchReference,
        expectedQuantity.toPlainString(), observedQuantity.toPlainString(),
        unit, reason, observationNotes,
        status, evidenceObjectId, version, recordedByMembershipId,
        recordedAt, submittedByMembershipId, submittedAt
    )

    private fun InboundDiscrepancyEvidenceProjection.toFeatureEvidence() =
        InboundDiscrepancyEvidence(
            id,
            subjectType,
            subjectId,
            lifecycleStatus,
            declaredContentType,
            checksumSha256,
            byteSize
        )

    private suspend fun safeMutation(
        action: suspend () -> InboundDiscrepancyMutationResult
    ): InboundDiscrepancyMutationResult = try {
        action()
    } catch (
        cancelled: CancellationException
    ) {
        throw cancelled
    } catch (_: Exception) {
        InboundDiscrepancyMutationResult.UnknownOutcome
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val RECEIVE_PERMISSION = setOf("inventory.receive", "warehouse:write")
        val DOCUMENT_UPLOAD_PERMISSION = setOf("document.upload")
        val DOCUMENT_READ_PERMISSION = setOf("document.read")
        val SUBMIT_DOCUMENT_PERMISSIONS = setOf("document.upload", "document.read")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object InboundDiscrepancyGatewayModule {
    @Provides
    @Singleton
    fun nexInboundDiscrepancyTransport(protectedCalls: ProtectedCallExecutor) =
        NexaInboundDiscrepancyGateway(protectedCalls)

    @Provides
    @Singleton
    fun inboundDiscrepancyGateway(
        sessions: SessionCoordinator,
        transport: NexaInboundDiscrepancyGateway
    ): InboundDiscrepancyGateway = OperationsInboundDiscrepancyGateway(sessions, transport)
}
