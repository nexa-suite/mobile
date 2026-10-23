package com.nexa.mobile.operations.salescommitment.presentation.commercial

import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderDeliveryLeadPolicy
import com.nexa.mobile.operations.salescommitment.application.commercial.isValidForSubmission
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import java.time.LocalDate

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

enum class DirectOrderDeliveryPolicyStatus {
    Loading,
    Available,
    PermissionDenied,
    Unavailable
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
    val quantity: String = "1",
    val deliveryLeadPolicy: DirectOrderDeliveryLeadPolicy? = null,
    val deliveryPolicyStatus: DirectOrderDeliveryPolicyStatus =
        DirectOrderDeliveryPolicyStatus.Loading
)

internal fun DirectOrderState.isDeliveryDateBeforeMinimum(): Boolean {
    val policy = deliveryLeadPolicy ?: return false
    val requested = runCatching { LocalDate.parse(record.draft.deliveryDate) }.getOrNull()
        ?: return false
    return requested.isBefore(policy.earliestAllowedDeliveryDate)
}

internal fun DirectOrderState.canReviewDirectOrder(): Boolean =
    deliveryPolicyStatus == DirectOrderDeliveryPolicyStatus.Available &&
        deliveryLeadPolicy != null && !isDeliveryDateBeforeMinimum() &&
        record.intent == null &&
        status !in setOf(
            DirectOrderStatus.Loading,
            DirectOrderStatus.MetadataUnavailable,
            DirectOrderStatus.Reviewing,
            DirectOrderStatus.Pending
        ) && record.draft.isValidForSubmission()
