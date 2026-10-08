package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

data class DispatchPlanChangeSnapshot(
    val readiness: DispatchReadiness,
    val candidates: List<DispatchDriverCandidate>,
    val assignment: PreparedFulfillmentDriverAssignment?,
    val history: List<PreparedFulfillmentDriverAssignment>
)
