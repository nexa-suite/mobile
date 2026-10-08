package com.nexa.mobile.operations.inventoryavailability.infrastructure.serialization.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyDraft
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.InboundDiscrepancyPayloadCodec

class CanonicalInboundDiscrepancyPayloadCodec : InboundDiscrepancyPayloadCodec {
    override fun createBody(draft: InboundDiscrepancyDraft): String = with(draft) {
        buildString {
            append('{')
            append("\"warehouseId\":\"").append(warehouseId.jsonEscaped()).append("\",")
            append("\"expectedSkuId\":").append(
                expectedSkuId.trim().takeIf(String::isNotEmpty)?.let {
                    "\"${it.jsonEscaped()}\""
                }
                    ?: "null"
            ).append(',')
            append("\"observedSkuId\":\"").append(observedSkuId.trim().jsonEscaped()).append("\",")
            append("\"expectedBatchReference\":").append(
                expectedBatchReference.trim().takeIf(String::isNotEmpty)?.let {
                    "\"${it.jsonEscaped()}\""
                }
                    ?: "null"
            ).append(',')
            append("\"observedBatchReference\":").append(
                observedBatchReference.trim().takeIf(String::isNotEmpty)?.let {
                    "\"${it.jsonEscaped()}\""
                }
                    ?: "null"
            ).append(',')
            append("\"expectedQuantity\":").append(expectedQuantityText.trim()).append(',')
            append("\"observedQuantity\":").append(observedQuantityText.trim()).append(',')
            append("\"unit\":\"").append(unit.trim().jsonEscaped()).append("\",")
            val reason = buildString {
                append(kind.apiReason)
                if (reasonDetails.isNotBlank()) append(": ").append(reasonDetails.trim())
            }
            append("\"reason\":\"").append(reason.jsonEscaped()).append("\",")
            append("\"observationNotes\":").append(
                observationNotes.trim().takeIf(String::isNotEmpty)?.let {
                    "\"${it.jsonEscaped()}\""
                }
                    ?: "null"
            )
            append('}')
        }
    }

    override fun submitBody(evidenceId: String): String =
        "{\"evidenceObjectId\":\"${evidenceId.jsonEscaped()}\"}"

    override fun isValid(draft: InboundDiscrepancyDraft): Boolean = runCatching {
        val create = draft.createBody
        val submit = draft.submitBody
        (
            (create == null && draft.createIdempotencyKey == null) ||
                (
                    create != null && draft.createIdempotencyKey.isValidIdempotencyKey() &&
                        create == createBody(draft)
                    )
            ) &&
            (
                (submit == null && draft.submitIdempotencyKey == null) ||
                    (
                        submit != null && draft.submitIdempotencyKey.isValidIdempotencyKey() &&
                            draft.evidenceId?.let { submit == submitBody(it) } == true
                        )
                )
    }.getOrDefault(false)

    private fun String?.isValidIdempotencyKey(): Boolean =
        this?.let { it.isNotBlank() && it.length <= MAX_IDEMPOTENCY_KEY_LENGTH } == true

    private fun String.jsonEscaped(): String = buildString(length) {
        for (character in this@jsonEscaped) {
            when (character) {
                '\\' -> append("\\\\")

                '"' -> append("\\\"")

                '\b' -> append("\\b")

                '\u000C' -> append("\\f")

                '\n' -> append("\\n")

                '\r' -> append("\\r")

                '\t' -> append("\\t")

                else ->
                    if (character.code <
                        0x20
                    ) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
            }
        }
    }

    private companion object {
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 160
    }
}
