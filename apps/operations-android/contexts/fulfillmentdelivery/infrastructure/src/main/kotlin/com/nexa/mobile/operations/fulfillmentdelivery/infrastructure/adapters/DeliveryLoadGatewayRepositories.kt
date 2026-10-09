package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommandAction
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommandIntentStatus as LoadCommandIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommandMetadataRead as LoadCommandMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommandMetadataStore as LoadCommandMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommandMetadataWrite as LoadCommandMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGatewayResult as ReadinessGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoad
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoadCompatibilityAttestation as LoadCompatibilityAttestation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoadDriverCandidate as LoadDriverCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoadHistoryEvent
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoadStop
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DeliveryLoadNetworkAction
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DeliveryLoadNetworkResult
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DeliveryLoadTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DispatchAssignmentNetworkOutcome as AssignmentOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaDeliveryLoadGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaDispatchAssignmentGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Module
@InstallIn(SingletonComponent::class)
object DeliveryLoadNetworkBindings {
    @Provides
    @Singleton
    fun deliveryLoadNetworkGateway(calls: ProtectedCallExecutor) = NexaDeliveryLoadGateway(calls)

    @Provides
    @Singleton
    fun deliveryLoadMetadataStore(
        implementation: OperationsDeliveryLoadCommandMetadataStore
    ): LoadCommandMetadataStore = implementation
}

