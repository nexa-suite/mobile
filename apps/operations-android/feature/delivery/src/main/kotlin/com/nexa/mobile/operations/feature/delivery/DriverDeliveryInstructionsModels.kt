package com.nexa.mobile.operations.feature.delivery

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val instructionUuidPattern =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

enum class DriverDeliveryInstructionKind {
    NORMAL,
    COLD_CHAIN,
    ACCESS_RESTRICTION,
    SPECIAL_UNLOADING,
    CUSTOMER_SAFETY,
    GOODS_HANDLING
}

@Immutable
data class DriverDeliveryInstruction(
    val id: String,
    val kind: DriverDeliveryInstructionKind,
    val content: String,
    val instructionVersion: Long,
    val critical: Boolean,
    val acknowledged: Boolean,
    val acknowledgedAt: String?,
    val acknowledgedByMembershipId: String?,
    val sourceKind: String? = null,
    val recordedByMembershipId: String? = null,
    val recordedAt: String? = null
) {
    init {
        require(instructionUuidPattern.matches(id))
        require(content.isNotBlank())
        require(instructionVersion >= 0)
        require(critical == (kind != DriverDeliveryInstructionKind.NORMAL))
        require(acknowledged == (!acknowledgedAt.isNullOrBlank() && !acknowledgedByMembershipId.isNullOrBlank()))
    }
}

@Immutable
data class DriverDeliveryInstructionsSnapshot(
    val deliveryId: String,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val instructions: List<DriverDeliveryInstruction>
) {
    init {
        require(instructionUuidPattern.matches(deliveryId))
        require(deliveryVersion >= 0 && instructionSetVersion >= 0)
        require(instructions.map { it.id.lowercase() }.distinct().size == instructions.size)
    }
}

@Immutable
data class DriverDeliveryInstructionAcknowledgementCommand(
    val deliveryId: String,
    val instructionSetVersion: Long,
    val instructionVersions: Map<String, Long>,
    val idempotencyKey: String,
    val frozenBody: String
) {
    val instructionIds: List<String> get() = instructionVersions.keys.sorted()

    init {
        require(instructionUuidPattern.matches(deliveryId))
        require(instructionSetVersion >= 0)
        require(instructionVersions.isNotEmpty())
        require(instructionVersions.all { (id, version) -> instructionUuidPattern.matches(id) && version >= 0 })
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(frozenBody == driverDeliveryInstructionAcknowledgementBody(instructionVersions.keys))
    }

    override fun toString(): String =
        "DriverDeliveryInstructionAcknowledgementCommand(setVersion=$instructionSetVersion, ids=${instructionVersions.size}, key=REDACTED)"
}

fun driverDeliveryInstructionAcknowledgementBody(instructionIds: Collection<String>): String {
    val ids = instructionIds.map(String::lowercase).distinct().sorted()
    require(ids.isNotEmpty() && ids.all(instructionUuidPattern::matches))
    return JsonObject(
        mapOf("instructionIds" to JsonArray(ids.map(::JsonPrimitive)))
    ).toString()
}

@Immutable
data class DriverDeliveryInstructionAcknowledgementFact(
    val instructionId: String,
    val instructionVersion: Long,
    val acknowledgedByMembershipId: String,
    val acknowledgedAt: String
) {
    init {
        require(instructionUuidPattern.matches(instructionId))
        require(instructionVersion >= 0)
        require(instructionUuidPattern.matches(acknowledgedByMembershipId))
        require(acknowledgedAt.isNotBlank())
    }
}

@Immutable
data class DriverDeliveryInstructionAcknowledgementSummary(
    val deliveryId: String,
    val instructionSetVersion: Long,
    val acknowledgements: List<DriverDeliveryInstructionAcknowledgementFact>,
    val replayed: Boolean
) {
    init {
        require(instructionUuidPattern.matches(deliveryId))
        require(instructionSetVersion >= 0)
        require(acknowledgements.map { it.instructionId.lowercase() }.distinct().size == acknowledgements.size)
    }
}

enum class DriverDeliveryInstructionIntentStatus { Pending, UnknownOutcome, StaleVersion }

/** Encrypted local retry metadata; never an instruction, permission, or assignment authority. */
@Immutable
data class DriverDeliveryInstructionIntentMetadata(
    val scope: DriverAttemptScopeIdentity,
    val command: DriverDeliveryInstructionAcknowledgementCommand,
    val initiatedByMembershipId: String,
    val initiatedAt: String,
    val status: DriverDeliveryInstructionIntentStatus
) {
    init {
        require(instructionUuidPattern.matches(initiatedByMembershipId))
        require(initiatedAt.isNotBlank())
        require(initiatedByMembershipId == scope.membershipId)
    }

    override fun toString(): String =
        "DriverDeliveryInstructionIntentMetadata(status=$status, command=REDACTED)"
}

sealed interface DriverDeliveryInstructionMetadataRead {
    data class Available(val intent: DriverDeliveryInstructionIntentMetadata?) :
        DriverDeliveryInstructionMetadataRead

    data object Unavailable : DriverDeliveryInstructionMetadataRead
}

sealed interface DriverDeliveryInstructionMetadataWrite {
    data object Saved : DriverDeliveryInstructionMetadataWrite
    data object Conflict : DriverDeliveryInstructionMetadataWrite
    data object Stale : DriverDeliveryInstructionMetadataWrite
    data object Unavailable : DriverDeliveryInstructionMetadataWrite
}

interface DriverDeliveryInstructionMetadataStore {
    suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverDeliveryInstructionMetadataRead
    suspend fun saveIntent(intent: DriverDeliveryInstructionIntentMetadata): DriverDeliveryInstructionMetadataWrite
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverDeliveryInstructionMetadataWrite
}

sealed interface DriverDeliveryInstructionsLoadResult {
    data class Loaded(val snapshot: DriverDeliveryInstructionsSnapshot) : DriverDeliveryInstructionsLoadResult
    data object NotFound : DriverDeliveryInstructionsLoadResult
    data object NetworkUnavailable : DriverDeliveryInstructionsLoadResult
    data object ServiceUnavailable : DriverDeliveryInstructionsLoadResult
    data object PermissionDenied : DriverDeliveryInstructionsLoadResult
    data object ContextInvalidated : DriverDeliveryInstructionsLoadResult
    data object SessionInvalidated : DriverDeliveryInstructionsLoadResult
}

sealed interface DriverDeliveryInstructionAcknowledgementResult {
    data class Acknowledged(val summary: DriverDeliveryInstructionAcknowledgementSummary) :
        DriverDeliveryInstructionAcknowledgementResult

    data class Rejected(val code: String?) : DriverDeliveryInstructionAcknowledgementResult
    data object NotFound : DriverDeliveryInstructionAcknowledgementResult
    data object StaleVersion : DriverDeliveryInstructionAcknowledgementResult
    data object UnknownOutcome : DriverDeliveryInstructionAcknowledgementResult
    data object NetworkUnavailable : DriverDeliveryInstructionAcknowledgementResult
    data object ServiceUnavailable : DriverDeliveryInstructionAcknowledgementResult
    data object PermissionDenied : DriverDeliveryInstructionAcknowledgementResult
    data object ContextInvalidated : DriverDeliveryInstructionAcknowledgementResult
    data object SessionInvalidated : DriverDeliveryInstructionAcknowledgementResult
}

interface DriverDeliveryInstructionsGateway {
    suspend fun currentInstructions(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryInstructionsLoadResult

    suspend fun acknowledgeCriticalInstructions(
        command: DriverDeliveryInstructionAcknowledgementCommand,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryInstructionAcknowledgementResult
}
