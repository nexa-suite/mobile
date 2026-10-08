package com.nexa.mobile.operations.feature.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.feature.dispatch.application.DispatchPlanChangeGateway
import com.nexa.mobile.operations.feature.dispatch.application.DispatchPlanChangeMetadataStore
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeGatewayResult as ChangeGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeIntentStatus
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeSnapshot
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.model.PreparedFulfillmentDriverAssignment
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DispatchPlanChangeViewModel(
    private val gateway: DispatchPlanChangeGateway,
    private val metadata: DispatchPlanChangeMetadataStore,
    private val commandKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchPlanChangeUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: DispatchAuthorityContext? = null
    private var activeFulfillmentId: String? = null
    private var pendingIntent: DispatchPlanChangeIntent? = null
    private var generation = 0L
    private var restoringMetadata = false

    fun activate(fulfillmentId: String, context: DispatchAuthorityContext) {
        if (activeFulfillmentId == fulfillmentId && activeContext == context &&
            mutableState.value.status !in INVALIDATED_STATUSES
        ) {
            return
        }
        activeFulfillmentId = fulfillmentId
        activeContext = context
        pendingIntent = null
        restoringMetadata = false
        generation++
        mutableState.value = baseState(fulfillmentId, context, context.readStatus())
        if (mutableState.value.status == DispatchPlanChangeStatus.Loading) {
            restoreThenRefresh(fulfillmentId, context)
        }
    }

    fun refresh() {
        if (restoringMetadata) return
        val fulfillmentId = activeFulfillmentId ?: return
        val context = activeContext ?: return
        val hint = context.readStatus()
        if (hint != DispatchPlanChangeStatus.Loading) {
            generation++
            mutableState.value = baseState(fulfillmentId, context, hint)
            return
        }
        load(fulfillmentId, context)
    }

    fun selectDriver(membershipId: String) {
        val state = mutableState.value
        if (!state.canReassign || state.status != DispatchPlanChangeStatus.Current ||
            state.pendingIntent != null || state.candidates.none { it.membershipId == membershipId }
        ) {
            return
        }
        mutableState.value = state.copy(selectedMembershipId = membershipId)
    }

    fun updatePlannedDispatchAt(value: String) {
        val state = mutableState.value
        if (!state.canSchedule || state.status != DispatchPlanChangeStatus.Current ||
            state.pendingIntent != null
        ) {
            return
        }
        mutableState.value = state.copy(
            plannedDispatchAtText = value,
            inputInvalid = value.isNotBlank() && value.toInstantOrNull() == null
        )
    }

    fun changePlan() {
        val state = mutableState.value
        val context = activeContext ?: return
        val readiness = state.readiness ?: return
        val assignment = state.assignment ?: return
        val fulfillmentId = activeFulfillmentId ?: return
        if (!state.canSave || state.pendingIntent != null) return

        val requestedMembership = state.selectedMembershipId
            ?.takeIf { state.canReassign && it != assignment.responsibleMembershipId }
        val enteredInstant = state.plannedDispatchAtText.toInstantOrNull()
        val requestedDispatchAt = enteredInstant
            ?.takeIf { state.canSchedule && it != assignment.plannedDispatchAt }
        if (requestedMembership == null && requestedDispatchAt == null) return

        val scope = context.scopeIdentity() ?: return
        val intent = DispatchPlanChangeIntent(
            scope = scope,
            fulfillmentId = fulfillmentId,
            expectedFulfillmentVersion = readiness.fulfillmentVersion,
            expectedAssignmentId = assignment.id,
            expectedAssignmentVersion = assignment.fulfillmentVersion,
            physicalAllocationId = readiness.physicalAllocationId,
            physicalAllocationVersion = readiness.physicalAllocationVersion,
            requestedMembershipId = requestedMembership,
            requestedDispatchAt = requestedDispatchAt,
            resultResponsibleMembershipId =
                requestedMembership ?: assignment.responsibleMembershipId,
            resultPlannedDispatchAt = requestedDispatchAt ?: assignment.plannedDispatchAt,
            requestBody = encodeBody(
                assignment,
                readiness,
                requestedMembership,
                requestedDispatchAt
            ),
            idempotencyKey = commandKey()
        )
        val request = ++generation
        mutableState.value = state.copy(status = DispatchPlanChangeStatus.Saving)
        viewModelScope.launch {
            when (metadataCall { metadata.saveIntent(intent) }) {
                DispatchPlanChangeMetadataWrite.Saved -> pendingIntent = intent

                DispatchPlanChangeMetadataWrite.Conflict -> {
                    fail(DispatchPlanChangeStatus.Conflict)
                    return@launch
                }

                DispatchPlanChangeMetadataWrite.Stale -> {
                    fail(DispatchPlanChangeStatus.Stale)
                    return@launch
                }

                DispatchPlanChangeMetadataWrite.Unavailable -> {
                    fail(DispatchPlanChangeStatus.ServiceUnavailable)
                    return@launch
                }
            }
            if (!isCurrent(request, context, fulfillmentId)) return@launch
            val result = safeCall { gateway.change(readiness, assignment, intent, context) }
            if (!isCurrent(request, context, fulfillmentId)) return@launch
            handleResult(result, intent, request, context, fulfillmentId)
        }
    }

    fun retryUnknownOutcome() {
        val intent = pendingIntent ?: return
        val context = activeContext ?: return
        val fulfillmentId = activeFulfillmentId ?: return
        val state = mutableState.value
        if (!state.canReplay || intent.fulfillmentId != fulfillmentId ||
            intent.scope != context.scopeIdentity()
        ) {
            return
        }
        val request = ++generation
        mutableState.value = state.copy(status = DispatchPlanChangeStatus.Saving)
        viewModelScope.launch {
            val result = safeCall { gateway.replay(intent, context) }
            if (!isCurrent(request, context, fulfillmentId)) return@launch
            handleResult(result, intent, request, context, fulfillmentId)
        }
    }

    fun deactivate() {
        generation++
        activeContext = null
        activeFulfillmentId = null
        pendingIntent = null
        restoringMetadata = false
        mutableState.value = DispatchPlanChangeUiState()
    }

    private fun load(fulfillmentId: String, context: DispatchAuthorityContext) {
        val request = ++generation
        mutableState.value = baseState(fulfillmentId, context, DispatchPlanChangeStatus.Loading)
        viewModelScope.launch {
            when (val result = safeCall { gateway.load(fulfillmentId, context) }) {
                is ChangeGatewayResult.Snapshot -> {
                    if (!isCurrent(request, context, fulfillmentId)) return@launch
                    val snapshot = result.value
                    if (!snapshot.isFor(fulfillmentId)) {
                        fail(DispatchPlanChangeStatus.ServiceUnavailable)
                    } else {
                        val current = snapshot.assignment
                        mutableState.value = baseState(
                            fulfillmentId,
                            context,
                            if (pendingIntent == null) {
                                DispatchPlanChangeStatus.Current
                            } else {
                                DispatchPlanChangeStatus.UnknownOutcome
                            }
                        ).copy(
                            readiness = snapshot.readiness,
                            candidates = snapshot.candidates,
                            assignment = current,
                            history = snapshot.history,
                            selectedMembershipId = current?.responsibleMembershipId,
                            plannedDispatchAtText =
                                current?.plannedDispatchAt?.toString().orEmpty(),
                            pendingIntent = pendingIntent
                        )
                    }
                }

                else -> if (isCurrent(request, context, fulfillmentId)) {
                    fail(result.toStatus())
                }
            }
        }
    }

    private fun restoreThenRefresh(fulfillmentId: String, context: DispatchAuthorityContext) {
        val scope = context.scopeIdentity() ?: return
        val request = generation
        restoringMetadata = true
        viewModelScope.launch {
            when (val read = metadataReadCall { metadata.loadIntent(scope, fulfillmentId) }) {
                DispatchPlanChangeMetadataRead.Unavailable -> {
                    restoringMetadata = false
                    fail(DispatchPlanChangeStatus.ServiceUnavailable)
                }

                is DispatchPlanChangeMetadataRead.Available -> {
                    val loaded = read.intent
                    if (loaded != null &&
                        (loaded.scope != scope || loaded.fulfillmentId != fulfillmentId)
                    ) {
                        restoringMetadata = false
                        fail(DispatchPlanChangeStatus.ServiceUnavailable)
                        return@launch
                    }
                    val recovered = loaded?.takeIf {
                        it.status == DispatchPlanChangeIntentStatus.Pending
                    }?.copy(status = DispatchPlanChangeIntentStatus.UnknownOutcome)
                    if (recovered != null && metadataCall { metadata.saveIntent(recovered) } !=
                        DispatchPlanChangeMetadataWrite.Saved
                    ) {
                        restoringMetadata = false
                        fail(DispatchPlanChangeStatus.ServiceUnavailable)
                        return@launch
                    }
                    if (!isCurrent(request, context, fulfillmentId)) return@launch
                    pendingIntent = recovered ?: loaded
                    restoringMetadata = false
                    load(fulfillmentId, context)
                }
            }
        }
    }

    private suspend fun handleResult(
        result: ChangeGatewayResult,
        intent: DispatchPlanChangeIntent,
        request: Long,
        context: DispatchAuthorityContext,
        fulfillmentId: String
    ) {
        when (result) {
            is ChangeGatewayResult.Changed -> {
                if (!result.value.matches(intent)) {
                    markUnknown(intent, request, context, fulfillmentId)
                } else {
                    val cleared = metadataCall {
                        metadata.clearIntent(intent.scope, fulfillmentId, intent.idempotencyKey)
                    }
                    if (!isCurrent(request, context, fulfillmentId)) return
                    if (cleared == DispatchPlanChangeMetadataWrite.Saved) {
                        pendingIntent = null
                        load(fulfillmentId, context)
                    } else {
                        markUnknown(intent, request, context, fulfillmentId)
                    }
                }
            }

            ChangeGatewayResult.NotReady -> reject(
                intent,
                DispatchPlanChangeStatus.NotReady,
                request,
                context,
                fulfillmentId
            )

            ChangeGatewayResult.Stale -> reject(
                intent,
                DispatchPlanChangeStatus.Stale,
                request,
                context,
                fulfillmentId
            )

            ChangeGatewayResult.Conflict -> reject(
                intent,
                DispatchPlanChangeStatus.Conflict,
                request,
                context,
                fulfillmentId
            )

            ChangeGatewayResult.PermissionDenied -> reject(
                intent,
                DispatchPlanChangeStatus.PermissionDenied,
                request,
                context,
                fulfillmentId
            )

            ChangeGatewayResult.ContextInvalidated -> invalidate(
                DispatchPlanChangeStatus.ContextInvalidated
            )

            ChangeGatewayResult.SessionInvalidated -> invalidate(
                DispatchPlanChangeStatus.SessionInvalidated
            )

            ChangeGatewayResult.UnknownOutcome,
            ChangeGatewayResult.NetworkUnavailable,
            ChangeGatewayResult.ServiceUnavailable ->
                markUnknown(intent, request, context, fulfillmentId)

            is ChangeGatewayResult.Snapshot ->
                fail(DispatchPlanChangeStatus.ServiceUnavailable)
        }
    }

    private suspend fun reject(
        intent: DispatchPlanChangeIntent,
        status: DispatchPlanChangeStatus,
        request: Long,
        context: DispatchAuthorityContext,
        fulfillmentId: String
    ) {
        val cleared = metadataCall {
            metadata.clearIntent(intent.scope, fulfillmentId, intent.idempotencyKey)
        }
        if (!isCurrent(request, context, fulfillmentId)) return
        if (cleared == DispatchPlanChangeMetadataWrite.Saved) {
            pendingIntent = null
            mutableState.value = mutableState.value.copy(status = status, pendingIntent = null)
        } else {
            val unknown = intent.copy(status = DispatchPlanChangeIntentStatus.UnknownOutcome)
            pendingIntent = unknown
            mutableState.value = mutableState.value.copy(
                status = DispatchPlanChangeStatus.UnknownOutcome,
                pendingIntent = unknown
            )
        }
    }

    private suspend fun markUnknown(
        intent: DispatchPlanChangeIntent,
        request: Long,
        context: DispatchAuthorityContext,
        fulfillmentId: String
    ) {
        val unknown = intent.copy(status = DispatchPlanChangeIntentStatus.UnknownOutcome)
        metadataCall { metadata.saveIntent(unknown) }
        if (!isCurrent(request, context, fulfillmentId)) return
        pendingIntent = unknown
        mutableState.value = mutableState.value.copy(
            status = DispatchPlanChangeStatus.UnknownOutcome,
            pendingIntent = unknown
        )
    }

    private fun baseState(
        fulfillmentId: String,
        context: DispatchAuthorityContext,
        status: DispatchPlanChangeStatus
    ) = DispatchPlanChangeUiState(
        authorityEpoch = context.authorityEpoch,
        fulfillmentId = fulfillmentId,
        status = status,
        canReassign = context.identity?.permissions?.contains(DISPATCH_ASSIGN_PERMISSION) == true,
        canSchedule = context.identity?.permissions?.contains(DISPATCH_SCHEDULE_PERMISSION) == true
    )

    private fun encodeBody(
        assignment: PreparedFulfillmentDriverAssignment,
        readiness: DispatchReadiness,
        membershipId: String?,
        dispatchAt: Instant?
    ): String = buildString {
        append('{')
        append("\"expectedAssignmentId\":").append(jsonString(assignment.id)).append(',')
        append("\"expectedAssignmentVersion\":").append(assignment.fulfillmentVersion).append(',')
        append(
            "\"physicalAllocationId\":"
        ).append(jsonString(readiness.physicalAllocationId)).append(',')
        append(
            "\"physicalAllocationVersion\":"
        ).append(readiness.physicalAllocationVersion).append(',')
        append("\"responsibleMembershipId\":")
            .append(membershipId?.let(::jsonString) ?: "null").append(',')
        append("\"plannedDispatchAt\":")
            .append(dispatchAt?.toString()?.let(::jsonString) ?: "null")
        append('}')
    }

    private fun fail(status: DispatchPlanChangeStatus) {
        mutableState.value = mutableState.value.copy(
            status = status,
            readiness = null,
            candidates = emptyList(),
            assignment = null,
            history = emptyList()
        )
    }

    private fun invalidate(status: DispatchPlanChangeStatus) {
        generation++
        pendingIntent = null
        restoringMetadata = false
        mutableState.value = DispatchPlanChangeUiState(
            authorityEpoch = mutableState.value.authorityEpoch,
            fulfillmentId = activeFulfillmentId,
            status = status
        )
    }

    private fun isCurrent(
        request: Long,
        context: DispatchAuthorityContext,
        fulfillmentId: String
    ): Boolean = request == generation && activeContext == context &&
        activeFulfillmentId == fulfillmentId

    private suspend fun safeCall(block: suspend () -> ChangeGatewayResult) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ChangeGatewayResult.ServiceUnavailable
    }

    private suspend fun metadataReadCall(block: suspend () -> DispatchPlanChangeMetadataRead) =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DispatchPlanChangeMetadataRead.Unavailable
        }

    private suspend fun metadataCall(block: suspend () -> DispatchPlanChangeMetadataWrite) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchPlanChangeMetadataWrite.Unavailable
    }

    private fun DispatchPlanChangeSnapshot.isFor(fulfillmentId: String): Boolean {
        val assignment = this.assignment
        return readiness.subjectKind == "PREPARED_FULFILLMENT" &&
            readiness.fulfillmentId == fulfillmentId &&
            candidates.map { it.membershipId.lowercase() }.distinct().size == candidates.size &&
            (
                assignment == null ||
                    (
                        assignment.fulfillmentId == fulfillmentId && assignment.current &&
                            assignment.physicalAllocationId == readiness.physicalAllocationId &&
                            assignment.physicalAllocationVersion ==
                            readiness.physicalAllocationVersion
                        )
                ) &&
            history.all { it.fulfillmentId == fulfillmentId } &&
            if (assignment == null) {
                history.isEmpty()
            } else {
                history.count { it.current } == 1 &&
                    history.any { it.current && it.id == assignment.id }
            }
    }

    private fun PreparedFulfillmentDriverAssignment.matches(intent: DispatchPlanChangeIntent) =
        fulfillmentId == intent.fulfillmentId &&
            fulfillmentVersion == intent.expectedFulfillmentVersion + 1 &&
            physicalAllocationId == intent.physicalAllocationId &&
            physicalAllocationVersion == intent.physicalAllocationVersion &&
            responsibleMembershipId == intent.resultResponsibleMembershipId &&
            plannedDispatchAt == intent.resultPlannedDispatchAt

    private fun ChangeGatewayResult.toStatus() = when (this) {
        ChangeGatewayResult.NotReady -> DispatchPlanChangeStatus.NotReady

        ChangeGatewayResult.Stale -> DispatchPlanChangeStatus.Stale

        ChangeGatewayResult.Conflict -> DispatchPlanChangeStatus.Conflict

        ChangeGatewayResult.UnknownOutcome -> DispatchPlanChangeStatus.UnknownOutcome

        ChangeGatewayResult.NetworkUnavailable -> DispatchPlanChangeStatus.NetworkUnavailable

        ChangeGatewayResult.ServiceUnavailable -> DispatchPlanChangeStatus.ServiceUnavailable

        ChangeGatewayResult.PermissionDenied -> DispatchPlanChangeStatus.PermissionDenied

        ChangeGatewayResult.ContextInvalidated -> DispatchPlanChangeStatus.ContextInvalidated

        ChangeGatewayResult.SessionInvalidated -> DispatchPlanChangeStatus.SessionInvalidated

        is ChangeGatewayResult.Snapshot,
        is ChangeGatewayResult.Changed -> DispatchPlanChangeStatus.ServiceUnavailable
    }

    private fun DispatchAuthorityContext.readStatus(): DispatchPlanChangeStatus {
        val identity = identity ?: return DispatchPlanChangeStatus.ContextInvalidated
        if (authorityEpoch <= 0 || listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).any(String::isBlank) || identity.permissions.isEmpty()
        ) {
            return DispatchPlanChangeStatus.ContextInvalidated
        }
        return if (DISPATCH_READ_PERMISSION in
            identity.permissions
        ) {
            DispatchPlanChangeStatus.Loading
        } else {
            DispatchPlanChangeStatus.PermissionDenied
        }
    }

    private fun DispatchAuthorityContext.scopeIdentity(): DispatchPlanChangeScopeIdentity? {
        val value = identity ?: return null
        if (listOf(value.userId, value.tenantId, value.workspaceId, value.membershipId)
                .any(String::isBlank)
        ) {
            return null
        }
        return DispatchPlanChangeScopeIdentity(
            value.userId,
            value.tenantId,
            value.workspaceId,
            value.membershipId
        )
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun String.toInstantOrNull(): Instant? =
        takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private companion object {
        const val DISPATCH_READ_PERMISSION = "dispatch.read"
        const val DISPATCH_ASSIGN_PERMISSION = "dispatch.assign"
        const val DISPATCH_SCHEDULE_PERMISSION = "dispatch.schedule"
        val INVALIDATED_STATUSES = setOf(
            DispatchPlanChangeStatus.PermissionDenied,
            DispatchPlanChangeStatus.ContextInvalidated,
            DispatchPlanChangeStatus.SessionInvalidated
        )
    }
}
