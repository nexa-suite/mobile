package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Current verified identity and permission snapshot supplied by the application boundary. */
@Immutable
data class PickingAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
        require(authorityEpoch > 0)
    }

    val scope: PickingScopeIdentity
        get() = PickingScopeIdentity(userId, tenantId, workspaceId, membershipId)

    val canRead: Boolean
        get() = permissions.any { it in FULFILLMENT_READ_PERMISSIONS }

    val canPick: Boolean
        get() = permissions.any { it in FULFILLMENT_WRITE_PERMISSIONS }

    override fun toString(): String =
        "PickingAuthority(scope=REDACTED, permissions=${permissions.size}, epoch=$authorityEpoch)"

    private companion object {
        val FULFILLMENT_READ_PERMISSIONS = setOf("fulfillment.read", "fulfillment:read")
        val FULFILLMENT_WRITE_PERMISSIONS = setOf("fulfillment.manage", "warehouse:write")
    }
}

/** Scope key for harmless local command metadata. It never stores permission authority. */
@Immutable
data class PickingScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "PickingScopeIdentity(REDACTED)"
}

@Immutable
data class FulfillmentPickingLine(
    val id: String,
    val skuId: String,
    val catalogItemId: String?,
    val allocatedQuantity: BigDecimal,
    val pickedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String
)

/** Server projection returned by GET /api/v1/fulfillments/{id}. */
@Immutable
data class FulfillmentPickingSnapshot(
    val id: String,
    val status: String,
    val version: Long,
    val lines: List<FulfillmentPickingLine>
)

/** Exact current BC-05 allocation projection paired with the BC-06 fulfillment. */
@Immutable
data class PickingAllocationProjection(
    val allocationId: String,
    val status: String,
    val version: Long,
    val asOf: Instant,
    val lines: List<PickingAllocationLine>
) {
    override fun toString(): String =
        "PickingAllocationProjection(status=$status, version=$version, lines=${lines.size})"
}

@Immutable
data class PickingAllocationLine(
    val physicalAllocationLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val warehouseId: String,
    val zoneId: String?,
    val lotId: String,
    val quantity: BigDecimal,
    val releasedQuantity: BigDecimal,
    val consumedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String,
    val expirationDate: LocalDate?
) {
    override fun toString(): String =
        "PickingAllocationLine(lotId=REDACTED, remaining=$remainingQuantity, unit=$unit)"
}

/** Typed proposal joined by server identifiers; a match is usable only when unique. */
@Immutable
data class PickingOffer(
    val fulfillmentLine: FulfillmentPickingLine?,
    val allocationLine: PickingAllocationLine,
    val ambiguousFulfillmentMatch: Boolean
) {
    val isPickable: Boolean
        get() = !ambiguousFulfillmentMatch &&
            fulfillmentLine != null &&
            allocationLine.remainingQuantity.signum() > 0 &&
            fulfillmentLine.remainingQuantity.signum() > 0 &&
            allocationLine.unit.equals(fulfillmentLine.unit, ignoreCase = true)
}

@Immutable
data class PickingFulfillmentSnapshot(
    val fulfillment: FulfillmentPickingSnapshot,
    val allocation: PickingAllocationProjection
) {
    fun offers(): List<PickingOffer> = allocation.lines.map { allocated ->
        val matching = fulfillment.lines.filter { line ->
            line.skuId == allocated.skuId &&
                (line.catalogItemId == null || line.catalogItemId == allocated.catalogItemId) &&
                line.unit.equals(allocated.unit, ignoreCase = true)
        }
        PickingOffer(
            fulfillmentLine = matching.singleOrNull(),
            allocationLine = allocated,
            ambiguousFulfillmentMatch = matching.size != 1
        )
    }
}

