package com.nexa.mobile.operations.inventoryavailability.application.model.warehouse

import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionAlternative
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionCurrentFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionRequest
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork

enum class LotSubstitutionIntentStatus { Pending, UnknownOutcome }

data class LotSubstitutionIntent(
    val scope: PickingScopeIdentity,
    val idempotencyKey: String,
    val work: LotSubstitutionWork,
    val alternativeLotId: String,
    val reason: String,
    val frozenBody: String,
    val status: LotSubstitutionIntentStatus
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(alternativeLotId.isNotBlank() && reason.isNotBlank())
        require(frozenBody.isNotBlank() && frozenBody.length <= 4_096)
    }

    fun sameFrozenCommand(other: LotSubstitutionIntent): Boolean =
        scope == other.scope && idempotencyKey == other.idempotencyKey && work == other.work &&
            alternativeLotId == other.alternativeLotId && reason == other.reason &&
            frozenBody == other.frozenBody

    override fun toString(): String = "LotSubstitutionIntent(status=$status, key=REDACTED)"
}

sealed interface LotSubstitutionLookupResult {
    data class Alternatives(val items: List<LotSubstitutionAlternative>) :
        LotSubstitutionLookupResult
    data object NetworkUnavailable : LotSubstitutionLookupResult
    data object ServiceUnavailable : LotSubstitutionLookupResult
    data object PermissionDenied : LotSubstitutionLookupResult
    data object ContextInvalidated : LotSubstitutionLookupResult
    data object SessionInvalidated : LotSubstitutionLookupResult
}

sealed interface LotSubstitutionResult {
    data class Requested(val request: LotSubstitutionRequest) : LotSubstitutionResult
    data class Rejected(val code: String?) : LotSubstitutionResult
    data class Stale(val currentAllocationVersion: Long?) : LotSubstitutionResult
    data object Conflict : LotSubstitutionResult
    data object UnknownOutcome : LotSubstitutionResult
    data object NetworkUnavailable : LotSubstitutionResult
    data object ServiceUnavailable : LotSubstitutionResult
    data object PermissionDenied : LotSubstitutionResult
    data object ContextInvalidated : LotSubstitutionResult
    data object SessionInvalidated : LotSubstitutionResult
}

sealed interface LotSubstitutionCurrentResult {
    data class Current(val facts: LotSubstitutionCurrentFacts) : LotSubstitutionCurrentResult
    data object NotFound : LotSubstitutionCurrentResult
    data object NetworkUnavailable : LotSubstitutionCurrentResult
    data object ServiceUnavailable : LotSubstitutionCurrentResult
    data object PermissionDenied : LotSubstitutionCurrentResult
    data object ContextInvalidated : LotSubstitutionCurrentResult
    data object SessionInvalidated : LotSubstitutionCurrentResult
}

sealed interface LotSubstitutionMetadataRead {
    data class Available(val value: LotSubstitutionIntent?) : LotSubstitutionMetadataRead
    data object Unavailable : LotSubstitutionMetadataRead
}

enum class LotSubstitutionMetadataWrite { Saved, Unavailable }
