package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListCacheItem
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListCacheProjection
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCache
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCacheRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCacheWrite
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkPage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encrypted, scope-bound cache of summary fields returned by the Picking work-list read. */
class AppPickingWorkListCache(private val local: ScopedMetadataStore) : PickingWorkListCache {
    override suspend fun load(authority: PickingAuthority, page: Int): PickingWorkListCacheRead =
        withScopeLock(authority.scope.toLocal()) {
            when (val stored = safeLoad(authority.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> PickingWorkListCacheRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = stored.payload
                    if (payload == null) {
                        return@withScopeLock PickingWorkListCacheRead.Available(null)
                    }
                    val cache = payload.decodeCache()
                    if (cache == null || cache.denial != null || !cache.matches(authority)) {
                        return@withScopeLock PickingWorkListCacheRead.Unavailable
                    }
                    PickingWorkListCacheRead.Available(cache.pages.singleOrNull { it.page == page })
                }
            }
        }

    override suspend fun saveServerConfirmedPage(
        authority: PickingAuthority,
        sessionEpoch: Long,
        page: PickingWorkPage
    ): PickingWorkListCacheWrite = withScopeLock(authority.scope.toLocal()) {
        if (sessionEpoch <= 0 || !page.isValid()) {
            return@withScopeLock PickingWorkListCacheWrite.Unavailable
        }
        val localScope = authority.scope.toLocal()
        val existing = when (val read = safeLoad(localScope)) {
            ScopedMetadataRead.Unavailable ->
                return@withScopeLock PickingWorkListCacheWrite.Unavailable

            is ScopedMetadataRead.Value -> read.payload?.decodeCache()
                ?: if (read.payload == null) {
                    null
                } else {
                    return@withScopeLock PickingWorkListCacheWrite.Unavailable
                }
        }
        val projection = page.toCacheProjection()
        val snapshot = existing?.takeUnless { it.denial != null || !it.matches(authority) }
        val snapshotAsOf = snapshot?.asOf
        val pages = when {
            snapshot == null || snapshotAsOf == null || projection.asOf.isAfter(snapshotAsOf) ->
                listOf(projection)

            projection.asOf.isBefore(snapshotAsOf) ->
                return@withScopeLock PickingWorkListCacheWrite.Superseded

            else -> {
                val existingPage = snapshot.pages.singleOrNull { it.page == projection.page }
                if (existingPage != null && existingPage != projection) {
                    return@withScopeLock PickingWorkListCacheWrite.Superseded
                }
                snapshot.pages + listOfNotNull(projection.takeUnless { it == existingPage })
            }
        }.sortedBy(PickingWorkListCacheProjection::page).takeLast(MAX_CACHED_PAGES)
        val next = StoredPickingWorkListCache(
            authorityEpoch = authority.authorityEpoch,
            permissionsFingerprint = authority.permissionsFingerprint(),
            pages = pages.map { it.toStored() }
        )
        val encoded = try {
            pickingWorkCacheJson.encodeToString(next)
        } catch (_: SerializationException) {
            return@withScopeLock PickingWorkListCacheWrite.Unavailable
        }
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_CACHE_BYTES) {
            return@withScopeLock PickingWorkListCacheWrite.Unavailable
        }
        if (safeSave(localScope, encoded)) {
            PickingWorkListCacheWrite.Saved
        } else {
            PickingWorkListCacheWrite.Unavailable
        }
    }

    override suspend fun markDenied(
        authority: PickingAuthority,
        sessionEpoch: Long
    ): PickingWorkListCacheWrite = withScopeLock(authority.scope.toLocal()) {
        if (sessionEpoch <= 0) {
            return@withScopeLock PickingWorkListCacheWrite.Unavailable
        }
        val encoded = try {
            pickingWorkCacheJson.encodeToString(
                StoredPickingWorkListCache(
                    authorityEpoch = authority.authorityEpoch,
                    permissionsFingerprint = authority.permissionsFingerprint(),
                    denial = StoredPickingWorkListDenial(
                        authority.authorityEpoch,
                        authority.permissionsFingerprint(),
                        sessionEpoch
                    ),
                    pages = emptyList()
                )
            )
        } catch (_: SerializationException) {
            return@withScopeLock PickingWorkListCacheWrite.Unavailable
        }
        if (safeSave(authority.scope.toLocal(), encoded)) {
            PickingWorkListCacheWrite.Saved
        } else {
            PickingWorkListCacheWrite.Unavailable
        }
    }

    private suspend fun safeLoad(scope: ScopedMetadataScope): ScopedMetadataRead = try {
        local.load(scope)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ScopedMetadataRead.Unavailable
    }

    private suspend fun safeSave(scope: ScopedMetadataScope, payload: String): Boolean = try {
        local.save(scope, payload)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun <T> withScopeLock(scope: ScopedMetadataScope, action: suspend () -> T): T =
        scopeLocks.getOrPut(scope) { Mutex() }.withLock { action() }

    private fun String.decodeCache(): DecodedPickingWorkListCache? = try {
        if (toByteArray(Charsets.UTF_8).size > MAX_CACHE_BYTES) return null
        val stored = pickingWorkCacheJson.decodeFromString<StoredPickingWorkListCache>(this)
        if (stored.schemaVersion != CACHE_SCHEMA_VERSION || stored.authorityEpoch <= 0 ||
            !FINGERPRINT_REGEX.matches(stored.permissionsFingerprint) ||
            stored.pages.size > MAX_CACHED_PAGES
        ) {
            return null
        }
        val denial = stored.denial
        if (denial != null) {
            if (denial.authorityEpoch <= 0 || denial.sessionEpoch <= 0 ||
                denial.authorityEpoch != stored.authorityEpoch ||
                denial.permissionsFingerprint != stored.permissionsFingerprint ||
                stored.pages.isNotEmpty()
            ) {
                return null
            }
            return DecodedPickingWorkListCache(
                authorityEpoch = stored.authorityEpoch,
                permissionsFingerprint = stored.permissionsFingerprint,
                asOf = null,
                pages = emptyList(),
                denial = denial
            )
        }
        if (stored.pages.isEmpty()) return null
        val pages = stored.pages.map { it.toProjection() ?: return null }
        val asOf = pages.first().asOf
        if (pages.any { it.asOf != asOf } || pages.map { it.page }.distinct().size != pages.size) {
            return null
        }
        DecodedPickingWorkListCache(
            authorityEpoch = stored.authorityEpoch,
            permissionsFingerprint = stored.permissionsFingerprint,
            asOf = asOf,
            pages = pages,
            denial = null
        )
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun StoredPickingWorkListProjection.toProjection(): PickingWorkListCacheProjection? {
        val pageAsOf = runCatching { Instant.parse(asOf) }.getOrNull() ?: return null
        if (page < 0 || size !in 1..MAX_PAGE_SIZE || items.size > size || totalItems < items.size) {
            return null
        }
        val mapped = items.map { item -> item.toProjection() ?: return null }
        if (mapped.map(PickingWorkListCacheItem::fulfillmentId).distinct().size != mapped.size) {
            return null
        }
        return PickingWorkListCacheProjection(mapped, page, size, totalItems, pageAsOf)
    }

    private fun StoredPickingWorkListCacheItem.toProjection(): PickingWorkListCacheItem? {
        if (!UUID_REGEX.matches(fulfillmentId) || !UUID_REGEX.matches(salesOrderId) ||
            status !in PREPARATION_STATUSES || version < 0 || lineCount !in 1..MAX_LINE_COUNT ||
            (orderNumber?.length ?: 0) > MAX_ORDER_NUMBER_LENGTH
        ) {
            return null
        }
        return PickingWorkListCacheItem(
            fulfillmentId = fulfillmentId,
            salesOrderId = salesOrderId,
            orderNumber = orderNumber,
            status = status,
            version = version,
            lineCount = lineCount
        )
    }

    private fun PickingWorkPage.isValid(): Boolean =
        page >= 0 && size in 1..MAX_PAGE_SIZE && items.size <= size && totalItems >= items.size &&
            items.size <= MAX_CACHED_ITEMS && items.all { item ->
                UUID_REGEX.matches(item.fulfillmentId) && UUID_REGEX.matches(item.salesOrderId) &&
                    item.status in PREPARATION_STATUSES && item.version >= 0 &&
                    item.lineCount in 1..MAX_LINE_COUNT &&
                    (item.orderNumber?.length ?: 0) <= MAX_ORDER_NUMBER_LENGTH
            } && items.map { it.fulfillmentId }.distinct().size == items.size

    private fun PickingWorkPage.toCacheProjection() = PickingWorkListCacheProjection(
        items = items.map { item ->
            PickingWorkListCacheItem(
                fulfillmentId = item.fulfillmentId,
                salesOrderId = item.salesOrderId,
                orderNumber = item.orderNumber,
                status = item.status,
                version = item.version,
                lineCount = item.lineCount
            )
        },
        page = page,
        size = size,
        totalItems = totalItems,
        asOf = asOf
    )

    private fun PickingWorkListCacheProjection.toStored() = StoredPickingWorkListProjection(
        page = page,
        size = size,
        totalItems = totalItems,
        asOf = asOf.toString(),
        items = items.map { item ->
            StoredPickingWorkListCacheItem(
                fulfillmentId = item.fulfillmentId,
                salesOrderId = item.salesOrderId,
                orderNumber = item.orderNumber,
                status = item.status,
                version = item.version,
                lineCount = item.lineCount
            )
        }
    )

    private fun PickingScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun DecodedPickingWorkListCache.matches(authority: PickingAuthority): Boolean =
        authorityEpoch == authority.authorityEpoch &&
            permissionsFingerprint == authority.permissionsFingerprint()

    private fun PickingAuthority.permissionsFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        permissions.sorted().forEach { permission ->
            val bytes = permission.toByteArray(Charsets.UTF_8)
            val length = ByteArray(4)
            length[0] = (bytes.size ushr 24).toByte()
            length[1] = (bytes.size ushr 16).toByte()
            length[2] = (bytes.size ushr 8).toByte()
            length[3] = bytes.size.toByte()
            digest.update(length)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private companion object {
        const val MAX_CACHED_PAGES = 12
        const val MAX_CACHED_ITEMS = 100
        const val MAX_PAGE_SIZE = 100
        const val MAX_LINE_COUNT = 100_000
        const val MAX_ORDER_NUMBER_LENGTH = 120
        const val MAX_CACHE_BYTES = 60 * 1024
        val FINGERPRINT_REGEX = Regex("[0-9a-f]{64}")
        val UUID_REGEX = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val PREPARATION_STATUSES = setOf(
            "ALLOCATED",
            "PICKING",
            "PICKED",
            "PACKED",
            "STAGED",
            "READY_FOR_DISPATCH"
        )
        val pickingWorkCacheJson = Json { ignoreUnknownKeys = false }
        val scopeLocks = ConcurrentHashMap<ScopedMetadataScope, Mutex>()
    }
}

private data class DecodedPickingWorkListCache(
    val authorityEpoch: Long,
    val permissionsFingerprint: String,
    val asOf: Instant?,
    val pages: List<PickingWorkListCacheProjection>,
    val denial: StoredPickingWorkListDenial?
)

@Serializable
private data class StoredPickingWorkListCache(
    val schemaVersion: Int = CACHE_SCHEMA_VERSION,
    val authorityEpoch: Long,
    val permissionsFingerprint: String,
    val denial: StoredPickingWorkListDenial? = null,
    val pages: List<StoredPickingWorkListProjection>
)

@Serializable
private data class StoredPickingWorkListDenial(
    val authorityEpoch: Long,
    val permissionsFingerprint: String,
    val sessionEpoch: Long
)

@Serializable
private data class StoredPickingWorkListProjection(
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val asOf: String,
    val items: List<StoredPickingWorkListCacheItem>
)

@Serializable
private data class StoredPickingWorkListCacheItem(
    val fulfillmentId: String,
    val salesOrderId: String,
    val orderNumber: String?,
    val status: String,
    val version: Long,
    val lineCount: Int
)

private const val CACHE_SCHEMA_VERSION = 2

@Module
@InstallIn(SingletonComponent::class)
object AppPickingWorkListCacheBindings {
    @Provides
    @Singleton
    fun pickingWorkListCache(@ApplicationContext context: Context): PickingWorkListCache =
        AppPickingWorkListCache(
            AndroidScopedMetadataStore(context, ScopedMetadataPurpose.PickingWorkListCache)
        )
}
