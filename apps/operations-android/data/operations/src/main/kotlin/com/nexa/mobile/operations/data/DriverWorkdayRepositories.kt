package com.nexa.mobile.operations.data

import android.content.Context
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.network.DriverWorkdayLocationCommand as WorkdayLocationCommand
import com.nexa.mobile.operations.core.network.DriverWorkdayNetworkResult
import com.nexa.mobile.operations.core.network.DriverWorkdayProjection
import com.nexa.mobile.operations.core.network.NexaDriverWorkdayGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.delivery.application.DriverWorkdayCommandStore
import com.nexa.mobile.operations.feature.delivery.application.DriverWorkdayGateway
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkday
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayCommandAction
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayCommandIntent
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayCommandIntentRead as WorkdayCommandIntentRead
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayCommandResult
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayCommandScope
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayLocationSample as WorkdayLocationSample
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayReadResult
import com.nexa.mobile.operations.feature.delivery.model.DriverWorkdayStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Singleton
class OperationsDriverWorkdayGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    protectedCalls: ProtectedCallExecutor
) : DriverWorkdayGateway {
    private val api = NexaDriverWorkdayGateway(protectedCalls)

    override suspend fun current(authority: DriverDeliveryAuthority): DriverWorkdayReadResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toReadResult()
        val result = try {
            api.current()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverWorkdayReadResult.Unavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDriftRead()
        return when (result) {
            is DriverWorkdayNetworkResult.Current -> DriverWorkdayReadResult.Current(
                result.workday?.toFeature()
            )

            DriverWorkdayNetworkResult.PermissionDenied -> DriverWorkdayReadResult.PermissionDenied

            else -> DriverWorkdayReadResult.Unavailable
        }
    }

    override suspend fun start(
        authority: DriverDeliveryAuthority,
        idempotencyKey: String
    ): DriverWorkdayCommandResult = command(authority) { api.start(idempotencyKey) }

    override suspend fun end(
        authority: DriverDeliveryAuthority,
        workdayId: String,
        version: Long,
        idempotencyKey: String
    ): DriverWorkdayCommandResult = command(authority) {
        api.end(workdayId, version, idempotencyKey)
    }

    override suspend fun setLocationAvailability(
        authority: DriverDeliveryAuthority,
        workdayId: String,
        version: Long,
        locationAvailable: Boolean,
        idempotencyKey: String
    ): DriverWorkdayCommandResult = command(authority) {
        api.setLocationAvailability(workdayId, version, locationAvailable, idempotencyKey)
    }

    override suspend fun reportLocation(
        authority: DriverDeliveryAuthority,
        workdayId: String,
        location: WorkdayLocationSample
    ): DriverWorkdayCommandResult = command(authority) {
        api.reportLocation(
            workdayId,
            WorkdayLocationCommand(
                location.sampleId,
                location.latitude,
                location.longitude,
                location.accuracyMeters,
                location.capturedAt
            )
        )
    }

    private suspend fun command(
        authority: DriverDeliveryAuthority,
        block: suspend () -> DriverWorkdayNetworkResult
    ): DriverWorkdayCommandResult {
        val before = authorize(authority, DRIVER_START_PERMISSIONS)
        if (before !is Authorization.Current) return before.toCommandResult()
        val result = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverWorkdayCommandResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) return authorityDriftCommand()
        return result.toCommandResult()
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
        lease: AccessTokenLease
    ): Boolean = sessions.sessionState.value == SessionState.Active && sessions.isEpochCurrent(
        lease.epoch
    ) &&
        sessions.verifiedSession.value?.matches(authority) == true

    private suspend fun authorityDriftRead(): DriverWorkdayReadResult =
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            DriverWorkdayReadResult.SessionInvalidated
        } else {
            DriverWorkdayReadResult.ContextInvalidated
        }

    private suspend fun authorityDriftCommand(): DriverWorkdayCommandResult =
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            DriverWorkdayCommandResult.SessionInvalidated
        } else {
            DriverWorkdayCommandResult.ContextInvalidated
        }

    private fun DriverWorkdayProjection.toFeature() = DriverWorkday(
        id,
        version,
        DriverWorkdayStatus.valueOf(status),
        startedAt,
        endedAt,
        locationAvailable
    )

    private fun DriverWorkdayNetworkResult.toCommandResult(): DriverWorkdayCommandResult =
        when (this) {
            DriverWorkdayNetworkResult.Accepted, is DriverWorkdayNetworkResult.LocationAccepted ->
                DriverWorkdayCommandResult.Accepted

            is DriverWorkdayNetworkResult.Rejected -> DriverWorkdayCommandResult.Rejected(code)

            DriverWorkdayNetworkResult.NotFound -> DriverWorkdayCommandResult.NotFound

            DriverWorkdayNetworkResult.StaleVersion -> DriverWorkdayCommandResult.StaleVersion

            DriverWorkdayNetworkResult.UnknownOutcome -> DriverWorkdayCommandResult.UnknownOutcome

            DriverWorkdayNetworkResult.PermissionDenied ->
                DriverWorkdayCommandResult.PermissionDenied

            DriverWorkdayNetworkResult.Unavailable, is DriverWorkdayNetworkResult.Current ->
                DriverWorkdayCommandResult.Unavailable
        }

    private fun Authorization.toReadResult(): DriverWorkdayReadResult = when (this) {
        Authorization.PermissionDenied -> DriverWorkdayReadResult.PermissionDenied
        Authorization.ContextInvalidated -> DriverWorkdayReadResult.ContextInvalidated
        Authorization.SessionInvalidated -> DriverWorkdayReadResult.SessionInvalidated
        is Authorization.Current -> error("current authorization must be executed")
    }

    private fun Authorization.toCommandResult(): DriverWorkdayCommandResult = when (this) {
        Authorization.PermissionDenied -> DriverWorkdayCommandResult.PermissionDenied
        Authorization.ContextInvalidated -> DriverWorkdayCommandResult.ContextInvalidated
        Authorization.SessionInvalidated -> DriverWorkdayCommandResult.SessionInvalidated
        is Authorization.Current -> error("current authorization must be executed")
    }

    private fun VerifiedSession.matches(authority: DriverDeliveryAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object PermissionDenied : Authorization
        data object ContextInvalidated : Authorization
        data object SessionInvalidated : Authorization
    }

    private companion object {
        val DRIVER_READ_PERMISSIONS = setOf("dispatch.read", "logistics:read")
        val DRIVER_START_PERMISSIONS = setOf("dispatch.start_route")
    }
}

