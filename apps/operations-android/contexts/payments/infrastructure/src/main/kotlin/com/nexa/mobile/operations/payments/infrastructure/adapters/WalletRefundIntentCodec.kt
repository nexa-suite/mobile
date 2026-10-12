package com.nexa.mobile.operations.payments.infrastructure.adapters

import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Versioned codec for the exact request bytes required by explicit retries. */
internal object WalletRefundIntentCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(intents: List<WalletRefundIntent>): String = buildJsonObject {
        put("version", VERSION)
        put(
            "intents",
            JsonArray(
                intents.map { intent ->
                    buildJsonObject {
                        put("salesOrderId", intent.salesOrderId)
                        put("amountPEN", intent.amountPEN)
                        put("reason", intent.reason)
                        put("idempotencyKey", intent.idempotencyKey)
                        put("payloadHash", intent.payloadHash)
                        put("exactPayload", intent.exactPayload)
                    }
                }
            )
        )
    }.toString()

    fun decode(payload: String): List<WalletRefundIntent>? = runCatching {
        val root = json.parseToJsonElement(payload).jsonObject
        if (root.keys != setOf("version", "intents") ||
            root["version"].asInt() != VERSION
        ) {
            return null
        }
        val items = root["intents"]?.jsonArray ?: return null
        if (items.size > MAX_INTENTS) return null
        val decoded = items.map { it.jsonObject.toIntent() ?: return null }
        if (decoded.map { it.idempotencyKey }.distinct().size != decoded.size ||
            decoded.map { it.salesOrderId }.distinct().size != decoded.size
        ) {
            return null
        }
        decoded
    }.getOrNull()

    private fun JsonObject.toIntent(): WalletRefundIntent? {
        if (keys != setOf(
                "salesOrderId",
                "amountPEN",
                "reason",
                "idempotencyKey",
                "payloadHash",
                "exactPayload"
            )
        ) {
            return null
        }
        val intent = WalletRefundIntent(
            salesOrderId = get("salesOrderId").asString() ?: return null,
            amountPEN = get("amountPEN").asString() ?: return null,
            reason = get("reason").asString() ?: return null,
            idempotencyKey = get("idempotencyKey").asString() ?: return null,
            payloadHash = get("payloadHash").asString() ?: return null,
            exactPayload = get("exactPayload").asString() ?: return null
        )
        return intent.takeIf(WalletRefundIntentIntegrity::isValid)
    }

    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonElement?.asInt(): Int? =
        (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private const val VERSION = 1
    private const val MAX_INTENTS = 100
}
