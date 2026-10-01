package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.Instant

@Immutable
data class DispatchTemperatureEvidence(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val lotId: String,
    val valueCelsius: BigDecimal,
    val occurredAt: Instant,
    val actorMembershipId: String,
    val status: String
) {
    override fun toString(): String = "DispatchTemperatureEvidence(REDACTED, status=$status)"
}

@Immutable
data class DispatchTemperatureLot(
    val skuId: String,
    val lotId: String?,
    val warehouseId: String?,
    val zoneId: String?,
    val skuColdChainRequired: Boolean,
    val requiredForFulfillment: Boolean,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?,
    val status: String,
    val latestEvidence: DispatchTemperatureEvidence?
) {
    val supportsInRangeEvidence: Boolean
        get() = skuColdChainRequired && (minimumCelsius != null || maximumCelsius != null) &&
            lotId != null && warehouseId != null && zoneId != null

    fun isWithinRange(value: BigDecimal): Boolean =
        (minimumCelsius == null || value >= minimumCelsius) &&
            (maximumCelsius == null || value <= maximumCelsius)

    override fun toString(): String = "DispatchTemperatureLot(REDACTED, status=$status)"
}

@Immutable
data class DispatchTemperatureReadiness(
    val fulfillmentId: String,
    val fulfillmentStatus: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val temperatureRequiredForFulfillment: Boolean,
    val asOf: Instant,
    val lots: List<DispatchTemperatureLot>
) {
    override fun toString(): String = "DispatchTemperatureReadiness(REDACTED, " +
        "version=$fulfillmentVersion, lots=${lots.size})"
}

data class DispatchTemperatureCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val lotId: String,
    val valueCelsius: BigDecimal,
    val occurredAt: Instant,
    val idempotencyKey: String,
    val exactRequestBody: String
) {
    override fun toString(): String = "DispatchTemperatureCommand(REDACTED, " +
        "version=$expectedFulfillmentVersion)"

    fun buildRequestBody(): String = "{\"lotId\":\"$lotId\",\"value\":" +
        "${valueCelsius.stripTrailingZeros().toPlainString()},\"unit\":\"CELSIUS\",\"occurredAt\":\"$occurredAt\"}"

    fun isValid(): Boolean = UUID_PATTERN.matches(fulfillmentId) && UUID_PATTERN.matches(lotId) &&
        expectedFulfillmentVersion >= 0 && idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 &&
        exactRequestBody == buildRequestBody()

    private companion object {
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

data class DispatchTemperatureScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispatchTemperatureScopeIdentity(REDACTED)"
}

enum class DispatchTemperatureIntentStatus { Pending, UnknownOutcome }

data class DispatchTemperatureIntent(
    val scope: DispatchTemperatureScopeIdentity,
    val command: DispatchTemperatureCommand,
    val status: DispatchTemperatureIntentStatus = DispatchTemperatureIntentStatus.Pending
) {
    override fun toString(): String = "DispatchTemperatureIntent(REDACTED, status=$status)"
}

sealed interface DispatchTemperatureMetadataRead {
    data class Available(val intent: DispatchTemperatureIntent?) : DispatchTemperatureMetadataRead
    data object Unavailable : DispatchTemperatureMetadataRead
}

enum class DispatchTemperatureMetadataWrite { Saved, Conflict, Stale, Unavailable }

/** Encrypted, all-identity-scoped exact Celsius command staging. */
interface DispatchTemperatureMetadataStore {
    suspend fun loadIntent(
        scope: DispatchTemperatureScopeIdentity,
        fulfillmentId: String
    ): DispatchTemperatureMetadataRead

    suspend fun saveIntent(intent: DispatchTemperatureIntent): DispatchTemperatureMetadataWrite

    suspend fun clearIntent(
        scope: DispatchTemperatureScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchTemperatureMetadataWrite
}

enum class DispatchTemperatureStatus {
    Initial,
    Loading,
    Current,
    PermissionUnknown,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable,
    ContextInvalidated,
    SessionInvalidated
}

enum class DispatchTemperatureMutationStatus {
    Idle,
    Submitting,
    Recorded,
    OutsideRangeBackendContractGap,
    UnknownOutcome,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    Stale,
    Conflict
}

@Immutable
data class DispatchTemperatureUiState(
    val authorityEpoch: Long = 0,
    val fulfillmentId: String? = null,
    val status: DispatchTemperatureStatus = DispatchTemperatureStatus.Initial,
    val readiness: DispatchTemperatureReadiness? = null,
    val observedAt: Instant? = null,
    val valuesCelsius: Map<String, String> = emptyMap(),
    val mutationStatus: DispatchTemperatureMutationStatus = DispatchTemperatureMutationStatus.Idle,
    val mutationLotId: String? = null,
    val canRecord: Boolean = false,
    val metadataReady: Boolean = false,
    val hasPendingCommand: Boolean = false,
    val pendingCommand: DispatchTemperatureCommand? = null
) {
    val canRetryUnknownOutcome: Boolean
        get() = canRecord && hasPendingCommand && pendingCommand != null &&
            mutationStatus in setOf(
                DispatchTemperatureMutationStatus.UnknownOutcome,
                DispatchTemperatureMutationStatus.Recorded
            )

    override fun toString(): String = "DispatchTemperatureUiState(status=$status, " +
        "mutationStatus=$mutationStatus, readiness=${readiness != null})"
}

sealed interface DispatchTemperatureGatewayResult {
    data class Current(val readiness: DispatchTemperatureReadiness) : DispatchTemperatureGatewayResult
    data class Recorded(val evidence: DispatchTemperatureEvidence) : DispatchTemperatureGatewayResult
    data object OutsideRangeBackendContractGap : DispatchTemperatureGatewayResult
    data object UnknownOutcome : DispatchTemperatureGatewayResult
    data object NetworkUnavailable : DispatchTemperatureGatewayResult
    data object ServiceUnavailable : DispatchTemperatureGatewayResult
    data object PermissionDenied : DispatchTemperatureGatewayResult
    data object ContextInvalidated : DispatchTemperatureGatewayResult
    data object SessionInvalidated : DispatchTemperatureGatewayResult
    data object Stale : DispatchTemperatureGatewayResult
    data object Conflict : DispatchTemperatureGatewayResult
}

/** Client port for the current fulfillment temperature view and its authorized manual evidence. */
interface DispatchTemperatureGateway {
    suspend fun current(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchTemperatureGatewayResult

    suspend fun record(
        command: DispatchTemperatureCommand,
        context: DispatchAuthorityContext
    ): DispatchTemperatureGatewayResult
}
