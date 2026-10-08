package com.nexa.mobile.operations.data

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.feature.dispatch.application.DispatchAssignmentMetadataStore as AssignmentMetadataStore
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAssignmentIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAssignmentIntentStatus as AssignmentIntentStatus
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAssignmentMetadataRead as AssignmentMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAssignmentMetadataWrite as AssignmentMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAssignmentScopeIdentity as AssignmentScopeIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Typed frozen-command adapter over encrypted storage bound to DispatchAssignment purpose. */
class AppDispatchAssignmentMetadataStore(private val local: ScopedMetadataStore) :
    AssignmentMetadataStore {
    override suspend fun loadIntent(
        scope: AssignmentScopeIdentity,
        fulfillmentId: String
    ): AssignmentMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> AssignmentMetadataRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        emptyList()
                    } else {
                        return@withLock AssignmentMetadataRead.Unavailable
                    }
                val intent = intents.firstOrNull { it.fulfillmentId == fulfillmentId }
                    ?: return@withLock AssignmentMetadataRead.Available(null)
                if (intent.scope != scope) {
                    return@withLock AssignmentMetadataRead.Unavailable
                }
                if (intent.status == AssignmentIntentStatus.Pending) {
                    val recovered = intent.copy(
                        status = AssignmentIntentStatus.UnknownOutcome
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
                        AssignmentMetadataRead.Available(recovered)
                    } else {
                        AssignmentMetadataRead.Unavailable
                    }
                } else {
                    AssignmentMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(intent: DispatchAssignmentIntent): AssignmentMetadataWrite =
        mutex(intent.scope).withLock {
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> AssignmentMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val intents = stored.payload?.let(::decode)
                        ?: if (stored.payload == null) {
                            emptyList()
                        } else {
                            return@withLock AssignmentMetadataWrite.Unavailable
                        }
                    val current = intents.firstOrNull { it.fulfillmentId == intent.fulfillmentId }
                    if (current == null) {
                        if (intents.size >= MAX_PENDING_INTENTS) {
                            return@withLock AssignmentMetadataWrite.Unavailable
                        }
                        write(intent.scope, intents + intent)
                    } else if (current.scope != intent.scope || !current.sameCommand(intent)) {
                        AssignmentMetadataWrite.Conflict
                    } else if (current.status == AssignmentIntentStatus.UnknownOutcome &&
                        intent.status == AssignmentIntentStatus.Pending
                    ) {
                        AssignmentMetadataWrite.Conflict
                    } else if (current == intent) {
                        AssignmentMetadataWrite.Saved
                    } else {
                        write(
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
        scope: AssignmentScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): AssignmentMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> AssignmentMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        emptyList()
                    } else {
                        return@withLock AssignmentMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.fulfillmentId == fulfillmentId }
                    ?: return@withLock AssignmentMetadataWrite.Saved
                if (current.scope != scope || current.idempotencyKey != idempotencyKey) {
                    AssignmentMetadataWrite.Stale
                } else {
                    val remaining = intents.filterNot { it.fulfillmentId == fulfillmentId }
                    val cleared = if (remaining.isEmpty()) {
                        local.clear(scope.toLocal())
                    } else {
                        local.save(scope.toLocal(), encode(remaining))
                    }
                    if (cleared) {
                        AssignmentMetadataWrite.Saved
                    } else {
                        AssignmentMetadataWrite.Unavailable
                    }
                }
            }
        }
    }

    private suspend fun write(
        scope: AssignmentScopeIdentity,
        intents: List<DispatchAssignmentIntent>
    ): AssignmentMetadataWrite = if (local.save(scope.toLocal(), encode(intents))) {
        AssignmentMetadataWrite.Saved
    } else {
        AssignmentMetadataWrite.Unavailable
    }

    private fun encode(intents: List<DispatchAssignmentIntent>): String = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA_VERSION))
        put("commands", JsonArray(intents.map(::encodeIntent)))
    }.toString()

    private fun encodeIntent(intent: DispatchAssignmentIntent): JsonObject = buildJsonObject {
        put("userId", JsonPrimitive(intent.scope.userId))
        put("tenantId", JsonPrimitive(intent.scope.tenantId))
        put("workspaceId", JsonPrimitive(intent.scope.workspaceId))
        put("membershipId", JsonPrimitive(intent.scope.membershipId))
        put("fulfillmentId", JsonPrimitive(intent.fulfillmentId))
        put("expectedFulfillmentVersion", JsonPrimitive(intent.expectedFulfillmentVersion))
        put("physicalAllocationId", JsonPrimitive(intent.physicalAllocationId))
        put("physicalAllocationVersion", JsonPrimitive(intent.physicalAllocationVersion))
        put("responsibleMembershipId", JsonPrimitive(intent.responsibleMembershipId))
        put("idempotencyKey", JsonPrimitive(intent.idempotencyKey))
        put("status", JsonPrimitive(intent.status.name))
    }

    private fun decode(payload: String): List<DispatchAssignmentIntent>? = try {
        val envelope = Json.parseToJsonElement(payload).jsonObject
        if (envelope.requiredLong("schema") != SCHEMA_VERSION) {
            null
        } else {
            val intents = envelope["commands"]?.jsonArray?.map { element ->
                val value = element.jsonObject
                DispatchAssignmentIntent(
                    scope = AssignmentScopeIdentity(
                        value.requiredString("userId"),
                        value.requiredString("tenantId"),
                        value.requiredString("workspaceId"),
                        value.requiredString("membershipId")
                    ),
                    fulfillmentId = value.requiredString("fulfillmentId"),
                    expectedFulfillmentVersion = value.requiredLong("expectedFulfillmentVersion"),
                    physicalAllocationId = value.requiredString("physicalAllocationId"),
                    physicalAllocationVersion = value.requiredLong("physicalAllocationVersion"),
                    responsibleMembershipId = value.requiredString("responsibleMembershipId"),
                    idempotencyKey = value.requiredString("idempotencyKey"),
                    status = AssignmentIntentStatus.valueOf(value.requiredString("status"))
                )
            } ?: error("Dispatch assignment metadata commands are missing")
            if (intents.map { it.fulfillmentId }.distinct().size != intents.size) null else intents
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank)
            ?: error("Dispatch assignment metadata field is invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Dispatch assignment metadata number is invalid")

    private fun DispatchAssignmentIntent.sameCommand(other: DispatchAssignmentIntent): Boolean =
        scope == other.scope && fulfillmentId == other.fulfillmentId &&
            expectedFulfillmentVersion == other.expectedFulfillmentVersion &&
            physicalAllocationId == other.physicalAllocationId &&
            physicalAllocationVersion == other.physicalAllocationVersion &&
            responsibleMembershipId == other.responsibleMembershipId &&
            idempotencyKey == other.idempotencyKey

    private fun AssignmentScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: AssignmentScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }

    private companion object {
        const val SCHEMA_VERSION = 1L
        const val MAX_PENDING_INTENTS = 32
        val locks = ConcurrentHashMap<AssignmentScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppDispatchAssignmentMetadataBindings {
    @Provides
    @Singleton
    fun dispatchAssignmentMetadataStore(
        @ApplicationContext context: Context
    ): AssignmentMetadataStore = AppDispatchAssignmentMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DispatchAssignment)
    )
}
