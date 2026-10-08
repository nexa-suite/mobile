package com.nexa.mobile.operations

import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters.mapConfirmedCatalogDetail
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogDetailProjection
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.operations.ActiveOperationsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCatalogConfirmationMappingTest {
    @Test
    fun matchingCatalogItemAndSkuMapCurrentDetailWithoutChangingSelection() {
        val result = mapConfirmedCatalogDetail(
            candidate = candidate,
            detail = detail(),
            context = context,
            authorityEpoch = context.authorityEpoch
        )

        assertTrue(result is CandidateConfirmationResult.Confirmed)
        val confirmed = (result as CandidateConfirmationResult.Confirmed).sku
        assertEquals(candidate.key, confirmed.candidateKey)
        assertEquals(candidate.sku, confirmed.sku)
        assertEquals("Current family", confirmed.productDisplayName)
        assertEquals("Current variant", confirmed.variant)
        assertEquals("Current pack", confirmed.presentation)
        assertEquals("Current brand", confirmed.brand)
        assertEquals("kg", confirmed.unit)
        assertEquals("bag", confirmed.packaging)
        assertEquals("chilled", confirmed.coldChain)
        assertEquals("cavour-salame-milano-100g.jpeg", confirmed.imageFileName)
        assertEquals(context, confirmed.context)
        assertEquals(context.authorityEpoch, confirmed.authorityEpoch)
    }

    @Test
    fun changedSkuRequiresCandidateReselection() {
        val result = mapConfirmedCatalogDetail(
            candidate = candidate,
            detail = detail().copy(skuCode = "SKU-OTHER"),
            context = context,
            authorityEpoch = context.authorityEpoch
        )

        assertEquals(CandidateConfirmationResult.CandidateUnavailable, result)
    }

    @Test
    fun detailMustUseExactCatalogItemIdSelectedFromSearch() {
        val changedId = mapConfirmedCatalogDetail(
            candidate = candidate,
            detail = detail().copy(catalogItemId = "CAT-0002"),
            context = context,
            authorityEpoch = context.authorityEpoch
        )
        val changedCase = mapConfirmedCatalogDetail(
            candidate = candidate,
            detail = detail().copy(catalogItemId = "cat-0001"),
            context = context,
            authorityEpoch = context.authorityEpoch
        )

        assertEquals(CandidateConfirmationResult.CandidateUnavailable, changedId)
        assertEquals(CandidateConfirmationResult.CandidateUnavailable, changedCase)
    }

    @Test
    fun omittedOptionalDetailFieldsStayUnknown() {
        val result = mapConfirmedCatalogDetail(
            candidate = candidate,
            detail = detail().copy(
                productFamilyName = null,
                productVariantName = null,
                brandName = null,
                unitOfMeasure = null,
                packagingType = null,
                coldChainRequirement = null
            ),
            context = context,
            authorityEpoch = context.authorityEpoch
        )

        assertTrue(result is CandidateConfirmationResult.Confirmed)
        val confirmed = (result as CandidateConfirmationResult.Confirmed).sku
        assertEquals("Current item", confirmed.productDisplayName)
        assertNull(confirmed.variant)
        assertNull(confirmed.brand)
        assertNull(confirmed.unit)
        assertNull(confirmed.packaging)
        assertNull(confirmed.coldChain)
    }

    private fun detail() = CatalogDetailProjection(
        catalogItemId = candidate.key,
        itemName = "Current item",
        presentation = "Current pack",
        skuCode = candidate.sku,
        brandName = "Current brand",
        productVariantName = "Current variant",
        productFamilyName = "Current family",
        unitOfMeasure = "kg",
        packagingType = "bag",
        coldChainRequirement = "chilled",
        imageFileName = "cavour-salame-milano-100g.jpeg"
    )

    private companion object {
        val candidate = ProductCandidate(
            key = "CAT-0001",
            productDisplayName = "Search result family",
            brandOrVariant = "Search result variant",
            presentation = "Search result pack",
            sku = "SKU-0001"
        )
        val context = ActiveOperationsContext(
            companyName = "Company",
            workspaceName = "Warehouse",
            authorityEpoch = 7
        )
    }
}
