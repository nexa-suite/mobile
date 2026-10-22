package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraft
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraftLine
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

internal object FieldRequestDraftCodec {
    fun encode(draft: FieldRequestDraft): String = buildJsonObject {
        put("schema", 1)
        put("customerId", draft.customerId)
        draft.customerName?.let { put("customerName", it) }
        draft.customerVersion?.let { put("customerVersion", it) }
        putJsonArray("lines") {
            draft.lines.forEach { line ->
                add(
                    buildJsonObject {
                        put("catalogItemId", line.catalogItemId)
                        put("productId", line.productId)
                        put("name", line.name)
                        line.skuCode?.let { put("skuCode", it) }
                        line.brandName?.let { put("brandName", it) }
                        line.categoryName?.let { put("categoryName", it) }
                        line.familyName?.let { put("familyName", it) }
                        line.variantName?.let { put("variantName", it) }
                        line.unit?.let { put("unit", it) }
                        put("quantity", line.quantity)
                        line.price?.let { put("price", it) }
                        line.currency?.let { put("currency", it) }
                        line.pricingAsOf?.let { put("pricingAsOf", it) }
                        line.availability?.let { put("availability", it) }
                        line.availabilityStatus?.let { put("availabilityStatus", it) }
                        line.availabilityAsOf?.let { put("availabilityAsOf", it) }
                    }
                )
            }
        }
    }.toString()

    fun decode(payload: String): FieldRequestDraft? = runCatching {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root["schema"]?.jsonPrimitive?.int == 1)
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        fun JsonObject.optionalText(key: String) = get(key)?.jsonPrimitive?.content
        val customerId = root.getValue("customerId").jsonPrimitive.content
        require(customerId.length <= 200)
        val customerName = root["customerName"]?.jsonPrimitive?.content?.also {
            require(it.isNotBlank() && it.length <= 240)
        }
        val customerVersion = root["customerVersion"]?.jsonPrimitive?.long?.also {
            require(it >= 0)
        }
        val lines = root.getValue("lines").jsonArray.map { value ->
            val line = value.jsonObject
            FieldRequestDraftLine(
                catalogItemId = line.text("catalogItemId").bounded(80),
                productId = line.text("productId").bounded(120),
                name = line.text("name").bounded(240),
                skuCode = line.optionalText("skuCode").boundedOrNull(120),
                brandName = line.optionalText("brandName").boundedOrNull(160),
                categoryName = line.optionalText("categoryName").boundedOrNull(160),
                familyName = line.optionalText("familyName").boundedOrNull(160),
                variantName = line.optionalText("variantName").boundedOrNull(160),
                unit = line.optionalText("unit").boundedOrNull(80),
                quantity = line.text("quantity").boundedOrEmpty(32),
                price = line.optionalText("price").boundedOrNull(40),
                currency = line.optionalText("currency").boundedOrNull(3),
                pricingAsOf = line.optionalText("pricingAsOf").boundedOrNull(80),
                availability = line.optionalText("availability").boundedOrNull(40),
                availabilityStatus = line.optionalText("availabilityStatus").boundedOrNull(80),
                availabilityAsOf = line.optionalText("availabilityAsOf").boundedOrNull(80)
            )
        }
        require(lines.size <= 100)
        FieldRequestDraft(customerId, lines, customerName, customerVersion)
    }.getOrNull()

    private fun String.bounded(max: Int): String {
        require(isNotBlank() && length <= max)
        return this
    }

    private fun String.boundedOrEmpty(max: Int): String {
        require(length <= max)
        return this
    }

    private fun String?.boundedOrNull(max: Int): String? = this?.also {
        require(it.isNotBlank() && it.length <= max)
    }
}
