package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable

@Immutable
data class DispatchHandoffIdentityCommand(
    val deliveryId: String,
    val assignmentId: String,
    val idempotencyKey: String,
    val frozenBody: String
) {
    init {
        require(DISPATCH_HANDOFF_UUID.matches(deliveryId))
        require(DISPATCH_HANDOFF_UUID.matches(assignmentId))
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(frozenBody == dispatchHandoffIssueBody(assignmentId))
    }

    override fun toString(): String =
        "DispatchHandoffIdentityCommand(delivery=REDACTED, assignment=REDACTED, key=REDACTED)"
}

fun dispatchHandoffIssueBody(assignmentId: String): String =
    """{"purpose":"DISPATCH_HANDOFF","assignmentId":"$assignmentId"}"""

@Immutable
data class DispatchHandoffIdentity(
    val handoffId: String,
    val deliveryId: String,
    val assignmentId: String,
    val deliveryVersion: Long,
    val expiresAt: String,
    val status: String
) {
    override fun toString(): String =
        "DispatchHandoffIdentity(delivery=REDACTED, assignment=REDACTED, status=$status)"
}

enum class DispatchHandoffIdentityStatus {
    Loading,
    Ready,
    Issuing,
    TokenVisible,
    Validating,
    IdentityValidated,
    UnknownOutcome,
    ReissueRequired,
    ValidationRejected,
    ValidationUnknown,
    Rejected,
    NotFound,
    Unavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    PersistenceUnavailable
}

@Immutable
data class DispatchHandoffIdentityUiState(
    val authorityEpoch: Long = 0,
    val deliveryId: String? = null,
    val assignmentId: String? = null,
    val status: DispatchHandoffIdentityStatus = DispatchHandoffIdentityStatus.Loading,
    val identity: DispatchHandoffIdentity? = null,
    val oneTimeToken: String? = null,
    val enteredToken: String = "",
    val command: DispatchHandoffIdentityCommand? = null,
    val errorCode: String? = null,
    val busy: Boolean = false,
    val canIssueByAuthority: Boolean = false,
    val canValidateByAuthority: Boolean = false
) {
    val canIssue: Boolean
        get() = canIssueByAuthority && !busy && command == null &&
            status in setOf(
                DispatchHandoffIdentityStatus.Ready,
                DispatchHandoffIdentityStatus.ReissueRequired,
                DispatchHandoffIdentityStatus.ValidationRejected,
                DispatchHandoffIdentityStatus.ValidationUnknown
            )

    val canRetrySame: Boolean
        get() = !busy && command != null &&
            status == DispatchHandoffIdentityStatus.UnknownOutcome

    val canValidate: Boolean
        get() = canValidateByAuthority && !busy && enteredToken.isNotBlank() &&
            status !in setOf(
                DispatchHandoffIdentityStatus.Loading,
                DispatchHandoffIdentityStatus.PermissionDenied,
                DispatchHandoffIdentityStatus.ContextInvalidated,
                DispatchHandoffIdentityStatus.SessionInvalidated,
                DispatchHandoffIdentityStatus.PersistenceUnavailable
            )

    override fun toString(): String =
        "DispatchHandoffIdentityUiState(status=$status, delivery=${deliveryId != null}, " +
            "assignment=${assignmentId != null}, token=REDACTED, authorityEpoch=$authorityEpoch)"
}

sealed interface DispatchHandoffIssueResult {
    data class Issued(val identity: DispatchHandoffIdentity, val token: String) :
        DispatchHandoffIssueResult {
        override fun toString(): String = "DispatchHandoffIssueResult.Issued(token=REDACTED)"
    }

    data class AcceptedWithoutToken(val identity: DispatchHandoffIdentity) :
        DispatchHandoffIssueResult
    data class Rejected(val code: String?) : DispatchHandoffIssueResult
    data object NotFound : DispatchHandoffIssueResult
    data object UnknownOutcome : DispatchHandoffIssueResult
    data object Unavailable : DispatchHandoffIssueResult
    data object PermissionDenied : DispatchHandoffIssueResult
    data object ContextInvalidated : DispatchHandoffIssueResult
    data object SessionInvalidated : DispatchHandoffIssueResult
}

sealed interface DispatchHandoffValidationResult {
    data class Validated(val identity: DispatchHandoffIdentity) : DispatchHandoffValidationResult
    data class Rejected(val code: String?) : DispatchHandoffValidationResult
    data object NotFound : DispatchHandoffValidationResult
    data object Unavailable : DispatchHandoffValidationResult
    data object PermissionDenied : DispatchHandoffValidationResult
    data object ContextInvalidated : DispatchHandoffValidationResult
    data object SessionInvalidated : DispatchHandoffValidationResult
}

sealed interface DispatchHandoffMetadataRead {
    data class Available(val command: DispatchHandoffIdentityCommand?) : DispatchHandoffMetadataRead
    data object Unavailable : DispatchHandoffMetadataRead
}

enum class DispatchHandoffMetadataWrite { Saved, Conflict, Stale, Unavailable }

interface DispatchHandoffIdentityMetadataStore {
    suspend fun load(
        scope: DispatchAuthorityIdentity,
        deliveryId: String,
        assignmentId: String
    ): DispatchHandoffMetadataRead

    suspend fun persistIntent(
        scope: DispatchAuthorityIdentity,
        command: DispatchHandoffIdentityCommand,
        replacingIdempotencyKey: String? = null
    ): DispatchHandoffMetadataWrite

    suspend fun clearCommand(
        scope: DispatchAuthorityIdentity,
        deliveryId: String,
        assignmentId: String,
        idempotencyKey: String
    ): DispatchHandoffMetadataWrite
}

interface DispatchHandoffIdentityGateway {
    suspend fun issue(
        command: DispatchHandoffIdentityCommand,
        context: DispatchAuthorityContext
    ): DispatchHandoffIssueResult

    suspend fun validate(
        deliveryId: String,
        assignmentId: String,
        token: String,
        context: DispatchAuthorityContext
    ): DispatchHandoffValidationResult
}

internal const val MAX_DISPATCH_HANDOFF_TOKEN_LENGTH = 400
internal val DISPATCH_HANDOFF_UUID =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
