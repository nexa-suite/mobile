package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial

import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogResult
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductFacts
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial.CommercialCatalogStatus
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.commercial.CommercialAuthority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommercialCatalogViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private fun authority() =
        CommercialAuthority("u", "t", "w", "m", setOf("client.read", "catalog.read"), 7)

    @Test fun missingPriceAndAvailabilityStayUnknown() = runTest {
        val facts =
            CommercialProductFacts(
                "CAT-1", "p", "Product", "s", "SKU", "KG", null, null, null, null, null, null,
                "agriform-queso-grana-padano-dop-150g.png"
            )
        val model = CommercialCatalogViewModel(object : CommercialCatalogGateway {
            override suspend fun search(
                authority: CommercialAuthority,
                customerId: String,
                query: String,
                page: String?
            ) = CommercialCatalogResult.Choices(
                listOf(CommercialProductChoice("CAT-1", "Product", "SKU")),
                null
            )
            override suspend fun detail(
                authority: CommercialAuthority,
                customerId: String,
                id: String
            ) = CommercialCatalogResult.Product(facts)
        })
        model.activate(authority())
        model.customerIdChanged("customer")
        model.queryChanged("Product")
        model.search()
        runCurrent()
        model.selectProduct("CAT-1")
        runCurrent()
        assertNull(model.state.value.product?.price)
        assertNull(model.state.value.product?.sellableAvailability)
        assertEquals(
            "agriform-queso-grana-padano-dop-150g.png",
            model.state.value.product?.imageFileName
        )
        model.customerIdChanged("another")
        assertNull(model.state.value.product)
        assertTrue(model.state.value.choices.isEmpty())
    }

    @Test fun lateSearchAfterAuthorityReplacementDoesNotRestoreProducts() = runTest {
        val pending = CompletableDeferred<CommercialCatalogResult>()
        val model = CommercialCatalogViewModel(object : CommercialCatalogGateway {
            override suspend fun search(
                authority: CommercialAuthority,
                customerId: String,
                query: String,
                page: String?
            ) = pending.await()
            override suspend fun detail(
                authority: CommercialAuthority,
                customerId: String,
                id: String
            ) = CommercialCatalogResult.Unavailable
        })
        model.activate(authority())
        model.search()
        runCurrent()
        model.deactivate()
        pending.complete(
            CommercialCatalogResult.Choices(
                listOf(CommercialProductChoice("CAT-1", "Product", "SKU")),
                null
            )
        )
        runCurrent()
        assertEquals(CommercialCatalogStatus.Idle, model.state.value.status)
        assertTrue(model.state.value.choices.isEmpty())
    }
}