/** Uses current protected server projections and rechecks source versions before a new mutation. */
@Singleton
class OperationsDeliveryLoadGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val api: NexaDeliveryLoadGateway,
    private val readiness: OperationsDispatchReadinessGateway,
    private val assignments: NexaDispatchAssignmentGateway
) : DeliveryLoadGateway {
    override suspend fun current(
        context: DispatchAuthorityContext,
        driver: Boolean
    ): DeliveryLoadGatewayResult {
        val permissionGroups = listOf(DISPATCH_READ_PERMISSIONS)
        val authorization = authorize(context, permissionGroups)
        if (authorization !is Authorization.Current) return authorization.toFailure()

        val loads = when (val result = safeList(driver)) {
            is DeliveryLoadNetworkResult.ListResult -> result.loads.mapNotNull {
                it.toFeatureOrNull()
            }
                .takeIf { it.size == result.loads.size }
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_RESPONSE")

            is DeliveryLoadNetworkResult.Current -> return DeliveryLoadGatewayResult.Failed(
                "INVALID_RESPONSE"
            )

            is DeliveryLoadNetworkResult.WindowPlanned -> return DeliveryLoadGatewayResult.Failed(
                "INVALID_RESPONSE"
            )

            is DeliveryLoadNetworkResult.Failed -> return result.toFailure()
        }

        val candidates: List<DispatchReadiness>
        val drivers: List<LoadDriverCandidate>
        if (driver) {
            candidates = emptyList()
            drivers = emptyList()
        } else {
            candidates = when (val result = readiness.list(context)) {
                is ReadinessGatewayResult.ListResult -> result.items.filter {
                    it.isCreateCandidate()
                }

                else -> return result.toFailure()
            }
            drivers =
                if (context.identity?.permissions?.any { it in DISPATCH_ASSIGN_PERMISSIONS } ==
                    true
                ) {
                    when (val result = safeCandidates()) {
                        is AssignmentOutcome.Candidates -> result.items.map {
                            LoadDriverCandidate(it.membershipId, it.displayName)
                        }

                        else -> return result.toFailure()
                    }
                } else {
                    emptyList()
                }
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift()
        return DeliveryLoadGatewayResult.Loaded(loads, candidates, drivers)
    }

    override suspend fun mutate(
        command: DeliveryLoadCommand,
        context: DispatchAuthorityContext
    ): DeliveryLoadGatewayResult {
        if (command.scope != context.scopeIdentity() ||
            command.driverCommand != (command.action == DeliveryLoadCommandAction.ACCEPT)
        ) {
            return DeliveryLoadGatewayResult.Failed("ACCESS_CONTEXT_INVALID")
        }
        val required = when (command.action) {
            DeliveryLoadCommandAction.CREATE,
            DeliveryLoadCommandAction.REORDER,
            DeliveryLoadCommandAction.OFFER,
            DeliveryLoadCommandAction.CONFIRM_HANDOFF,
            DeliveryLoadCommandAction.PLAN_WINDOW -> listOf(
                DISPATCH_READ_PERMISSIONS,
                DISPATCH_SCHEDULE_PERMISSIONS
            )

            DeliveryLoadCommandAction.ASSIGN -> listOf(
                DISPATCH_READ_PERMISSIONS,
                DISPATCH_ASSIGN_PERMISSIONS
            )

            DeliveryLoadCommandAction.ACCEPT -> listOf(
                DISPATCH_READ_PERMISSIONS,
                DRIVER_START_PERMISSIONS
            )
        }
        val authorization = authorize(context, required)
        if (authorization !is Authorization.Current) return authorization.toFailure()

        // Replays keep their frozen key, version and body. The server resolves the exact idempotent
        // command; comparing a newer read version here could incorrectly block a safe replay.
        if (command.status == LoadCommandIntentStatus.Pending) {
            val preflight = when (command.action) {
                DeliveryLoadCommandAction.CREATE -> validateFreshSources(command, context)
                DeliveryLoadCommandAction.PLAN_WINDOW -> validateWindowPlan(command, context)
                DeliveryLoadCommandAction.ASSIGN -> validateAssignmentCandidate(command)
                else -> validateCurrentLoad(command)
            }
            if (preflight != null) return preflight
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift()

        val action = when (command.action) {
            DeliveryLoadCommandAction.CREATE -> DeliveryLoadNetworkAction.CREATE
            DeliveryLoadCommandAction.REORDER -> DeliveryLoadNetworkAction.REORDER
            DeliveryLoadCommandAction.ASSIGN -> DeliveryLoadNetworkAction.ASSIGN
            DeliveryLoadCommandAction.OFFER -> DeliveryLoadNetworkAction.OFFER
            DeliveryLoadCommandAction.CONFIRM_HANDOFF -> DeliveryLoadNetworkAction.CONFIRM_HANDOFF
            DeliveryLoadCommandAction.ACCEPT -> DeliveryLoadNetworkAction.ACCEPT
            DeliveryLoadCommandAction.PLAN_WINDOW -> DeliveryLoadNetworkAction.PLAN_WINDOW
        }
        val result = try {
            api.mutate(
                command.driverCommand,
                command.loadId,
                action,
                command.expectedVersion,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DeliveryLoadGatewayResult.Failed("UNKNOWN_OUTCOME", unknownOutcome = true)
        }
        if (!isCurrent(
                context,
                authorization.lease
            )
        ) {
            return DeliveryLoadGatewayResult.Failed(
                "ACCESS_CONTEXT_INVALID",
                unknownOutcome = true
            )
        }
        return when (result) {
            is DeliveryLoadNetworkResult.Current -> result.load.toFeatureOrNull()?.let(
                DeliveryLoadGatewayResult::Changed
            )
                ?: DeliveryLoadGatewayResult.Failed("INVALID_RESPONSE", unknownOutcome = true)

            is DeliveryLoadNetworkResult.WindowPlanned -> DeliveryLoadGatewayResult.WindowPlanned(
                result.plan.fulfillmentId
            )

            is DeliveryLoadNetworkResult.Failed -> result.toFailure()

            is DeliveryLoadNetworkResult.ListResult -> DeliveryLoadGatewayResult.Failed(
                "INVALID_RESPONSE",
                unknownOutcome = true
            )
        }
    }

    private suspend fun validateFreshSources(
        command: DeliveryLoadCommand,
        context: DispatchAuthorityContext
    ): DeliveryLoadGatewayResult.Failed? {
        val body =
            decodeBody(command.frozenBody)
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val ids =
            body.stringArray("fulfillmentIds")
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val order =
            body.stringArray("stopOrder")
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val versions = (body["expectedFulfillmentVersions"] as? JsonObject)
            ?.mapValues { (_, value) -> value.jsonPrimitive.longOrNull }
            ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        if (ids.size !in 2..20 || ids.distinct().size != ids.size || order.size != ids.size ||
            order.distinct().size != order.size || order.toSet() != ids.toSet() ||
            versions.keys != ids.toSet() || versions.values.any { it == null }
        ) {
            return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        }

        for (id in ids) {
            when (val result = readiness.detail(id, context)) {
                is ReadinessGatewayResult.Detail -> {
                    val current = result.item
                    if (!current.isCreateCandidate() || current.fulfillmentId != id ||
                        current.fulfillmentVersion != versions[id]
                    ) {
                        return DeliveryLoadGatewayResult.Failed("STALE_VERSION")
                    }
                }

                else -> return result.toFailure()
            }
        }
        return null
    }

    private suspend fun validateWindowPlan(
        command: DeliveryLoadCommand,
        context: DispatchAuthorityContext
    ): DeliveryLoadGatewayResult.Failed? {
        val id = command.loadId ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val expectedVersion =
            command.expectedVersion ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val body =
            decodeBody(command.frozenBody)
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val start =
            body["windowStart"]?.jsonPrimitive?.content?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val end =
            body["windowEnd"]?.jsonPrimitive?.content?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val reason = body["reason"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (!start.isBefore(end) || reason.isBlank() || reason.length > 2000) {
            return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        }
        return when (val result = readiness.detail(id, context)) {
            is ReadinessGatewayResult.Detail -> {
                val current = result.item
                when {
                    current.fulfillmentId != id || current.fulfillmentVersion != expectedVersion ->
                        DeliveryLoadGatewayResult.Failed("STALE_VERSION")

                    !current.ready || current.fulfillmentStatus != "READY_FOR_DISPATCH" ->
                        DeliveryLoadGatewayResult.Failed("FULFILLMENT_NOT_READY_FOR_DISPATCH")

                    current.windowStart != null || current.windowEnd != null ||
                        current.windowSource != null ->
                        DeliveryLoadGatewayResult.Failed(
                            "FULFILLMENT_DISPATCH_WINDOW_ALREADY_DEFINED"
                        )

                    else -> null
                }
            }

            else -> result.toFailure()
        }
    }

    private suspend fun validateAssignmentCandidate(
        command: DeliveryLoadCommand
    ): DeliveryLoadGatewayResult.Failed? {
        val body =
            decodeBody(command.frozenBody)
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val membershipId = body["driverMembershipId"]?.jsonPrimitive?.content
            ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        return when (val result = safeCandidates()) {
            is AssignmentOutcome.Candidates -> if (result.items.any {
                    it.membershipId ==
                        membershipId
                }
            ) {
                null
            } else {
                DeliveryLoadGatewayResult.Failed("ASSIGNEE_NOT_ELIGIBLE")
            }

            else -> result.toFailure()
        }
    }

    private suspend fun validateCurrentLoad(
        command: DeliveryLoadCommand
    ): DeliveryLoadGatewayResult.Failed? {
        val id = command.loadId ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
        val driver = command.action == DeliveryLoadCommandAction.ACCEPT
        val loads = when (val result = safeList(driver)) {
            is DeliveryLoadNetworkResult.ListResult -> result.loads

            is DeliveryLoadNetworkResult.Failed -> return result.toFailure()

            is DeliveryLoadNetworkResult.Current -> return DeliveryLoadGatewayResult.Failed(
                "INVALID_RESPONSE"
            )

            is DeliveryLoadNetworkResult.WindowPlanned -> return DeliveryLoadGatewayResult.Failed(
                "INVALID_RESPONSE"
            )
        }
        val current = loads.singleOrNull { it.id == id }
            ?: return DeliveryLoadGatewayResult.Failed("LOAD_NOT_CURRENT")
        if (current.version !=
            command.expectedVersion
        ) {
            return DeliveryLoadGatewayResult.Failed("STALE_VERSION")
        }
        val validStage = when (command.action) {
            DeliveryLoadCommandAction.REORDER -> current.status in setOf("DRAFT", "ASSIGNED")

            DeliveryLoadCommandAction.ASSIGN -> current.status == "DRAFT"

            DeliveryLoadCommandAction.OFFER -> current.status == "ASSIGNED"

            DeliveryLoadCommandAction.CONFIRM_HANDOFF ->
                current.status in
                    setOf("OFFERED", "DRIVER_ACCEPTED")

            DeliveryLoadCommandAction.ACCEPT ->
                current.status in
                    setOf("OFFERED", "HANDOFF_CONFIRMED")

            DeliveryLoadCommandAction.CREATE -> false

            DeliveryLoadCommandAction.PLAN_WINDOW -> false
        }
        if (!validStage) return DeliveryLoadGatewayResult.Failed("LOAD_STAGE_CONFLICT")
        if (command.action == DeliveryLoadCommandAction.ASSIGN) {
            val body =
                decodeBody(command.frozenBody)
                    ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
            val membershipId = body["driverMembershipId"]?.jsonPrimitive?.content
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
            val candidates = when (val result = safeCandidates()) {
                is AssignmentOutcome.Candidates -> result.items
                else -> return result.toFailure()
            }
            if (candidates.none {
                    it.membershipId == membershipId
                }
            ) {
                return DeliveryLoadGatewayResult.Failed("ASSIGNEE_NOT_ELIGIBLE")
            }
        }
        if (command.action == DeliveryLoadCommandAction.REORDER) {
            val order = decodeBody(command.frozenBody)?.stringArray("stopOrder")
                ?: return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
            if (order.size != current.stops.size ||
                order.toSet() != current.stops.map { it.fulfillmentId }.toSet()
            ) {
                return DeliveryLoadGatewayResult.Failed("INVALID_COMMAND")
            }
        }
        return null
    }

    private suspend fun authorize(
        context: DispatchAuthorityContext,
        groups: List<Set<String>>
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.identity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 ||
            !verified.matches(identity)
        ) {
            return Authorization.ContextInvalidated
        }
        if (groups.any { required ->
                identity.permissions.none { it in required }
            }
        ) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun isCurrent(
        context: DispatchAuthorityContext,
        lease: AccessTokenLease
    ): Boolean = sessions.sessionState.value == SessionState.Active && sessions.isEpochCurrent(
        lease.epoch
    ) &&
        context.identity?.let { expected ->
            sessions.verifiedSession.value?.matches(expected) ==
                true
        } ==
        true

    private suspend fun authorityDrift() = if (sessions.sessionState.value != SessionState.Active) {
        DeliveryLoadGatewayResult.Failed("SESSION_INVALIDATED")
    } else {
        DeliveryLoadGatewayResult.Failed("ACCESS_CONTEXT_INVALID")
    }

    private suspend fun safeList(driver: Boolean) = try {
        api.list(driver)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DeliveryLoadNetworkResult.Failed("SERVICE_UNAVAILABLE")
    }

    private suspend fun safeCandidates() = try {
        assignments.candidates()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AssignmentOutcome.ServiceUnavailable
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun DispatchReadiness.isCreateCandidate(): Boolean =
        subjectKind == "PREPARED_FULFILLMENT" &&
            ready && fulfillmentStatus == "READY_FOR_DISPATCH" && fulfillmentVersion >= 0 &&
            physicalAllocationVersion >= 0 && deliveryId == null && reasons.isEmpty()

    private fun ReadinessGatewayResult.toFailure(): DeliveryLoadGatewayResult.Failed = when (this) {
        is ReadinessGatewayResult.ListResult,
        is ReadinessGatewayResult.Detail ->
            DeliveryLoadGatewayResult.Failed("INVALID_RESPONSE")

        ReadinessGatewayResult.NetworkUnavailable -> DeliveryLoadGatewayResult.Failed(
            "NETWORK_UNAVAILABLE"
        )

        ReadinessGatewayResult.ServiceUnavailable -> DeliveryLoadGatewayResult.Failed(
            "SERVICE_UNAVAILABLE"
        )

        ReadinessGatewayResult.PermissionDenied -> DeliveryLoadGatewayResult.Failed(
            "FORBIDDEN"
        )

        ReadinessGatewayResult.ContextInvalidated -> DeliveryLoadGatewayResult.Failed(
            "ACCESS_CONTEXT_INVALID"
        )

        ReadinessGatewayResult.SessionInvalidated -> DeliveryLoadGatewayResult.Failed(
            "SESSION_INVALIDATED"
        )
    }

    private fun AssignmentOutcome.toFailure(): DeliveryLoadGatewayResult.Failed = when (this) {
        AssignmentOutcome.NetworkUnavailable -> DeliveryLoadGatewayResult.Failed(
            "NETWORK_UNAVAILABLE"
        )

        AssignmentOutcome.UnknownOutcome -> DeliveryLoadGatewayResult.Failed(
            "UNKNOWN_OUTCOME",
            true
        )

        AssignmentOutcome.ServiceUnavailable -> DeliveryLoadGatewayResult.Failed(
            "SERVICE_UNAVAILABLE"
        )

        AssignmentOutcome.PermissionDenied -> DeliveryLoadGatewayResult.Failed(
            "FORBIDDEN"
        )

        AssignmentOutcome.ContextInvalidated -> DeliveryLoadGatewayResult.Failed(
            "ACCESS_CONTEXT_INVALID"
        )

        AssignmentOutcome.SessionInvalidated -> DeliveryLoadGatewayResult.Failed(
            "SESSION_INVALIDATED"
        )

        AssignmentOutcome.Stale -> DeliveryLoadGatewayResult.Failed(
            "STALE_VERSION"
        )

        AssignmentOutcome.Conflict -> DeliveryLoadGatewayResult.Failed(
            "CONFLICT"
        )

        is AssignmentOutcome.Candidates,
        is AssignmentOutcome.Current,
        is AssignmentOutcome.Assigned -> DeliveryLoadGatewayResult.Failed(
            "SERVICE_UNAVAILABLE"
        )
    }

    private fun Authorization.toFailure(): DeliveryLoadGatewayResult.Failed = when (this) {
        Authorization.SessionInvalidated -> DeliveryLoadGatewayResult.Failed("SESSION_INVALIDATED")

        Authorization.ContextInvalidated -> DeliveryLoadGatewayResult.Failed(
            "ACCESS_CONTEXT_INVALID"
        )

        Authorization.PermissionDenied -> DeliveryLoadGatewayResult.Failed("FORBIDDEN")

        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun DeliveryLoadNetworkResult.Failed.toFailure() =
        DeliveryLoadGatewayResult.Failed(code ?: "SERVICE_UNAVAILABLE", unknownOutcome)

    private fun DeliveryLoadScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun DispatchAuthorityContext.scopeIdentity(): DeliveryLoadScopeIdentity? =
        identity?.let {
            runCatching {
                DeliveryLoadScopeIdentity(it.userId, it.tenantId, it.workspaceId, it.membershipId)
            }.getOrNull()
        }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val DISPATCH_READ_PERMISSIONS = setOf("dispatch.read")
        val DISPATCH_SCHEDULE_PERMISSIONS = setOf("dispatch.schedule", "logistics:write")
        val DISPATCH_ASSIGN_PERMISSIONS = setOf("dispatch.assign", "logistics:write")
        val DRIVER_START_PERMISSIONS = setOf("dispatch.start_route")
    }
}

@Singleton
class OperationsDeliveryLoadCommandMetadataStore @Inject constructor(
    @ApplicationContext context: Context
) : LoadCommandMetadataStore {
    private val local =
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DeliveryLoadCommand)
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun load(scope: DeliveryLoadScopeIdentity): LoadCommandMetadataRead =
        lock(scope).withLock {
            when (val result = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> LoadCommandMetadataRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload =
                        result.payload
                            ?: return@withLock LoadCommandMetadataRead.Available(null)
                    val command =
                        decode(payload)
                            ?: return@withLock LoadCommandMetadataRead.Unavailable
                    if (command.scope !=
                        scope
                    ) {
                        return@withLock LoadCommandMetadataRead.Unavailable
                    }
                    if (command.status == LoadCommandIntentStatus.Pending) {
                        val recovered = command.copy(
                            status = LoadCommandIntentStatus.UnknownOutcome
                        )
                        if (local.save(scope.toLocal(), encode(recovered))) {
                            LoadCommandMetadataRead.Available(recovered)
                        } else {
                            LoadCommandMetadataRead.Unavailable
                        }
                    } else {
                        LoadCommandMetadataRead.Available(command)
                    }
                }
            }
        }

    override suspend fun save(command: DeliveryLoadCommand): LoadCommandMetadataWrite =
        lock(command.scope).withLock {
            when (val result = local.load(command.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> LoadCommandMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = result.payload?.let(::decode)
                    if (result.payload != null &&
                        (current == null || current.scope != command.scope)
                    ) {
                        return@withLock LoadCommandMetadataWrite.Unavailable
                    }
                    when {
                        current == null -> if (local.save(
                                command.scope.toLocal(),
                                encode(command)
                            )
                        ) {
                            LoadCommandMetadataWrite.Saved
                        } else {
                            LoadCommandMetadataWrite.Unavailable
                        }

                        current.sameIdentity(
                            command
                        ) && current.status == command.status ->
                            LoadCommandMetadataWrite.Saved

                        current.sameIdentity(command) &&
                            current.status == LoadCommandIntentStatus.Pending &&
                            command.status == LoadCommandIntentStatus.UnknownOutcome ->
                            if (local.save(command.scope.toLocal(), encode(command))) {
                                LoadCommandMetadataWrite.Saved
                            } else {
                                LoadCommandMetadataWrite.Unavailable
                            }

                        else -> LoadCommandMetadataWrite.Conflict
                    }
                }
            }
        }

    override suspend fun clear(
        scope: DeliveryLoadScopeIdentity,
        idempotencyKey: String
    ): LoadCommandMetadataWrite = lock(scope).withLock {
        when (val result = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> LoadCommandMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload =
                    result.payload ?: return@withLock LoadCommandMetadataWrite.Saved
                val current =
                    decode(payload) ?: return@withLock LoadCommandMetadataWrite.Unavailable
                if (current.scope != scope ||
                    current.idempotencyKey != idempotencyKey
                ) {
                    LoadCommandMetadataWrite.Conflict
                } else if (local.clear(scope.toLocal())) {
                    LoadCommandMetadataWrite.Saved
                } else {
                    LoadCommandMetadataWrite.Unavailable
                }
            }
        }
    }

    private fun lock(scope: DeliveryLoadScopeIdentity) = sharedMutexes.getOrPut(scope) { Mutex() }
    private fun DeliveryLoadScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
    private fun DeliveryLoadCommand.sameIdentity(other: DeliveryLoadCommand) =
        scope == other.scope &&
            driverCommand == other.driverCommand && action == other.action &&
            loadId == other.loadId &&
            expectedVersion == other.expectedVersion && idempotencyKey == other.idempotencyKey &&
            frozenBody == other.frozenBody

    private fun encode(command: DeliveryLoadCommand): String = buildJsonObject {
        put("schema", 1)
        put(
            "scope",
            buildJsonObject {
                put("userId", command.scope.userId)
                put("tenantId", command.scope.tenantId)
                put("workspaceId", command.scope.workspaceId)
                put("membershipId", command.scope.membershipId)
            }
        )
        put("driverCommand", command.driverCommand)
        put("action", command.action.name)
        put("loadId", command.loadId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("expectedVersion", command.expectedVersion?.let { JsonPrimitive(it) } ?: JsonNull)
        put("idempotencyKey", command.idempotencyKey)
        put("frozenBody", command.frozenBody?.let { JsonPrimitive(it) } ?: JsonNull)
        put("status", command.status.name)
    }.toString()

    private fun decode(payload: String): DeliveryLoadCommand? = runCatching {
        val root = json.parseToJsonElement(payload).jsonObject
        require(root["schema"]?.jsonPrimitive?.intOrNull == 1)
        val values = root.getValue("scope").jsonObject
        DeliveryLoadCommand(
            scope = DeliveryLoadScopeIdentity(
                values.getValue("userId").jsonPrimitive.content,
                values.getValue("tenantId").jsonPrimitive.content,
                values.getValue("workspaceId").jsonPrimitive.content,
                values.getValue("membershipId").jsonPrimitive.content
            ),
            driverCommand = root.getValue("driverCommand").jsonPrimitive.content.toBooleanStrict(),
            action = DeliveryLoadCommandAction.valueOf(
                root.getValue("action").jsonPrimitive.content
            ),
            loadId = root["loadId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            expectedVersion = root["expectedVersion"]?.takeUnless {
                it == JsonNull
            }?.jsonPrimitive?.longOrNull,
            idempotencyKey = root.getValue("idempotencyKey").jsonPrimitive.content,
            frozenBody = root["frozenBody"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            status = LoadCommandIntentStatus.valueOf(
                root.getValue("status").jsonPrimitive.content
            )
        )
    }.getOrNull()

    private companion object {
        val sharedMutexes = ConcurrentHashMap<DeliveryLoadScopeIdentity, Mutex>()
    }
}

private fun DeliveryLoadTransport.toFeatureOrNull(): DeliveryLoad? = runCatching {
    DeliveryLoad(
        id = id,
        version = version,
        status = status,
        originWarehouseId = originWarehouseId,
        stops = stops.map {
            DeliveryLoadStop(it.fulfillmentId, it.deliveryId, it.position, it.deliveryVersion)
        },
        assignedDriverMembershipId = assignedDriverMembershipId,
        vehicleReference = vehicleReference,
        compatibilityAttestation = LoadCompatibilityAttestation(
            capacitySufficient = compatibilityAttestation.capacitySufficient,
            handlingCompatible = compatibilityAttestation.handlingCompatible,
            zoneReasonable = compatibilityAttestation.zoneReasonable,
            noExclusiveTransportRestriction =
                compatibilityAttestation.noExclusiveTransportRestriction,
            observation = compatibilityAttestation.observation,
            attestedByMembershipId = compatibilityAttestation.attestedByMembershipId,
            attestedAt = Instant.parse(compatibilityAttestation.attestedAt)
        ),
        offeredByMembershipId = offeredByMembershipId,
        offeredAt = offeredAt?.let { Instant.parse(it) },
        dispatchConfirmedByMembershipId = dispatchConfirmedByMembershipId,
        dispatchConfirmedAt = dispatchConfirmedAt?.let(Instant::parse),
        driverAcceptedByMembershipId = driverAcceptedByMembershipId,
        driverAcceptedAt = driverAcceptedAt?.let(Instant::parse),
        history = history.map {
            DeliveryLoadHistoryEvent(
                it.eventType,
                it.actorMembershipId,
                Instant.parse(it.occurredAt),
                it.reason,
                affectedDriverMembershipId = null,
                previousStopOrder = it.previousStopOrder,
                newStopOrder = it.newStopOrder
            )
        }
    )
}.getOrNull()

private fun decodeBody(body: String?): JsonObject? = runCatching {
    Json.parseToJsonElement(body ?: "").jsonObject
}.getOrNull()

private fun JsonObject.stringArray(key: String): List<String>? = runCatching {
    getValue(key).jsonArray.map { it.jsonPrimitive.content }
}.getOrNull()
