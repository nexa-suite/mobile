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
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val CATALOG_ITEMS_PATH = "/api/v1/catalog-items"
private const val CATALOG_PAGE_SIZE = 20
private const val MAX_QUERY_LENGTH = 120
private val pageKeyPattern = Regex("(?:0|[1-9][0-9]*)")
private val catalogItemIdPattern = Regex("(?i)CAT-[A-Z0-9-]{1,63}")
private val catalogJson = Json { ignoreUnknownKeys = true }

/** Safe candidate fields used by client presentation and explicit detail requests. */
data class CatalogCandidateProjection(
    val catalogItemId: String,
    val itemName: String,
    val presentation: String,
    val skuCode: String,
    val brandName: String?,
    val productVariantName: String?,
    val productFamilyName: String?,
    val imageFileName: String? = null
) {
    override fun toString(): String =
        "CatalogCandidateProjection(itemName=$itemName, catalogItemId=REDACTED, skuCode=REDACTED)"
}

/** Search results own an immutable copy of candidate rows. */
class CatalogSearchPage(candidates: List<CatalogCandidateProjection>, val nextPageKey: String?) {
    val candidates: List<CatalogCandidateProjection> =
        Collections.unmodifiableList(ArrayList(candidates))

    override fun toString(): String =
        "CatalogSearchPage(candidates=${candidates.size}, hasNextPage=${nextPageKey != null})"
}

/** Detail projection uses fields returned by the selected-item endpoint. */
data class CatalogDetailProjection(
    val catalogItemId: String,
    val itemName: String,
    val presentation: String,
    val skuCode: String,
    val brandName: String?,
    val productVariantName: String?,
    val productFamilyName: String?,
    val unitOfMeasure: String?,
    val packagingType: String?,
    val coldChainRequirement: String?,
    val imageFileName: String? = null
) {
    override fun toString(): String =
        "CatalogDetailProjection(itemName=$itemName, catalogItemId=REDACTED, skuCode=REDACTED)"
}

sealed interface CatalogSearchOutcome {
    data class Page(val value: CatalogSearchPage) : CatalogSearchOutcome
    data object InvalidQuery : CatalogSearchOutcome
    data object NetworkUnavailable : CatalogSearchOutcome
    data object ServiceUnavailable : CatalogSearchOutcome
    data object PermissionDenied : CatalogSearchOutcome
    data object ContextInvalidated : CatalogSearchOutcome
    data object SessionExpired : CatalogSearchOutcome
}

sealed interface CatalogDetailOutcome {
    data class Found(val value: CatalogDetailProjection) : CatalogDetailOutcome
    data object CandidateUnavailable : CatalogDetailOutcome
    data object NetworkUnavailable : CatalogDetailOutcome
    data object ServiceUnavailable : CatalogDetailOutcome
    data object PermissionDenied : CatalogDetailOutcome
    data object ContextInvalidated : CatalogDetailOutcome
    data object SessionExpired : CatalogDetailOutcome
}

