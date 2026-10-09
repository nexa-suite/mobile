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
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureCommand as TemperatureCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureGateway as TemperatureGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureIntent as TemperatureIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureIntentStatus as TemperatureIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureLoadResult as TemperatureLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMetadataRead as TemperatureMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMetadataStore as TemperatureMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMetadataWrite as TemperatureMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMutationResult as TemperatureMutationResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureDisposition as TemperatureDisposition
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureHold as TemperatureHold
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureLine as TemperatureLine
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureMode as TemperatureMode
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureReading as TemperatureReading
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureSnapshot as TemperatureSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.ExecutionTemperatureHoldTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.ExecutionTemperatureNetworkOutcome as ExecutionTemperatureOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.ExecutionTemperatureReadingTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.ExecutionTemperatureReadingTransportCommand
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.ExecutionTemperatureSnapshotTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaExecutionTemperatureGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Singleton
class OperationsDriverExecutionTemperatureGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val api: NexaExecutionTemperatureGateway,
    private val requestBodyCodec: JsonDeliveryRequestBodyCodec
) : TemperatureGateway {
    override suspend fun current(
        deliveryId: String,
        mode: TemperatureMode,
        authority: DriverDeliveryAuthority
    ): TemperatureLoadResult {
        val auth = authorize(
            authority,
            if (mode ==
                TemperatureMode.DRIVER
            ) {
                DRIVER_READ
            } else {
                HOLD_DISPOSE
            }
        )
        if (auth !is Authorization.Current) return auth.toLoad()
        val outcome = try {
            api.current(deliveryId, mode == TemperatureMode.DRIVER)
        } catch (
            e: CancellationException
        ) {
            throw e
        } catch (
            _: Exception
        ) {
            return TemperatureLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, auth.lease)) return driftLoad()
        return when (outcome) {
            is ExecutionTemperatureOutcome.Loaded -> try {
                if (outcome.snapshot.deliveryId !=
                    deliveryId
                ) {
                    TemperatureLoadResult.ServiceUnavailable
                } else {
                    TemperatureLoadResult.Loaded(outcome.snapshot.toFeature())
                }
            } catch (_: Exception) {
                TemperatureLoadResult.ServiceUnavailable
            }

            else -> outcome.toLoad()
        }
    }

    override suspend fun record(
        command: TemperatureCommand.Reading,
        authority: DriverDeliveryAuthority
    ): TemperatureMutationResult {
        val auth = authorize(authority, DRIVER_WRITE)
        if (auth !is Authorization.Current) return auth.toMutation()
        val wire =
            ExecutionTemperatureReadingTransportCommand(
                command.fulfillmentLineId,
                command.skuId,
                command.affectedQuantity,
                command.valueCelsius,
                command.occurredAt,
                command.sourceIncidentId,
                command.evidenceObjectId
            )
        val outcome = try {
            api.record(
                command.deliveryId,
                command.expectedDeliveryVersion,
                command.idempotencyKey,
                command.frozenBody,
                wire
            )
        } catch (
            e: CancellationException
        ) {
            throw e
        } catch (
            _: Exception
        ) {
            return TemperatureMutationResult.UnknownOutcome
        }
        if (!currentAfter(authority, auth.lease)) return driftMutation()
        return when (outcome) {
            is ExecutionTemperatureOutcome.ReadingRecorded -> {
                val r = outcome.reading
                if (r.actorMembershipId != authority.membershipId ||
                    r.deliveryId != command.deliveryId
                ) {
                    TemperatureMutationResult.UnknownOutcome
                } else {
                    TemperatureMutationResult.ReadingRecorded(r.toFeature())
                }
            }

            else -> outcome.toMutation()
        }
    }

    override suspend fun dispose(
        command: TemperatureCommand.Disposition,
        authority: DriverDeliveryAuthority
    ): TemperatureMutationResult {
        val auth = authorize(authority, HOLD_DISPOSE)
        if (auth !is Authorization.Current) return auth.toMutation()
        val outcome = try {
            api.dispose(
                command.deliveryId,
                command.holdId,
                command.expectedDeliveryVersion,
                command.idempotencyKey,
                command.frozenBody,
                command.disposition.name
            )
        } catch (
            e: CancellationException
        ) {
            throw e
        } catch (
            _: Exception
        ) {
            return TemperatureMutationResult.UnknownOutcome
        }
        if (!currentAfter(authority, auth.lease)) return driftMutation()
        return when (outcome) {
            is ExecutionTemperatureOutcome.Disposed -> {
                val fact = outcome.result
                if (fact.hold.authorizedByMembershipId != authority.membershipId) {
                    TemperatureMutationResult.UnknownOutcome
                } else {
                    TemperatureMutationResult.DispositionRecorded(
                        fact.hold.toFeature(),
                        fact.deliveryVersion,
                        fact.replayed
                    )
                }
            }

            else -> outcome.toMutation()
        }
    }

    private suspend fun authorize(
        a: DriverDeliveryAuthority,
        required: Set<String>
    ): Authorization {
        if (sessions.sessionState.value != SessionState.Active) return Authorization.Session
        val lease = sessions.currentAccess() ?: return Authorization.Session
        val verified = sessions.verifiedSession.value ?: return Authorization.Context
        if (!verified.matches(a)) return Authorization.Context
        if (a.permissions.none { it in required }) return Authorization.Permission
        return Authorization.Current(lease)
    }
    private suspend fun currentAfter(a: DriverDeliveryAuthority, lease: AccessTokenLease) =
        sessions.sessionState.value == SessionState.Active && sessions.isEpochCurrent(
            lease.epoch
        ) &&
            sessions.verifiedSession.value?.matches(a) == true
    private suspend fun driftLoad() = if (sessions.sessionState.value == SessionState.Active) {
        TemperatureLoadResult.ContextInvalidated
    } else {
        TemperatureLoadResult.SessionInvalidated
    }
    private suspend fun driftMutation() = if (sessions.sessionState.value == SessionState.Active) {
        TemperatureMutationResult.ContextInvalidated
    } else {
        TemperatureMutationResult.SessionInvalidated
    }
    private fun VerifiedSession.matches(a: DriverDeliveryAuthority) =
        hasAuthorizedContext && userId == a.userId &&
            tenantId == a.tenantId && workspaceId == a.workspaceId &&
            membershipId == a.membershipId &&
            permissions == a.permissions
    private fun Authorization.toLoad() = when (this) {
        Authorization.Session -> TemperatureLoadResult.SessionInvalidated
        Authorization.Context -> TemperatureLoadResult.ContextInvalidated
        Authorization.Permission -> TemperatureLoadResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }
    private fun Authorization.toMutation() = when (this) {
        Authorization.Session -> TemperatureMutationResult.SessionInvalidated
        Authorization.Context -> TemperatureMutationResult.ContextInvalidated
        Authorization.Permission -> TemperatureMutationResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }
    private fun ExecutionTemperatureOutcome.toLoad() = when (this) {
        ExecutionTemperatureOutcome.NotFound -> TemperatureLoadResult.NotFound

        ExecutionTemperatureOutcome.NetworkUnavailable ->
            TemperatureLoadResult.NetworkUnavailable

        ExecutionTemperatureOutcome.PermissionDenied ->
            TemperatureLoadResult.PermissionDenied

        ExecutionTemperatureOutcome.ContextInvalidated ->
            TemperatureLoadResult.ContextInvalidated

        ExecutionTemperatureOutcome.SessionInvalidated ->
            TemperatureLoadResult.SessionInvalidated

        else -> TemperatureLoadResult.ServiceUnavailable
    }
    private fun ExecutionTemperatureOutcome.toMutation() = when (this) {
        is ExecutionTemperatureOutcome.Rejected ->
            TemperatureMutationResult.Rejected(
                code
            )

        ExecutionTemperatureOutcome.NotFound ->
            TemperatureMutationResult.NotFound

        ExecutionTemperatureOutcome.StaleVersion ->
            TemperatureMutationResult.StaleVersion

        ExecutionTemperatureOutcome.Conflict ->
            TemperatureMutationResult.Conflict

        ExecutionTemperatureOutcome.UnknownOutcome ->
            TemperatureMutationResult.UnknownOutcome

        ExecutionTemperatureOutcome.NetworkUnavailable ->
            TemperatureMutationResult.NetworkUnavailable

        ExecutionTemperatureOutcome.ServiceUnavailable ->
            TemperatureMutationResult.ServiceUnavailable

        ExecutionTemperatureOutcome.PermissionDenied ->
            TemperatureMutationResult.PermissionDenied

        ExecutionTemperatureOutcome.ContextInvalidated ->
            TemperatureMutationResult.ContextInvalidated

        ExecutionTemperatureOutcome.SessionInvalidated ->
            TemperatureMutationResult.SessionInvalidated

        is ExecutionTemperatureOutcome.Loaded,
        is ExecutionTemperatureOutcome.ReadingRecorded,
        is ExecutionTemperatureOutcome.Disposed ->
            TemperatureMutationResult.ServiceUnavailable
    }
    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object Session : Authorization
        data object Context : Authorization
        data object Permission : Authorization
    }
    private companion object {
        val DRIVER_READ = setOf("dispatch.read", "logistics:read")
        val DRIVER_WRITE = setOf("dispatch.start_route", "logistics:write")
        val HOLD_DISPOSE = setOf("delivery.execution_hold.dispose")
    }
}