/** Durable encrypted intent metadata only. Location samples are deliberately never stored here. */
@Singleton
class OperationsDriverWorkdayCommandStore @Inject constructor(
    @ApplicationContext context: Context
) : DriverWorkdayCommandStore {
    private val local =
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverWorkdayCommand)
    private val mutex = Mutex()

    override suspend fun load(scope: DriverWorkdayCommandScope): WorkdayCommandIntentRead =
        mutex.withLock {
            when (val stored = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> WorkdayCommandIntentRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload =
                        stored.payload
                            ?: return@withLock WorkdayCommandIntentRead.Available(null)
                    val intent =
                        decode(payload)
                            ?: return@withLock WorkdayCommandIntentRead.Unavailable
                    if (intent.scope != scope) {
                        WorkdayCommandIntentRead.Unavailable
                    } else {
                        WorkdayCommandIntentRead.Available(intent)
                    }
                }
            }
        }

    override suspend fun save(intent: DriverWorkdayCommandIntent): Boolean = mutex.withLock {
        when (val stored = local.load(intent.scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> false

            is ScopedMetadataRead.Value -> {
                val encoded = encode(intent)
                when {
                    stored.payload == null -> local.save(intent.scope.toLocal(), encoded)
                    stored.payload == encoded -> true
                    else -> false
                }
            }
        }
    }

    override suspend fun clear(scope: DriverWorkdayCommandScope, idempotencyKey: String): Boolean =
        mutex.withLock {
            when (val stored = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> false

                is ScopedMetadataRead.Value -> {
                    val payload = stored.payload ?: return@withLock true
                    val intent = decode(payload) ?: return@withLock false
                    if (intent.scope != scope || intent.idempotencyKey != idempotencyKey) {
                        false
                    } else {
                        local.clear(scope.toLocal())
                    }
                }
            }
        }

    private fun DriverWorkdayCommandScope.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun encode(intent: DriverWorkdayCommandIntent): String = JsonObject(
        mapOf(
            "schema" to JsonPrimitive(1),
            "action" to JsonPrimitive(intent.action.name),
            "workdayId" to (intent.workdayId?.let { JsonPrimitive(it) } ?: JsonNull),
            "expectedVersion" to (intent.expectedVersion?.let { JsonPrimitive(it) } ?: JsonNull),
            "locationAvailable" to (
                intent.locationAvailable?.let {
                    JsonPrimitive(it)
                } ?: JsonNull
                ),
            "idempotencyKey" to JsonPrimitive(intent.idempotencyKey),
            "initiatedAt" to JsonPrimitive(intent.initiatedAt),
            "userId" to JsonPrimitive(intent.scope.userId),
            "tenantId" to JsonPrimitive(intent.scope.tenantId),
            "workspaceId" to JsonPrimitive(intent.scope.workspaceId),
            "membershipId" to JsonPrimitive(intent.scope.membershipId)
        )
    ).toString()

    private fun decode(payload: String): DriverWorkdayCommandIntent? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root["schema"]?.jsonPrimitive?.longOrNull == 1L)
        fun text(key: String): String =
            root[key]?.jsonPrimitive?.content?.also { require(it.isNotBlank()) }
                ?: error("missing $key")
        fun nullableText(key: String): String? = root[key]?.let {
            if (it ==
                JsonNull
            ) {
                null
            } else {
                it.jsonPrimitive.content
            }
        }
        fun nullableLong(key: String): Long? = root[key]?.let {
            if (it ==
                JsonNull
            ) {
                null
            } else {
                it.jsonPrimitive.longOrNull
            }
        }
        fun nullableBoolean(key: String): Boolean? = root[key]?.let {
            if (it ==
                JsonNull
            ) {
                null
            } else {
                it.jsonPrimitive.booleanOrNull
            }
        }
        DriverWorkdayCommandIntent(
            scope = DriverWorkdayCommandScope(
                text("userId"),
                text("tenantId"),
                text("workspaceId"),
                text("membershipId")
            ),
            action = DriverWorkdayCommandAction.valueOf(text("action")),
            workdayId = nullableText("workdayId"),
            expectedVersion = nullableLong("expectedVersion"),
            locationAvailable = nullableBoolean("locationAvailable"),
            idempotencyKey = text("idempotencyKey"),
            initiatedAt = text("initiatedAt")
        )
    } catch (_: Exception) {
        null
    }
}
