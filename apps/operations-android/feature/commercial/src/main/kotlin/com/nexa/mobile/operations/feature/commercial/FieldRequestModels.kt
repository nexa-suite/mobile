package com.nexa.mobile.operations.feature.commercial

import com.nexa.mobile.operations.feature.commercial.model.FieldRequestRecord

enum class FieldRequestStatus {
    Loading,
    Draft,
    Reviewing,
    Reviewed,
    Changed,
    Pending,
    UnknownOutcome,
    Confirmed,
    Conflict,
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
