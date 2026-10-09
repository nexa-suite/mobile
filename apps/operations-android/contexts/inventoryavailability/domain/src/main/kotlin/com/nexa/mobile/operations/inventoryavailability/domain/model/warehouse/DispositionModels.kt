package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class LotDispositionAction { RELEASE, HOLD, WASTE, RETURN_TO_SUPPLIER }

/** Explicit temperature-evaluation scope for a partial disposition. */

data class PartialDispositionEvaluation(
    val temperatureEvaluationId: String,
    val affectedQuantity: BigDecimal
) {
    init {
        require(DISPOSITION_UUID_PATTERN.matches(temperatureEvaluationId))
        require(
            affectedQuantity.signum() > 0 && affectedQuantity.scale() <= MAX_QUANTITY_SCALE &&
                affectedQuantity.fitsDispositionPrecision()
        )
    }

    override fun toString(): String = "PartialDispositionEvaluation(values=REDACTED)"

    companion object {
        const val MAX_QUANTITY_SCALE = 4
        const val MAX_QUANTITY_PRECISION = 19
    }
}

private fun BigDecimal.fitsDispositionPrecision(): Boolean {
    val precisionAtScale = precision().toLong() +
        (PartialDispositionEvaluation.MAX_QUANTITY_SCALE.toLong() - scale().toLong())
    return precisionAtScale <= PartialDispositionEvaluation.MAX_QUANTITY_PRECISION
}

/** Actual server lot projection; quantities are not derived or persisted locally. */

data class DispositionLotFacts(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val expirationDate: LocalDate,
    val receivedAt: Instant,
    val onHand: BigDecimal,
    val reserved: BigDecimal,
    val available: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String =
        "DispositionLotFacts(id=REDACTED, status=$status, version=$version)"
}

/** Frozen body and optimistic version sent to the existing lot-disposition route. */

data class LotDispositionCommand(
    val lotId: String,
    val disposition: LotDispositionAction,
    val reason: String,
    val expectedVersion: Long,
    val partialEvaluation: PartialDispositionEvaluation? = null
) {
    init {
        require(lotId.isUuid())
        require(reason.isNotBlank() && reason == reason.trim() && reason.length <= 2_000)
        require(expectedVersion >= 0)
    }

    override fun toString(): String =
        "LotDispositionCommand(lotId=REDACTED, disposition=$disposition, " +
            "partial=${partialEvaluation != null}, reason=REDACTED)"
}

private val DISPOSITION_UUID_PATTERN =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun String.isUuid(): Boolean = DISPOSITION_UUID_PATTERN.matches(this)
