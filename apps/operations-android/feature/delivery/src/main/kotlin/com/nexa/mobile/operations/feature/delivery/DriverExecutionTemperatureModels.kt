package com.nexa.mobile.operations.feature.delivery

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureCommandStatus as TemperatureCommandStatus
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureEvidenceStatus as TemperatureEvidenceStatus
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureLoadStatus as TemperatureLoadStatus
import com.nexa.mobile.operations.feature.delivery.DriverExecutionTemperatureUiState as TemperatureUiState
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val executionTemperatureUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

enum class DriverExecutionTemperatureMode {
    DRIVER,
    HOLD_DISPOSITION
}
enum class DriverExecutionTemperatureDisposition {
    RELEASE,
    CONTINUE_HOLD,
    REJECT,
    WASTE
}
enum class DriverExecutionTemperatureIntentStatus {
    Pending,
    UnknownOutcome,
    StaleVersion
}
enum class DriverExecutionTemperatureMetadataWrite {
    Saved,
    Conflict,
    Stale,
    Unavailable
}
enum class DriverExecutionTemperatureCommandStatus {
    Idle,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Recorded,
    Disposed,
    StaleVersion,
    Rejected,
    PersistenceUnavailable
}
enum class DriverExecutionTemperatureLoadStatus {
    NotRequested,
    Loading,
    Ready,
    NotFound,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

@Immutable
data class DriverExecutionTemperatureLine(
    val fulfillmentLineId: String,
    val skuId: String,
    val unit: String,
    val remainingQuantity: BigDecimal,
    val coldChainRequired: Boolean,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?
) {
    init {
        require(
            executionTemperatureUuid.matches(fulfillmentLineId) &&
                executionTemperatureUuid.matches(skuId)
        )
        require(unit.isNotBlank() && remainingQuantity.signum() >= 0)
        require(
            minimumCelsius == null || maximumCelsius == null || minimumCelsius <= maximumCelsius
        )
    }

    val supportsReading: Boolean
        get() = coldChainRequired && minimumCelsius != null && maximumCelsius != null &&
            remainingQuantity.signum() > 0

    fun isWithinRange(value: BigDecimal): Boolean =
        (minimumCelsius == null || value >= minimumCelsius) &&
            (maximumCelsius == null || value <= maximumCelsius)
}

@Immutable
data class DriverExecutionTemperatureHold(
    val id: String,
    val readingId: String,
    val exceptionId: String,
    val fulfillmentLineId: String,
    val skuId: String,
    val affectedQuantity: BigDecimal,
    val quantityUnit: String,
    val status: String,
    val reportedByMembershipId: String,
    val reportedAt: Instant,
    val disposition: String?,
    val authorizedByMembershipId: String?,
    val disposedAt: Instant?,
    val reason: String?
) {
    init {
        require(
            listOf(id, readingId, exceptionId, fulfillmentLineId, skuId, reportedByMembershipId)
                .all(executionTemperatureUuid::matches)
        )
        require(affectedQuantity.signum() > 0 && quantityUnit.isNotBlank() && status.isNotBlank())
        require(
            disposition == null ||
                disposition in DriverExecutionTemperatureDisposition.entries.map { it.name }
        )
        require(
            authorizedByMembershipId == null ||
                executionTemperatureUuid.matches(authorizedByMembershipId)
        )
    }
}

@Immutable
data class DriverExecutionTemperatureSnapshot(
    val deliveryId: String,
    val deliveryVersion: Long,
    val deliveryStatus: String,
    val attemptId: String?,
    val originWarehouseId: String?,
    val lines: List<DriverExecutionTemperatureLine>,
    val holds: List<DriverExecutionTemperatureHold>
) {
    init {
        require(
            executionTemperatureUuid.matches(deliveryId) && deliveryVersion >= 0 &&
                deliveryStatus.isNotBlank()
        )
        require(attemptId == null || executionTemperatureUuid.matches(attemptId))
        require(originWarehouseId == null || executionTemperatureUuid.matches(originWarehouseId))
        require(lines.map { it.fulfillmentLineId.lowercase() }.distinct().size == lines.size)
        require(holds.map { it.id.lowercase() }.distinct().size == holds.size)
    }
}

@Immutable
data class DriverExecutionTemperatureReading(
    val id: String,
    val deliveryId: String,
    val attemptId: String?,
    val fulfillmentLineId: String,
    val skuId: String,
    val affectedQuantity: BigDecimal,
    val quantityUnit: String,
    val valueCelsius: BigDecimal,
    val temperatureUnit: String,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?,
    val status: String,
    val actorMembershipId: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val evidenceObjectId: String?,
    val sourceIncidentId: String?,
    val hold: DriverExecutionTemperatureHold?,
    val deliveryVersion: Long,
    val replayed: Boolean
)

@Immutable
sealed interface DriverExecutionTemperatureCommand {
    val deliveryId: String
    val expectedDeliveryVersion: Long
    val idempotencyKey: String
    val frozenBody: String
    fun isValid(): Boolean

