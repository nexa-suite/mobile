package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderReceipt
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderDraft
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Stable schema-1 codec; absent operation markers remain legacy Purchase Request intents. */
internal object DirectOrderRecordCodec {
    fun encode(record: DirectOrderRecord): String = buildJsonObject {
        put("schema", 1)
        putJsonObject("draft") {
            val draft = record.draft
            put("customerId", draft.customerId)
            draft.customerVersion?.let { put("customerVersion", it) }
            put("deliveryDate", draft.deliveryDate)
            put("deliveryProfile", draft.deliveryProfile)
            put("paymentOption", draft.paymentOption)
            put("comment", draft.comment)
            putJsonArray("lines") {
                draft.lines.forEach { line ->
                    add(
                        buildJsonObject {
                            put("id", line.catalogItemId)
                            put("name", line.name)
                            put("quantity", line.quantity)
                            line.unit?.let { put("unit", it) }
                            put("price", line.price)
                            put("currency", line.currency)
                            put("asOf", line.asOf)
                        }
                    )
                }
            }
        }
        record.intent?.let { intent ->
            putJsonObject("intent") {
                put("key", intent.key)
                put("body", intent.exactBody)
                put("outcome", intent.outcome)
                put("operation", intent.operation)
                intent.receiptId?.let { put("receiptId", it) }
                intent.receipt?.let { receipt ->
                    putJsonObject("receipt") {
                        put("id", receipt.id)
                        put("number", receipt.number)
                        put("status", receipt.status)
                        put("paymentOption", receipt.paymentOption)
                        put("currency", receipt.currency)
                        put("total", receipt.total)
                        put("version", receipt.version)
                    }
                }
            }
        }
    }.toString()
    fun decode(payload: String): DirectOrderRecord? = runCatching {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root["schema"]?.jsonPrimitive?.int == 1)
        val d = root.getValue("draft").jsonObject
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        val lines = d.getValue("lines").jsonArray.map { value ->
            val l = value.jsonObject
            DirectOrderLine(
                l.text("id"),
                l.text("name"),
                l.text("quantity"),
                l["unit"]?.jsonPrimitive?.content,
                l.text("price"),
                l.text("currency"),
                l.text("asOf")
            )
        }
        require(lines.size <= 100)
        val intent = root["intent"]?.jsonObject?.let { i ->
            require(
                i.text("key").isNotBlank() &&
                    i.text("outcome") in setOf(
                        "Pending",
                        "UnknownOutcome",
                        "Confirmed",
                        "PrepaidPending",
                        "Conflict",
                        "PermissionDenied",
                        "Rejected",
                        "Unavailable",
                        "LegacyIntent"
                    )
            )
            require(Json.parseToJsonElement(i.text("body")) is JsonObject)
            val receipt = i["receipt"]?.jsonObject?.let { value ->
                DirectOrderReceipt(
                    value.text("id"),
                    value.text("number"),
                    value.text("status"),
                    value.text("paymentOption"),
                    value.text("currency"),
                    value.text("total"),
                    value.getValue("version").jsonPrimitive.long
                )
            }
            DirectOrderIntent(
                i.text("key"),
                i.text("body"),
                i.text("outcome"),
                i["receiptId"]?.jsonPrimitive?.content,
                i["operation"]?.jsonPrimitive?.content
                    ?: DirectOrderIntent.LEGACY_FIELD_REQUEST_OPERATION,
                receipt
            )
        }
        DirectOrderRecord(
            DirectOrderDraft(
                d.text("customerId"),
                d["customerVersion"]?.jsonPrimitive?.long,
                lines,
                d.text("deliveryDate"),
                d.text("deliveryProfile"),
                d.text("paymentOption"),
                d.text("comment")
            ),
            intent
        )
    }.getOrNull()
}
