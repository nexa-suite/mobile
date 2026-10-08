package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeMetadataStore as PlanChangeMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeIntentStatus as PlanChangeIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeMetadataRead as PlanChangeMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeMetadataWrite as PlanChangeMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeScopeIdentity as PlanChangeScopeIdentity
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
class AppDispatchPlanChangeMetadataStore(private val local: ScopedMetadataStore) :
    PlanChangeMetadataStore {
    override suspend fun loadIntent(
        scope: PlanChangeScopeIdentity,
        fulfillmentId: String
    ): PlanChangeMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> PlanChangeMetadataRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        emptyList()
                    } else {
                        return@withLock PlanChangeMetadataRead.Unavailable
                    }
                val intent = intents.firstOrNull { it.fulfillmentId == fulfillmentId }
                    ?: return@withLock PlanChangeMetadataRead.Available(null)
                if (intent.scope != scope) {
                    return@withLock PlanChangeMetadataRead.Unavailable
                }
                if (intent.status == PlanChangeIntentStatus.Pending) {
                    val recovered = intent.copy(
                        status = PlanChangeIntentStatus.UnknownOutcome
                    )
                    val updated = intents.map {
                        if (it.fulfillmentId ==
                            fulfillmentId
                        ) {
                            recovered
                        } else {
                            it
                        }
                    }
                    if (local.save(scope.toLocal(), encode(updated))) {
                        PlanChangeMetadataRead.Available(recovered)
                    } else {
                        PlanChangeMetadataRead.Unavailable
                    }
                } else {
                    PlanChangeMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(intent: DispatchPlanChangeIntent): PlanChangeMetadataWrite =
        mutex(intent.scope).withLock {
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> PlanChangeMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val intents = stored.payload?.let(::decode)
                        ?: if (stored.payload == null) {
                            emptyList()
                        } else {
                            return@withLock PlanChangeMetadataWrite.Unavailable
                        }
                    val current = intents.firstOrNull { it.fulfillmentId == intent.fulfillmentId }
                    when {
                        current == null && intents.size >= MAX_PENDING_INTENTS ->
                            PlanChangeMetadataWrite.Unavailable

                        current == null -> write(intent.scope, intents + intent)

                        current.scope != intent.scope || !current.sameCommand(intent) ->
                            PlanChangeMetadataWrite.Conflict

                        current.status == PlanChangeIntentStatus.UnknownOutcome &&
                            intent.status == PlanChangeIntentStatus.Pending ->
                            PlanChangeMetadataWrite.Conflict

                        current == intent -> PlanChangeMetadataWrite.Saved

                        else -> write(
                            intent.scope,
                            intents.map {
                                if (it.fulfillmentId ==
                                    intent.fulfillmentId
                                ) {
                                    intent
                                } else {
                                    it
                                }
                            }
                        )
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: PlanChangeScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): PlanChangeMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> PlanChangeMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        emptyList()
                    } else {
                        return@withLock PlanChangeMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.fulfillmentId == fulfillmentId }
                    ?: return@withLock PlanChangeMetadataWrite.Saved
                if (current.scope != scope || current.idempotencyKey != idempotencyKey) {
                    PlanChangeMetadataWrite.Stale
                } else {
                    val remaining = intents.filterNot { it.fulfillmentId == fulfillmentId }
                    val cleared = if (remaining.isEmpty()) {
                        local.clear(scope.toLocal())
                    } else {
                        local.save(scope.toLocal(), encode(remaining))
                    }
                    if (cleared) {
                        PlanChangeMetadataWrite.Saved
                    } else {
                        PlanChangeMetadataWrite.Unavailable
                    }
                }
            }
        }
    }

    private suspend fun write(
        scope: PlanChangeScopeIdentity,
        intents: List<DispatchPlanChangeIntent>
    ): PlanChangeMetadataWrite = if (local.save(scope.toLocal(), encode(intents))) {
        PlanChangeMetadataWrite.Saved
    } else {
        PlanChangeMetadataWrite.Unavailable
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
        put(
            "requestedDispatchAt",
            intent.requestedDispatchAt?.toString()?.let(::JsonPrimitive) ?: JsonNull
        )
        put("resultResponsibleMembershipId", JsonPrimitive(intent.resultResponsibleMembershipId))
        put(
            "resultPlannedDispatchAt",
            intent.resultPlannedDispatchAt?.toString()?.let(::JsonPrimitive) ?: JsonNull
        )
        put("requestBody", JsonPrimitive(intent.requestBody))
        put("idempotencyKey", JsonPrimitive(intent.idempotencyKey))
        put("status", JsonPrimitive(intent.status.name))
    }

    private fun decode(payload: String): List<DispatchPlanChangeIntent>? = try {
        val envelope = Json.parseToJsonElement(payload).jsonObject
        if (envelope.requiredLong("schema") != SCHEMA_VERSION) {
            null
        } else {
            val intents = envelope["commands"]?.jsonArray?.map { element ->
                val value = element.jsonObject
                DispatchPlanChangeIntent(
                    scope = PlanChangeScopeIdentity(
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
                    resultResponsibleMembershipId = value.requiredString(
                        "resultResponsibleMembershipId"
                    ),
                    resultPlannedDispatchAt = value.optionalInstant("resultPlannedDispatchAt"),
                    requestBody = value.requiredString("requestBody"),
                    idempotencyKey = value.requiredString("idempotencyKey"),
                    status = PlanChangeIntentStatus.valueOf(value.requiredString("status"))
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
        runCatching { Instant.parse(it) }.getOrNull()
            ?: error("Dispatch plan-change instant is invalid")
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

    private fun PlanChangeScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: PlanChangeScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }

    private companion object {
        const val SCHEMA_VERSION = 1L
        const val MAX_PENDING_INTENTS = 32
        val locks = ConcurrentHashMap<PlanChangeScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppDispatchPlanChangeMetadataBindings {
    @Provides
    @Singleton
    fun dispatchPlanChangeMetadataStore(
        @ApplicationContext context: Context
    ): PlanChangeMetadataStore = AppDispatchPlanChangeMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DispatchPlanChange)
    )
}
