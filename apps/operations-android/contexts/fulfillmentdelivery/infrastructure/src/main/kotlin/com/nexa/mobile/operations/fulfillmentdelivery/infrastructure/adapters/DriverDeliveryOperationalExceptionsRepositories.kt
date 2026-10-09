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
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionAction as OperationalExceptionAction
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionCommand as ExceptionCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionIntent as OperationalExceptionIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionIntentStatus as ExceptionIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionMetadataRead as ExceptionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionMetadataStore as ExceptionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionMetadataWrite as ExceptionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionMutation as ExceptionMutation
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionMutationResult as ExceptionMutationResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionsGateway as ExceptionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionsLoadResult as ExceptionsLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryOperationalException as OperationalException
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryOperationalExceptionsSnapshot as ExceptionsSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DriverOperationalExceptionActionTransport as ExceptionActionTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DriverOperationalExceptionTransport as ExceptionTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DriverOperationalExceptionsNetworkOutcome as ExceptionsOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaDriverDeliveryOperationalExceptionsGateway
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Singleton
class OperationsDriverDeliveryOperationalExceptionsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val api: NexaDriverDeliveryOperationalExceptionsGateway
) : ExceptionsGateway {
    override suspend fun currentExceptions(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): ExceptionsLoadResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLoadResult()
        val outcome = try {
            api.currentExceptions(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ExceptionsLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift()
        return when (outcome) {
            is ExceptionsOutcome.Loaded -> try {
                if (outcome.value.deliveryId != deliveryId) {
                    ExceptionsLoadResult.ServiceUnavailable
                } else {
                    ExceptionsLoadResult.Loaded(
                        ExceptionsSnapshot(
                            outcome.value.deliveryId,
                            outcome.value.deliveryVersion,
                            outcome.value.exceptions.map { it.toFeatureException() }
                        )
                    )
                }
            } catch (_: Exception) {
                ExceptionsLoadResult.ServiceUnavailable
            }

            else -> outcome.toLoadResult()
        }
    }

    override suspend fun mutate(
        command: ExceptionCommand,
        authority: DriverDeliveryAuthority
    ): ExceptionMutationResult {
        val before = authorize(authority, DRIVER_START_PERMISSIONS)
        if (before !is Authorization.Current) return before.toMutationResult()
        val action = when (command.action) {
            OperationalExceptionAction.Claim ->
                ExceptionActionTransport.Claim

            OperationalExceptionAction.Review ->
                ExceptionActionTransport.Review

            OperationalExceptionAction.ResolveWarning ->
                ExceptionActionTransport.ResolveWarning

            OperationalExceptionAction.CloseWarning ->
                ExceptionActionTransport.CloseWarning
        }
        val outcome = try {
            api.mutate(
                command.deliveryId,
                command.exceptionId,
                action,
                command.expectedDeliveryVersion,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ExceptionMutationResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) {
            return ExceptionMutationResult.UnknownOutcome
        }
        return when (outcome) {
            is ExceptionsOutcome.Changed -> try {
                if (outcome.value.deliveryId != command.deliveryId ||
                    outcome.value.exception.id != command.exceptionId
                ) {
                    ExceptionMutationResult.UnknownOutcome
                } else {
                    ExceptionMutationResult.Changed(
                        ExceptionMutation(
                            deliveryId = outcome.value.deliveryId,
                            deliveryVersion = outcome.value.deliveryVersion,
                            exception = outcome.value.exception.toFeatureException(),
                            replayed = outcome.value.replayed
                        )
                    )
                }
            } catch (_: Exception) {
                ExceptionMutationResult.UnknownOutcome
            }

            else -> outcome.toMutationResult()
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

    private suspend fun authorityDrift(): ExceptionsLoadResult =
        if (sessions.sessionState.value != SessionState.Active) {
            ExceptionsLoadResult.SessionInvalidated
        } else {
            ExceptionsLoadResult.ContextInvalidated
        }

    private fun VerifiedSession.matches(authority: DriverDeliveryAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLoadResult(): ExceptionsLoadResult = when (this) {
        Authorization.SessionInvalidated ->
            ExceptionsLoadResult.SessionInvalidated

        Authorization.ContextInvalidated ->
            ExceptionsLoadResult.ContextInvalidated

        Authorization.PermissionDenied ->
            ExceptionsLoadResult.PermissionDenied

        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toMutationResult(): ExceptionMutationResult = when (this) {
        Authorization.SessionInvalidated ->
            ExceptionMutationResult.SessionInvalidated

        Authorization.ContextInvalidated ->
            ExceptionMutationResult.ContextInvalidated

        Authorization.PermissionDenied ->
            ExceptionMutationResult.PermissionDenied

        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun ExceptionsOutcome.toLoadResult(): ExceptionsLoadResult = when (this) {
        ExceptionsOutcome.NotFound ->
            ExceptionsLoadResult.NotFound

        ExceptionsOutcome.NetworkUnavailable ->
            ExceptionsLoadResult.NetworkUnavailable

        ExceptionsOutcome.PermissionDenied ->
            ExceptionsLoadResult.PermissionDenied

        ExceptionsOutcome.ContextInvalidated ->
            ExceptionsLoadResult.ContextInvalidated

        ExceptionsOutcome.SessionInvalidated ->
            ExceptionsLoadResult.SessionInvalidated

        else -> ExceptionsLoadResult.ServiceUnavailable
    }

    private fun ExceptionsOutcome.toMutationResult(): ExceptionMutationResult = when (this) {
        is ExceptionsOutcome.Rejected ->
            ExceptionMutationResult.Rejected(
                code
            )

        ExceptionsOutcome.NotFound ->
            ExceptionMutationResult.NotFound

        ExceptionsOutcome.StaleVersion ->
            ExceptionMutationResult.StaleVersion

        ExceptionsOutcome.UnknownOutcome ->
            ExceptionMutationResult.UnknownOutcome

        ExceptionsOutcome.NetworkUnavailable ->
            ExceptionMutationResult.UnknownOutcome

        ExceptionsOutcome.ServiceUnavailable ->
            ExceptionMutationResult.UnknownOutcome

        ExceptionsOutcome.PermissionDenied ->
            ExceptionMutationResult.PermissionDenied

        ExceptionsOutcome.ContextInvalidated ->
            ExceptionMutationResult.ContextInvalidated

        ExceptionsOutcome.SessionInvalidated ->
            ExceptionMutationResult.SessionInvalidated

        is ExceptionsOutcome.Loaded,
        is ExceptionsOutcome.Changed ->
            ExceptionMutationResult.ServiceUnavailable
    }

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

/** Typed response intent stored in the existing encrypted, identity-scoped metadata store. */
class AppDriverDeliveryOperationalExceptionMetadataStore(
    private val local: AndroidScopedMetadataStore
) : ExceptionMetadataStore {
    override suspend fun loadIntent(scope: DriverAttemptScopeIdentity): ExceptionMetadataRead =
        mutex(scope).withLock {
            when (val stored = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable ->
                    ExceptionMetadataRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload =
                        stored.payload
                            ?: return@withLock ExceptionMetadataRead.Available(
                                null
                            )
                    val intent =
                        decode(payload)
                            ?: return@withLock ExceptionMetadataRead.Unavailable
                    if (intent.scope !=
                        scope
                    ) {
                        return@withLock ExceptionMetadataRead.Unavailable
                    }
                    if (intent.status == ExceptionIntentStatus.Pending) {
                        val recovered = intent.copy(
                            status = ExceptionIntentStatus.UnknownOutcome
                        )
                        if (local.save(scope.toLocal(), encode(recovered))) {
                            ExceptionMetadataRead.Available(recovered)
                        } else {
                            ExceptionMetadataRead.Unavailable
                        }
                    } else {
                        ExceptionMetadataRead.Available(intent)
                    }
                }
            }
        }

    override suspend fun saveIntent(intent: OperationalExceptionIntent): ExceptionMetadataWrite =
        mutex(intent.scope).withLock {
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable ->
                    ExceptionMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = stored.payload?.let(::decode)
                    if (stored.payload != null &&
                        current == null
                    ) {
                        return@withLock ExceptionMetadataWrite.Unavailable
                    }
                    when {
                        current == null -> save(intent)

                        current.scope != intent.scope ->
                            ExceptionMetadataWrite.Unavailable

                        current.command != intent.command ||
                            current.initiatedByMembershipId != intent.initiatedByMembershipId ->
                            ExceptionMetadataWrite.Conflict

                        current.status ==
                            ExceptionIntentStatus.UnknownOutcome &&
                            intent.status == ExceptionIntentStatus.Pending ->
                            ExceptionMetadataWrite.Conflict

                        current == intent -> ExceptionMetadataWrite.Saved

                        else -> save(intent)
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): ExceptionMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable ->
                ExceptionMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload =
                    stored.payload
                        ?: return@withLock ExceptionMetadataWrite.Saved
                val current =
                    decode(payload)
                        ?: return@withLock ExceptionMetadataWrite.Unavailable
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    ExceptionMetadataWrite.Stale
                } else if (local.clear(scope.toLocal())) {
                    ExceptionMetadataWrite.Saved
                } else {
                    ExceptionMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun save(intent: OperationalExceptionIntent): ExceptionMetadataWrite =
        if (local.save(intent.scope.toLocal(), encode(intent))) {
            ExceptionMetadataWrite.Saved
        } else {
            ExceptionMetadataWrite.Unavailable
        }

    private fun encode(intent: OperationalExceptionIntent): String {
        val command = intent.command
        return JsonObject(
            mapOf(
                "schema" to JsonPrimitive(1),
                "userId" to JsonPrimitive(intent.scope.userId),
                "tenantId" to JsonPrimitive(intent.scope.tenantId),
                "workspaceId" to JsonPrimitive(intent.scope.workspaceId),
                "membershipId" to JsonPrimitive(intent.scope.membershipId),
                "deliveryId" to JsonPrimitive(command.deliveryId),
                "exceptionId" to JsonPrimitive(command.exceptionId),
                "action" to JsonPrimitive(command.action.name),
                "expectedDeliveryVersion" to JsonPrimitive(command.expectedDeliveryVersion),
                "idempotencyKey" to JsonPrimitive(command.idempotencyKey),
                "frozenBody" to JsonPrimitive(command.frozenBody),
                "initiatedByMembershipId" to JsonPrimitive(intent.initiatedByMembershipId),
                "initiatedAt" to JsonPrimitive(intent.initiatedAt),
                "status" to JsonPrimitive(intent.status.name)
            )
        ).toString()
    }

    private fun decode(payload: String): OperationalExceptionIntent? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.requiredLong("schema") == 1L)
        val scope = DriverAttemptScopeIdentity(
            root.requiredString("userId"),
            root.requiredString("tenantId"),
            root.requiredString("workspaceId"),
            root.requiredString("membershipId")
        )
        val body = root.requiredStringOrEmpty("frozenBody")
        OperationalExceptionIntent(
            scope = scope,
            command = ExceptionCommand(
                deliveryId = root.requiredString("deliveryId"),
                exceptionId = root.requiredString("exceptionId"),
                action = OperationalExceptionAction.valueOf(
                    root.requiredString("action")
                ),
                expectedDeliveryVersion = root.requiredLong("expectedDeliveryVersion"),
                idempotencyKey = root.requiredString("idempotencyKey"),
                frozenBody = body
            ),
            initiatedByMembershipId = root.requiredString("initiatedByMembershipId"),
            initiatedAt = root.requiredString("initiatedAt"),
            status = ExceptionIntentStatus.valueOf(
                root.requiredString("status")
            )
        )
    } catch (_: Exception) {
        null
    }

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex = locks.computeIfAbsent(scope) {
        Mutex()
    }

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)
        ?: error("Invalid operational exception intent field")

    private fun JsonObject.requiredStringOrEmpty(key: String): String = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.content
        ?: error("Invalid operational exception intent field")

    private fun JsonObject.requiredLong(key: String): Long = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?: error("Invalid operational exception intent field")

    private companion object {
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

private fun ExceptionTransport.toFeatureException() = OperationalException(
    id = id,
    sourceKind = sourceKind,
    sourceIncidentId = sourceIncidentId,
    affectedObjectType = affectedObjectType,
    affectedObjectId = affectedObjectId,
    type = type,
    severity = severity,
    status = status,
    reason = reason,
    description = description,
    place = place,
    resolution = resolution,
    outcome = outcome,
    reportedByMembershipId = reportedByMembershipId,
    occurredAt = occurredAt,
    reportedAt = reportedAt,
    responsibleMembershipId = responsibleMembershipId,
    claimedAt = claimedAt,
    underReviewByMembershipId = underReviewByMembershipId,
    underReviewAt = underReviewAt,
    evidenceObjectIds = evidenceObjectIds
)

@Module
@InstallIn(SingletonComponent::class)
object DriverDeliveryOperationalExceptionsModule {
    @Provides
    @Singleton
    fun provideOperationalExceptionsApi(protectedCalls: ProtectedCallExecutor) =
        NexaDriverDeliveryOperationalExceptionsGateway(protectedCalls)

    @Provides
    @Singleton
    fun provideOperationalExceptionsGateway(
        sessions: SessionCoordinator,
        api: NexaDriverDeliveryOperationalExceptionsGateway
    ): ExceptionsGateway = OperationsDriverDeliveryOperationalExceptionsGateway(sessions, api)

    @Provides
    @Singleton
    fun provideOperationalExceptionMetadataStore(
        @ApplicationContext context: Context
    ): ExceptionMetadataStore = AppDriverDeliveryOperationalExceptionMetadataStore(
        AndroidScopedMetadataStore(
            context,
            ScopedMetadataPurpose.DriverDeliveryOperationalException
        )
    )
}
