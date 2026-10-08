package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyDraft

/** Encodes frozen discrepancy commands behind the transport boundary. */
interface InboundDiscrepancyPayloadCodec {
    fun createBody(draft: InboundDiscrepancyDraft): String
    fun submitBody(evidenceId: String): String
    fun isValid(draft: InboundDiscrepancyDraft): Boolean
}
