package com.nexa.mobile.operations.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

private const val COMMERCIAL_CATALOG_PATH = "/api/v1/catalog-items"
private val commercialCatalogJson = Json { ignoreUnknownKeys = true }

/** BC-03 price and BC-05 sellable availability are independent server facts. */
class NexaCommercialCatalogGateway(private val calls: ProtectedCallExecutor) {
    suspend fun detail(id: String): CommercialCatalogNetworkResult {
        if (!Regex("(?i)CAT-[A-Z0-9-]{1,63}").matches(id)) return CommercialCatalogNetworkResult.Unavailable
        return when (val result = calls.execute(ProtectedRequest(ProtectedMethod.GET, "$COMMERCIAL_CATALOG_PATH/$id"))) {
            is ProtectedResult.Success -> try {
                val value = commercialCatalogJson.decodeFromString<CommercialCatalogWire>(result.body ?: "")
                if (!value.catalogItemId.equals(id, true) || value.status != "ACTIVE" || value.itemName.isBlank())
                    CommercialCatalogNetworkResult.Unavailable else CommercialCatalogNetworkResult.Found(value)
            } catch (_: Exception) { CommercialCatalogNetworkResult.Unavailable }
            is ProtectedResult.Failure -> if (result.error.kind == FailureKind.AuthorizationFailure ||
                result.error.kind == FailureKind.AuthenticationRequired || result.error.httpStatus == 404
            ) CommercialCatalogNetworkResult.PermissionDenied else CommercialCatalogNetworkResult.Unavailable
        }
    }
}
@Serializable
data class CommercialMoneyWire(val amount: JsonPrimitive, val currency: String)
@Serializable
data class CommercialCatalogWire(val catalogItemId: String, val productId: String, val itemName: String,
    val status: String, val sellableSkuId: String? = null, val skuCode: String? = null,
    val unitOfMeasure: String? = null, val currentOfferPrice: CommercialMoneyWire? = null,
    val effectivePrice: CommercialMoneyWire? = null, val unitPrice: CommercialMoneyWire? = null,
    val pricingAsOf: String? = null, val availabilityStatus: String? = null,
    val sellableAvailability: JsonPrimitive? = null, val availabilityAsOf: String? = null) {
    override fun toString(): String = "CommercialCatalogWire(REDACTED)"
}
sealed interface CommercialCatalogNetworkResult {
    data class Found(val value: CommercialCatalogWire) : CommercialCatalogNetworkResult
    data object PermissionDenied : CommercialCatalogNetworkResult
    data object Unavailable : CommercialCatalogNetworkResult
}
