package com.nexa.mobile.operations.businessdocuments.domain.model.commercial

data class BusinessDocumentIdentity(
    val id: String,
    val customerId: String,
    val subjectType: String,
    val subjectId: String,
    val number: String?,
    val type: String,
    val version: Long,
    val generatedAt: String?,
    val checksum: String,
    val byteSize: Long,
    val format: String = "PDF",
    val contentType: String = "application/pdf"
)

data class BusinessDocumentContent(val identity: BusinessDocumentIdentity, val bytes: ByteArray) {
    override fun toString(): String = "BusinessDocumentContent(REDACTED)"
}
