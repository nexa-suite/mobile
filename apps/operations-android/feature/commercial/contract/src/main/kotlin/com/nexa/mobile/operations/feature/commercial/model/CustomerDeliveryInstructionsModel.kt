package com.nexa.mobile.operations.feature.commercial.model

data class CustomerInstructionRow(
    val id: String,
    val version: Long,
    val kind: String,
    val content: String,
    val source: String,
    val recordedBy: String,
    val recordedAt: String
)

data class CustomerInstructionSnapshot(
    val orderId: String,
    val version: Long,
    val editable: Boolean,
    val rows: List<CustomerInstructionRow>
)

data class CustomerInstructionCommand(
    val orderId: String,
    val version: Long,
    val instructionId: String,
    val kind: String,
    val content: String,
    val source: String,
    val key: String
) {
    override fun toString() = "CustomerInstructionCommand(REDACTED)"
}
