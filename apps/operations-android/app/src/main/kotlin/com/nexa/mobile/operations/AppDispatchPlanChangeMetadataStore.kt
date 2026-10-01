package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeIntentStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeScopeIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Exact plan-change commands use an independent AES-GCM metadata purpose. */
internal class AppDispatchPlanChangeMetadataStore(
    private val local: ScopedMetadataStore
) : DispatchPlanChangeMetadataStore {
    override suspend fun loadIntent(
        scope: DispatchPlanChangeScopeIdentity,
        fulfillmentId: String
    ): DispatchPlanChangeMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchPlanChangeMetadataRead.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchPlanChangeMetadataRead.Unavailable
                    }
                val intent = intents.firstOrNull { it.fulfillmentId == fulfillmentId }
                    ?: return@withLock DispatchPlanChangeMetadataRead.Available(null)
                if (intent.scope != scope) {
                    return@withLock DispatchPlanChangeMetadataRead.Unavailable
                }
                if (intent.status == DispatchPlanChangeIntentStatus.Pending) {
                    val recovered = intent.copy(status = DispatchPlanChangeIntentStatus.UnknownOutcome)
                    val updated = intents.map { if (it.fulfillmentId == fulfillmentId) recovered else it }
                    if (local.save(scope.toLocal(), encode(updated))) {
                        DispatchPlanChangeMetadataRead.Available(recovered)
                    } else {
                        DispatchPlanChangeMetadataRead.Unavailable
                    }
                } else {
                    DispatchPlanChangeMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(
        intent: DispatchPlanChangeIntent
    ): DispatchPlanChangeMetadataWrite = mutex(intent.scope).withLock {
        when (val stored = local.load(intent.scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchPlanChangeMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchPlanChangeMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.fulfillmentId == intent.fulfillmentId }
                when {
                    current == null && intents.size >= MAX_PENDING_INTENTS ->
                        DispatchPlanChangeMetadataWrite.Unavailable

                    current == null -> write(intent.scope, intents + intent)
                    current.scope != intent.scope || !current.sameCommand(intent) ->
                        DispatchPlanChangeMetadataWrite.Conflict

                    current.status == DispatchPlanChangeIntentStatus.UnknownOutcome &&
                        intent.status == DispatchPlanChangeIntentStatus.Pending ->
                        DispatchPlanChangeMetadataWrite.Conflict

                    current == intent -> DispatchPlanChangeMetadataWrite.Saved
                    else -> write(
                        intent.scope,
                        intents.map { if (it.fulfillmentId == intent.fulfillmentId) intent else it }
                    )
                }
            }
        }
    }

    override suspend fun clearIntent(
        scope: DispatchPlanChangeScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchPlanChangeMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchPlanChangeMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchPlanChangeMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.fulfillmentId == fulfillmentId }
                    ?: return@withLock DispatchPlanChangeMetadataWrite.Saved
                if (current.scope != scope || current.idempotencyKey != idempotencyKey) {
                    DispatchPlanChangeMetadataWrite.Stale
                } else {
                    val remaining = intents.filterNot { it.fulfillmentId == fulfillmentId }
                    val cleared = if (remaining.isEmpty()) local.clear(scope.toLocal()) else
                        local.save(scope.toLocal(), encode(remaining))
                    if (cleared) DispatchPlanChangeMetadataWrite.Saved else
                        DispatchPlanChangeMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun write(
        scope: DispatchPlanChangeScopeIdentity,
        intents: List<DispatchPlanChangeIntent>
    ): DispatchPlanChangeMetadataWrite = if (local.save(scope.toLocal(), encode(intents))) {
        DispatchPlanChangeMetadataWrite.Saved
    } else {
        DispatchPlanChangeMetadataWrite.Unavailable
    }

    private fun encode(intents: List<DispatchPlanChangeIntent>): String = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA_VERSION))
        put("commands", JsonArray(intents.map(::encodeIntent)))
    }.toString()

    private fun encodeIntent(intent: DispatchPlanChangeIntent): JsonObject = buildJsonObject {
        put("userId", JsonPrimitive(intent.scope.userId))
        put("tenantId", JsonPrimitive(intent.scope.tenantId))
        put("workspaceId", JsonPrimitive(intent.scope.workspaceId))
        put("membershipId", JsonPrimitive(intent.scope.membershipId))
        put("fulfillmentId", JsonPrimitive(intent.fulfillmentId))
        put("expectedFulfillmentVersion", JsonPrimitive(intent.expectedFulfillmentVersion))
        put("expectedAssignmentId", JsonPrimitive(intent.expectedAssignmentId))
        put("expectedAssignmentVersion", JsonPrimitive(intent.expectedAssignmentVersion))
        put("physicalAllocationId", JsonPrimitive(intent.physicalAllocationId))
        put("physicalAllocationVersion", JsonPrimitive(intent.physicalAllocationVersion))
        put("requestedMembershipId", intent.requestedMembershipId?.let(::JsonPrimitive) ?: JsonNull)
        put("requestedDispatchAt", intent.requestedDispatchAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("resultResponsibleMembershipId", JsonPrimitive(intent.resultResponsibleMembershipId))
        put("resultPlannedDispatchAt", intent.resultPlannedDispatchAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("requestBody", JsonPrimitive(intent.requestBody))
        put("idempotencyKey", JsonPrimitive(intent.idempotencyKey))
        put("status", JsonPrimitive(intent.status.name))
    }

    private fun decode(payload: String): List<DispatchPlanChangeIntent>? = try {
        val envelope = Json.parseToJsonElement(payload).jsonObject
        if (envelope.requiredLong("schema") != SCHEMA_VERSION) null else {
            val intents = envelope["commands"]?.jsonArray?.map { element ->
                val value = element.jsonObject
                DispatchPlanChangeIntent(
                    scope = DispatchPlanChangeScopeIdentity(
                        value.requiredString("userId"),
                        value.requiredString("tenantId"),
                        value.requiredString("workspaceId"),
                        value.requiredString("membershipId")
                    ),
                    fulfillmentId = value.requiredString("fulfillmentId"),
                    expectedFulfillmentVersion = value.requiredLong("expectedFulfillmentVersion"),
                    expectedAssignmentId = value.requiredString("expectedAssignmentId"),
                    expectedAssignmentVersion = value.requiredLong("expectedAssignmentVersion"),
                    physicalAllocationId = value.requiredString("physicalAllocationId"),
                    physicalAllocationVersion = value.requiredLong("physicalAllocationVersion"),
                    requestedMembershipId = value.optionalString("requestedMembershipId"),
                    requestedDispatchAt = value.optionalInstant("requestedDispatchAt"),
                    resultResponsibleMembershipId = value.requiredString("resultResponsibleMembershipId"),
                    resultPlannedDispatchAt = value.optionalInstant("resultPlannedDispatchAt"),
                    requestBody = value.requiredString("requestBody"),
                    idempotencyKey = value.requiredString("idempotencyKey"),
                    status = DispatchPlanChangeIntentStatus.valueOf(value.requiredString("status"))
                )
            } ?: error("Dispatch plan-change commands are missing")
            if (intents.map { it.fulfillmentId }.distinct().size != intents.size) null else intents
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Dispatch plan-change field is invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Dispatch plan-change number is invalid")

    private fun JsonObject.optionalString(key: String): String? = when (val field = this[key]) {
        null, JsonNull -> null
        else -> field.jsonPrimitive.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Dispatch plan-change field is invalid")
    }

    private fun JsonObject.optionalInstant(key: String): Instant? = optionalString(key)?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: error("Dispatch plan-change instant is invalid")
    }

    private fun DispatchPlanChangeIntent.sameCommand(other: DispatchPlanChangeIntent): Boolean =
        scope == other.scope && fulfillmentId == other.fulfillmentId &&
            expectedFulfillmentVersion == other.expectedFulfillmentVersion &&
            expectedAssignmentId == other.expectedAssignmentId &&
            expectedAssignmentVersion == other.expectedAssignmentVersion &&
            physicalAllocationId == other.physicalAllocationId &&
            physicalAllocationVersion == other.physicalAllocationVersion &&
            requestedMembershipId == other.requestedMembershipId &&
            requestedDispatchAt == other.requestedDispatchAt &&
            resultResponsibleMembershipId == other.resultResponsibleMembershipId &&
            resultPlannedDispatchAt == other.resultPlannedDispatchAt &&
            requestBody == other.requestBody && idempotencyKey == other.idempotencyKey

    private fun DispatchPlanChangeScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: DispatchPlanChangeScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }

    private companion object {
        const val SCHEMA_VERSION = 1L
        const val MAX_PENDING_INTENTS = 32
        val locks = ConcurrentHashMap<DispatchPlanChangeScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AppDispatchPlanChangeMetadataBindings {
    @Provides
    @Singleton
    fun dispatchPlanChangeMetadataStore(
        @ApplicationContext context: Context
    ): DispatchPlanChangeMetadataStore = AppDispatchPlanChangeMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DispatchPlanChange)
    )
}
