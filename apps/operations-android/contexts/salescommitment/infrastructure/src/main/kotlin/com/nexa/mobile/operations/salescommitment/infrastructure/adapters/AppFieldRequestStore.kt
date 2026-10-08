package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestStore
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.commercial.CommercialAuthority
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.FieldRequestDraft
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestIntent
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.FieldRequestLine
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestRecord
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestReceipt
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Encrypted scope-bound drafts are unconfirmed; the frozen write body is never regenerated on replay. */
@Singleton
class AppFieldRequestStore @Inject constructor(@ApplicationContext context: Context) :
    FieldRequestStore {
    private val local =
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.FieldPurchaseRequest)
    private val mutex = Mutex()
    private fun CommercialAuthority.scope() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
    override suspend fun load(authority: CommercialAuthority): FieldRequestRead = mutex.withLock {
        when (val stored = local.load(authority.scope())) {
            ScopedMetadataRead.Unavailable -> FieldRequestRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload =
                    stored.payload
                        ?: return@withLock FieldRequestRead.Available(FieldRequestRecord())
                val record = decode(payload) ?: return@withLock FieldRequestRead.Unavailable
                val intent = record.intent
                if (intent?.outcome == "Pending") {
                    val recovered = record.copy(
                        intent = intent.copy(outcome = "UnknownOutcome")
                    )
                    if (!local.save(authority.scope(), encode(recovered))) {
                        FieldRequestRead.Unavailable
                    } else {
                        FieldRequestRead.Available(recovered)
                    }
                } else {
                    FieldRequestRead.Available(record)
                }
            }
        }
    }
    override suspend fun save(authority: CommercialAuthority, record: FieldRequestRecord): Boolean =
        mutex.withLock {
            val stored = local.load(authority.scope())
            if (stored !is ScopedMetadataRead.Value) return@withLock false
            val before = stored.payload?.let(::decode)
            if (stored.payload != null && before == null) return@withLock false
            val prior = before?.intent
            val intent = record.intent
            if (prior?.operation == FieldRequestIntent.DIRECT_ORDER_OPERATION &&
                prior.outcome !in setOf(
                    "Confirmed",
                    "PrepaidPending",
                    "Conflict",
                    "PermissionDenied",
                    "Rejected",
                    "Unavailable"
                )
            ) {
                val changed = intent == null || intent.key != prior.key ||
                    intent.exactBody != prior.exactBody
                if (changed) return@withLock false
            }
            local.save(authority.scope(), encode(record))
        }
    private fun encode(record: FieldRequestRecord): String = buildJsonObject {
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
    private fun decode(payload: String): FieldRequestRecord? = runCatching {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root["schema"]?.jsonPrimitive?.int == 1)
        val d = root.getValue("draft").jsonObject
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        val lines = d.getValue("lines").jsonArray.map { value ->
            val l = value.jsonObject
            FieldRequestLine(
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
                FieldRequestReceipt(
                    value.text("id"),
                    value.text("number"),
                    value.text("status"),
                    value.text("paymentOption"),
                    value.text("currency"),
                    value.text("total"),
                    value.getValue("version").jsonPrimitive.long
                )
            }
            FieldRequestIntent(
                i.text("key"),
                i.text("body"),
                i.text("outcome"),
                i["receiptId"]?.jsonPrimitive?.content,
                i["operation"]?.jsonPrimitive?.content
                    ?: FieldRequestIntent.LEGACY_FIELD_REQUEST_OPERATION,
                receipt
            )
        }
        FieldRequestRecord(
            FieldRequestDraft(
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
