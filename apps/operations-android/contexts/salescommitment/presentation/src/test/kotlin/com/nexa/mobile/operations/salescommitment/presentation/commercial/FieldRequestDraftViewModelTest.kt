package com.nexa.mobile.operations.salescommitment.presentation.commercial

import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestCustomerRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestDraftGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestDraftRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestDraftStore
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestProductRead
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestCustomerFacts
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraft
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestProductFacts
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FieldRequestDraftViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    private val authority = CommercialAuthority(
        "user-1",
        "tenant-1",
        "workspace-1",
        "member-1",
        setOf("client.read", "catalog.read"),
        7
    )

    private class Store(var draft: FieldRequestDraft = FieldRequestDraft()) : FieldRequestDraftStore {
        var saveCount = 0
        override suspend fun load(authority: CommercialAuthority) =
            FieldRequestDraftRead.Available(draft)

        override suspend fun save(authority: CommercialAuthority, draft: FieldRequestDraft): Boolean {
            this.draft = draft
            saveCount++
            return true
        }
    }

    private inner class Gateway : FieldRequestDraftGateway {
        var product = facts()
        var customer = FieldRequestCustomerFacts("Current Customer", "ACTIVE", 1)
        var productCalls = 0

        override suspend fun product(
            authority: CommercialAuthority,
            customerId: String,
            catalogItemId: String
        ): FieldRequestProductRead {
            productCalls++
            return FieldRequestProductRead.Current(product)
        }

        override suspend fun customer(
            authority: CommercialAuthority,
            customerId: String
        ) = FieldRequestCustomerRead.Current(customer)
    }

    @Test
    fun selectedProductIsStoredAsUnconfirmedLocalIntentionAndChangesAreVisibleOnReview() =
        runTest {
            val store = Store()
            val gateway = Gateway()
            val model = FieldRequestDraftViewModel(store, gateway)
            model.activate(authority, "customer-1")
            advanceUntilIdle()

            model.addProduct("CAT-0001")
            advanceUntilIdle()

            assertEquals(FieldRequestDraftStatus.Saved, model.state.value.status)
            assertEquals("1", model.state.value.draft.lines.single().quantity)
            assertEquals("Pasta", model.state.value.draft.lines.single().name)
            assertEquals(1, gateway.productCalls)

            model.review()
            advanceUntilIdle()
            assertEquals(FieldRequestDraftStatus.Reviewed, model.state.value.status)
            assertEquals("Current Customer", model.state.value.draft.customerName)

            gateway.product = facts().copy(name = "Pasta updated", price = "8.40")
            gateway.customer = FieldRequestCustomerFacts("Renamed Customer", "ACTIVE", 2)
            model.review()
            advanceUntilIdle()

            assertEquals(FieldRequestDraftStatus.Reviewed, model.state.value.status)
            assertTrue(model.state.value.customerChanged)
            assertEquals(setOf("CAT-0001"), model.state.value.changedProducts)
            assertEquals("Pasta", model.state.value.draft.lines.single().name)
            assertEquals("Pasta updated", model.state.value.currentProducts.getValue("CAT-0001").name)
            assertTrue(store.saveCount >= 2)
            model.deactivate()
        }

    @Test
    fun changingCustomerClearsPriorProductsAndOfflineEditsStayLocal() = runTest {
        val initial = FieldRequestDraft(
            customerId = "customer-1",
            lines = listOf(facts().toLine("2")),
            customerName = "Old Customer",
            customerVersion = 4
        )
        val store = Store(initial)
        val gateway = Gateway()
        val model = FieldRequestDraftViewModel(store, gateway)
        model.activate(authority)
        runCurrent()

        model.quantityChanged("CAT-0001", "3")
        runCurrent()
        assertEquals("3", store.draft.lines.single().quantity)
        assertEquals(0, gateway.productCalls)

        model.customerChanged("customer-2")
        runCurrent()
        assertEquals("customer-2", store.draft.customerId)
        assertTrue(store.draft.lines.isEmpty())
        assertEquals(null, store.draft.customerName)
        assertEquals(null, store.draft.customerVersion)
        assertEquals(0, gateway.productCalls)
        model.deactivate()
    }

    private fun facts() = FieldRequestProductFacts(
        catalogItemId = "CAT-0001",
        productId = "product-1",
        name = "Pasta",
        skuCode = "SKU-1",
        brandName = "Brand",
        categoryName = "Grocery",
        familyName = "Pasta",
        variantName = "Whole grain",
        unit = "KG",
        price = "7.90",
        currency = "PEN",
        pricingAsOf = "2026-10-15T10:00:00Z",
        availability = "6",
        availabilityStatus = "AVAILABLE",
        availabilityAsOf = "2026-10-15T10:00:00Z"
    )

    private fun FieldRequestProductFacts.toLine(quantity: String) =
        com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraftLine(
            catalogItemId,
            productId,
            name,
            skuCode,
            brandName,
            categoryName,
            familyName,
            variantName,
            unit,
            quantity,
            price,
            currency,
            pricingAsOf,
            availability,
            availabilityStatus,
            availabilityAsOf
        )
}