private fun ExecutionTemperatureSnapshotTransport.toFeature() = TemperatureSnapshot(
    deliveryId,
    deliveryVersion,
    deliveryStatus,
    attemptId,
    originWarehouseId,
    lines.map {
        TemperatureLine(
            it.fulfillmentLineId,
            it.skuId,
            it.unit,
            it.remainingQuantity,
            it.coldChainRequired,
            it.minimumCelsius,
            it.maximumCelsius
        )
    },
    holds.map(ExecutionTemperatureHoldTransport::toFeature)
)
private fun ExecutionTemperatureReadingTransport.toFeature() = TemperatureReading(
    id, deliveryId, attemptId, fulfillmentLineId, skuId, affectedQuantity,
    quantityUnit, valueCelsius,
    temperatureUnit, minimumCelsius, maximumCelsius, status, actorMembershipId,
    occurredAt, recordedAt,
    evidenceObjectId, sourceIncidentId, hold?.toFeature(), deliveryVersion, replayed
)
private fun ExecutionTemperatureHoldTransport.toFeature() = TemperatureHold(
    id, readingId, exceptionId, fulfillmentLineId, skuId, affectedQuantity, quantityUnit, status,
    reportedByMembershipId, reportedAt, disposition, authorizedByMembershipId, disposedAt, reason
)

