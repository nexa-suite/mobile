package com.nexa.mobile.operations.inventoryavailability.application.model.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingProductReference

enum class ReceivingCatalogFilterKind(val wireValue: String) {
    Brand("brand"),
    Category("category")
}

data class ReceivingCatalogFilterChoice(val id: String, val name: String)

data class ReceivingCatalogCandidate(
    val catalogItemId: String,
    val itemName: String,
    val presentation: String,
    val skuCode: String,
    val brandName: String?,
    val categoryName: String?
)

data class ReceivingCatalogPage(
    val items: List<ReceivingCatalogCandidate>,
    val nextPageKey: String?
)

data class ReceivingCatalogFilterPage(
    val items: List<ReceivingCatalogFilterChoice>,
    val page: Int,
    val size: Int,
    val total: Long
)

sealed interface ReceivingCatalogOutcome<out T> {
    data class Success<T>(val value: T) : ReceivingCatalogOutcome<T>
    data object InvalidQuery : ReceivingCatalogOutcome<Nothing>
    data object CandidateUnavailable : ReceivingCatalogOutcome<Nothing>
    data object NetworkUnavailable : ReceivingCatalogOutcome<Nothing>
    data object ServiceUnavailable : ReceivingCatalogOutcome<Nothing>
    data object PermissionDenied : ReceivingCatalogOutcome<Nothing>
    data object ContextInvalidated : ReceivingCatalogOutcome<Nothing>
    data object SessionInvalidated : ReceivingCatalogOutcome<Nothing>
}
