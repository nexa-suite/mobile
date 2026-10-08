package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchHandoffIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffIdentityCommand

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

internal const val MAX_DISPATCH_HANDOFF_TOKEN_LENGTH = 400