    @Immutable
    data class Reading(
        override val deliveryId: String,
        override val expectedDeliveryVersion: Long,
        val fulfillmentLineId: String,
        val skuId: String,
        val affectedQuantity: BigDecimal,
        val valueCelsius: BigDecimal,
        val occurredAt: Instant,
        val sourceIncidentId: String?,
        val evidenceObjectId: String?,
        override val idempotencyKey: String,
        override val frozenBody: String
    ) : DriverExecutionTemperatureCommand {
        override fun isValid(): Boolean = executionTemperatureUuid.matches(deliveryId) &&
            expectedDeliveryVersion >= 0 && executionTemperatureUuid.matches(fulfillmentLineId) &&
            executionTemperatureUuid.matches(skuId) && affectedQuantity.signum() > 0 &&
            valueCelsius.abs() < BigDecimal("1000") && idempotencyKey.isNotBlank() &&
            idempotencyKey.length <= 160 &&
            (
                (sourceIncidentId == null && evidenceObjectId == null) ||
                    (
                        sourceIncidentId != null && evidenceObjectId != null &&
                            executionTemperatureUuid.matches(sourceIncidentId) &&
                            executionTemperatureUuid.matches(evidenceObjectId)
                        )
                ) &&
            frozenBody == driverExecutionTemperatureReadingBody(
                fulfillmentLineId,
                skuId,
                affectedQuantity,
                valueCelsius,
                occurredAt,
                sourceIncidentId,
                evidenceObjectId
            )

        override fun toString(): String =
            "ExecutionTemperatureReadingCommand(version=$expectedDeliveryVersion, key=REDACTED)"
    }

    @Immutable
    data class Disposition(
        override val deliveryId: String,
        val holdId: String,
        override val expectedDeliveryVersion: Long,
        val disposition: DriverExecutionTemperatureDisposition,
        val reason: String,
        override val idempotencyKey: String,
        override val frozenBody: String
    ) : DriverExecutionTemperatureCommand {
        override fun isValid(): Boolean = executionTemperatureUuid.matches(deliveryId) &&
            executionTemperatureUuid.matches(holdId) && expectedDeliveryVersion >= 0 &&
            reason.trim().isNotEmpty() && reason.trim().length <= 2000 &&
            idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 &&
            frozenBody == driverExecutionTemperatureDispositionBody(disposition, reason)

        override fun toString(): String =
            "ExecutionTemperatureDispositionCommand(version=$expectedDeliveryVersion, key=REDACTED)"
    }
}

fun driverExecutionTemperatureReadingBody(
    fulfillmentLineId: String,
    skuId: String,
    affectedQuantity: BigDecimal,
    valueCelsius: BigDecimal,
    occurredAt: Instant,
    sourceIncidentId: String?,
    evidenceObjectId: String?
): String {
    val source = sourceIncidentId?.let { ",\"sourceIncidentId\":\"$it\"" }.orEmpty()
    val evidence = evidenceObjectId?.let { ",\"evidenceObjectId\":\"$it\"" }.orEmpty()
    return "{\"fulfillmentLineId\":\"$fulfillmentLineId\",\"skuId\":\"$skuId\"," +
        "\"affectedQuantity\":${affectedQuantity.stripTrailingZeros().toPlainString()}," +
        "\"value\":${valueCelsius.stripTrailingZeros().toPlainString()},\"unit\":\"CELSIUS\"," +
        "\"occurredAt\":\"$occurredAt\"$source$evidence}"
}

fun driverExecutionTemperatureDispositionBody(
    disposition: DriverExecutionTemperatureDisposition,
    reason: String
): String = JsonObject(
    linkedMapOf(
        "disposition" to JsonPrimitive(disposition.name),
        "reason" to JsonPrimitive(reason.trim())
    )
).toString()

@Immutable
data class DriverExecutionTemperatureIntent(
    val scope: DriverAttemptScopeIdentity,
    val command: DriverExecutionTemperatureCommand,
    val initiatedAt: Instant,
    val status: DriverExecutionTemperatureIntentStatus
) {
    init {
        require(command.isValid())
        require(command.deliveryId.isNotBlank() && command.idempotencyKey.isNotBlank())
    }

    override fun toString(): String =
        "DriverExecutionTemperatureIntent(status=$status, command=REDACTED)"
}

sealed interface DriverExecutionTemperatureMetadataRead {
    data class Available(val intent: DriverExecutionTemperatureIntent?) :
        DriverExecutionTemperatureMetadataRead
    data object Unavailable : DriverExecutionTemperatureMetadataRead
}

interface DriverExecutionTemperatureMetadataStore {
    suspend fun loadIntent(
        scope: DriverAttemptScopeIdentity
    ): DriverExecutionTemperatureMetadataRead
    suspend fun saveIntent(
        intent: DriverExecutionTemperatureIntent
    ): DriverExecutionTemperatureMetadataWrite
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverExecutionTemperatureMetadataWrite
}

sealed interface DriverExecutionTemperatureLoadResult {
    data class Loaded(val snapshot: DriverExecutionTemperatureSnapshot) :
        DriverExecutionTemperatureLoadResult
    data object NotFound : DriverExecutionTemperatureLoadResult
    data object NetworkUnavailable : DriverExecutionTemperatureLoadResult
    data object ServiceUnavailable : DriverExecutionTemperatureLoadResult
    data object PermissionDenied : DriverExecutionTemperatureLoadResult
    data object ContextInvalidated : DriverExecutionTemperatureLoadResult
    data object SessionInvalidated : DriverExecutionTemperatureLoadResult
}

sealed interface DriverExecutionTemperatureMutationResult {
    data class ReadingRecorded(val reading: DriverExecutionTemperatureReading) :
        DriverExecutionTemperatureMutationResult
    data class DispositionRecorded(
        val hold: DriverExecutionTemperatureHold,
        val deliveryVersion: Long,
        val replayed: Boolean
    ) : DriverExecutionTemperatureMutationResult
    data class Rejected(val code: String?) : DriverExecutionTemperatureMutationResult
    data object NotFound : DriverExecutionTemperatureMutationResult
    data object StaleVersion : DriverExecutionTemperatureMutationResult
    data object Conflict : DriverExecutionTemperatureMutationResult
    data object UnknownOutcome : DriverExecutionTemperatureMutationResult
    data object NetworkUnavailable : DriverExecutionTemperatureMutationResult
    data object ServiceUnavailable : DriverExecutionTemperatureMutationResult
    data object PermissionDenied : DriverExecutionTemperatureMutationResult
    data object ContextInvalidated : DriverExecutionTemperatureMutationResult
    data object SessionInvalidated : DriverExecutionTemperatureMutationResult
}

interface DriverExecutionTemperatureGateway {
    suspend fun current(
        deliveryId: String,
        mode: DriverExecutionTemperatureMode,
        authority: DriverDeliveryAuthority
    ): DriverExecutionTemperatureLoadResult

