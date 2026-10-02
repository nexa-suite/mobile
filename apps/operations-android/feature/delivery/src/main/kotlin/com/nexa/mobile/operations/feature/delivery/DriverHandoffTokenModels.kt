package com.nexa.mobile.operations.feature.delivery

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Immutable
data class DriverHandoffCurrentDelivery(
    val deliveryId: String,
    val status: String,
    val version: Long,
    val activeAttemptId: String?
)

@Immutable
data class DriverHandoffIssueCommand(
    val deliveryId: String,
    val attemptId: String,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String
) {
    init {
        require(deliveryId.isNotBlank() && attemptId.isNotBlank())
        require(expectedVersion >= 0)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(frozenBody == driverHandoffIssueBody(attemptId))
    }

    override fun toString(): String =
        "DriverHandoffIssueCommand(version=$expectedVersion, key=REDACTED)"
}

fun driverHandoffIssueBody(attemptId: String): String =
    JsonObject(mapOf("attemptId" to JsonPrimitive(attemptId))).toString()

@Immutable
data class DriverHandoffTokenReceipt(
    val handoffId: String,
    val deliveryId: String,
    val attemptId: String,
    val expiresAt: String,
    val status: String
) {
    override fun toString(): String = "DriverHandoffTokenReceipt(status=$status, token=REDACTED)"
}

enum class DriverHandoffUiStatus {
    Loading,
    Ready,
    Issuing,
    TokenVisible,
    UnknownOutcome,
    TokenUnavailable,
    TokenExpired,
    Cleared,
    Stale,
    Rejected,
    NotFound,
    Unavailable,
    PermissionDenied,
    PersistenceUnavailable
}

data class DriverHandoffTokenUiState(
    val authorityEpoch: Long = 0,
    val deliveryId: String? = null,
    val attemptId: String? = null,
    val delivery: DriverHandoffCurrentDelivery? = null,
    val status: DriverHandoffUiStatus = DriverHandoffUiStatus.Loading,
    val receipt: DriverHandoffTokenReceipt? = null,
    val token: String? = null,
    val command: DriverHandoffIssueCommand? = null,
    val errorCode: String? = null,
    val busy: Boolean = false
) {
    val canIssue: Boolean
        get() = status == DriverHandoffUiStatus.Ready && command == null && delivery != null &&
            !busy

    val canRetrySame: Boolean
        get() = token.isNullOrBlank() && command != null && !busy &&
            status in
            setOf(
                DriverHandoffUiStatus.UnknownOutcome,
                DriverHandoffUiStatus.TokenUnavailable,
                DriverHandoffUiStatus.Cleared,
                DriverHandoffUiStatus.TokenExpired
            )

    override fun toString(): String =
        "DriverHandoffTokenUiState(status=$status, delivery=" + (deliveryId != null) +
            ", token=REDACTED)"
}

sealed interface DriverHandoffCurrentDeliveryResult {
    data class Loaded(val delivery: DriverHandoffCurrentDelivery) :
        DriverHandoffCurrentDeliveryResult
    data object NotFound : DriverHandoffCurrentDeliveryResult
    data object Unavailable : DriverHandoffCurrentDeliveryResult
    data object PermissionDenied : DriverHandoffCurrentDeliveryResult
    data object ContextInvalidated : DriverHandoffCurrentDeliveryResult
    data object SessionInvalidated : DriverHandoffCurrentDeliveryResult
}

sealed interface DriverHandoffIssueResult {
    data class Issued(val receipt: DriverHandoffTokenReceipt, val token: String) :
        DriverHandoffIssueResult
    data class TokenUnavailable(val receipt: DriverHandoffTokenReceipt) : DriverHandoffIssueResult
    data class Rejected(val code: String?) : DriverHandoffIssueResult
    data object NotFound : DriverHandoffIssueResult
    data object UnknownOutcome : DriverHandoffIssueResult
    data object Unavailable : DriverHandoffIssueResult
    data object PermissionDenied : DriverHandoffIssueResult
    data object ContextInvalidated : DriverHandoffIssueResult
    data object SessionInvalidated : DriverHandoffIssueResult
}

sealed interface DriverHandoffMetadataRead {
    data class Available(val command: DriverHandoffIssueCommand?) : DriverHandoffMetadataRead
    data object Unavailable : DriverHandoffMetadataRead
}

enum class DriverHandoffMetadataWrite { Saved, Conflict, Stale, Unavailable }

interface DriverHandoffTokenMetadataStore {
    suspend fun load(
        scope: DriverAttemptScopeIdentity,
        deliveryId: String,
        attemptId: String
    ): DriverHandoffMetadataRead

    suspend fun persistIntent(
        scope: DriverAttemptScopeIdentity,
        command: DriverHandoffIssueCommand
    ): DriverHandoffMetadataWrite

    suspend fun clearKnownRejection(
        scope: DriverAttemptScopeIdentity,
        deliveryId: String,
        attemptId: String,
        idempotencyKey: String
    ): DriverHandoffMetadataWrite
}

interface DriverHandoffTokenGateway {
    suspend fun currentDelivery(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverHandoffCurrentDeliveryResult

    suspend fun issue(
        command: DriverHandoffIssueCommand,
        authority: DriverDeliveryAuthority
    ): DriverHandoffIssueResult
}
