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
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.BusinessOperationalExceptionActionTransport as ExceptionActionTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.BusinessOperationalExceptionAssigneeTransport as ExceptionAssigneeTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.BusinessOperationalExceptionTransport as ExceptionTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.BusinessOperationalExceptionsNetworkResult as ExceptionsNetworkResult
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaBusinessOperationalExceptionsGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionMetadataStore as ExceptionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionsGateway as ExceptionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.BusinessOperationalException as Exception
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAction as ExceptionAction
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.BusinessOperationalExceptionActor as ExceptionActor
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAssigneesResult as ExceptionAssigneesResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAuthority as ExceptionAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionCommand as ExceptionCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionIntent as ExceptionIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionIntentStatus as ExceptionIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionMetadataRead as ExceptionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionMetadataWrite as ExceptionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionScopeIdentity as ExceptionScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionsGatewayResult as ExceptionsGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.BusinessOperationalExceptionsSnapshot as ExceptionsSnapshot
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Module
@InstallIn(SingletonComponent::class)
object BusinessOperationalExceptionsBindings {
    @Provides
    @Singleton
    fun businessOperationalExceptionsApi(calls: ProtectedCallExecutor) =
        NexaBusinessOperationalExceptionsGateway(calls)

    @Provides
    @Singleton
    fun businessOperationalExceptionMetadataStore(
        @ApplicationContext context: Context
    ): ExceptionMetadataStore = AppBusinessOperationalExceptionMetadataStore(context)

    @Provides
    @Singleton
    fun businessOperationalExceptionsGateway(
        gateway: OperationsBusinessOperationalExceptionsGateway
    ): ExceptionsGateway = gateway
}

