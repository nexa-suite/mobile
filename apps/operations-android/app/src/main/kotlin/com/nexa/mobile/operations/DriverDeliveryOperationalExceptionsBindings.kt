package com.nexa.mobile.operations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.network.DriverOperationalExceptionActionTransport
import com.nexa.mobile.operations.core.network.DriverOperationalExceptionTransport
import com.nexa.mobile.operations.core.network.DriverOperationalExceptionsNetworkOutcome
import com.nexa.mobile.operations.core.network.NexaDriverDeliveryOperationalExceptionsGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalException
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionAction
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionCommand
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionIntent
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionIntentStatus
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionMetadataRead
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionMetadataWrite
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionMutation
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionMutationResult
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsGateway
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsLoadResult
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsSnapshot
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsViewModel
import com.nexa.mobile.operations.feature.delivery.driverDeliveryOperationalExceptionEmptyBody
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
internal class OperationsDriverDeliveryOperationalExceptionsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val api: NexaDriverDeliveryOperationalExceptionsGateway
) : DriverDeliveryOperationalExceptionsGateway {
    override suspend fun currentExceptions(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryOperationalExceptionsLoadResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLoadResult()
        val outcome = try {
            api.currentExceptions(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverDeliveryOperationalExceptionsLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift()
        return when (outcome) {
            is DriverOperationalExceptionsNetworkOutcome.Loaded -> try {
                if (outcome.value.deliveryId != deliveryId) {
                    DriverDeliveryOperationalExceptionsLoadResult.ServiceUnavailable
                } else {
                    DriverDeliveryOperationalExceptionsLoadResult.Loaded(
                        DriverDeliveryOperationalExceptionsSnapshot(
                            outcome.value.deliveryId,
                            outcome.value.deliveryVersion,
                            outcome.value.exceptions.map { it.toFeatureException() }
                        )
                    )
                }
            } catch (_: Exception) {
                DriverDeliveryOperationalExceptionsLoadResult.ServiceUnavailable
            }

            else -> outcome.toLoadResult()
        }
    }

    override suspend fun mutate(
        command: DriverDeliveryOperationalExceptionCommand,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryOperationalExceptionMutationResult {
        val before = authorize(authority, DRIVER_START_PERMISSIONS)
        if (before !is Authorization.Current) return before.toMutationResult()
        val action = when (command.action) {
            DriverDeliveryOperationalExceptionAction.Claim -> DriverOperationalExceptionActionTransport.Claim
            DriverDeliveryOperationalExceptionAction.Review -> DriverOperationalExceptionActionTransport.Review
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
            return DriverDeliveryOperationalExceptionMutationResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) {
            return DriverDeliveryOperationalExceptionMutationResult.UnknownOutcome
        }
        return when (outcome) {
            is DriverOperationalExceptionsNetworkOutcome.Changed -> try {
                if (outcome.value.deliveryId != command.deliveryId ||
                    outcome.value.exception.id != command.exceptionId
                ) {
                    DriverDeliveryOperationalExceptionMutationResult.UnknownOutcome
                } else {
                    DriverDeliveryOperationalExceptionMutationResult.Changed(
                        DriverDeliveryOperationalExceptionMutation(
                            deliveryId = outcome.value.deliveryId,
                            deliveryVersion = outcome.value.deliveryVersion,
                            exception = outcome.value.exception.toFeatureException(),
                            replayed = outcome.value.replayed
                        )
                    )
                }
            } catch (_: Exception) {
                DriverDeliveryOperationalExceptionMutationResult.UnknownOutcome
            }

            else -> outcome.toMutationResult()
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
        if (authority.permissions.none { it in requiredPermissions }) return Authorization.PermissionDenied
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: DriverDeliveryAuthority,
        originalLease: AccessTokenLease
    ): Boolean = sessions.sessionState.value == SessionState.Active &&
        sessions.isEpochCurrent(originalLease.epoch) && sessions.verifiedSession.value?.matches(authority) == true

    private suspend fun authorityDrift(): DriverDeliveryOperationalExceptionsLoadResult =
        if (sessions.sessionState.value != SessionState.Active) {
            DriverDeliveryOperationalExceptionsLoadResult.SessionInvalidated
        } else {
            DriverDeliveryOperationalExceptionsLoadResult.ContextInvalidated
        }

    private fun com.nexa.mobile.operations.core.auth.session.VerifiedSession.matches(
        authority: DriverDeliveryAuthority
    ): Boolean = hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
        workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
        permissions == authority.permissions

    private fun Authorization.toLoadResult(): DriverDeliveryOperationalExceptionsLoadResult = when (this) {
        Authorization.SessionInvalidated -> DriverDeliveryOperationalExceptionsLoadResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverDeliveryOperationalExceptionsLoadResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverDeliveryOperationalExceptionsLoadResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toMutationResult(): DriverDeliveryOperationalExceptionMutationResult = when (this) {
        Authorization.SessionInvalidated -> DriverDeliveryOperationalExceptionMutationResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverDeliveryOperationalExceptionMutationResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverDeliveryOperationalExceptionMutationResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun DriverOperationalExceptionsNetworkOutcome.toLoadResult(): DriverDeliveryOperationalExceptionsLoadResult = when (this) {
        DriverOperationalExceptionsNetworkOutcome.NotFound -> DriverDeliveryOperationalExceptionsLoadResult.NotFound
        DriverOperationalExceptionsNetworkOutcome.NetworkUnavailable -> DriverDeliveryOperationalExceptionsLoadResult.NetworkUnavailable
        DriverOperationalExceptionsNetworkOutcome.PermissionDenied -> DriverDeliveryOperationalExceptionsLoadResult.PermissionDenied
        DriverOperationalExceptionsNetworkOutcome.ContextInvalidated -> DriverDeliveryOperationalExceptionsLoadResult.ContextInvalidated
        DriverOperationalExceptionsNetworkOutcome.SessionInvalidated -> DriverDeliveryOperationalExceptionsLoadResult.SessionInvalidated
        else -> DriverDeliveryOperationalExceptionsLoadResult.ServiceUnavailable
    }

    private fun DriverOperationalExceptionsNetworkOutcome.toMutationResult(): DriverDeliveryOperationalExceptionMutationResult = when (this) {
        is DriverOperationalExceptionsNetworkOutcome.Rejected -> DriverDeliveryOperationalExceptionMutationResult.Rejected(code)
        DriverOperationalExceptionsNetworkOutcome.NotFound -> DriverDeliveryOperationalExceptionMutationResult.NotFound
        DriverOperationalExceptionsNetworkOutcome.StaleVersion -> DriverDeliveryOperationalExceptionMutationResult.StaleVersion
        DriverOperationalExceptionsNetworkOutcome.UnknownOutcome -> DriverDeliveryOperationalExceptionMutationResult.UnknownOutcome
        DriverOperationalExceptionsNetworkOutcome.NetworkUnavailable -> DriverDeliveryOperationalExceptionMutationResult.UnknownOutcome
        DriverOperationalExceptionsNetworkOutcome.ServiceUnavailable -> DriverDeliveryOperationalExceptionMutationResult.UnknownOutcome
        DriverOperationalExceptionsNetworkOutcome.PermissionDenied -> DriverDeliveryOperationalExceptionMutationResult.PermissionDenied
        DriverOperationalExceptionsNetworkOutcome.ContextInvalidated -> DriverDeliveryOperationalExceptionMutationResult.ContextInvalidated
        DriverOperationalExceptionsNetworkOutcome.SessionInvalidated -> DriverDeliveryOperationalExceptionMutationResult.SessionInvalidated
        is DriverOperationalExceptionsNetworkOutcome.Loaded,
        is DriverOperationalExceptionsNetworkOutcome.Changed -> DriverDeliveryOperationalExceptionMutationResult.ServiceUnavailable
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

internal class DriverDeliveryOperationalExceptionsBindings @Inject constructor(
    private val gateway: DriverDeliveryOperationalExceptionsGateway,
    private val metadataStore: DriverDeliveryOperationalExceptionMetadataStore
) {
    fun viewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriverDeliveryOperationalExceptionsViewModel::class.java))
            return DriverDeliveryOperationalExceptionsViewModel(gateway, metadataStore) as T
        }
    }
}

/** Typed response intent stored in the existing encrypted, identity-scoped metadata store. */
internal class AppDriverDeliveryOperationalExceptionMetadataStore(
    private val local: AndroidScopedMetadataStore
) : DriverDeliveryOperationalExceptionMetadataStore {
    override suspend fun loadIntent(
        scope: DriverAttemptScopeIdentity
    ): DriverDeliveryOperationalExceptionMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverDeliveryOperationalExceptionMetadataRead.Unavailable
            is ScopedMetadataRead.Value -> {
                val payload = stored.payload ?: return@withLock DriverDeliveryOperationalExceptionMetadataRead.Available(null)
                val intent = decode(payload) ?: return@withLock DriverDeliveryOperationalExceptionMetadataRead.Unavailable
                if (intent.scope != scope) return@withLock DriverDeliveryOperationalExceptionMetadataRead.Unavailable
                if (intent.status == DriverDeliveryOperationalExceptionIntentStatus.Pending) {
                    val recovered = intent.copy(status = DriverDeliveryOperationalExceptionIntentStatus.UnknownOutcome)
                    if (local.save(scope.toLocal(), encode(recovered))) {
                        DriverDeliveryOperationalExceptionMetadataRead.Available(recovered)
                    } else {
                        DriverDeliveryOperationalExceptionMetadataRead.Unavailable
                    }
                } else {
                    DriverDeliveryOperationalExceptionMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(
        intent: DriverDeliveryOperationalExceptionIntent
    ): DriverDeliveryOperationalExceptionMetadataWrite = mutex(intent.scope).withLock {
        when (val stored = local.load(intent.scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val current = stored.payload?.let(::decode)
                if (stored.payload != null && current == null) return@withLock DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
                when {
                    current == null -> save(intent)
                    current.scope != intent.scope -> DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
                    current.command != intent.command || current.initiatedByMembershipId != intent.initiatedByMembershipId ->
                        DriverDeliveryOperationalExceptionMetadataWrite.Conflict
                    current.status == DriverDeliveryOperationalExceptionIntentStatus.UnknownOutcome &&
                        intent.status == DriverDeliveryOperationalExceptionIntentStatus.Pending ->
                        DriverDeliveryOperationalExceptionMetadataWrite.Conflict
                    current == intent -> DriverDeliveryOperationalExceptionMetadataWrite.Saved
                    else -> save(intent)
                }
            }
        }
    }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverDeliveryOperationalExceptionMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val payload = stored.payload ?: return@withLock DriverDeliveryOperationalExceptionMetadataWrite.Saved
                val current = decode(payload) ?: return@withLock DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    DriverDeliveryOperationalExceptionMetadataWrite.Stale
                } else if (local.clear(scope.toLocal())) {
                    DriverDeliveryOperationalExceptionMetadataWrite.Saved
                } else {
                    DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun save(intent: DriverDeliveryOperationalExceptionIntent): DriverDeliveryOperationalExceptionMetadataWrite =
        if (local.save(intent.scope.toLocal(), encode(intent))) {
            DriverDeliveryOperationalExceptionMetadataWrite.Saved
        } else {
            DriverDeliveryOperationalExceptionMetadataWrite.Unavailable
        }

    private fun encode(intent: DriverDeliveryOperationalExceptionIntent): String {
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

    private fun decode(payload: String): DriverDeliveryOperationalExceptionIntent? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.requiredLong("schema") == 1L)
        val scope = DriverAttemptScopeIdentity(
            root.requiredString("userId"), root.requiredString("tenantId"),
            root.requiredString("workspaceId"), root.requiredString("membershipId")
        )
        val body = root.requiredString("frozenBody")
        require(body == driverDeliveryOperationalExceptionEmptyBody())
        DriverDeliveryOperationalExceptionIntent(
            scope = scope,
            command = DriverDeliveryOperationalExceptionCommand(
                deliveryId = root.requiredString("deliveryId"),
                exceptionId = root.requiredString("exceptionId"),
                action = DriverDeliveryOperationalExceptionAction.valueOf(root.requiredString("action")),
                expectedDeliveryVersion = root.requiredLong("expectedDeliveryVersion"),
                idempotencyKey = root.requiredString("idempotencyKey"),
                frozenBody = body
            ),
            initiatedByMembershipId = root.requiredString("initiatedByMembershipId"),
            initiatedAt = root.requiredString("initiatedAt"),
            status = DriverDeliveryOperationalExceptionIntentStatus.valueOf(root.requiredString("status"))
        )
    } catch (_: Exception) {
        null
    }

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex = locks.computeIfAbsent(scope) { Mutex() }

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)
        ?: error("Invalid operational exception intent field")

    private fun JsonObject.requiredLong(key: String): Long = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?: error("Invalid operational exception intent field")

    private companion object {
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

private fun DriverOperationalExceptionTransport.toFeatureException() = DriverDeliveryOperationalException(
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
internal object DriverDeliveryOperationalExceptionsModule {
    @Provides
    @Singleton
    fun provideOperationalExceptionsApi(protectedCalls: ProtectedCallExecutor) =
        NexaDriverDeliveryOperationalExceptionsGateway(protectedCalls)

    @Provides
    @Singleton
    fun provideOperationalExceptionsGateway(
        sessions: SessionCoordinator,
        api: NexaDriverDeliveryOperationalExceptionsGateway
    ): DriverDeliveryOperationalExceptionsGateway = OperationsDriverDeliveryOperationalExceptionsGateway(sessions, api)

    @Provides
    @Singleton
    fun provideOperationalExceptionMetadataStore(
        @ApplicationContext context: Context
    ): DriverDeliveryOperationalExceptionMetadataStore = AppDriverDeliveryOperationalExceptionMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverDeliveryOperationalException)
    )
}
