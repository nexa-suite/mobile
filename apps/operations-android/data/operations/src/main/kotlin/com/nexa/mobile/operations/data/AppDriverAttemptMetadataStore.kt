package com.nexa.mobile.operations.data

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.delivery.application.DriverAttemptMetadataStore
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptIntentMetadata as AttemptIntentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptMetadataStatus as AttemptMetadataStatus
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Typed driver-start intent adapter over purpose and scope bound encrypted local storage. */
class AppDriverAttemptMetadataStore(private val local: AndroidScopedMetadataStore) :
    DriverAttemptMetadataStore {
    override suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverAttemptMetadataRead =
        mutex(scope).withLock {
            when (val stored = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> DriverAttemptMetadataRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload =
                        stored.payload ?: return@withLock DriverAttemptMetadataRead.Available(null)
                    val intent =
                        decode(payload) ?: return@withLock DriverAttemptMetadataRead.Unavailable
                    if (intent.scope != scope) return@withLock DriverAttemptMetadataRead.Unavailable
                    if (intent.status == AttemptMetadataStatus.Pending) {
                        val recovered = intent.copy(
                            status = AttemptMetadataStatus.UnknownOutcome
                        )
                        if (!local.save(scope.toLocal(), encode(recovered))) {
                            DriverAttemptMetadataRead.Unavailable
                        } else {
                            DriverAttemptMetadataRead.Available(recovered)
                        }
                    } else {
                        DriverAttemptMetadataRead.Available(intent)
                    }
                }
            }
        }

    override suspend fun saveIntent(intent: AttemptIntentMetadata): DriverAttemptMetadataWrite =
        mutex(intent.scope).withLock {
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> DriverAttemptMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = stored.payload?.let(::decode)
                    if (stored.payload != null && current == null) {
                        return@withLock DriverAttemptMetadataWrite.Unavailable
                    }
                    if (current == null) {
                        if (local.save(intent.scope.toLocal(), encode(intent))) {
                            DriverAttemptMetadataWrite.Saved
                        } else {
                            DriverAttemptMetadataWrite.Unavailable
                        }
                    } else if (current.scope != intent.scope) {
                        DriverAttemptMetadataWrite.Unavailable
                    } else if (current.idempotencyKey != intent.idempotencyKey ||
                        current.deliveryId != intent.deliveryId ||
                        current.expectedVersion != intent.expectedVersion
                    ) {
                        DriverAttemptMetadataWrite.Conflict
                    } else if (current == intent) {
                        DriverAttemptMetadataWrite.Saved
                    } else if (current.status == AttemptMetadataStatus.UnknownOutcome &&
                        intent.status == AttemptMetadataStatus.Pending
                    ) {
                        DriverAttemptMetadataWrite.Conflict
                    } else if (local.save(intent.scope.toLocal(), encode(intent))) {
                        DriverAttemptMetadataWrite.Saved
                    } else {
                        DriverAttemptMetadataWrite.Unavailable
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverAttemptMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverAttemptMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = stored.payload ?: return@withLock DriverAttemptMetadataWrite.Saved
                val current =
                    decode(payload) ?: return@withLock DriverAttemptMetadataWrite.Unavailable
                if (current.scope != scope || current.idempotencyKey != idempotencyKey) {
                    DriverAttemptMetadataWrite.Stale
                } else if (local.clear(scope.toLocal())) {
                    DriverAttemptMetadataWrite.Saved
                } else {
                    DriverAttemptMetadataWrite.Unavailable
                }
            }
        }
    }

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }

    private fun encode(intent: AttemptIntentMetadata): String = JsonObject(
        mapOf(
            "schema" to JsonPrimitive(1),
            "userId" to JsonPrimitive(intent.scope.userId),
            "tenantId" to JsonPrimitive(intent.scope.tenantId),
            "workspaceId" to JsonPrimitive(intent.scope.workspaceId),
            "membershipId" to JsonPrimitive(intent.scope.membershipId),
            "idempotencyKey" to JsonPrimitive(intent.idempotencyKey),
            "deliveryId" to JsonPrimitive(intent.deliveryId),
            "expectedVersion" to JsonPrimitive(intent.expectedVersion),
            "status" to JsonPrimitive(intent.status.name)
        )
    ).toString()

    private fun decode(payload: String): AttemptIntentMetadata? = try {
        val value = Json.parseToJsonElement(payload).jsonObject
        if (value.requiredLong("schema") != 1L) {
            null
        } else {
            AttemptIntentMetadata(
                scope = DriverAttemptScopeIdentity(
                    value.requiredString("userId"),
                    value.requiredString("tenantId"),
                    value.requiredString("workspaceId"),
                    value.requiredString("membershipId")
                ),
                idempotencyKey = value.requiredString("idempotencyKey"),
                deliveryId = value.requiredString("deliveryId"),
                expectedVersion = value.requiredLong("expectedVersion"),
                status = AttemptMetadataStatus.valueOf(value.requiredString("status"))
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank)
            ?: error("Driver attempt metadata field is invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Driver attempt metadata number is invalid")

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private companion object {
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppDriverAttemptMetadataBindings {
    @Provides
    @Singleton
    fun driverAttemptMetadataStore(
        @ApplicationContext context: Context
    ): DriverAttemptMetadataStore = AppDriverAttemptMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverAttemptStart)
    )
}