@Immutable
data class PickingConfirmationCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val allocationVersion: Long,
    val fulfillmentLineId: String,
    val skuId: String,
    val physicalAllocationLineId: String,
    val lotId: String,
    val warehouseId: String,
    val quantity: BigDecimal,
    val unit: String
) {
    override fun toString(): String =
        "PickingConfirmationCommand(fulfillmentId=REDACTED, versions=$expectedFulfillmentVersion/$allocationVersion, quantity=$quantity, unit=$unit)"
}

sealed interface PickingIntentCommand {
    data class Start(val fulfillmentId: String, val expectedFulfillmentVersion: Long) :
        PickingIntentCommand

    data class Confirm(val request: PickingConfirmationCommand) : PickingIntentCommand
}

enum class PickingIntentMetadataStatus { Pending, UnknownOutcome }

/** Persist only scope-bound frozen command identity. Never persist server stock facts or tokens. */
@Immutable
data class PickingIntentMetadata(
    val scope: PickingScopeIdentity,
    val idempotencyKey: String,
    val command: PickingIntentCommand,
    val status: PickingIntentMetadataStatus
) {
    override fun toString(): String = "PickingIntentMetadata(status=$status, key=REDACTED)"
}

sealed interface PickingMetadataRead<out T> {
    data class Available<T>(val value: T?) : PickingMetadataRead<T>
    data object Unavailable : PickingMetadataRead<Nothing>
}

enum class PickingMetadataWrite { Saved, Unavailable }

interface PickingMetadataStore {
    suspend fun loadIntent(scope: PickingScopeIdentity): PickingMetadataRead<PickingIntentMetadata>
    suspend fun saveIntent(intent: PickingIntentMetadata): PickingMetadataWrite
    suspend fun clearIntent(
        scope: PickingScopeIdentity,
        idempotencyKey: String
    ): PickingMetadataWrite
}

/** Safe default until the durable scope-bound adapter is supplied by the integrator. */
object UnavailablePickingMetadataStore : PickingMetadataStore {
    override suspend fun loadIntent(
        scope: PickingScopeIdentity
    ): PickingMetadataRead<PickingIntentMetadata> = PickingMetadataRead.Unavailable

    override suspend fun saveIntent(intent: PickingIntentMetadata): PickingMetadataWrite =
        PickingMetadataWrite.Unavailable

    override suspend fun clearIntent(
        scope: PickingScopeIdentity,
        idempotencyKey: String
    ): PickingMetadataWrite = PickingMetadataWrite.Unavailable
}

sealed interface PickingLoadResult {
    data class Loaded(val snapshot: PickingFulfillmentSnapshot) : PickingLoadResult
    data class AllocationUnavailable(val fulfillment: FulfillmentPickingSnapshot) :
        PickingLoadResult
    data object NotFound : PickingLoadResult
    data object NetworkUnavailable : PickingLoadResult
    data object ServiceUnavailable : PickingLoadResult
    data object PermissionDenied : PickingLoadResult
    data object ContextInvalidated : PickingLoadResult
    data object SessionInvalidated : PickingLoadResult
}

sealed interface PickingMutationResult {
    data class Confirmed(val fulfillment: FulfillmentPickingSnapshot) : PickingMutationResult
    data class Rejected(val code: String?) : PickingMutationResult
    data object StaleVersion : PickingMutationResult
    data object UnknownOutcome : PickingMutationResult
    data object PermissionDenied : PickingMutationResult
    data object ContextInvalidated : PickingMutationResult
    data object SessionInvalidated : PickingMutationResult
    data object ServiceUnavailable : PickingMutationResult
}

/** Feature port: all facts and authorization are supplied by the current server session. */
interface PickingGateway {
    suspend fun load(fulfillmentId: String, authority: PickingAuthority): PickingLoadResult

    suspend fun startPicking(
        command: PickingIntentCommand.Start,
        idempotencyKey: String,
        authority: PickingAuthority
    ): PickingMutationResult

    suspend fun confirmPicking(
        command: PickingConfirmationCommand,
        idempotencyKey: String,
        authority: PickingAuthority
    ): PickingMutationResult
}
