package com.nexa.mobile.operations.payments.infrastructure.adapters

import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntent
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Owns exact frozen-request bytes and integrity checks at the serialization boundary. */
internal object WalletRefundIntentIntegrity {
    private val json = Json
    private val uuidRegex = Regex(
        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    )
    private val hashRegex = Regex("[0-9a-f]{64}")

    fun freeze(salesOrderId: String, amountPEN: String, reason: String): WalletRefundIntent {
        val exactPayload = encodePayload(amountPEN, reason)
        return WalletRefundIntent(
            salesOrderId = salesOrderId,
            amountPEN = amountPEN,
            reason = reason,
            idempotencyKey = UUID.randomUUID().toString(),
            payloadHash = exactPayload.sha256(),
            exactPayload = exactPayload
        )
    }

    fun isValid(intent: WalletRefundIntent): Boolean = runCatching {
        if (!uuidRegex.matches(intent.idempotencyKey) ||
            !uuidRegex.matches(intent.salesOrderId) ||
            !hashRegex.matches(intent.payloadHash) ||
            intent.reason.isBlank() || intent.reason.length > MAX_REASON_LENGTH
        ) {
            return false
        }
        val amount = intent.amountPEN.toBigDecimalOrNull() ?: return false
        if (amount.signum() <= 0 || amount.scale() > MAX_SCALE ||
            amount.toPlainString() != intent.amountPEN
        ) {
            return false
        }
        if (intent.exactPayload != encodePayload(intent.amountPEN, intent.reason)) return false
        val body = json.parseToJsonElement(intent.exactPayload).jsonObject
        body.hasExactFields(intent)
    }.getOrDefault(false)

    private fun JsonObject.hasExactFields(intent: WalletRefundIntent): Boolean {
        if (keys != setOf("amountPEN", "reason")) return false
        val amount = get("amountPEN") as? JsonPrimitive ?: return false
        val reason = get("reason") as? JsonPrimitive ?: return false
        return !amount.isString && amount.content == intent.amountPEN &&
            reason.isString && reason.content == intent.reason &&
            intent.exactPayload.sha256() == intent.payloadHash
    }

    private fun encodePayload(amountPEN: String, reason: String): String = buildJsonObject {
        put("amountPEN", JsonPrimitive(BigDecimal(amountPEN)))
        put("reason", JsonPrimitive(reason))
    }.toString()

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val MAX_SCALE = 4
    private const val MAX_REASON_LENGTH = 500
}