    suspend fun record(
        command: DriverExecutionTemperatureCommand.Reading,
        authority: DriverDeliveryAuthority
    ): DriverExecutionTemperatureMutationResult

    suspend fun dispose(
        command: DriverExecutionTemperatureCommand.Disposition,
        authority: DriverDeliveryAuthority
    ): DriverExecutionTemperatureMutationResult
}

sealed interface DriverExecutionTemperatureEvidenceStatus {
    data object None : TemperatureEvidenceStatus
    data object Checking : TemperatureEvidenceStatus
    data object Available : TemperatureEvidenceStatus
    data class Unavailable(val code: String? = null) : TemperatureEvidenceStatus
}

@Immutable
data class DriverExecutionTemperatureUiState(
    val authorityEpoch: Long = 0,
    val mode: DriverExecutionTemperatureMode = DriverExecutionTemperatureMode.DRIVER,
    val deliveryId: String? = null,
    val canRead: Boolean = false,
    val canRecord: Boolean = false,
    val canDispose: Boolean = false,
    val currentMembershipId: String? = null,
    val snapshot: DriverExecutionTemperatureSnapshot? = null,
    val lastReading: DriverExecutionTemperatureReading? = null,
    val loadStatus: TemperatureLoadStatus = TemperatureLoadStatus.NotRequested,
    val commandStatus: TemperatureCommandStatus = TemperatureCommandStatus.Idle,
    val command: DriverExecutionTemperatureCommand? = null,
    val hasRecoverableCommand: Boolean = false,
    val unresolvedCommandForOtherDelivery: Boolean = false,
    val quantitiesByLine: Map<String, String> = emptyMap(),
    val valuesCelsiusByLine: Map<String, String> = emptyMap(),
    val dispositionReasonsByHold: Map<String, String> = emptyMap(),
    val sourceIncidentId: String? = null,
    val sourceEvidenceObjectId: String? = null,
    val sourceEvidenceStatus: TemperatureEvidenceStatus = TemperatureEvidenceStatus.None,
    val replayed: Boolean = false,
    val rejectionCode: String? = null
) {
    val canRetryUnknownOutcome: Boolean
        get() = hasRecoverableCommand &&
            commandStatus == TemperatureCommandStatus.UnknownOutcome &&
            command != null && !unresolvedCommandForOtherDelivery

    override fun toString(): String =
        "TemperatureUiState(mode=$mode, load=$loadStatus, command=$commandStatus, payload=REDACTED)"
}
