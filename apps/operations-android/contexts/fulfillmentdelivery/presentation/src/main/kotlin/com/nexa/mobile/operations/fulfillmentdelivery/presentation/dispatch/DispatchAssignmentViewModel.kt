package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchAssignmentSnapshot
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DispatchAssignmentViewModel(
    private val gateway: DispatchAssignmentGateway,
    private val metadata: DispatchAssignmentMetadataStore,
    private val commandKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchAssignmentUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: DispatchAuthorityContext? = null
    private var activeFulfillmentId: String? = null
    private var pendingIntent: DispatchAssignmentIntent? = null
    private var restoringMetadata = false
    private var generation = 0L

    fun activate(fulfillmentId: String, context: DispatchAuthorityContext) {
        if (activeFulfillmentId == fulfillmentId && activeContext == context &&
            mutableState.value.status !in INVALIDATED_STATUS
        ) {
            return
        }
        activeFulfillmentId = fulfillmentId
        activeContext = context
        pendingIntent = null
        restoringMetadata = false
        generation++
        mutableState.value = DispatchAssignmentUiState(
            authorityEpoch = context.authorityEpoch,
            fulfillmentId = fulfillmentId,
            status = context.readPermissionHint(),
            assignmentPermission = context.hasAssignmentPermission()
        )
        if (mutableState.value.status == DispatchAssignmentStatus.Loading) {
            restoreIntentAndRefresh(fulfillmentId, context)
        }
    }

    fun refresh() {
        if (restoringMetadata) return
        val fulfillmentId = activeFulfillmentId ?: return
        val context = activeContext ?: return
        val permissionStatus = context.readPermissionHint()
        if (permissionStatus != DispatchAssignmentStatus.Loading) {
            generation++
            mutableState.value = DispatchAssignmentUiState(
                authorityEpoch = context.authorityEpoch,
                fulfillmentId = fulfillmentId,
                status = permissionStatus,
                assignmentPermission = context.hasAssignmentPermission()
            )
            return
        }
        val request = ++generation
        mutableState.value = DispatchAssignmentUiState(
            authorityEpoch = context.authorityEpoch,
            fulfillmentId = fulfillmentId,
            status = DispatchAssignmentStatus.Loading,
            assignmentPermission = context.hasAssignmentPermission()
        )
        viewModelScope.launch {
            val result = safeCall { gateway.load(fulfillmentId, context) }
            if (!isCurrent(request, context, fulfillmentId)) return@launch
            when (result) {
                is DispatchAssignmentGatewayResult.Snapshot -> {
                    val snapshot = result.value
                    if (!snapshot.isFor(fulfillmentId)) {
                        fail(DispatchAssignmentStatus.ServiceUnavailable)
                    } else {
                        mutableState.value = DispatchAssignmentUiState(
                            authorityEpoch = context.authorityEpoch,
                            fulfillmentId = fulfillmentId,
                            status = if (pendingIntent == null) {
                                DispatchAssignmentStatus.Current
                            } else {
                                DispatchAssignmentStatus.UnknownOutcome
                            },
                            readiness = snapshot.readiness,
                            candidates = snapshot.candidates,
                            selectedMembershipId = snapshot.assignment?.responsibleMembershipId
                                ?: pendingIntent?.responsibleMembershipId,
                            assignmentPermission = context.hasAssignmentPermission(),
                            assignment = snapshot.assignment,
                            pendingIntent = pendingIntent
                        )
                    }
                }

                DispatchAssignmentGatewayResult.NotReady -> fail(DispatchAssignmentStatus.NotReady)

                DispatchAssignmentGatewayResult.Stale -> fail(DispatchAssignmentStatus.Stale)

                DispatchAssignmentGatewayResult.Conflict -> fail(DispatchAssignmentStatus.Conflict)

                DispatchAssignmentGatewayResult.UnknownOutcome -> fail(
                    DispatchAssignmentStatus.UnknownOutcome
                )

                DispatchAssignmentGatewayResult.NetworkUnavailable -> fail(
                    DispatchAssignmentStatus.NetworkUnavailable
                )

                DispatchAssignmentGatewayResult.ServiceUnavailable -> fail(
                    DispatchAssignmentStatus.ServiceUnavailable
                )

                DispatchAssignmentGatewayResult.PermissionDenied -> invalidate(
                    DispatchAssignmentStatus.PermissionDenied
                )

                DispatchAssignmentGatewayResult.ContextInvalidated -> invalidate(
                    DispatchAssignmentStatus.ContextInvalidated
                )

                DispatchAssignmentGatewayResult.SessionInvalidated -> invalidate(
                    DispatchAssignmentStatus.SessionInvalidated
                )

                is DispatchAssignmentGatewayResult.Assigned -> fail(
                    DispatchAssignmentStatus.ServiceUnavailable
                )
            }
        }
    }

    fun selectDriver(membershipId: String) {
        val current = mutableState.value
        if (current.status != DispatchAssignmentStatus.Current || current.assignment != null ||
            pendingIntent != null ||
            current.candidates.none { it.membershipId == membershipId }
        ) {
            return
        }
        mutableState.value = current.copy(selectedMembershipId = membershipId)
    }

    fun assign() {
        val context = activeContext ?: return
        val current = mutableState.value
        val fulfillment = current.readiness ?: return
        val fulfillmentId = activeFulfillmentId ?: return
        val membershipId = current.selectedMembershipId ?: return
        if (!current.canAssign || current.status == DispatchAssignmentStatus.Saving ||
            pendingIntent != null
        ) {
            return
        }
        val intent = DispatchAssignmentIntent(
            scope = context.scopeIdentity() ?: return,
            fulfillmentId = fulfillment.fulfillmentId,
            expectedFulfillmentVersion = fulfillment.fulfillmentVersion,
            physicalAllocationId = fulfillment.physicalAllocationId,
            physicalAllocationVersion = fulfillment.physicalAllocationVersion,
            responsibleMembershipId = membershipId,
            idempotencyKey = commandKey()
        )
        val request = ++generation
        mutableState.value = current.copy(status = DispatchAssignmentStatus.Saving)
        viewModelScope.launch {
            val saved = metadataCall { metadata.saveIntent(intent) }
            if (!isCurrent(request, context, fulfillmentId)) return@launch
            when (saved) {
                DispatchAssignmentMetadataWrite.Saved -> pendingIntent = intent

                DispatchAssignmentMetadataWrite.Conflict -> {
                    fail(DispatchAssignmentStatus.Conflict)
                    return@launch
                }

                DispatchAssignmentMetadataWrite.Stale -> {
                    fail(DispatchAssignmentStatus.Stale)
                    return@launch
                }

                DispatchAssignmentMetadataWrite.Unavailable -> {
                    fail(DispatchAssignmentStatus.ServiceUnavailable)
                    return@launch
                }
            }
            if (!isCurrent(request, context, fulfillmentId)) return@launch
            val result =
                safeCall {
                    gateway.assign(fulfillment, membershipId, context, intent.idempotencyKey)
                }
            if (!isCurrent(request, context, fulfillmentId)) return@launch
            handleCommandResult(result, intent, request, context, fulfillmentId)
        }
    }

    fun retryUnknownOutcome() {
        val intent = pendingIntent ?: return
        val context = activeContext ?: return
        val fulfillmentId = activeFulfillmentId ?: return
        val current = mutableState.value
        if (!current.canReplay || intent.fulfillmentId != fulfillmentId ||
            intent.scope != context.scopeIdentity()
        ) {
            return
        }
        val request = ++generation
        mutableState.value = current.copy(status = DispatchAssignmentStatus.Saving)
        viewModelScope.launch {
            val result = safeCall { gateway.replay(intent, context) }
            if (!isCurrent(request, context, fulfillmentId)) return@launch
            handleCommandResult(result, intent, request, context, fulfillmentId)
        }
    }

    fun deactivate() {
        generation++
        activeContext = null
        activeFulfillmentId = null
        pendingIntent = null
        restoringMetadata = false
        mutableState.value = DispatchAssignmentUiState()
    }

    private fun restoreIntentAndRefresh(fulfillmentId: String, context: DispatchAuthorityContext) {
        val scope = context.scopeIdentity() ?: return
        val request = generation
        restoringMetadata = true
        viewModelScope.launch {
            val read = metadataReadCall { metadata.loadIntent(scope, fulfillmentId) }
            if (!isCurrent(request, context, fulfillmentId)) return@launch
            when (read) {
                DispatchAssignmentMetadataRead.Unavailable -> {
                    restoringMetadata = false
                    fail(DispatchAssignmentStatus.ServiceUnavailable)
                }

                is DispatchAssignmentMetadataRead.Available -> {
                    val loaded = read.intent
                    if (loaded != null && (
                            loaded.scope != scope ||
                                loaded.fulfillmentId != fulfillmentId
                            )
                    ) {
                        restoringMetadata = false
                        fail(DispatchAssignmentStatus.ServiceUnavailable)
                        return@launch
                    }
                    if (loaded?.status == DispatchAssignmentIntentStatus.Pending) {
                        val recovered = loaded.copy(
                            status = DispatchAssignmentIntentStatus.UnknownOutcome
                        )
                        if (metadataCall { metadata.saveIntent(recovered) } !=
                            DispatchAssignmentMetadataWrite.Saved
                        ) {
                            restoringMetadata = false
                            fail(DispatchAssignmentStatus.ServiceUnavailable)
                            return@launch
                        }
                        pendingIntent = recovered
                    } else {
                        pendingIntent = loaded
                    }
                    restoringMetadata = false
                    refresh()
                }
            }
        }
    }

    private suspend fun handleCommandResult(
        result: DispatchAssignmentGatewayResult,
        intent: DispatchAssignmentIntent,
        request: Long,
        context: DispatchAuthorityContext,
        fulfillmentId: String
    ) {
        when (result) {
            is DispatchAssignmentGatewayResult.Assigned -> {
                val assigned = result.value
                if (assigned.fulfillmentId != intent.fulfillmentId ||
                    assigned.responsibleMembershipId != intent.responsibleMembershipId ||
                    assigned.fulfillmentVersion != intent.expectedFulfillmentVersion + 1 ||
                    assigned.physicalAllocationId != intent.physicalAllocationId ||
                    assigned.physicalAllocationVersion != intent.physicalAllocationVersion
                ) {
                    markUnknown(intent, request, context, fulfillmentId)
                } else {
                    metadataCall {
                        metadata.clearIntent(
                            intent.scope,
                            intent.fulfillmentId,
                            intent.idempotencyKey
                        )
                    }
                    if (!isCurrent(request, context, fulfillmentId)) return
                    pendingIntent = null
                    mutableState.value = mutableState.value.copy(
                        status = DispatchAssignmentStatus.Current,
                        assignment = assigned,
                        selectedMembershipId = assigned.responsibleMembershipId,
                        pendingIntent = null
                    )
                }
            }

            is DispatchAssignmentGatewayResult.Snapshot -> fail(
                DispatchAssignmentStatus.ServiceUnavailable
            )

            DispatchAssignmentGatewayResult.NotReady -> rejectIntent(
                intent,
                DispatchAssignmentStatus.NotReady,
                request,
                context,
                fulfillmentId
            )

            DispatchAssignmentGatewayResult.Stale -> rejectIntent(
                intent,
                DispatchAssignmentStatus.Stale,
                request,
                context,
                fulfillmentId
            )

            DispatchAssignmentGatewayResult.Conflict -> rejectIntent(
                intent,
                DispatchAssignmentStatus.Conflict,
                request,
                context,
                fulfillmentId
            )

            DispatchAssignmentGatewayResult.UnknownOutcome -> markUnknown(
                intent,
                request,
                context,
                fulfillmentId
            )

            DispatchAssignmentGatewayResult.NetworkUnavailable -> markUnknown(
                intent,
                request,
                context,
                fulfillmentId
            )

            DispatchAssignmentGatewayResult.ServiceUnavailable -> markUnknown(
                intent,
                request,
                context,
                fulfillmentId
            )

            DispatchAssignmentGatewayResult.PermissionDenied -> invalidate(
                DispatchAssignmentStatus.PermissionDenied
            )

            DispatchAssignmentGatewayResult.ContextInvalidated -> invalidate(
                DispatchAssignmentStatus.ContextInvalidated
            )

            DispatchAssignmentGatewayResult.SessionInvalidated -> invalidate(
                DispatchAssignmentStatus.SessionInvalidated
            )
        }
    }

    private suspend fun rejectIntent(
        intent: DispatchAssignmentIntent,
        status: DispatchAssignmentStatus,
        request: Long,
        context: DispatchAuthorityContext,
        fulfillmentId: String
    ) {
        val cleared = metadataCall {
            metadata.clearIntent(intent.scope, intent.fulfillmentId, intent.idempotencyKey)
        }
        if (!isCurrent(request, context, fulfillmentId)) return
        if (cleared == DispatchAssignmentMetadataWrite.Saved) pendingIntent = null
        mutableState.value = mutableState.value.copy(
            status = status,
            pendingIntent = if (cleared == DispatchAssignmentMetadataWrite.Saved) null else intent
        )
    }

    private suspend fun markUnknown(
        intent: DispatchAssignmentIntent,
        request: Long,
        context: DispatchAuthorityContext,
        fulfillmentId: String
    ) {
        val unknown = intent.copy(status = DispatchAssignmentIntentStatus.UnknownOutcome)
        metadataCall { metadata.saveIntent(unknown) }
        if (!isCurrent(request, context, fulfillmentId)) return
        pendingIntent = unknown
        mutableState.value = mutableState.value.copy(
            status = DispatchAssignmentStatus.UnknownOutcome,
            pendingIntent = unknown
        )
    }

    private suspend fun metadataReadCall(
        block: suspend () -> DispatchAssignmentMetadataRead
    ): DispatchAssignmentMetadataRead = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchAssignmentMetadataRead.Unavailable
    }

    private suspend fun metadataCall(
        block: suspend () -> DispatchAssignmentMetadataWrite
    ): DispatchAssignmentMetadataWrite = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchAssignmentMetadataWrite.Unavailable
    }

    private fun DispatchAuthorityContext.scopeIdentity(): DispatchAssignmentScopeIdentity? {
        val value = identity ?: return null
        if (listOf(value.userId, value.tenantId, value.workspaceId, value.membershipId)
                .any(String::isBlank)
        ) {
            return null
        }
        return DispatchAssignmentScopeIdentity(
            value.userId,
            value.tenantId,
            value.workspaceId,
            value.membershipId
        )
    }

    private fun DispatchAssignmentSnapshot.isFor(fulfillmentId: String): Boolean {
        val assignment = this.assignment
        return readiness.subjectKind == PREPARED_FULFILLMENT &&
            readiness.fulfillmentId == fulfillmentId &&
            candidates.map { it.membershipId.lowercase() }.distinct().size == candidates.size &&
            (
                assignment == null || (
                    assignment.fulfillmentId == fulfillmentId &&
                        assignment.physicalAllocationId == readiness.physicalAllocationId &&
                        assignment.fulfillmentVersion <= readiness.fulfillmentVersion &&
                        assignment.physicalAllocationVersion <= readiness.physicalAllocationVersion
                    )
                )
    }

    private fun fail(status: DispatchAssignmentStatus) {
        mutableState.value = mutableState.value.copy(status = status)
    }

    private fun invalidate(status: DispatchAssignmentStatus) {
        generation++
        activeContext = null
        mutableState.value = DispatchAssignmentUiState(
            authorityEpoch = mutableState.value.authorityEpoch,
            fulfillmentId = mutableState.value.fulfillmentId,
            status = status
        )
    }

    private suspend fun safeCall(
        block: suspend () -> DispatchAssignmentGatewayResult
    ): DispatchAssignmentGatewayResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchAssignmentGatewayResult.ServiceUnavailable
    }

    private fun isCurrent(
        request: Long,
        context: DispatchAuthorityContext,
        fulfillmentId: String
    ): Boolean = request == generation && activeContext == context &&
        activeFulfillmentId == fulfillmentId &&
        mutableState.value.authorityEpoch == context.authorityEpoch

    private fun DispatchAuthorityContext.readPermissionHint(): DispatchAssignmentStatus {
        val identity = identity ?: return DispatchAssignmentStatus.PermissionUnknown
        if (authorityEpoch <= 0 || listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).any(String::isBlank) || identity.permissions.isEmpty()
        ) {
            return DispatchAssignmentStatus.PermissionUnknown
        }
        return if (DISPATCH_READ_PERMISSION in identity.permissions) {
            DispatchAssignmentStatus.Loading
        } else {
            DispatchAssignmentStatus.PermissionDenied
        }
    }

    private fun DispatchAuthorityContext.hasAssignmentPermission(): Boolean =
        identity?.permissions?.any { it in DISPATCH_ASSIGN_PERMISSIONS } == true

    private companion object {
        const val PREPARED_FULFILLMENT = "PREPARED_FULFILLMENT"
        const val DISPATCH_READ_PERMISSION = "dispatch.read"
        const val LOGISTICS_READ_PERMISSION = "logistics.read"
        val DISPATCH_ASSIGN_PERMISSIONS = setOf("dispatch.assign", "logistics:write")
        val INVALIDATED_STATUS = setOf(
            DispatchAssignmentStatus.ContextInvalidated,
            DispatchAssignmentStatus.SessionInvalidated
        )
    }
}