/** Verifies current native session, exact context, epoch and explicit permission around each request. */
@Singleton
class OperationsBusinessOperationalExceptionsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val api: NexaBusinessOperationalExceptionsGateway
) : ExceptionsGateway {
    override suspend fun current(authority: ExceptionAuthority): ExceptionsGatewayResult {
        val before = authorize(authority, ExceptionAuthority.READ_PERMISSION)
        if (before !is Authorization.Current) return before.toGatewayResult()
        val result = try {
            api.current()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: kotlin.Exception) {
            return ExceptionsGatewayResult.Failed("NETWORK_UNAVAILABLE")
        }
        if (!isCurrent(authority, before.lease)) return authorityDrift()
        return when (result) {
            is ExceptionsNetworkResult.Current -> try {
                ExceptionsGatewayResult.Current(
                    ExceptionsSnapshot(
                        asOf = result.value.asOf,
                        exceptions = result.value.exceptions.map { it.toFeature() }
                    )
                )
            } catch (_: kotlin.Exception) {
                ExceptionsGatewayResult.Failed("INVALID_RESPONSE")
            }

            is ExceptionsNetworkResult.Failed -> result.toFeature()

            is ExceptionsNetworkResult.Assignees,
            is ExceptionsNetworkResult.Changed ->
                ExceptionsGatewayResult.Failed(
                    "INVALID_RESPONSE"
                )
        }
    }

    override suspend fun assignees(
        exceptionId: String,
        authority: ExceptionAuthority
    ): ExceptionAssigneesResult {
        val before = authorize(authority, ExceptionAuthority.READ_PERMISSION)
        if (before !is Authorization.Current) return before.toAssigneesResult()
        val result = try {
            api.assignees(exceptionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: kotlin.Exception) {
            return ExceptionAssigneesResult.Failed("NETWORK_UNAVAILABLE")
        }
        if (!isCurrent(
                authority,
                before.lease
            )
        ) {
            return ExceptionAssigneesResult.Failed("ACCESS_CONTEXT_INVALID")
        }
        return when (result) {
            is ExceptionsNetworkResult.Assignees -> try {
                ExceptionAssigneesResult.Loaded(
                    result.values.map {
                        it.toFeature()
                    }
                )
            } catch (_: kotlin.Exception) {
                ExceptionAssigneesResult.Failed("INVALID_RESPONSE")
            }

            is ExceptionsNetworkResult.Failed ->
                ExceptionAssigneesResult.Failed(
                    result.code
                )

            is ExceptionsNetworkResult.Current,
            is ExceptionsNetworkResult.Changed ->
                ExceptionAssigneesResult.Failed(
                    "INVALID_RESPONSE"
                )
        }
    }

    override suspend fun mutate(
        command: ExceptionCommand,
        authority: ExceptionAuthority
    ): ExceptionsGatewayResult {
        if (command.scope !=
            authority.scope
        ) {
            return ExceptionsGatewayResult.Failed("ACCESS_CONTEXT_INVALID")
        }
        val before =
            authorize(authority, ExceptionAuthority.COORDINATE_PERMISSION)
        if (before !is Authorization.Current) return before.toGatewayResult()
        val result = try {
            api.mutate(
                command.exceptionId,
                command.action.toTransport(),
                command.expectedDeliveryVersion,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: kotlin.Exception) {
            return ExceptionsGatewayResult.Failed("UNKNOWN_OUTCOME", true)
        }
        if (!isCurrent(authority, before.lease)) {
            return ExceptionsGatewayResult.Failed("ACCESS_CONTEXT_INVALID", true)
        }
        return when (result) {
            is ExceptionsNetworkResult.Changed -> try {
                val value = result.value
                if (value.exception.id != command.exceptionId ||
                    value.exception.deliveryVersion != value.deliveryVersion
                ) {
                    ExceptionsGatewayResult.Failed("UNKNOWN_OUTCOME", true)
                } else {
                    ExceptionsGatewayResult.Changed(
                        value.exception.toFeature(),
                        value.deliveryVersion,
                        value.replayed
                    )
                }
            } catch (_: kotlin.Exception) {
                ExceptionsGatewayResult.Failed("UNKNOWN_OUTCOME", true)
            }

            is ExceptionsNetworkResult.Failed -> result.toFeature()

            is ExceptionsNetworkResult.Current,
            is ExceptionsNetworkResult.Assignees ->
                ExceptionsGatewayResult.Failed(
                    "UNKNOWN_OUTCOME",
                    true
                )
        }
    }

    private suspend fun authorize(
        authority: ExceptionAuthority,
        permission: String
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        if (!sessions.isEpochCurrent(lease.epoch)) return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        if (permission !in authority.permissions) return Authorization.PermissionDenied
        return Authorization.Current(lease)
    }

    private suspend fun isCurrent(authority: ExceptionAuthority, lease: AccessTokenLease) =
        sessions.sessionState.value == SessionState.Active && sessions.isEpochCurrent(
            lease.epoch
        ) &&
            sessions.verifiedSession.value?.matches(authority) == true

    private fun VerifiedSession.matches(authority: ExceptionAuthority): Boolean {
        val scope = authority.scope ?: return false
        return hasAuthorizedContext && userId == scope.userId && tenantId == scope.tenantId &&
            workspaceId == scope.workspaceId && membershipId == scope.membershipId &&
            permissions == authority.permissions
    }

    private suspend fun authorityDrift() = if (sessions.sessionState.value != SessionState.Active) {
        ExceptionsGatewayResult.Failed("SESSION_INVALID")
    } else {
        ExceptionsGatewayResult.Failed("ACCESS_CONTEXT_INVALID")
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private fun Authorization.toGatewayResult() = when (this) {
        is Authorization.Current -> error("current authorization is not a failure")

        Authorization.SessionInvalidated -> ExceptionsGatewayResult.Failed(
            "SESSION_INVALID"
        )

        Authorization.ContextInvalidated -> ExceptionsGatewayResult.Failed(
            "ACCESS_CONTEXT_INVALID"
        )

        Authorization.PermissionDenied -> ExceptionsGatewayResult.Failed(
            "PERMISSION_DENIED"
        )
    }

    private fun Authorization.toAssigneesResult() = when (this) {
        is Authorization.Current -> error("current authorization is not a failure")

        Authorization.SessionInvalidated -> ExceptionAssigneesResult.Failed(
            "SESSION_INVALID"
        )

        Authorization.ContextInvalidated -> ExceptionAssigneesResult.Failed(
            "ACCESS_CONTEXT_INVALID"
        )

        Authorization.PermissionDenied -> ExceptionAssigneesResult.Failed(
            "PERMISSION_DENIED"
        )
    }

    private fun ExceptionAction.toTransport() = when (this) {
        ExceptionAction.CLAIM ->
            ExceptionActionTransport.CLAIM

        ExceptionAction.REASSIGN ->
            ExceptionActionTransport.REASSIGN

        ExceptionAction.FOLLOW_UP ->
            ExceptionActionTransport.FOLLOW_UP

        ExceptionAction.RESOLVE ->
            ExceptionActionTransport.RESOLVE

        ExceptionAction.CLOSE ->
            ExceptionActionTransport.CLOSE
    }

    private fun ExceptionsNetworkResult.toFeature() = when (this) {
        is ExceptionsNetworkResult.Failed ->
            ExceptionsGatewayResult.Failed(
                code,
                unknownOutcome
            )

        else -> ExceptionsGatewayResult.Failed("INVALID_RESPONSE")
    }

    private fun ExceptionTransport.toFeature() = Exception(
        id, deliveryId, deliveryVersion, sourceKind, sourceIncidentId,
        affectedObjectType, affectedObjectId, type,
        severity, status, reason, description, place, resolution,
        outcome, reportedByMembershipId, occurredAt,
        reportedAt, responsibleMembershipId, claimedAt, underReviewByMembershipId, underReviewAt,
        coordinationOwnerMembershipId, coordinationClaimedAt, evidenceObjectIds
    )

    private fun ExceptionAssigneeTransport.toFeature() = ExceptionActor(
        membershipId,
        displayName,
        coordinator,
        driverReporter
    )
}

@Singleton
class AppBusinessOperationalExceptionMetadataStore @Inject constructor(
    @ApplicationContext context: Context
) : ExceptionMetadataStore {
    private val local =
        AndroidScopedMetadataStore(
            context,
            ScopedMetadataPurpose.BusinessOperationalExceptionCommand
        )
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun load(scope: ExceptionScopeIdentity): ExceptionMetadataRead =
        lock(scope).withLock {
            when (val read = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> ExceptionMetadataRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = read.payload
                    if (payload ==
                        null
                    ) {
                        return@withLock ExceptionMetadataRead.Available(null)
                    }
                    val command = decode(payload)
                        ?: return@withLock ExceptionMetadataRead.Unavailable
                    if (command.scope !=
                        scope
                    ) {
                        return@withLock ExceptionMetadataRead.Unavailable
                    }
                    if (command.status == ExceptionIntentStatus.Pending) {
                        val recovered = command.copy(
                            status = ExceptionIntentStatus.UnknownOutcome
                        )
                        if (local.save(scope.toLocal(), encode(recovered))) {
                            ExceptionMetadataRead.Available(
                                ExceptionIntent(recovered)
                            )
                        } else {
                            ExceptionMetadataRead.Unavailable
                        }
                    } else {
                        ExceptionMetadataRead.Available(
                            ExceptionIntent(command)
                        )
                    }
                }
            }
        }

    override suspend fun save(intent: ExceptionIntent): ExceptionMetadataWrite =
        lock(intent.command.scope).withLock {
            val scope = intent.command.scope
            when (val read = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> ExceptionMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = read.payload
                    if (payload == null) {
                        if (local.save(scope.toLocal(), encode(intent.command))) {
                            ExceptionMetadataWrite.Saved
                        } else {
                            ExceptionMetadataWrite.Unavailable
                        }
                    } else {
                        val current =
                            decode(payload)
                                ?: return@withLock ExceptionMetadataWrite.Unavailable
                        when {
                            current.scope != scope ->
                                ExceptionMetadataWrite.Unavailable

                            current.sameIdentity(
                                intent.command
                            ) && current == intent.command ->
                                ExceptionMetadataWrite.Saved

                            current.sameIdentity(intent.command) &&
                                current.status == ExceptionIntentStatus.Pending &&
                                intent.command.status ==
                                ExceptionIntentStatus.UnknownOutcome -> {
                                if (local.save(scope.toLocal(), encode(intent.command))) {
                                    ExceptionMetadataWrite.Saved
                                } else {
                                    ExceptionMetadataWrite.Unavailable
                                }
                            }

                            else -> ExceptionMetadataWrite.Conflict
                        }
                    }
                }
            }
        }

    override suspend fun clear(
        scope: ExceptionScopeIdentity,
        idempotencyKey: String
    ): ExceptionMetadataWrite = lock(scope).withLock {
        when (val read = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> ExceptionMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = read.payload
                if (payload ==
                    null
                ) {
                    return@withLock ExceptionMetadataWrite.Saved
                }
                val current =
                    decode(payload)
                        ?: return@withLock ExceptionMetadataWrite.Unavailable
                if (current.scope != scope ||
                    current.idempotencyKey != idempotencyKey
                ) {
                    ExceptionMetadataWrite.Conflict
                } else if (local.clear(scope.toLocal())) {
                    ExceptionMetadataWrite.Saved
                } else {
                    ExceptionMetadataWrite.Unavailable
                }
            }
        }
    }

    private fun lock(scope: ExceptionScopeIdentity) = locks.getOrPut(scope) {
        Mutex()
    }
    private fun ExceptionScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
    private fun ExceptionCommand.sameIdentity(other: ExceptionCommand) =
        scope == other.scope && action == other.action && exceptionId == other.exceptionId &&
            expectedDeliveryVersion == other.expectedDeliveryVersion &&
            idempotencyKey == other.idempotencyKey &&
            frozenBody == other.frozenBody

    private fun encode(command: ExceptionCommand) = buildJsonObject {
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
        put("action", command.action.name)
        put("exceptionId", command.exceptionId)
        put("expectedDeliveryVersion", command.expectedDeliveryVersion)
        put("idempotencyKey", command.idempotencyKey)
        put("frozenBody", command.frozenBody)
        put("status", command.status.name)
    }.toString()

    private fun decode(payload: String) = runCatching {
        val root = json.parseToJsonElement(payload).jsonObject
        require(root.getValue("schema").jsonPrimitive.intOrNull == 1)
        val rawScope = root.getValue("scope").jsonObject
        ExceptionCommand(
            scope = ExceptionScopeIdentity(
                rawScope.getValue("userId").jsonPrimitive.content,
                rawScope.getValue("tenantId").jsonPrimitive.content,
                rawScope.getValue("workspaceId").jsonPrimitive.content,
                rawScope.getValue("membershipId").jsonPrimitive.content
            ),
            action = ExceptionAction.valueOf(
                root.getValue("action").jsonPrimitive.content
            ),
            exceptionId = root.getValue("exceptionId").jsonPrimitive.content,
            expectedDeliveryVersion =
                root.getValue("expectedDeliveryVersion").jsonPrimitive.longOrNull
                    ?: error("Missing version"),
            idempotencyKey = root.getValue("idempotencyKey").jsonPrimitive.content,
            frozenBody = root.getValue("frozenBody").jsonPrimitive.content,
            status = ExceptionIntentStatus.valueOf(
                root.getValue("status").jsonPrimitive.content
            )
        )
    }.getOrNull()

    private companion object {
        val locks = ConcurrentHashMap<ExceptionScopeIdentity, Mutex>()
    }
}
