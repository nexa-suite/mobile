package com.nexa.mobile.operations.inventoryavailability.infrastructure.serialization.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyDraft
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.InboundDiscrepancyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalInboundDiscrepancyPayloadCodecTest {
    private val codec = CanonicalInboundDiscrepancyPayloadCodec()

    @Test
    fun createBodyMatchesCanonicalGoldenWithNullsNumericLexemesAndEscapedText() {
        val draft = draft().copy(
            expectedSkuId = "",
            observedBatchReference = "",
            reasonDetails = "broken \"seal\"\\line\nnext",
            observationNotes = "line 1\nline 2\t\u0001"
        )

        assertEquals(
            """{"warehouseId":"$WAREHOUSE_ID","expectedSkuId":null,"observedSkuId":"$SKU_ID","expectedBatchReference":"LOT-17","observedBatchReference":null,"expectedQuantity":10.2500,"observedQuantity":9.7500,"unit":"UNIT","reason":"DAMAGE: broken \"seal\"\\line\nnext","observationNotes":"line 1\nline 2\t\u0001"}""",
            codec.createBody(draft)
        )
    }

    @Test
    fun submitBodyMatchesCanonicalGolden() {
        assertEquals(
            """{"evidenceObjectId":"$EVIDENCE_ID"}""",
            codec.submitBody(EVIDENCE_ID)
        )
    }

    @Test
    fun frozenBodiesMustMatchStagedFactsAndHavePairedValidKeys() {
        val editable = draft()
        val frozen = editable.copy(
            createIdempotencyKey = "create-key",
            createBody = codec.createBody(editable)
        )
        assertTrue(codec.isValid(frozen))
        assertFalse(codec.isValid(frozen.copy(expectedQuantityText = "10.5000")))
        assertFalse(codec.isValid(frozen.copy(createBody = "{}")))
        assertFalse(codec.isValid(editable.copy(createIdempotencyKey = "orphan-key")))
        assertFalse(
            codec.isValid(
                frozen.copy(createIdempotencyKey = "x".repeat(MAX_IDEMPOTENCY_KEY_LENGTH + 1))
            )
        )

        val submitted = frozen.copy(
            evidenceId = EVIDENCE_ID,
            submitIdempotencyKey = "submit-key",
            submitBody = codec.submitBody(EVIDENCE_ID)
        )
        assertTrue(codec.isValid(submitted))
        assertFalse(codec.isValid(submitted.copy(evidenceId = OTHER_EVIDENCE_ID)))
        assertFalse(codec.isValid(submitted.copy(submitBody = "{}")))
        assertFalse(codec.isValid(frozen.copy(submitIdempotencyKey = "orphan-key")))
    }

    private fun draft() = InboundDiscrepancyDraft(
        id = "draft-1",
        warehouseId = WAREHOUSE_ID,
        expectedSkuId = SKU_ID,
        observedSkuId = SKU_ID,
        expectedBatchReference = "LOT-17",
        observedBatchReference = "LOT-18",
        expectedQuantityText = "10.2500",
        observedQuantityText = "9.7500",
        unit = "UNIT",
        kind = InboundDiscrepancyKind.Damage,
        reasonDetails = "",
        observationNotes = "",
        capturedAtDeviceMillis = 1_727_700_000_000
    )

    private companion object {
        const val WAREHOUSE_ID = "00000000-0000-0000-0000-000000000050"
        const val SKU_ID = "00000000-0000-0000-0000-000000000051"
        const val EVIDENCE_ID = "00000000-0000-0000-0000-000000000052"
        const val OTHER_EVIDENCE_ID = "00000000-0000-0000-0000-000000000053"
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 160
    }
}
