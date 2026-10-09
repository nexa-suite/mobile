package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectOrderRecordCodecTest {
    @Test
    fun schemaOneWithoutOperationRemainsLegacyWithExactFrozenBytes() {
        val record = DirectOrderRecordCodec.decode(legacyRecord)!!
        val intent = record.intent!!
        assertEquals(DirectOrderIntent.LEGACY_FIELD_REQUEST_OPERATION, intent.operation)
        assertEquals("legacy-key", intent.key)
        assertEquals(frozenBody, intent.exactBody)
        val reread = DirectOrderRecordCodec.decode(DirectOrderRecordCodec.encode(record))!!
        assertEquals(record, reread)
    }

    @Test
    fun directOrderUnknownOutcomeRoundTripKeepsExactKeyBodyAndOperation() {
        val record = DirectOrderRecord(
            intent = DirectOrderIntent(
                key = "direct-key",
                exactBody = frozenBody,
                outcome = "UnknownOutcome"
            )
        )
        val encoded = DirectOrderRecordCodec.encode(record)
        assertEquals(record, DirectOrderRecordCodec.decode(encoded))
        val intent = Json.parseToJsonElement(encoded).jsonObject.getValue("intent").jsonObject
        assertEquals("direct-order-v1", intent.getValue("operation").jsonPrimitive.content)
        assertEquals(frozenBody, intent.getValue("body").jsonPrimitive.content)
    }

    @Test
    fun unsupportedSchemaAndMalformedFrozenBodyFailClosed() {
        assertNull(
            DirectOrderRecordCodec.decode(legacyRecord.replace("\"schema\":1", "\"schema\":2"))
        )
        assertNull(
            DirectOrderRecordCodec.decode(
                legacyRecord.replace("{ \\\"legacy\\\": true }", "not-json")
            )
        )
    }

    private val frozenBody = "{ \"legacy\": true }"
    private val legacyRecord = """
        {"schema":1,"draft":{
          "customerId":"customer-1","lines":[],"deliveryDate":"",
          "deliveryProfile":"","paymentOption":"CASH_ON_DELIVERY","comment":""
        },"intent":{
          "key":"legacy-key","body":"{ \"legacy\": true }","outcome":"UnknownOutcome"
        }}
    """.trimIndent()
}