/** Protected native read adapter for the catalog list and selected-item routes. */
class NexaCatalogGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun search(query: String, pageKey: String?): CatalogSearchOutcome {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty() || normalizedQuery.length > MAX_QUERY_LENGTH) {
            return CatalogSearchOutcome.InvalidQuery
        }
        val page = if (pageKey == null) {
            0
        } else {
            parsePageKey(pageKey) ?: return CatalogSearchOutcome.InvalidQuery
        }

        val encodedQuery = URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8.name())
        val path = "$CATALOG_ITEMS_PATH?q=$encodedQuery&page=$page&size=$CATALOG_PAGE_SIZE"
        return when (
            val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, path))
        ) {
            is ProtectedResult.Failure -> result.error.toSearchOutcome()
            is ProtectedResult.Success -> parsePage(result.body, page)
        }
    }

    suspend fun loadDetail(catalogItemId: String): CatalogDetailOutcome {
        if (!catalogItemIdPattern.matches(catalogItemId)) {
            return CatalogDetailOutcome.CandidateUnavailable
        }
        val path = "$CATALOG_ITEMS_PATH/$catalogItemId"
        return when (
            val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, path))
        ) {
            is ProtectedResult.Failure -> result.error.toDetailOutcome()
            is ProtectedResult.Success -> parseDetail(result.body, catalogItemId)
        }
    }

    private fun parsePage(body: String?, requestedPage: Int): CatalogSearchOutcome {
        val response =
            body.decode<CatalogPageWire>() ?: return CatalogSearchOutcome.ServiceUnavailable
        val wireItems = response.items ?: return CatalogSearchOutcome.ServiceUnavailable
        val responsePage = response.page ?: return CatalogSearchOutcome.ServiceUnavailable
        val responseSize = response.size ?: return CatalogSearchOutcome.ServiceUnavailable
        val totalItems = response.totalItems ?: return CatalogSearchOutcome.ServiceUnavailable
        val totalPages = response.totalPages ?: return CatalogSearchOutcome.ServiceUnavailable
        if (responsePage != requestedPage || responseSize != CATALOG_PAGE_SIZE ||
            totalItems < 0 || totalPages < 0
        ) {
            return CatalogSearchOutcome.ServiceUnavailable
        }

        val candidates = wireItems.map { item ->
            item.toCandidateProjection() ?: return CatalogSearchOutcome.ServiceUnavailable
        }
        val nextPage = requestedPage.toLong() + 1
        val nextPageKey = nextPage.takeIf { it < totalPages.toLong() }?.toString()
        return CatalogSearchOutcome.Page(CatalogSearchPage(candidates, nextPageKey))
    }

    private fun parseDetail(body: String?, requestedId: String): CatalogDetailOutcome {
        val response =
            body.decode<CatalogDetailWire>() ?: return CatalogDetailOutcome.ServiceUnavailable
        val detail = response.toDetailProjection() ?: return CatalogDetailOutcome.ServiceUnavailable
        if (!detail.catalogItemId.equals(requestedId, ignoreCase = true)) {
            return CatalogDetailOutcome.ServiceUnavailable
        }
        return CatalogDetailOutcome.Found(detail)
    }

    private fun CatalogCandidateWire.toCandidateProjection(): CatalogCandidateProjection? {
        val safeId = catalogItemId.requiredText() ?: return null
        val safeName = itemName.requiredText() ?: return null
        val safePresentation = presentation.requiredText() ?: return null
        val safeSkuCode = skuCode.requiredText() ?: return null
        if (!catalogItemIdPattern.matches(safeId)) return null
        return CatalogCandidateProjection(
            catalogItemId = safeId,
            itemName = safeName,
            presentation = safePresentation,
            skuCode = safeSkuCode,
            brandName = brandName.optionalText(),
            productVariantName = productVariantName.optionalText(),
            productFamilyName = productFamilyName.optionalText(),
            imageFileName = image?.fileName.optionalText()
        )
    }

    private fun CatalogDetailWire.toDetailProjection(): CatalogDetailProjection? {
        val safeId = catalogItemId.requiredText() ?: return null
        val safeName = itemName.requiredText() ?: return null
        val safePresentation = presentation.requiredText() ?: return null
        val safeSkuCode = skuCode.requiredText() ?: return null
        if (!catalogItemIdPattern.matches(safeId)) return null
        return CatalogDetailProjection(
            catalogItemId = safeId,
            itemName = safeName,
            presentation = safePresentation,
            skuCode = safeSkuCode,
            brandName = brandName.optionalText(),
            productVariantName = productVariantName.optionalText(),
            productFamilyName = productFamilyName.optionalText(),
            unitOfMeasure = unitOfMeasure.optionalText(),
            packagingType = packagingType.optionalText(),
            coldChainRequirement = coldChainRequirement.optionalText(),
            imageFileName = image?.fileName.optionalText()
        )
    }

    private fun ClientFailure.toSearchOutcome(): CatalogSearchOutcome = when {
        httpStatus == 400 -> CatalogSearchOutcome.InvalidQuery

        kind == FailureKind.AuthenticationRequired -> CatalogSearchOutcome.SessionExpired

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            CatalogSearchOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> CatalogSearchOutcome.PermissionDenied

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            CatalogSearchOutcome.NetworkUnavailable

        else -> CatalogSearchOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toDetailOutcome(): CatalogDetailOutcome = when {
        httpStatus == 404 -> CatalogDetailOutcome.CandidateUnavailable

        kind == FailureKind.AuthenticationRequired -> CatalogDetailOutcome.SessionExpired

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            CatalogDetailOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> CatalogDetailOutcome.PermissionDenied

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            CatalogDetailOutcome.NetworkUnavailable

        else -> CatalogDetailOutcome.ServiceUnavailable
    }

    private fun parsePageKey(pageKey: String): Int? =
        if (pageKeyPattern.matches(pageKey)) pageKey.toIntOrNull() else null

    private fun String?.requiredText(): String? = this?.takeIf { it.isNotBlank() }

    private fun String?.optionalText(): String? = this?.takeIf { it.isNotBlank() }

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { catalogJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}

/** Canonical media metadata returned by the server catalog contract. */
@Serializable
data class CatalogMediaWire(val url: String? = null, val fileName: String? = null)

@Serializable
private data class CatalogPageWire(
    val items: List<CatalogCandidateWire>? = null,
    val page: Int? = null,
    val size: Int? = null,
    val totalItems: Long? = null,
    val totalPages: Int? = null
)

@Serializable
private data class CatalogCandidateWire(
    val catalogItemId: String? = null,
    val itemName: String? = null,
    val presentation: String? = null,
    val skuCode: String? = null,
    val brandName: String? = null,
    val productVariantName: String? = null,
    val productFamilyName: String? = null,
    val image: CatalogMediaWire? = null
)

@Serializable
private data class CatalogDetailWire(
    val catalogItemId: String? = null,
    val itemName: String? = null,
    val presentation: String? = null,
    val skuCode: String? = null,
    val brandName: String? = null,
    val productVariantName: String? = null,
    val productFamilyName: String? = null,
    val unitOfMeasure: String? = null,
    val packagingType: String? = null,
    val coldChainRequirement: String? = null,
    val image: CatalogMediaWire? = null
)
