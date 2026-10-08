package com.nexa.mobile.operations.salescommitment.presentation.commercial

import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestRecord

enum class FieldRequestStatus {
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

data class FieldRequestState(
    val record: FieldRequestRecord = FieldRequestRecord(),
    val status: FieldRequestStatus = FieldRequestStatus.Loading,
    val productId: String = "",
    val quantity: String = "1"
)
