package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

private const val OPERATIONS_CATALOG_PATH = "/api/v1/catalog/products"
private const val OPERATIONS_CATALOG_PAGE_SIZE = 20
private const val MAX_QUERY_LENGTH = 120
private val pageKeyPattern = Regex("(?:0|[1-9][0-9]*)")
private val catalogItemIdPattern = Regex("(?i)CAT-[A-Z0-9-]{1,63}")
private val productCodePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val imageFileNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
private val operationsCatalogJson = Json { ignoreUnknownKeys = true }

/** Product facts returned by the authorized Operations catalog projection. */
data class OperationsCatalogCandidateProjection(
    val productId: String,
    val catalogItemId: String,
    val itemName: String,
    val presentation: String,
    val skuCode: String,
    val brandName: String?,
    val imageFileName: String?,
    val priceAmount: String?,
    val priceCurrency: String?
) {
    override fun toString(): String = "OperationsCatalogCandidateProjection(itemName=$itemName, " +
        "catalogItemId=REDACTED, skuCode=REDACTED)"
}

data class OperationsCatalogDetailProjection(
    val productId: String,
    val catalogItemId: String,
    val itemName: String,
    val presentation: String,
    val skuCode: String,
    val brandName: String?,
    val unitOfMeasure: String?,
    val storageTemperature: String?,
    val imageFileName: String?,
    val priceAmount: String?,
    val priceCurrency: String?
) {
    override fun toString(): String = "OperationsCatalogDetailProjection(itemName=$itemName, " +
        "catalogItemId=REDACTED, skuCode=REDACTED)"
}

class OperationsCatalogSearchPage(
    candidates: List<OperationsCatalogCandidateProjection>,
    val nextPageKey: String?
) {
    val candidates: List<OperationsCatalogCandidateProjection> =
        Collections.unmodifiableList(ArrayList(candidates))

    override fun toString(): String =
        "OperationsCatalogSearchPage(candidates=${candidates.size}, " +
            "hasNextPage=${nextPageKey != null})"
}

sealed interface OperationsCatalogSearchOutcome {
    data class Page(val value: OperationsCatalogSearchPage) : OperationsCatalogSearchOutcome
    data object InvalidQuery : OperationsCatalogSearchOutcome
    data object NetworkUnavailable : OperationsCatalogSearchOutcome
    data object ServiceUnavailable : OperationsCatalogSearchOutcome
    data object PermissionDenied : OperationsCatalogSearchOutcome
    data object ContextInvalidated : OperationsCatalogSearchOutcome
    data object SessionExpired : OperationsCatalogSearchOutcome
}

sealed interface OperationsCatalogDetailOutcome {
    data class Found(val value: OperationsCatalogDetailProjection) : OperationsCatalogDetailOutcome
    data object CandidateUnavailable : OperationsCatalogDetailOutcome
    data object NetworkUnavailable : OperationsCatalogDetailOutcome
    data object ServiceUnavailable : OperationsCatalogDetailOutcome
    data object PermissionDenied : OperationsCatalogDetailOutcome
    data object ContextInvalidated : OperationsCatalogDetailOutcome
    data object SessionExpired : OperationsCatalogDetailOutcome
}

/**
 * Protected adapter for the catalog-management projection used by Operations Mobile.
 * The commercial projection intentionally remains owned by [NexaCatalogGateway]
 * because it applies a different buyer visibility contract.
 */
class NexaOperationsCatalogGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun search(query: String, pageKey: String?): OperationsCatalogSearchOutcome {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty() || normalizedQuery.length > MAX_QUERY_LENGTH) {
            return OperationsCatalogSearchOutcome.InvalidQuery
        }
        val page = if (pageKey == null) {
            0
        } else {
            parsePageKey(pageKey) ?: return OperationsCatalogSearchOutcome.InvalidQuery
        }
        val encodedQuery = URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8.name())
        val path = "$OPERATIONS_CATALOG_PATH?page=$page&size=$OPERATIONS_CATALOG_PAGE_SIZE" +
            "&search=$encodedQuery&status=ACTIVE"
        return when (
            val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, path))
        ) {
            is ProtectedResult.Failure -> result.error.toSearchOutcome()
            is ProtectedResult.Success -> parsePage(result.body, page)
        }
    }

    suspend fun loadDetail(productId: String): OperationsCatalogDetailOutcome {
        if (runCatching { UUID.fromString(productId) }.isFailure) {
            return OperationsCatalogDetailOutcome.CandidateUnavailable
        }
        val path = "$OPERATIONS_CATALOG_PATH/$productId"
        return when (
            val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, path))
        ) {
            is ProtectedResult.Failure -> result.error.toDetailOutcome()
            is ProtectedResult.Success -> parseDetail(result.body, productId)
        }
    }

    private fun parsePage(body: String?, requestedPage: Int): OperationsCatalogSearchOutcome {
        val response = body.decode<OperationsCatalogProductPageWire>()
            ?: return OperationsCatalogSearchOutcome.ServiceUnavailable
        val wireItems = response.items ?: return OperationsCatalogSearchOutcome.ServiceUnavailable
        val responsePage = response.page ?: return OperationsCatalogSearchOutcome.ServiceUnavailable
        val responseSize = response.size ?: return OperationsCatalogSearchOutcome.ServiceUnavailable
        val total = response.total ?: return OperationsCatalogSearchOutcome.ServiceUnavailable
        if (
            responsePage != requestedPage ||
            responseSize != OPERATIONS_CATALOG_PAGE_SIZE ||
            total < 0
        ) {
            return OperationsCatalogSearchOutcome.ServiceUnavailable
        }
        val candidates = wireItems.map { item ->
            item.toCandidate() ?: return OperationsCatalogSearchOutcome.ServiceUnavailable
        }
        val nextPage = requestedPage.toLong() + 1
        val nextPageKey = nextPage.takeIf {
            it * OPERATIONS_CATALOG_PAGE_SIZE.toLong() < total
        }?.toString()
        return OperationsCatalogSearchOutcome.Page(
            OperationsCatalogSearchPage(candidates, nextPageKey)
        )
    }

    private fun parseDetail(body: String?, requestedId: String): OperationsCatalogDetailOutcome {
        val response = body.decode<OperationsCatalogProductWire>()
            ?: return OperationsCatalogDetailOutcome.ServiceUnavailable
        val detail = response.toDetail()
            ?: return OperationsCatalogDetailOutcome.ServiceUnavailable
        if (detail.productId != requestedId) {
            return OperationsCatalogDetailOutcome.ServiceUnavailable
        }
        return OperationsCatalogDetailOutcome.Found(detail)
    }

    private fun OperationsCatalogProductWire.toCandidate(): OperationsCatalogCandidateProjection? {
        val safe = requiredIdentity() ?: return null
        return OperationsCatalogCandidateProjection(
            productId = safe.productId,
            catalogItemId = safe.catalogItemId,
            itemName = safe.itemName,
            presentation = safe.presentation,
            skuCode = safe.skuCode,
            brandName = brandName.optionalText(),
            imageFileName = imageFileName(),
            priceAmount = currentPrice.safeAmount(),
            priceCurrency = currentPrice.safeCurrency()
        )
    }

    private fun OperationsCatalogProductWire.toDetail(): OperationsCatalogDetailProjection? {
        val safe = requiredIdentity() ?: return null
        return OperationsCatalogDetailProjection(
            productId = safe.productId,
            catalogItemId = safe.catalogItemId,
            itemName = safe.itemName,
            presentation = safe.presentation,
            skuCode = safe.skuCode,
            brandName = brandName.optionalText(),
            unitOfMeasure = unitOfMeasure.optionalText(),
            storageTemperature = storageTemperature.optionalText(),
            imageFileName = imageFileName(),
            priceAmount = currentPrice.safeAmount(),
            priceCurrency = currentPrice.safeCurrency()
        )
    }

    private fun OperationsCatalogProductWire.requiredIdentity(): SafeIdentity? {
        val safeProductId = id.requiredText()?.takeIf {
            runCatching { UUID.fromString(it) }.isSuccess
        } ?: return null
        val safeCatalogItemId = catalogItemId.requiredText()
            ?.takeIf { catalogItemIdPattern.matches(it) } ?: return null
        val safeName = name.requiredText() ?: return null
        val safePresentation = presentation.requiredText() ?: return null
        val safeSku = productCode.requiredText()
            ?.takeIf { productCodePattern.matches(it) } ?: return null
        if (!status.equals("ACTIVE", ignoreCase = true)) return null
        return SafeIdentity(
            productId = safeProductId,
            catalogItemId = safeCatalogItemId,
            itemName = safeName,
            presentation = safePresentation,
            skuCode = safeSku
        )
    }

    private fun OperationsCatalogProductWire.imageFileName(): String? =
        imagePath?.trim()?.substringAfterLast('/')?.takeIf {
            !it.contains('\\') && imageFileNamePattern.matches(it)
        }

    private fun OperationsCatalogPriceWire?.safeAmount(): String? {
        if (this == null) return null
        val amount = amount?.content?.toBigDecimalOrNull() ?: return null
        if (amount.signum() < 0 || amount.scale() > 2) return null
        return amount.toPlainString()
    }

    private fun OperationsCatalogPriceWire?.safeCurrency(): String? {
        if (this == null) return null
        return currency?.trim()?.takeIf { Regex("[A-Z]{3}").matches(it) }
    }

    private fun ClientFailure.toSearchOutcome(): OperationsCatalogSearchOutcome = when {
        httpStatus == 400 -> OperationsCatalogSearchOutcome.InvalidQuery

        kind == FailureKind.AuthenticationRequired -> OperationsCatalogSearchOutcome.SessionExpired

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            OperationsCatalogSearchOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> OperationsCatalogSearchOutcome.PermissionDenied

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            OperationsCatalogSearchOutcome.NetworkUnavailable

        else -> OperationsCatalogSearchOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toDetailOutcome(): OperationsCatalogDetailOutcome = when {
        httpStatus == 404 -> OperationsCatalogDetailOutcome.CandidateUnavailable

        kind == FailureKind.AuthenticationRequired -> OperationsCatalogDetailOutcome.SessionExpired

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            OperationsCatalogDetailOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> OperationsCatalogDetailOutcome.PermissionDenied

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            OperationsCatalogDetailOutcome.NetworkUnavailable

        else -> OperationsCatalogDetailOutcome.ServiceUnavailable
    }

    private fun parsePageKey(pageKey: String): Int? =
        if (pageKeyPattern.matches(pageKey)) pageKey.toIntOrNull() else null

    private fun String?.requiredText(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun String?.optionalText(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { operationsCatalogJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }

    private data class SafeIdentity(
        val productId: String,
        val catalogItemId: String,
        val itemName: String,
        val presentation: String,
        val skuCode: String
    )

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}

@Serializable
private data class OperationsCatalogProductPageWire(
    val items: List<OperationsCatalogProductWire>? = null,
    val page: Int? = null,
    val size: Int? = null,
    val total: Long? = null
)

@Serializable
private data class OperationsCatalogProductWire(
    val id: String? = null,
    val catalogItemId: String? = null,
    val productCode: String? = null,
    val name: String? = null,
    val brandName: String? = null,
    val status: String? = null,
    val presentation: String? = null,
    val unitOfMeasure: String? = null,
    val storageTemperature: String? = null,
    val buyerVisible: Boolean? = null,
    val imagePath: String? = null,
    val currentPrice: OperationsCatalogPriceWire? = null
)

@Serializable
private data class OperationsCatalogPriceWire(
    val amount: JsonPrimitive? = null,
    val currency: String? = null
)
