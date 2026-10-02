package com.nexa.mobile.operations.commercial

import java.time.LocalDate

data class FieldRequestLine(
    val catalogItemId: String,
    val name: String,
    val quantity: String,
    val unit: String?,
    val price: String,
    val currency: String,
    val asOf: String
)
data class FieldRequestDraft(
    val customerId: String = "",
    val customerVersion: Long? = null,
    val lines: List<FieldRequestLine> = emptyList(),
    val deliveryDate: String = "",
    val deliveryProfile: String = "",
    val paymentOption: String = "CASH_ON_DELIVERY",
    val comment: String = ""
) {
    fun valid(): Boolean = customerId.isNotBlank() && lines.isNotEmpty() && lines.size <= 100 &&
        lines.all { it.quantity.toBigDecimalOrNull()?.signum() == 1 } &&
        runCatching { LocalDate.parse(deliveryDate) }.isSuccess && deliveryProfile.isNotBlank()
    override fun toString(): String = "FieldRequestDraft(REDACTED)"
}
data class FieldRequestIntent(
    val key: String,
    val exactBody: String,
    val outcome: String = "Pending",
    val receiptId: String? = null
) {
    override fun toString(): String = "FieldRequestIntent(REDACTED)"
}
data class FieldRequestRecord(
    val draft: FieldRequestDraft = FieldRequestDraft(),
    val intent: FieldRequestIntent? = null
)
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
sealed interface FieldRequestRead {
    data class Available(val record: FieldRequestRecord) : FieldRequestRead
    data object Unavailable : FieldRequestRead
}
interface FieldRequestStore {
    suspend fun load(authority: CommercialAuthority): FieldRequestRead
    suspend fun save(authority: CommercialAuthority, record: FieldRequestRecord): Boolean
}
sealed interface FieldRequestReview {
    data class Current(val draft: FieldRequestDraft) : FieldRequestReview
    data object PermissionDenied : FieldRequestReview
    data object Unavailable : FieldRequestReview
}
sealed interface FieldRequestSubmission {
    data class Confirmed(val id: String) : FieldRequestSubmission
    data object Conflict : FieldRequestSubmission
    data object PermissionDenied : FieldRequestSubmission
    data object Rejected : FieldRequestSubmission
    data object UnknownOutcome : FieldRequestSubmission
}
interface FieldRequestGateway {
    suspend fun quote(
        authority: CommercialAuthority,
        customerId: String,
        productId: String,
        quantity: String
    ): FieldRequestLine?
    suspend fun review(authority: CommercialAuthority, draft: FieldRequestDraft): FieldRequestReview
    fun freeze(draft: FieldRequestDraft): String
    suspend fun submit(
        authority: CommercialAuthority,
        intent: FieldRequestIntent
    ): FieldRequestSubmission
}
