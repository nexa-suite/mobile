package com.nexa.mobile.operations.customerbuyerrelationships.domain.model.commercial

data class FieldVisitIntent(
    val key: String,
    val customerId: String,
    val version: Long,
    val body: String,
    val outcome: String = "Pending",
    val receiptId: String? = null
) {
    override fun toString(): String = "FieldVisitIntent(REDACTED)"
}

data class FieldVisitRecord(
    val customerId: String = "",
    val purpose: String = "",
    val followUp: String = "",
    val intent: FieldVisitIntent? = null
) {
    override fun toString(): String = "FieldVisitRecord(REDACTED)"
}
