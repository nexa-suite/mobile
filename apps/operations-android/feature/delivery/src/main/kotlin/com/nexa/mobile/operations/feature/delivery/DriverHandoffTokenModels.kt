package com.nexa.mobile.operations.feature.delivery

import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffCurrentDelivery
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffIssueCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffTokenReceipt

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
