package com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse

import java.math.BigDecimal
import java.time.Instant

enum class TemperatureEvidenceSubjectType { LOT, WAREHOUSE }

enum class TemperatureEvidenceUnit { CELSIUS, FAHRENHEIT }

data class TemperatureEvidenceSubject(
    val id: String,
    val type: TemperatureEvidenceSubjectType,
    val primaryLabel: String,
    val detailLabel: String,
    val warehouseId: String? = null,
    val lotVersion: Long? = null,
    val physicalRemaining: BigDecimal? = null,
    val quantityUnit: String? = null
) {
    init {
        require(id.isNotBlank() && primaryLabel.isNotBlank())
        require(warehouseId == null || warehouseId.isNotBlank())
        require(lotVersion == null || lotVersion >= 0)
        require(physicalRemaining == null || physicalRemaining.signum() >= 0)
    }
}

data class TemperatureEvidencePayload(
    val subjectType: TemperatureEvidenceSubjectType,
    val subjectId: String,
    val value: String,
    val unit: TemperatureEvidenceUnit,
    val occurredAt: String,
    val evidenceObjectId: String? = null,
    val expectedLotVersion: Long? = null,
    val affectedQuantity: String? = null,
    val reason: String? = null,
    val sourceEvidenceId: String? = null
) {
    init {
        require(subjectId.isNotBlank() && value.isNotBlank() && occurredAt.isNotBlank())
        require(evidenceObjectId == null || UUID_PATTERN.matches(evidenceObjectId))
        require(expectedLotVersion == null || expectedLotVersion >= 0)
        require(affectedQuantity == null || BigDecimal(affectedQuantity).signum() > 0)
        require(reason == null || reason.isNotBlank())
        require(sourceEvidenceId == null || UUID_PATTERN.matches(sourceEvidenceId))
    }

    override fun toString(): String = "TemperatureEvidencePayload(REDACTED)"

    private companion object {
        val UUID_PATTERN =
            Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

data class TemperatureEvidenceSelectionFacts(
    val temperatureEvidenceId: String,
    val lotId: String,
    val affectedQuantity: BigDecimal,
    val expectedLotVersion: Long,
    val resultingLotVersion: Long,
    val inventoryTemperatureEvaluationId: String?,
    val inventoryLotStatus: String?,
    val remainingHeldQuantity: BigDecimal?,
    val actorMembershipId: String,
    val occurredAt: Instant,
    val evidenceObjectId: String?,
    val reason: String?,
    val evaluationStatus: String?,
    val disposition: String?,
    val blocksCommittedExecution: Boolean
) {
    init {
        require(affectedQuantity.signum() > 0)
        require(expectedLotVersion >= 0 && resultingLotVersion >= 0)
        require(remainingHeldQuantity == null || remainingHeldQuantity.signum() >= 0)
    }
}

/** Facts shown as confirmed only when decoded from the authoritative POST response. */

data class TemperatureEvidenceFacts(
    val id: String,
    val subjectType: TemperatureEvidenceSubjectType,
    val subjectId: String,
    val lotId: String?,
    val warehouseId: String?,
    val value: BigDecimal,
    val unit: TemperatureEvidenceUnit,
    val occurredAt: Instant,
    val actorMembershipId: String,
    val status: String,
    val source: String,
    val evidenceObjectId: String? = null,
    val expectedLotVersion: Long? = null,
    val resultingLotVersion: Long? = null,
    val inventoryTemperatureEvaluationId: String? = null,
    val inventoryLotStatus: String? = null,
    val affectedQuantity: BigDecimal? = null,
    val remainingHeldQuantity: BigDecimal? = null,
    val reason: String? = null,
    val sourceEvidenceId: String? = null,
    val exceptionId: String? = null,
    val exceptionStatus: String? = null,
    val evaluationStatus: String? = null,
    val disposition: String? = null,
    val selections: List<TemperatureEvidenceSelectionFacts> = emptyList()
) {
    init {
        require(remainingHeldQuantity == null || remainingHeldQuantity.signum() >= 0)
    }
}