@Singleton
class AppDriverExecutionTemperatureMetadataStore(
    private val local: ScopedMetadataStore,
    private val requestBodyCodec: JsonDeliveryRequestBodyCodec
) : TemperatureMetadataStore {
    override suspend fun loadIntent(scope: DriverAttemptScopeIdentity): TemperatureMetadataRead =
        mutex(scope).withLock {
            when (val record = local.load(scope.local())) {
                ScopedMetadataRead.Unavailable -> TemperatureMetadataRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload =
                        record.payload
                            ?: return@withLock TemperatureMetadataRead.Available(
                                null
                            )
                    val intent =
                        decode(payload)
                            ?: return@withLock TemperatureMetadataRead.Unavailable
                    if (intent.scope != scope || !requestBodyCodec.isValid(intent.command)) {
                        return@withLock TemperatureMetadataRead.Unavailable
                    }
                    if (intent.status == TemperatureIntentStatus.Pending) {
                        val unknown = intent.copy(
                            status = TemperatureIntentStatus.UnknownOutcome
                        )
                        if (local.save(scope.local(), encode(unknown))) {
                            TemperatureMetadataRead.Available(unknown)
                        } else {
                            TemperatureMetadataRead.Unavailable
                        }
                    } else {
                        TemperatureMetadataRead.Available(intent)
                    }
                }
            }
        }

    override suspend fun saveIntent(intent: TemperatureIntent): TemperatureMetadataWrite =
        mutex(intent.scope).withLock {
            if (!requestBodyCodec.isValid(intent.command)) {
                return@withLock TemperatureMetadataWrite.Conflict
            }
            when (val record = local.load(intent.scope.local())) {
                ScopedMetadataRead.Unavailable -> TemperatureMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val old = record.payload?.let(::decode)
                    if (record.payload != null && (old == null || old.scope != intent.scope)) {
                        return@withLock TemperatureMetadataWrite.Unavailable
                    }
                    when {
                        old == null -> write(intent)

                        old.command != intent.command || old.initiatedAt != intent.initiatedAt ->
                            TemperatureMetadataWrite.Conflict

                        old.status == TemperatureIntentStatus.UnknownOutcome &&
                            intent.status == TemperatureIntentStatus.Pending -> write(
                            intent
                        )

                        old == intent -> TemperatureMetadataWrite.Saved

                        else -> write(intent)
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): TemperatureMetadataWrite = mutex(scope).withLock {
        when (val record = local.load(scope.local())) {
            ScopedMetadataRead.Unavailable -> TemperatureMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload =
                    record.payload ?: return@withLock TemperatureMetadataWrite.Saved
                val intent =
                    decode(payload)
                        ?: return@withLock TemperatureMetadataWrite.Unavailable
                if (intent.scope != scope || intent.command.idempotencyKey != idempotencyKey) {
                    TemperatureMetadataWrite.Stale
                } else if (local.clear(scope.local())) {
                    TemperatureMetadataWrite.Saved
                } else {
                    TemperatureMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun write(intent: TemperatureIntent) =
        if (local.save(intent.scope.local(), encode(intent))) {
            TemperatureMetadataWrite.Saved
        } else {
            TemperatureMetadataWrite.Unavailable
        }
    private fun encode(intent: TemperatureIntent): String {
        val c = intent.command
        val fields = mutableMapOf<String, JsonElement>(
            "schema" to JsonPrimitive(1), "userId" to JsonPrimitive(intent.scope.userId),
            "tenantId" to JsonPrimitive(intent.scope.tenantId),
            "workspaceId" to JsonPrimitive(intent.scope.workspaceId),
            "membershipId" to JsonPrimitive(intent.scope.membershipId),
            "deliveryId" to JsonPrimitive(c.deliveryId),
            "expectedDeliveryVersion" to JsonPrimitive(c.expectedDeliveryVersion),
            "idempotencyKey" to JsonPrimitive(c.idempotencyKey),
            "frozenBody" to JsonPrimitive(c.frozenBody),
            "initiatedAt" to JsonPrimitive(intent.initiatedAt.toString()),
            "status" to JsonPrimitive(intent.status.name)
        )
        when (c) {
            is TemperatureCommand.Reading -> {
                fields["kind"] = JsonPrimitive("READING")
                fields["fulfillmentLineId"] = JsonPrimitive(c.fulfillmentLineId)
                fields["skuId"] = JsonPrimitive(c.skuId)
                fields["affectedQuantity"] = JsonPrimitive(c.affectedQuantity.toPlainString())
                fields["valueCelsius"] = JsonPrimitive(c.valueCelsius.toPlainString())
                fields["occurredAt"] = JsonPrimitive(c.occurredAt.toString())
                fields["sourceIncidentId"] = c.sourceIncidentId?.let(::JsonPrimitive) ?: JsonNull
                fields["evidenceObjectId"] = c.evidenceObjectId?.let(::JsonPrimitive) ?: JsonNull
            }

            is TemperatureCommand.Disposition -> {
                fields["kind"] = JsonPrimitive("DISPOSITION")
                fields["holdId"] =
                    JsonPrimitive(c.holdId)
                fields["disposition"] = JsonPrimitive(c.disposition.name)
                fields["reason"] =
                    JsonPrimitive(c.reason)
            }
        }
        return JsonObject(fields).toString()
    }
    private fun decode(raw: String): TemperatureIntent? = try {
        val json = Json.parseToJsonElement(raw).jsonObject
        require(json.number("schema") == 1L)
        val scope = DriverAttemptScopeIdentity(
            json.text("userId"),
            json.text("tenantId"),
            json.text("workspaceId"),
            json.text("membershipId")
        )
        val delivery = json.text("deliveryId")
        val version = json.number("expectedDeliveryVersion")
        val key = json.text("idempotencyKey")
        val body = json.textAllowEmpty("frozenBody")
        val command = when (json.text("kind")) {
            "READING" -> TemperatureCommand.Reading(
                delivery, version, json.text("fulfillmentLineId"), json.text("skuId"),
                BigDecimal(json.text("affectedQuantity")), BigDecimal(json.text("valueCelsius")),
                Instant.parse(json.text("occurredAt")), json.optionalText("sourceIncidentId"),
                json.optionalText("evidenceObjectId"), key, body
            )

            "DISPOSITION" -> TemperatureCommand.Disposition(
                delivery,
                json.text("holdId"),
                version,
                TemperatureDisposition.valueOf(json.text("disposition")),
                json.textAllowEmpty("reason"),
                key,
                body
            )

            else -> return null
        }
        TemperatureIntent(
            scope,
            command,
            Instant.parse(json.text("initiatedAt")),
            TemperatureIntentStatus.valueOf(json.text("status"))
        )
    } catch (_: Exception) {
        null
    }
    private fun JsonObject.text(name: String) =
        this[name]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Missing field")
    private fun JsonObject.textAllowEmpty(name: String) =
        this[name]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?: error("Missing field")
    private fun JsonObject.optionalText(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content.takeIf(String::isNotBlank)
        else -> error("Invalid field")
    }
    private fun JsonObject.number(name: String) =
        this[name]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Missing number")
    private fun DriverAttemptScopeIdentity.local() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }
    private companion object {
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DriverExecutionTemperatureModule {
    @Provides @Singleton
    fun api(calls: ProtectedCallExecutor) = NexaExecutionTemperatureGateway(calls)

    @Provides @Singleton
    fun gateway(
        sessions: SessionCoordinator,
        api: NexaExecutionTemperatureGateway,
        requestBodyCodec: JsonDeliveryRequestBodyCodec
    ): TemperatureGateway = OperationsDriverExecutionTemperatureGateway(
        sessions,
        api,
        requestBodyCodec
    )

    @Provides @Singleton
    fun metadata(
        @ApplicationContext context: Context,
        requestBodyCodec: JsonDeliveryRequestBodyCodec
    ): TemperatureMetadataStore = AppDriverExecutionTemperatureMetadataStore(
        AndroidScopedMetadataStore(
            context,
            ScopedMetadataPurpose.DriverExecutionTemperatureCommand
        ),
        requestBodyCodec
    )
}
