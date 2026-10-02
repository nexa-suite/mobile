package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.delivery.DriverArrivalCommand
import com.nexa.mobile.operations.feature.delivery.DriverArrivalIntentMetadata as ArrivalIntentMetadata
import com.nexa.mobile.operations.feature.delivery.DriverArrivalIntentStatus
import com.nexa.mobile.operations.feature.delivery.DriverArrivalMetadataRead
import com.nexa.mobile.operations.feature.delivery.DriverArrivalMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverArrivalMetadataWrite
import com.nexa.mobile.operations.feature.delivery.DriverAttemptScopeIdentity
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

/** Typed arrival intent storage; contains no access token, permissions, or server truth. */
internal class AppDriverArrivalMetadataStore(private val local: AndroidScopedMetadataStore) :
    DriverArrivalMetadataStore {
    override suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverArrivalMetadataRead =
        mutex(scope).withLock {
            when (val stored = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> DriverArrivalMetadataRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = stored.payload
                        ?: return@withLock DriverArrivalMetadataRead.Available(null)
                    val intent = decode(payload)
                        ?: return@withLock DriverArrivalMetadataRead.Unavailable
                    if (intent.scope != scope) return@withLock DriverArrivalMetadataRead.Unavailable
                    if (intent.status == DriverArrivalIntentStatus.Pending) {
                        val recovered = intent.copy(
                            status = DriverArrivalIntentStatus.UnknownOutcome
                        )
                        if (local.save(scope.toLocal(), encode(recovered))) {
                            DriverArrivalMetadataRead.Available(recovered)
                        } else {
                            DriverArrivalMetadataRead.Unavailable
                        }
                    } else {
                        DriverArrivalMetadataRead.Available(intent)
                    }
                }
            }
        }

    override suspend fun saveIntent(intent: ArrivalIntentMetadata): DriverArrivalMetadataWrite =
        mutex(intent.scope).withLock {
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> DriverArrivalMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = stored.payload?.let(::decode)
                    if (stored.payload != null && current == null) {
                        return@withLock DriverArrivalMetadataWrite.Unavailable
                    }
                    when {
                        current == null -> save(intent)

                        current.scope != intent.scope -> DriverArrivalMetadataWrite.Unavailable

                        current.command != intent.command -> DriverArrivalMetadataWrite.Conflict

                        current.status == DriverArrivalIntentStatus.UnknownOutcome &&
                            intent.status == DriverArrivalIntentStatus.Pending ->
                            DriverArrivalMetadataWrite.Conflict

                        current == intent -> DriverArrivalMetadataWrite.Saved

                        else -> save(intent)
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverArrivalMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverArrivalMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = stored.payload ?: return@withLock DriverArrivalMetadataWrite.Saved
                val current =
                    decode(payload) ?: return@withLock DriverArrivalMetadataWrite.Unavailable
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    DriverArrivalMetadataWrite.Stale
                } else if (local.clear(scope.toLocal())) {
                    DriverArrivalMetadataWrite.Saved
                } else {
                    DriverArrivalMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun save(intent: ArrivalIntentMetadata): DriverArrivalMetadataWrite =
        if (local.save(intent.scope.toLocal(), encode(intent))) {
            DriverArrivalMetadataWrite.Saved
        } else {
            DriverArrivalMetadataWrite.Unavailable
        }

    private fun encode(intent: ArrivalIntentMetadata): String {
        val command = intent.command
        return JsonObject(
            mapOf(
                "schema" to JsonPrimitive(1),
                "userId" to JsonPrimitive(intent.scope.userId),
                "tenantId" to JsonPrimitive(intent.scope.tenantId),
                "workspaceId" to JsonPrimitive(intent.scope.workspaceId),
                "membershipId" to JsonPrimitive(intent.scope.membershipId),
                "deliveryId" to JsonPrimitive(command.deliveryId),
                "attemptId" to JsonPrimitive(command.attemptId),
                "expectedVersion" to JsonPrimitive(command.expectedVersion),
                "idempotencyKey" to JsonPrimitive(command.idempotencyKey),
                "frozenBody" to JsonPrimitive(command.frozenBody),
                "status" to JsonPrimitive(intent.status.name)
            )
        ).toString()
    }

    private fun decode(payload: String): ArrivalIntentMetadata? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.requiredLong("schema") == 1L)
        val scope = DriverAttemptScopeIdentity(
            root.requiredString("userId"),
            root.requiredString("tenantId"),
            root.requiredString("workspaceId"),
            root.requiredString("membershipId")
        )
        ArrivalIntentMetadata(
            scope,
            DriverArrivalCommand(
                deliveryId = root.requiredString("deliveryId"),
                attemptId = root.requiredString("attemptId"),
                expectedVersion = root.requiredLong("expectedVersion"),
                idempotencyKey = root.requiredString("idempotencyKey"),
                frozenBody = root.requiredStringAllowEmpty("frozenBody")
            ),
            DriverArrivalIntentStatus.valueOf(root.requiredString("status"))
        )
    } catch (_: Exception) {
        null
    }

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex = locks.computeIfAbsent(scope) {
        Mutex()
    }

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Driver arrival metadata field is invalid")

    private fun JsonObject.requiredStringAllowEmpty(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?: error("Driver arrival metadata field is invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Driver arrival metadata field is invalid")

    private companion object {
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DriverArrivalMetadataModule {
    @Provides
    @Singleton
    fun provideDriverArrivalMetadataStore(
        @ApplicationContext context: Context
    ): DriverArrivalMetadataStore = AppDriverArrivalMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverDeliveryArrival)
    )
}
