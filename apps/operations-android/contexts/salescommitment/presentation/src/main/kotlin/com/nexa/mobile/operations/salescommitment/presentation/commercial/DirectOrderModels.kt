package com.nexa.mobile.operations.salescommitment.presentation.commercial

import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord

enum class DirectOrderStatus {
    Loading,
    Draft,
    Reviewing,
    Reviewed,
    Changed,
    Pending,
    PrepaidPending,
    UnknownOutcome,
    Confirmed,
    Conflict,
    Rejected,
    LegacyIntent,
    PermissionDenied,
    Unavailable,
    MetadataUnavailable
}

internal val DirectOrderStatus.permitsNewDecision: Boolean
    get() = when (this) {
        DirectOrderStatus.Confirmed,
        DirectOrderStatus.Conflict,
        DirectOrderStatus.PermissionDenied,
        DirectOrderStatus.Rejected,
        DirectOrderStatus.Unavailable,
        DirectOrderStatus.PrepaidPending -> true

        else -> false
    }

data class DirectOrderState(
    val record: DirectOrderRecord = DirectOrderRecord(),
    val status: DirectOrderStatus = DirectOrderStatus.Loading,
    val productId: String = "",
    val quantity: String = "1"
)
