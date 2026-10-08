package com.nexa.mobile.operations.inventoryavailability.application.model.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivedLotFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingEvidenceObject
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingProductReference
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingWarehouseChoice
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingZoneChoice
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

data class ReceivingAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
        require(authorityEpoch > 0)
    }

    val scope: ReceivingScopeIdentity
        get() = ReceivingScopeIdentity(userId, tenantId, workspaceId, membershipId)

    val canLookUpWarehouses: Boolean
        get() = permissions.any { it in WAREHOUSE_LOOKUP_PERMISSIONS }

    val canReceive: Boolean
        get() = permissions.any { it in RECEIPT_PERMISSIONS }

    override fun toString(): String =
        "ReceivingAuthority(scope=REDACTED, permissions=${permissions.size}, epoch=$authorityEpoch)"

    private companion object {
        val WAREHOUSE_LOOKUP_PERMISSIONS = setOf(
            "warehouse.read",
            "inventory.read",
            "warehouse:read"
        )
        val RECEIPT_PERMISSIONS = setOf("inventory.receive", "warehouse:write")
    }
}

/** Scope key for non-authoritative local draft metadata. Never persists permission authority. */

data class ReceivingScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "ReceivingScopeIdentity(REDACTED)"
}

/** In-memory picker context used only to bind a returned file to its original active warehouse. */

data class ReceivingEvidenceSelectionContext(
    val scope: ReceivingScopeIdentity,
    val warehouseId: String
) {
    init {
        require(warehouseId.isNotBlank())
    }

    override fun toString(): String =
        "ReceivingEvidenceSelectionContext(scope=REDACTED, warehouse=REDACTED)"
}

/** Catalog id and modern SKU UUID remain distinct server identifiers. */

data class InboundReceiptRequest(
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val expirationDate: LocalDate,
    val quantity: BigDecimal,
    val unit: String,
    val temperatureReading: BigDecimal? = null,
    val notes: String? = null,
    val temperatureEvidenceObjectId: String? = null
) {
    init {
        require(warehouseId.isNotBlank())
        require(zoneId.isNotBlank())
        require(!catalogItemId.isNullOrBlank() || !skuId.isNullOrBlank())
        require(batchNumber.isNotBlank())
        require(quantity.signum() > 0)
        require(unit.isNotBlank())
        require(
            temperatureEvidenceObjectId == null || UUID_PATTERN.matches(temperatureEvidenceObjectId)
        )
    }

    override fun toString(): String =
        "InboundReceiptRequest(warehouseId=REDACTED, zoneId=REDACTED, quantity=$quantity, " +
            "batchNumber=REDACTED, expirationDate=$expirationDate)"
}

/** A validated image staged by the application boundary; it is not server evidence yet. */

data class ReceivingEvidenceCandidate(
    val file: File,
    val originalFilename: String,
    val declaredContentType: String,
    val byteSize: Long,
    val checksumSha256: String
) {
    override fun toString(): String =
        "ReceivingEvidenceCandidate(type=$declaredContentType, bytes=$byteSize)"
}

sealed interface ReceivingEvidenceResult {
    data class Loaded(val evidence: ReceivingEvidenceObject) : ReceivingEvidenceResult
    data class Rejected(val code: String?) : ReceivingEvidenceResult
    data object UnknownOutcome : ReceivingEvidenceResult
    data object NetworkUnavailable : ReceivingEvidenceResult
    data object ServiceUnavailable : ReceivingEvidenceResult
    data object PermissionDenied : ReceivingEvidenceResult
    data object ContextInvalidated : ReceivingEvidenceResult
    data object SessionInvalidated : ReceivingEvidenceResult
}

/** Facts are projected only from the successful authoritative POST response. */

sealed interface ReceivingLookupResult {
    data class Warehouses(val items: List<ReceivingWarehouseChoice>) : ReceivingLookupResult
    data class Zones(val items: List<ReceivingZoneChoice>) : ReceivingLookupResult
    data object NetworkUnavailable : ReceivingLookupResult
    data object ServiceUnavailable : ReceivingLookupResult
    data object PermissionDenied : ReceivingLookupResult
    data object ContextInvalidated : ReceivingLookupResult
    data object SessionInvalidated : ReceivingLookupResult
}

sealed interface ReceivingSubmitResult {
    data class Confirmed(val facts: ReceivedLotFacts) : ReceivingSubmitResult
    data class Rejected(val code: String?) : ReceivingSubmitResult
    data object UnknownOutcome : ReceivingSubmitResult
    data object PermissionDenied : ReceivingSubmitResult
    data object ContextInvalidated : ReceivingSubmitResult
    data object SessionInvalidated : ReceivingSubmitResult
    data object ServiceUnavailable : ReceivingSubmitResult
}

data class ReceivingDraftMetadata(
    val selectedProduct: ReceivingProductReference?,
    val warehouseId: String?,
    val zoneId: String?,
    val batchNumber: String,
    val expirationDateText: String,
    val quantityText: String,
    val unit: String,
    val temperatureReadingText: String,
    val temperatureEvidenceObjectId: String? = null
)

enum class ReceivingIntentMetadataStatus { Pending, UnknownOutcome }

/** Persist only the scope-bound command needed for safe replay; never persist response stock facts. */

data class ReceivingIntentMetadata(
    val scope: ReceivingScopeIdentity,
    val idempotencyKey: String,
    val request: InboundReceiptRequest,
    val status: ReceivingIntentMetadataStatus
) {
    override fun toString(): String = "ReceivingIntentMetadata(status=$status, key=REDACTED)"
}

private val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

sealed interface ReceivingMetadataRead<out T> {
    data class Available<T>(val value: T?) : ReceivingMetadataRead<T>
    data object Unavailable : ReceivingMetadataRead<Nothing>
}

enum class ReceivingMetadataWrite { Saved, Unavailable }
